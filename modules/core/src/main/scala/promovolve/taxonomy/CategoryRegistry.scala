package promovolve.taxonomy

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.cluster.ddata.typed.scaladsl.{DistributedData, Replicator}
import org.apache.pekko.cluster.ddata.{LWWMap, LWWMapKey, SelfUniqueAddress}
import org.apache.pekko.cluster.typed.{ClusterSingleton, SingletonActor}
import promovolve.{CategoryId, SiteId}

/** CategoryRegistry - Cluster singleton aggregating publisher-declared taxonomy categories.
  *
  * == Purpose ==
  * When publishers register with the platform, they declare which IAB taxonomy
  * categories their content covers. This registry aggregates all declared categories
  * so advertisers can browse available targeting options.
  *
  * == Consistency Model ==
  * Uses DData (LWWMap) with '''WriteLocal''' for low-latency updates. This means:
  *  - Updates are immediately visible on this node
  *  - Other nodes converge '''eventually''' (typically sub-second in healthy cluster)
  *  - Queries return '''locally cached''' data, not fresh reads from replicator
  *
  * '''Staleness expectation''': After Register/Unregister, queries may return stale
  * data until the next `Changed` event is received (typically milliseconds).
  * This is acceptable for advertiser API browsing use cases.
  *
  * == Data Flow ==
  * {{{
  * SiteEntity (on config load)
  *   → RegisterPublisherCategories(siteId, categories)
  *   → DData LWWMap update (WriteLocal)
  *   → Changed event received
  *   → Local cache updated + derived indices recomputed
  * }}}
  *
  * == Relationship to Other Components ==
  *  - '''CampaignDirectory''': Tracks which campaigns target which categories (demand side)
  *  - '''CategoryRegistry''': Tracks which publishers have which categories (supply side)
  *  - '''TaxonomyRankerEntity''': Scores categories per-site using Thompson Sampling
  *
  * @see [[TAXONOMY_RANKING.md]] for category scoring details
  */
object CategoryRegistry {

  // ---------- DData Response Types ----------
  private type SubscribeResponse = Replicator.SubscribeResponse[LWWMap[String, Set[String]]]
  private type UpdateResponse    = Replicator.UpdateResponse[LWWMap[String, Set[String]]]
  /** LWWMap of publisher -> their categories (single source of truth) */
  private val PublisherCategoriesKey: LWWMapKey[String, Set[String]] =
    LWWMapKey[String, Set[String]]("publisher-categories")

  // ---------- Singleton Init ----------
  def init(system: ActorSystem[?]): ActorRef[Command] = {
    val singleton = ClusterSingleton(system)
    singleton.init(
      SingletonActor(apply(), "category-registry")
        .withStopMessage(Stop)
        .withSettings(
          org.apache.pekko.cluster.typed.ClusterSingletonSettings(system).withRole("singleton")
        )
    )
  }

  // ---------- Behavior ----------
  def apply(): Behavior[Command | SubscribeResponse | UpdateResponse] = Behaviors.setup { ctx =>
    given system: ActorSystem[?]  = ctx.system
    given node: SelfUniqueAddress = DistributedData(system).selfUniqueAddress

    val replicator = DistributedData(system).replicator

    ctx.log.info(
      "CategoryRegistry singleton starting on node {}",
      node.uniqueAddress.address
    )

    // Subscribe to DData changes
    replicator ! Replicator.Subscribe(PublisherCategoriesKey, ctx.self)

    def active(state: DerivedState): Behavior[Command | SubscribeResponse | UpdateResponse] =
      Behaviors.receiveMessage {

        case RegisterPublisherCategories(publisherId, categories) =>
          if (categories.isEmpty) {
            ctx.log.warn(
              "Ignoring empty category registration for publisher {}",
              publisherId
            )
            Behaviors.same
          } else {
            val pubKey     = encodePublisher(publisherId)
            val catStrings = encodeCategories(categories)

            // Use explicit .put() with node context for proper LWWMap semantics
            replicator ! Replicator.Update(
              PublisherCategoriesKey,
              LWWMap.empty[String, Set[String]],
              Replicator.WriteLocal,
              ctx.self
            )(_.put(node, pubKey, catStrings))

            ctx.log.info(
              "Registered {} categories for publisher {}",
              categories.size,
              publisherId
            )
            ctx.log.debug(
              "Publisher {} categories: {}",
              publisherId,
              catStrings.mkString(", ")
            )

            Behaviors.same
          }

        case UnregisterPublisher(publisherId) =>
          val pubKey = encodePublisher(publisherId)

          if (!state.publisherCategories.contains(pubKey)) {
            ctx.log.debug(
              "Unregister for unknown publisher {} (no-op)",
              publisherId
            )
            Behaviors.same
          } else {
            replicator ! Replicator.Update(
              PublisherCategoriesKey,
              LWWMap.empty[String, Set[String]],
              Replicator.WriteLocal,
              ctx.self
            )(_.remove(node, pubKey))

            ctx.log.info("Unregistered publisher {}", publisherId)
            Behaviors.same
          }

        case GetAllCategories(replyTo) =>
          replyTo ! AllCategories(state.allCategories.map(decodeCategory))
          Behaviors.same

        case GetCategoryStats(replyTo) =>
          val stats = state.categoryToPublishers.map { case (cat, pubs) =>
            CategorySummary(decodeCategory(cat), pubs.size)
          }.toVector.sortBy(_.categoryId.value)

          replyTo ! CategoryStatsResponse(
            categories      = stats,
            totalPublishers = state.publisherCategories.size,
            totalCategories = state.allCategories.size
          )
          Behaviors.same

        case GetPublishersForCategory(categoryId, replyTo) =>
          val catKey    = categoryId.value
          val publishers = state.categoryToPublishers.getOrElse(catKey, Set.empty)
          replyTo ! PublishersForCategory(categoryId, publishers.map(decodePublisher))
          Behaviors.same

        // Handle DData subscription responses (publisher map updates)
        case changed @ Replicator.Changed(PublisherCategoriesKey) =>
          val newEntries = changed.get(PublisherCategoriesKey).entries
          val newState   = DerivedState.from(newEntries)

          // Log convergence metrics for observability
          val pubDelta = newState.publisherCategories.size - state.publisherCategories.size
          val catDelta = newState.allCategories.size - state.allCategories.size

          if (pubDelta != 0 || catDelta != 0) {
            ctx.log.info(
              "DData changed: publishers={} ({}{}), categories={} ({}{})",
              newState.publisherCategories.size,
              if (pubDelta >= 0) "+" else "",
              pubDelta,
              newState.allCategories.size,
              if (catDelta >= 0) "+" else "",
              catDelta
            )
          } else {
            ctx.log.debug(
              "DData changed (no size change): publishers={}, categories={}",
              newState.publisherCategories.size,
              newState.allCategories.size
            )
          }

          active(newState)

        // Ignore other subscription responses
        case _: SubscribeResponse =>
          Behaviors.same

        // Ignore update responses (fire-and-forget with WriteLocal)
        case _: UpdateResponse =>
          Behaviors.same

        case Stop =>
          ctx.log.info("CategoryRegistry singleton stopping")
          Behaviors.stopped
      }

    active(DerivedState.empty)
  }

  private def encodePublisher(siteId: SiteId): String       = siteId.value
  private def decodePublisher(s: String): SiteId            = SiteId(s)
  private def encodeCategories(cats: Set[CategoryId]): Set[String] = cats.map(_.value)
  private def decodeCategory(s: String): CategoryId         = CategoryId(s)

  // ---------- Protocol ----------
  sealed trait Command extends promovolve.CborSerializable

  /** Publisher declares their taxonomy categories.
    *
    * Called when SiteEntity loads/reloads its configuration. '''Replaces''' any
    * previously registered categories for this publisher (not merge).
    *
    * @param publisherId The site/publisher identifier
    * @param categories  Full set of categories this publisher covers
    */
  final case class RegisterPublisherCategories(
      publisherId: SiteId,
      categories: Set[CategoryId]
  ) extends Command

  /** Publisher removed or deactivated - removes all category associations */
  final case class UnregisterPublisher(publisherId: SiteId) extends Command

  // ---------- DData Keys ----------

  /** Get all available categories (lightweight, for dropdowns/autocomplete) */
  final case class GetAllCategories(replyTo: ActorRef[AllCategories]) extends Command

  // ---------- Type Encode/Decode Helpers ----------
  // Centralized conversion to prevent encoding mistakes from spreading

  // ---------- Responses ----------
  final case class AllCategories(categories: Set[CategoryId]) extends promovolve.CborSerializable

  /** Lightweight stats without publisher details (for listings) */
  final case class CategorySummary(
      categoryId: CategoryId,
      publisherCount: Int
  ) extends promovolve.CborSerializable

  final case class CategoryStatsResponse(
      categories: Vector[CategorySummary],
      totalPublishers: Int,
      totalCategories: Int
  ) extends promovolve.CborSerializable

  /** Publishers for a single category (for detail view) */
  final case class PublishersForCategory(
      categoryId: CategoryId,
      publishers: Set[SiteId]
  ) extends promovolve.CborSerializable

  /** Get category summary stats (counts only, no publisher details) */
  private final case class GetCategoryStats(replyTo: ActorRef[CategoryStatsResponse]) extends Command

  /** Get publishers for a specific category (for detailed view/pagination) */
  private final case class GetPublishersForCategory(
      categoryId: CategoryId,
      replyTo: ActorRef[PublishersForCategory]
  ) extends Command

  /** Precomputed derived state (recomputed on each DData change) */
  private final case class DerivedState(
      publisherCategories: Map[String, Set[String]],
      categoryToPublishers: Map[String, Set[String]],
      allCategories: Set[String]
  )

  // ---------- Internal ----------
  private case object Stop extends Command

  private object DerivedState {
    val empty: DerivedState = DerivedState(Map.empty, Map.empty, Set.empty)

    def from(publisherCategories: Map[String, Set[String]]): DerivedState = {
      val categoryToPublishers = publisherCategories.foldLeft(Map.empty[String, Set[String]]) {
        case (acc, (pubId, cats)) =>
          cats.foldLeft(acc) { (a, cat) =>
            a.updatedWith(cat) {
              case Some(pubs) => Some(pubs + pubId)
              case None       => Some(Set(pubId))
            }
          }
      }
      val allCategories = publisherCategories.values.flatten.toSet
      DerivedState(publisherCategories, categoryToPublishers, allCategories)
    }
  }
}