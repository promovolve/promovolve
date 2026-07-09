package promovolve.advertiser

import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, EntityRef }
import org.apache.pekko.cluster.typed.*
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.state.scaladsl.{ DurableStateBehavior, Effect }
import promovolve.*
import promovolve.auction.CampaignDistributor
import promovolve.common.Aggregator

import scala.concurrent.duration.*

object CampaignDirectory {

  // ---------------- Public factory ----------------
  def singletonInit(
      system: ActorSystem[?],
      sharding: ClusterSharding,
      campaignChangedTopic: ActorRef[Topic.Command[CampaignEntity.CampaignChanged]],
      reconcileInterval: FiniteDuration = 60.seconds,
      bidderAskTimeout: FiniteDuration = 5.seconds
  ): ActorRef[Command] = ClusterSingleton(system).init(
    SingletonActor(
      Behaviors
        .supervise(
          CampaignDirectory(sharding, campaignChangedTopic, reconcileInterval, bidderAskTimeout)
        )
        .onFailure[Exception](SupervisorStrategy.restart),
      "campaign-directory"
    ).withStopMessage(Stop)
      .withSettings(ClusterSingletonSettings(system).withRole("singleton"))
  )

  // ---------------- Behavior ----------------
  private def apply(
      sharding: ClusterSharding,
      campaignChangedTopic: ActorRef[Topic.Command[CampaignEntity.CampaignChanged]],
      reconcileEvery: FiniteDuration,
      bidderAskTimeout: FiniteDuration
  ): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      // Ephemeral reverse index: campaignId -> categories (rebuilt on recovery)
      // Avoids O(categories) scan when looking up categories for a campaign
      var campaignToCategories: Map[CampaignId, Set[CategoryId]] = Map.empty

      // Ephemeral demand-registration health (not persisted; recomputed on
      // every reconcile tick). Surfaces the otherwise-silent failure where
      // campaign registrations time out against this singleton and advertiser
      // demand never reaches the auctions — the failure that today is
      // indistinguishable from "no advertisers exist". `degradedSinceMillis`
      // is set while fewer categories are acked than expected and cleared on
      // a clean reconcile, so the WARN can report how long it's been broken.
      var lastReconcile: Option[(Int, Int, Long)] = None // (acked, expected, atMillis)
      var degradedSinceMillis: Option[Long] = None

      /** Get entity ref for a CampaignDistributor worker */
      def publisherRef(categoryId: CategoryId): EntityRef[CampaignDistributor.Command] =
        sharding.entityRefFor(CampaignDistributor.TypeKey, CampaignDistributor.entityIdForCategory(categoryId))

      /**
       * Route publish requests to CampaignDistributor workers.
       *
       * Each category is routed to a specific worker based on hash(categoryId) % NumWorkers.
       * This distributes the broadcast workload across the cluster instead of having the
       * singleton do all the fan-out work.
       *
       * The CampaignDistributor workers handle:
       * - Expanding to all virtual shards for each category
       * - Rate-limiting concurrent broadcasts
       * - Queuing requests during high load
       */
      def publishToCategories[A <: Command](
          categoriesToPublish: Map[CategoryId, Map[CampaignId, AdvertiserId]],
          buildResult: Set[CategoryId] => A
      ): Unit =
        if (categoriesToPublish.nonEmpty) {
          ctx.log.info(
            "Routing {} categories to {} publisher workers: {}",
            categoriesToPublish.size,
            CampaignDistributor.NumWorkers,
            categoriesToPublish.keys.mkString(",")
          )

          // Pre-compute publisher refs here (on CampaignDirectory's thread)
          val publisherMessages = categoriesToPublish.map { case (categoryId, campaigns) =>
            (publisherRef(categoryId), categoryId, campaigns)
          }.toSeq

          ctx.spawnAnonymous(
            Aggregator[CampaignDistributor.PublishAck, Command](
              sendRequests = { replyAdapter =>
                // WARNING: This runs on Aggregator's thread - do NOT access ctx here
                publisherMessages.foreach { case (ref, categoryId, campaigns) =>
                  ref ! CampaignDistributor.Publish(categoryId, campaigns, replyAdapter)
                }
              },
              expectedReplies = categoriesToPublish.size,
              replyTo = ctx.self,
              aggregateReplies = replies => buildResult(replies.map(_.categoryId).toSet),
              timeout = bidderAskTimeout
            )
          )
        } else ctx.log.warn("publishToCategories called with empty map!")

      def notifyCampaignChanged(
          campaignId: CampaignId,
          categories: Set[CategoryId],
          isActive: Boolean,
          siteAllowlist: Set[String] = Set.empty,
          targetCategories: Set[CategoryId] = Set.empty,
          configEdit: Boolean = false
      ): Unit = {
        ctx.log.info(
          "CampaignChanged[{}] -> publishing to topic ({} categories, active={}, allowlist={})",
          campaignId.value,
          categories.size,
          isActive,
          siteAllowlist.size
        )
        ctx.log.debug(
          "CampaignChanged[{}] categories: {}",
          campaignId.value,
          categories.mkString(",")
        )
        campaignChangedTopic ! Topic.Publish(
          CampaignEntity.CampaignChanged(campaignId, categories, isActive, siteAllowlist, targetCategories, configEdit)
        )
      }

      /** Update ephemeral reverse index after a campaign's categories change */
      def updateReverseIndex(campaignId: CampaignId, newCategories: Set[CategoryId]): Unit =
        if (newCategories.isEmpty)
          campaignToCategories = campaignToCategories - campaignId
        else
          campaignToCategories = campaignToCategories.updated(campaignId, newCategories)

      /** Remove campaign from all its categories, update index, publish, notify */
      def removeCampaign(state: State, campaignId: CampaignId): Effect[State] = {
        val affectedCategories = campaignToCategories.getOrElse(campaignId, Set.empty)
        val wasFiller = state.fillerCampaigns.contains(campaignId)
        if (affectedCategories.nonEmpty || wasFiller) {
          // Drop from both the category index and the filler pool in
          // one persisted transition so a paused/deleted campaign
          // disappears from every auction surface.
          val afterCats = state.removeCampaignFromCategories(campaignId, affectedCategories)
          val newState = afterCats.copy(fillerCampaigns = afterCats.fillerCampaigns - campaignId)
          Effect
            .persist(newState)
            .thenRun { _ =>
              updateReverseIndex(campaignId, Set.empty)
              if (affectedCategories.nonEmpty) {
                val toPublish = affectedCategories.map(c => c -> newState.categories.getOrElse(c, Map.empty)).toMap
                publishToCategories(toPublish,
                  acked => CampaignRemovalAcknowledged(campaignId, acked, affectedCategories))
              }
            }
        } else Effect.none
      }

      def commandHandler(state: State, command: Command): Effect[State] = command match {

        case ready @ CampaignReady(campaignId, advertiserId, categoryIds, _, _, _, status, replyTo,
              bidOnUnmatchedContext, siteAllowlist, _) =>
          if (status == CampaignEntity.Status.Active) {
            // Use ephemeral reverse index for O(1) lookup of old categories
            val oldCategories = campaignToCategories.getOrElse(campaignId, Set.empty)
            val (catState, affectedCategories) =
              state.updateCampaignCategories(campaignId, advertiserId, oldCategories, categoryIds)
            // Filler pool is independent of category membership — a
            // campaign can live in both.
            val newState = catState.setFiller(campaignId, advertiserId, bidOnUnmatchedContext)
            val fillerChanged = state.fillerCampaigns.contains(campaignId) != bidOnUnmatchedContext
            if (affectedCategories.nonEmpty || fillerChanged)
              Effect
                .persist(newState)
                .thenRun { _ =>
                  updateReverseIndex(campaignId, categoryIds)
                  // Filler opt-OUT needs an explicit
                  // CampaignChanged(isActive=false) event: that path
                  // alone triggers AdServer.CampaignPaused, which is
                  // the only handler that cleans the SQL pending-
                  // approval queue (ServeIndex DData is cleaned by
                  // both paths). Emit it unconditionally on opt-out,
                  // regardless of whether categories also changed —
                  // if they did, the category-side
                  // CampaignChanged(isActive=true) fires afterward
                  // via CategoryBiddersAcknowledged and repopulates
                  // legit category slots.
                  // Opt-IN doesn't need an extra event: the category
                  // reauction path already invites the campaign into
                  // empty filler slots on next classify.
                  if (fillerChanged && !bidOnUnmatchedContext) {
                    notifyCampaignChanged(campaignId, Set(CategoryId.Filler), isActive = false)
                  }
                  if (affectedCategories.nonEmpty) {
                    val toPublish = affectedCategories.map(c => c -> newState.categories.getOrElse(c, Map.empty)).toMap
                    publishToCategories(toPublish,
                      acked =>
                        CategoryBiddersAcknowledged(campaignId, acked, replyTo, siteAllowlist, ready.targetCategories,
                          ready.configEdit))
                  } else {
                    replyTo ! CampaignRegistered(campaignId)
                  }
                }
            else {
              replyTo ! CampaignRegistered(campaignId)
              Effect.none
            }
          } else {
            // Defensive: treat non-Active status as implicit removal
            replyTo ! CampaignRegistered(campaignId)
            removeCampaign(state, campaignId)
          }

        case CategoryBiddersAcknowledged(campaignId, affectedCategories, replyTo, siteAllowlist, targetCategories,
              configEdit) =>
          ctx.log.debug("All category bidders acknowledged for campaign {}", campaignId.value)
          notifyCampaignChanged(campaignId, affectedCategories, isActive = true, siteAllowlist, targetCategories,
            configEdit)
          replyTo ! CampaignRegistered(campaignId)
          Effect.none

        case CampaignGone(campaignId) =>
          removeCampaign(state, campaignId)

        case CampaignRemovalAcknowledged(campaignId, acknowledged, expected) =>
          if (acknowledged.size < expected.size) {
            ctx.log.warn(
              "Campaign {} removal: {}/{} categories acknowledged",
              campaignId,
              acknowledged.size,
              expected.size
            )
          } else {
            ctx.log.debug("Campaign {} removal: all {} categories acknowledged", campaignId, expected.size)
          }
          // Notify AFTER bidders have acknowledged (campaign is now inactive/paused)
          notifyCampaignChanged(campaignId, expected, isActive = false)
          Effect.none

        case GetCampaignsFor(categoryId, replyTo) =>
          replyTo ! CampaignsForResult(state.categories.getOrElse(categoryId, Map.empty).keys.toVector)
          Effect.none

        case GetAllCategories(replyTo) =>
          replyTo ! AllCategoriesResult(state.categories.keySet.toVector.sorted)
          Effect.none

        case GetFillerCampaigns(replyTo) =>
          replyTo ! FillerCampaignsResult(state.fillerCampaigns)
          Effect.none

        case Reconcile =>
          val expected = state.categories.size
          publishToCategories(state.categories, acked => ReconcileCompleted(acked, expected))
          Effect.none

        case ReconcileCompleted(acknowledged, expected) =>
          val now = System.currentTimeMillis()
          lastReconcile = Some((acknowledged.size, expected, now))
          if (acknowledged.size < expected) {
            val since = degradedSinceMillis.getOrElse { degradedSinceMillis = Some(now); now }
            // Loud, greppable, persistent (repeats every tick while broken).
            // This is the signal that distinguishes a wedged singleton from an
            // empty marketplace — the ambiguity that cost hours on 2026-06-29.
            ctx.log.warn(
              "DEMAND REGISTRATION DEGRADED: {}/{} categories acknowledged (degraded for {}s) — advertiser demand is not fully reaching auctions; check the campaign-directory singleton",
              acknowledged.size,
              expected,
              (now - since) / 1000
            )
          } else {
            degradedSinceMillis.foreach { s =>
              ctx.log.warn(
                "DEMAND REGISTRATION RECOVERED: all {} categories acknowledged after {}s degraded",
                expected,
                (now - s) / 1000
              )
            }
            degradedSinceMillis = None
            ctx.log.info("Reconcile completed: all {} categories acknowledged", expected)
          }
          Effect.none

        case GetHealth(replyTo) =>
          val (acked, expected, at) = lastReconcile.getOrElse((0, 0, 0L))
          replyTo ! DemandHealthResult(
            categories = state.categories.size,
            lastAcked = acked,
            lastExpected = expected,
            lastReconcileAtMillis = at,
            degradedSinceMillis = degradedSinceMillis
          )
          Effect.none

        case RecoveryPublishCompleted(acknowledged, expected) =>
          if (acknowledged.size < expected) {
            ctx.log.warn(
              "Recovery publish completed: {}/{} categories acknowledged",
              acknowledged.size,
              expected
            )
          } else {
            ctx.log.info("Recovery publish completed: all {} categories acknowledged", expected)
          }
          Effect.none

        case Stop =>
          Effect.stop()
      }

      // Schedule periodic reconciliation
      timers.startTimerAtFixedRate(Reconcile, reconcileEvery)

      ctx.log.info("CampaignDirectory starting with DurableStateBehavior")

      DurableStateBehavior[Command, State](
        persistenceId = PersistenceId.ofUniqueId("singleton-campaign-directory"),
        emptyState = State.empty,
        commandHandler = commandHandler
      ).receiveSignal { case (state, org.apache.pekko.persistence.typed.state.RecoveryCompleted) =>
        // Rebuild ephemeral reverse index from recovered state
        campaignToCategories = state.categories.foldLeft(Map.empty[CampaignId, Set[CategoryId]]) {
          case (acc, (catId, campaigns)) =>
            campaigns.keys.foldLeft(acc) { (map, campId) =>
              map.updated(campId, map.getOrElse(campId, Set.empty) + catId)
            }
        }

        ctx.log.info(
          "CampaignDirectory recovered: {} categories, {} campaigns",
          state.categories.size,
          campaignToCategories.size
        )
        // After recovery, publish all state to CategoryBidderEntity shards
        publishToCategories(state.categories, RecoveryPublishCompleted(_, state.categories.size))
      }
    }
  }

  // ---------------- Protocol ----------------
  sealed trait Command extends promovolve.CborSerializable

  /** Read: return all known categories. */
  final case class GetAllCategories(replyTo: ActorRef[AllCategoriesResult]) extends Command
  final case class AllCategoriesResult(categories: Vector[CategoryId]) extends promovolve.CborSerializable

  /**
   * Read: demand-registration health (Layer-1 observability). A non-empty
   * `degradedSinceMillis` means fewer categories are acked than expected —
   * registrations are not landing and advertiser demand is not reaching the
   * auctions. Backs a future admin/health endpoint; lets an operator tell a
   * wedged singleton apart from a genuinely empty marketplace.
   */
  final case class GetHealth(replyTo: ActorRef[DemandHealthResult]) extends Command
  final case class DemandHealthResult(
      categories: Int, // categories currently in the directory map
      lastAcked: Int, // categories acked at the last reconcile
      lastExpected: Int, // categories expected at the last reconcile
      lastReconcileAtMillis: Long, // when the last reconcile completed (0 = none yet)
      degradedSinceMillis: Option[Long] // set while acked < expected, else None
  ) extends promovolve.CborSerializable

  /**
   * Read: return campaigns opted-in to the filler pool (i.e.,
   * willing to bid on pages Gemini could not match against any
   * advertiser's content categories). Replies with a map of
   * `campaignId → advertiserId` so the auction path can look up the
   * advertiser shard directly.
   */
  final case class GetFillerCampaigns(
      replyTo: ActorRef[FillerCampaignsResult]
  ) extends Command
  final case class FillerCampaignsResult(campaigns: Map[CampaignId, AdvertiserId]) extends promovolve.CborSerializable

  /** Sent by CampaignEntity when it's hydrated/ready. */
  final case class CampaignReady(
      campaignId: CampaignId,
      advertiserId: AdvertiserId,
      categories: Set[CategoryId],
      sizes: Set[AdSize],
      maxCpm: CPM,
      dailyBudget: Budget,
      status: CampaignEntity.Status,
      replyTo: ActorRef[CampaignRegistered],
      // Tracked alongside `categories` so publisher-side consumers
      // can enumerate campaigns willing to bid on pages that Gemini
      // could not match against any advertiser's content categories.
      // Independent of `categories` — a campaign can have both
      // contextual categories and filler opt-in.
      bidOnUnmatchedContext: Boolean = false,
      // Campaign media targeting (mirrors CampaignEntity.State.siteAllowlist).
      // Forwarded onto CampaignChanged so per-site AuctioneerEntities can
      // detect a site-narrow exclusion and evict. Empty = bid everywhere.
      siteAllowlist: Set[String] = Set.empty,
      // True only for a genuine config edit (see CampaignChanged.configEdit).
      // Boot/recovery registrations and re-tells send false.
      configEdit: Boolean = false
  ) extends Command {
    // The campaign's FULL target category set is carried in `categories`
    // above (CampaignEntity.notifyDirectory passes state.categories). It is
    // forwarded onto CampaignChanged.targetCategories for topic-narrow
    // eviction; `affectedCategories` (the added/removed delta) drives the
    // bidder pub/sub but is NOT the campaign's full targeting.
    def targetCategories: Set[CategoryId] = categories
  }

  /** Acknowledgment that campaign is registered in directory */
  final case class CampaignRegistered(campaignId: CampaignId) extends promovolve.CborSerializable

  /** Sent when a campaign is being stopped/disabled. */
  final case class CampaignGone(campaignId: CampaignId) extends Command

  /** Query campaigns for a specific category. */
  final case class GetCampaignsFor(
      categoryId: CategoryId,
      replyTo: ActorRef[CampaignsForResult]
  ) extends Command
  final case class CampaignsForResult(campaigns: Vector[CampaignId]) extends promovolve.CborSerializable

  /** Maps categoryId -> (campaignId -> advertiserId) for sharding lookup */
  final case class State(
      categories: Map[CategoryId, Map[CampaignId, AdvertiserId]],
      // Opted-in campaigns willing to bid on pages Gemini classified
      // as "no match" — the filler-auction pool. Tracked separately so
      // enumeration is O(1) and independent of the per-category index.
      fillerCampaigns: Map[CampaignId, AdvertiserId] = Map.empty
  ) extends CborSerializable {

    /**
     * Add or remove a campaign from the filler pool. Returns new
     * state unchanged when the flag already matches the desired
     * value for this campaign.
     */
    def setFiller(
        campaignId: CampaignId,
        advertiserId: AdvertiserId,
        optIn: Boolean
    ): State = {
      val already = fillerCampaigns.contains(campaignId)
      if (optIn && !already) copy(fillerCampaigns = fillerCampaigns + (campaignId -> advertiserId))
      else if (!optIn && already) copy(fillerCampaigns = fillerCampaigns - campaignId)
      else this
    }

    /** Remove campaign from specified categories (caller provides from ephemeral reverse index) */
    def removeCampaignFromCategories(
        campaignId: CampaignId,
        fromCategories: Set[CategoryId]
    ): State =
      copy(categories = fromCategories.foldLeft(categories) { (cats, catId) =>
        cats.get(catId) match {
          case Some(campaigns) =>
            val next = campaigns - campaignId
            if (next.isEmpty) cats - catId else cats.updated(catId, next)
          case None => cats
        }
      })

    /**
     * Update campaign's category membership, returning new state and all affected categories.
     * Handles both addition to new categories and removal from old categories.
     * @param oldCategories caller provides from ephemeral reverse index (avoids O(n) scan)
     * @return (newState, affectedCategories) where affectedCategories = oldCategories ∪ newCategories
     */
    def updateCampaignCategories(
        campaignId: CampaignId,
        advertiserId: AdvertiserId,
        oldCategories: Set[CategoryId],
        newCategories: Set[CategoryId]
    ): (State, Set[CategoryId]) = {
      val affected = oldCategories ++ newCategories
      val toRemove = oldCategories -- newCategories
      val toAdd = newCategories -- oldCategories

      // Remove from old categories
      val afterRemoval = toRemove.foldLeft(categories) { (cats, catId) =>
        val updated = cats.getOrElse(catId, Map.empty) - campaignId
        if (updated.isEmpty) cats - catId else cats.updated(catId, updated)
      }

      // Add to new categories (with advertiserId for sharding lookup)
      val afterAddition = toAdd.foldLeft(afterRemoval) { (cats, catId) =>
        cats.updated(catId, cats.getOrElse(catId, Map.empty) + (campaignId -> advertiserId))
      }

      (copy(categories = afterAddition), affected)
    }

    /** Publish all categories to CategoryBidderEntity shards. */
    def publishAll(fn: (CategoryId, Map[CampaignId, AdvertiserId]) => Unit): Unit =
      categories.foreach { case (categoryId, campaigns) =>
        fn(categoryId, campaigns)
      }

    /** Publish only specified categories (for incremental updates). */
    def publishCategories(
        categoryIds: Set[CategoryId]
    )(fn: (CategoryId, Map[CampaignId, AdvertiserId]) => Unit): Unit =
      categoryIds.foreach { categoryId =>
        fn(categoryId, categories.getOrElse(categoryId, Map.empty))
      }
  }

  // ---------------- Persistent State ----------------

  /** Internal: all category bidders have acknowledged the campaign update */
  private[advertiser] final case class CategoryBiddersAcknowledged(
      campaignId: CampaignId,
      affectedCategories: Set[CategoryId],
      replyTo: ActorRef[CampaignRegistered],
      // Forwarded onto the CampaignChanged event for site-narrow eviction.
      siteAllowlist: Set[String] = Set.empty,
      // The campaign's FULL target category set, forwarded onto the
      // CampaignChanged event for topic-narrow eviction.
      targetCategories: Set[CategoryId] = Set.empty,
      // Forwarded onto CampaignChanged.configEdit (see CampaignReady).
      configEdit: Boolean = false
  ) extends Command

  /** Internal: reconcile completed with results */
  private final case class ReconcileCompleted(
      acknowledged: Set[CategoryId],
      expected: Int
  ) extends Command

  /** Internal: campaign removal publish completed */
  private final case class CampaignRemovalAcknowledged(
      campaignId: CampaignId,
      acknowledged: Set[CategoryId],
      expected: Set[CategoryId]
  ) extends Command

  /** Internal: recovery publish completed */
  private final case class RecoveryPublishCompleted(
      acknowledged: Set[CategoryId],
      expected: Int
  ) extends Command

  case object Stop extends Command

  object State {
    def empty: State = State(categories = Map.empty)
  }

  private case object Reconcile extends Command
}
