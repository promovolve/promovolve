package promovolve.publisher

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.cluster.ddata.*
import org.apache.pekko.cluster.ddata.typed.scaladsl.{DistributedData, Replicator}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.state.scaladsl.{DurableStateBehavior, Effect}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.util.Timeout
import promovolve.*
import promovolve.advertiser.CampaignDirectory
import promovolve.auction.AuctioneerEntity
import promovolve.auction.AuctioneerEntity.AdSlotSpec
import promovolve.browser.UrlNormalizer
import promovolve.taxonomy.{CategoryRegistry, IABTaxonomy, TieredCategory}

import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpMethods, StatusCodes, Uri, headers}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/** Sharded entity representing a publisher's site.
  *
  * A site:
  *   - Belongs to a publisher
  *   - Owns its configuration (persisted with DurableStateBehavior)
  *   - Runs scheduled crawls based on cron configuration
  *   - Classifies page content using IAB taxonomy
  *   - Sends classified pages to AuctioneerEntity for auction
  *
  * Lifecycle:
  *   - Created via Initialize command (typically from API, after publisher registers the site)
  *   - Config can be updated via UpdateConfig command
  *   - Shutdown via Shutdown command (from PublisherEntity.DeleteSite)
  *
  * Note: SiteEntity persists its own config, so it can recover independently after node restarts
  * without waiting for PublisherEntity to re-initialize it.
  */
object SiteEntity {

  /** True for loopback / RFC-1918 LAN / .local hosts — useful to pick
    * HTTP over HTTPS when verifying dev publisher sites. */
  def isPrivateHost(host: String): Boolean = {
    val h = host.toLowerCase.takeWhile(_ != ':') // strip :port
    h == "localhost" ||
      h.startsWith("127.") ||
      h.startsWith("10.") ||
      h.startsWith("192.168.") ||
      h.endsWith(".local") ||
      (h.startsWith("172.") && {
        val second = h.stripPrefix("172.").takeWhile(_ != '.').toIntOption.getOrElse(0)
        second >= 16 && second <= 31
      })
  }

  /** Verification-fetch URLs to try, in order. Public hosts try HTTPS first
    * then HTTP — most managed hosts (incl. WordPress) 301 http→https, which
    * the plain-HTTP-only fetch used to trip over. Private/dev hosts stay
    * HTTP-only (no cert / self-signed on LAN). Pure + testable. */
  def verificationCandidates(host: String): List[String] = {
    val schemes = if (isPrivateHost(host)) List("http") else List("https", "http")
    schemes.map(s => s"$s://$host/.well-known/promovolve.txt")
  }

  /** Redirect targets we refuse to follow during host verification when the
    * site host is public — a pragmatic SSRF guard against pivots to internal
    * literals (loopback / RFC-1918 / link-local / unspecified). Literal-only;
    * NOT DNS-rebinding-proof (no resolution on the actor path). The token is
    * an unguessable UUID matched on body content, so residual risk is low. */
  def isBlockedRedirectTarget(host: String): Boolean = {
    val h = host.toLowerCase.takeWhile(_ != ':')
    isPrivateHost(h) || h.startsWith("169.254.") || h == "0.0.0.0" || h == "::1" || h == "[::1]"
  }

  /** Serve-time host canonicalization: drop a leading `www.` so a site
    * verified as `example.com` also fills on `www.example.com` and vice-versa
    * (the common WordPress www/non-www split). Lowercases; preserves `:port`. */
  def canonicalHost(host: String): String = {
    val h = host.toLowerCase
    if (h.startsWith("www.")) h.drop(4) else h
  }

  /** The value both the `.well-known` file and the DNS TXT record must carry. */
  def verificationRecord(token: String): String = s"promovolve-site-verification=$token"

  /** DNS TXT record NAME a publisher adds to prove control of the host when
    * they can't serve the `.well-known` file (locked-down managed hosting —
    * e.g. lower-tier WordPress.com, which owns the web root). Underscore-
    * prefixed like `_acme-challenge` / `_dmarc` so it never collides with
    * SPF/DMARC or other apex TXT. Port is stripped (DNS has none) and a
    * leading `www.` canonicalized so www/apex share one record. */
  def dnsVerificationName(host: String): String =
    s"_promovolve.${canonicalHost(host.takeWhile(_ != ':'))}"

  /** True if any TXT record carries the expected verification value. The JDK
    * DNS provider returns TXT chunks that may be wrapped in quotes (and a
    * record split into <=255-byte segments comes back space-joined), so quotes
    * are stripped before matching. Pure + testable. */
  def dnsRecordMatches(records: Iterable[String], expected: String): Boolean =
    records.exists(_.replace("\"", "").contains(expected))

  /** Blocking DNS TXT lookup via the JDK's `jdk.naming.dns` provider (no extra
    * dependency). Returns the raw TXT strings for `name`, or Nil if it has
    * none. MUST run on a blocking dispatcher — the caller wraps it. */
  def lookupTxt(name: String): List[String] = {
    val env = new java.util.Hashtable[String, String]()
    env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory")
    env.put("java.naming.provider.url", "dns:")
    val dctx = new javax.naming.directory.InitialDirContext(env)
    try {
      val txt = dctx.getAttributes(name, Array("TXT")).get("TXT")
      if (txt == null) Nil
      else {
        val buf = scala.collection.mutable.ListBuffer.empty[String]
        val en  = txt.getAll
        while (en.hasMore) buf += en.next().toString
        buf.toList
      }
    } finally dctx.close()
  }


  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("site-entity")

  /** Append `item` to a Vector treated as a most-recent-first ring
    * buffer of capacity `cap`. Pure, testable.  Used by SiteEntity's
    * observation log. */
  def appendBounded[A](vec: Vector[A], item: A, cap: Int): Vector[A] =
    if (cap <= 0) Vector.empty else (item +: vec).take(cap)

  // ---------- Site Configuration ----------
  /** DData key for pacing config distribution */
  val PacingConfigKey: LWWMapKey[SiteId, PacingConfig] = LWWMapKey("site-pacing-config")

  /** DData key for ad product blocklist distribution */
  val AdProductBlocklistKey: LWWMapKey[SiteId, CachedAdProductBlocklist] = LWWMapKey("site-adproduct-blocklist")

  /** DData key for verified host distribution — AdServer uses this for serve-time host check */
  val VerifiedHostKey: LWWMapKey[SiteId, String] = LWWMapKey("site-verified-host")

  /** Cached ad product blocklist for fast lookup by AdServer */
  final case class CachedAdProductBlocklist(categories: Set[AdProductCategoryId]) extends CborSerializable {
    def contains(cat: AdProductCategoryId): Boolean = categories.contains(cat)
    def contains(cat: Option[AdProductCategoryId]): Boolean = cat.exists(categories.contains)
  }

  // ---------- Behavior ----------
  def apply(
      siteId: SiteId,
      sharding: ClusterSharding,
      categoryRegistry: ActorRef[CategoryRegistry.Command],
      campaignDirectory: ActorRef[CampaignDirectory.Command],
      llmProvider: IABTaxonomy.Provider,
      geminiRateLimiter: Option[ActorRef[GeminiRateLimiter.Command]] = None,
  )(using system: ActorSystem[?]): Behavior[Command] =
    Behaviors.withTimers[Command] { timers =>
    Behaviors
      .setup[Command] { ctx =>

        given ExecutionContext = ctx.executionContext

        // Helper to get auctioneer ref
        def auctioneerRef: EntityRef[AuctioneerEntity.Command] =
          sharding.entityRefFor(AuctioneerEntity.TypeKey, siteId.value)

        // Mutable assistant reference (initialized when we have config)
        var assistant: Option[IABTaxonomy] = None
        var demandCategories: Option[Set[String]] = None

        // Single-flight guard for on-demand (serve-triggered) classification.
        // Keyed by NORMALIZED url. A breaking story's first traffic burst sends
        // many concurrent ClassifyUrl commands for the same page; without this
        // each would fire an independent Gemini call and blow the rate budget
        // (GeminiRateLimiter, ~8 RPM free tier). Entries are cleared in
        // ContentAnalyzed / FailedToAnalyzeContent so a failed attempt retries.
        // Transient — re-created empty on restart, which is safe (a stuck entry
        // would only suppress re-classification until the actor restarts).
        var pendingClassifications: Set[String] = Set.empty

        // Floor CPM sweep optimizer. Populated at RecoveryCompleted (None
        // during construction is expected — the actor restores its optimizer
        // in the recovery signal handler below).
        var floorSweepOptimizer: Option[FloorSweepOptimizer] = None

        // Per-category sweep optimizers — always on; per-category floors are
        // One instance per serving category, lazily created on the first
        // category-tagged signal. Transient — rebuilt from
        // `state.floorSweepSnapshotByCategory` on recovery. The site-level
        // `floorSweepOptimizer` keeps running alongside as the fallback
        // (and, in shadow mode, the only floor that is actually applied).
        var floorSweepOptimizerByCategory: Map[String, FloorSweepOptimizer] = Map.empty

        // Latest (bidderCount, bid) observed per category, from the per-category
        // AuctionOutcomeReport. Drives the bid-derived shortcut: a MONOPOLY
        // category (1 bidder) sets floor = that bid directly — no sweep needed,
        // no drift, instant adaptation. Competitive categories (≥2) fall back
        // to the sweep. Transient; rebuilt as auctions report.
        var categoryBidInfo: Map[String, (Int, Double)] = Map.empty


        // Sweep-optimizer tuning, read once from the `floor-optimizer`
        // HOCON block. Missing keys fall back to FloorSweepOptimizer.Config
        // defaults, so this is safe even when the block is absent. Used by
        // every `new FloorSweepOptimizer(...)` below so the duty cycle is
        // operator-tunable without a recompile.
        val floorSweepConfig: FloorSweepOptimizer.Config =
          if (ctx.system.settings.config.hasPath("promovolve.floor-optimizer"))
            FloorSweepOptimizer.configFrom(ctx.system.settings.config.getConfig("promovolve.floor-optimizer"))
          else
            FloorSweepOptimizer.Config()

        // In-memory ring buffer of recent floor-CPM RL decisions. Surfaces
        // production agent behavior to the dashboard without writing to
        // a journal. Capped so a long-lived SiteEntity doesn't grow this
        // unboundedly. ~100 entries × 15 min/entry ≈ last 25 hours of
        // observations. Re-created empty on actor restart.
        val FloorObservationCap: Int = 100
        var recentFloorObservations: Vector[FloorObservation] = Vector.empty

        def recordFloorObservation(o: FloorObservation): Unit = {
          recentFloorObservations = SiteEntity.appendBounded(recentFloorObservations, o, FloorObservationCap)
        }

        // Find-or-create the per-category optimizer, seeding a fresh one from
        // the persisted per-category floor (or the site floor) and the shared
        // per-site minimum. Mutates the in-memory map; the snapshot is
        // persisted at the next FloorCpmObservationTick (same intra-tick
        // durability contract as the site-level optimizer's record* calls).
        def categoryOptimizer(category: String, state: State): FloorSweepOptimizer =
          floorSweepOptimizerByCategory.getOrElse(category, {
            val opt = new FloorSweepOptimizer(siteId, floorSweepConfig)
            opt.setInitialFloor(state.floorCpmByCategory.getOrElse(category, state.floorCpm).toDouble)
            opt.setMinFloor(state.minFloorCpm.toDouble)
            state.floorSweepSnapshotByCategory.get(category).foreach(opt.restore)
            floorSweepOptimizerByCategory = floorSweepOptimizerByCategory.updated(category, opt)
            ctx.log.info("SiteEntity {} per-category sweep optimizer created for category={}", siteId.value, category)
            opt
          })

        // Journal one sweep-cycle decision. `category = None` is the
        // site-wide sweep; `Some(cat)` is a per-category sweep. Called from
        // the observation tick's `thenRun` (post-persist) so a crash can't
        // double-write (Init re-derives the same payload on replay). The
        // writer is optional and must never throw into the entity.
        def journalDecision(category: Option[String], d: FloorSweepOptimizer.CycleDecision): Unit =
          FloorDecisionRecorder.current.foreach { writer =>
            try
              writer.writeDecision(
                siteId       = siteId.value,
                ts           = java.time.Instant.now(),
                argmaxFloor  = d.argmaxFloor,
                prevArgmax   = d.prevArgmax,
                cycleRevenue = d.cycleRevenue,
                cycleImps    = d.cycleImps,
                candidates   = d.candidates,
                category     = category,
              )
            catch {
              case ex: Throwable =>
                ctx.log.warn("SiteEntity {} floor-decision journal write failed: {}", siteId.value, ex.getMessage)
            }
          }

        // Advance every per-category optimizer one observation window and
        // return the updated (floor, snapshot) maps to fold into the persisted
        // state, plus any completed-cycle decisions (logged here; journaled by
        // the caller post-persist). The floors are NOT pushed to the
        // auctioneer/AdServer in shadow mode — the site floor still rules.
        def observeAllCategories(state: State)
            : (Map[String, CPM], Map[String, FloorSweepOptimizer.Snapshot], Vector[(String, FloorSweepOptimizer.CycleDecision)]) =
          {
            var floors    = Map.empty[String, CPM]
            var snaps     = Map.empty[String, FloorSweepOptimizer.Snapshot]
            var decisions = Vector.empty[(String, FloorSweepOptimizer.CycleDecision)]
            floorSweepOptimizerByCategory.foreach { case (cat, opt) =>
              val info = categoryBidInfo.get(cat)
              // EXPLICIT zero-bidder report — the sole bidder's creative was
              // flagged/rejected/revoked so it no longer bids. Gate strictly
              // on Some((0, _)): a MISSING entry (None) is the transient
              // restart state (categoryBidInfo is rebuilt from reports), which
              // must retain the persisted floor, not collapse to min.
              val isZeroDemand = info.exists { case (n, _) => n == 0 }
              val bidDerived = info.flatMap { case (n, b) =>
                FloorSweepOptimizer.bidDerivedFloor(n, b, state.minFloorCpm.toDouble)
              }
              if (isZeroDemand) {
                // ZERO DEMAND: the reserve pinned to the departed bid is no
                // longer justified. Collapse to the publisher minimum
                // IMMEDIATELY rather than draining it over a full sweep
                // (~90 min dev / ~23 h prod) that would keep the floor
                // elevated and lock out other legitimate bidders meanwhile.
                // resetToMinFloor also wipes the optimizer's stale high anchor
                // so a later re-entrant bidder sweeps clean from the minimum.
                opt.resetToMinFloor()
                val floor = state.minFloorCpm.toDouble
                floors = floors.updated(cat, CPM(floor))
                snaps  = snaps.updated(cat, opt.snapshot())
                val prev = state.floorCpmByCategory.get(cat).map(_.toDouble)
                if (!prev.contains(floor)) {
                  decisions = decisions :+ (cat -> FloorSweepOptimizer.CycleDecision(
                    argmaxFloor  = floor,
                    prevArgmax   = prev,
                    cycleRevenue = 0.0,
                    cycleImps    = 0L,
                    candidates   = Vector(FloorDecisionCandidate(floor, 0.0, 0L)),
                  ))
                  ctx.log.info(
                    "SiteEntity {} [{}] per-category ZERO-DEMAND floor cat={} -> min ${}",
                    siteId.value, "enforce", cat, f"$floor%.4f",
                  )
                }
              } else bidDerived match {
                // BID-DERIVED MONOPOLY: exactly one bidder in this category →
                // the revenue-optimal floor IS its bid (it pays its full value
                // and still clears). Computed directly — no sweep, no noise
                // drift, follows a bid change instantly. Journal only on change.
                case Some(floor) =>
                  floors = floors.updated(cat, CPM(floor))
                  opt.setInitialFloor(floor)            // keep optimizer in sync for a later flip to competitive
                  snaps = snaps.updated(cat, opt.snapshot())
                  val prev = state.floorCpmByCategory.get(cat).map(_.toDouble)
                  if (!prev.contains(floor)) {
                    decisions = decisions :+ (cat -> FloorSweepOptimizer.CycleDecision(
                      argmaxFloor  = floor,
                      prevArgmax   = prev,
                      cycleRevenue = 0.0,
                      cycleImps    = 0L,
                      candidates   = Vector(FloorDecisionCandidate(floor, 0.0, 0L)),
                    ))
                    ctx.log.info(
                      "SiteEntity {} [{}] per-category MONOPOLY floor cat={} = bid ${}",
                      siteId.value, "enforce", cat, f"$floor%.4f",
                    )
                  }
                // COMPETITIVE (≥2 bidders) or not-yet-known: run the sweep.
                case _ =>
                  val r = opt.observe()
                  floors = floors.updated(cat, CPM(opt.currentFloorCpm))
                  snaps  = snaps.updated(cat, opt.snapshot())
                  r.flatMap(_.completedCycle).foreach { d =>
                    decisions = decisions :+ (cat -> d)
                    ctx.log.info(
                      "SiteEntity {} [{}] per-category floor cat={} argmax={} prev={} cycleRev={} imps={}",
                      siteId.value, "enforce", cat,
                      f"${d.argmaxFloor}%.4f",
                      d.prevArgmax.map(p => f"$p%.4f").getOrElse("-"),
                      f"${d.cycleRevenue}%.4f",
                      d.cycleImps: java.lang.Long,
                    )
                  }
              }
            }
            (floors, snaps, decisions)
          }

        // Cached traffic ratio from AdServer (published via DData)
        var cachedTrafficRatio: Double = 1.0
        var cachedTrafficWarmedUp: Boolean = false

        def initializeAssistant(): Unit =
          assistant = Some(new IABTaxonomy(llmProvider, geminiRateLimiter))

        // Shared classification trigger for the on-demand serve path (ClassifyUrl). Fires the
        // Gemini taxonomy call off the actor thread via pipeToSelf; the result
        // is handled on the actor thread in ContentAnalyzed / FailedToAnalyzeContent.
        def triggerClassification(url: String, text: String, discoveredSlots: List[AdSlotConfig]): Unit =
          // Gated on demand EXISTING (a cost guard — don't classify when there
          // are zero advertisers), but classification itself is demand-INDEPENDENT
          // (full taxonomy); the demand set is not passed in.
          (assistant, demandCategories) match {
            case (Some(iabTaxonomy), Some(cats)) =>
              // cats is the FALLBACK only (used if the LLM fails); classification
              // itself is full-taxonomy / demand-independent.
              ctx.pipeToSelf(iabTaxonomy.analyzeTaxonomy(url, text, fallbackCategories = cats)) {
                case Success(selections) => ContentAnalyzed(url, text, selections, discoveredSlots)
                case Failure(ex)         => FailedToAnalyzeContent(url, ex)
              }
            case (Some(_), None) =>
              ctx.log.warn(
                "SiteEntity {} skipping classification for url={} — demand categories not yet available",
                siteId.value, url
              )
            case (None, _) =>
              ctx.log.warn(
                "SiteEntity {} received content before assistant was ready; ignoring url={}",
                siteId.value, url
              )
          }

        // DData replicator for pacing config distribution
        val replicator = DistributedData(system).replicator
        given selfUniqueAddress: SelfUniqueAddress = DistributedData(system).selfUniqueAddress
        given cluster: org.apache.pekko.cluster.Cluster = org.apache.pekko.cluster.Cluster(system)

        // DData update response handler (ignored)
        val ddataResponseAdapter: ActorRef[Replicator.UpdateResponse[?]] =
          ctx.messageAdapter[Replicator.UpdateResponse[?]](_ => IgnoreDDataResponse)

        // Subscribe to traffic ratio from AdServer via DData
        type TrafficRatioMap = LWWMap[SiteId, delivery.AdServer.TrafficRatioData]
        val trafficRatioAdapter = ctx.messageAdapter[Replicator.SubscribeResponse[ReplicatedData]] {
          case changed: Replicator.Changed[?] =>
            changed.key match {
              case delivery.AdServer.TrafficRatioKey =>
                val data = changed.dataValue.asInstanceOf[TrafficRatioMap].get(siteId)
                data match {
                  case Some(tr) => TrafficRatioUpdated(tr.ratio, tr.isWarmedUp)
                  case None     => IgnoreDDataResponse
                }
              case _ => IgnoreDDataResponse
            }
          case _ => IgnoreDDataResponse
        }
        replicator ! Replicator.Subscribe(delivery.AdServer.TrafficRatioKey, trafficRatioAdapter)

        def publishPacingConfig(config: PacingConfig): Unit = {
          ctx.log.info("SiteEntity {} publishing pacing config to DData: dayDurationSeconds={}",
            siteId.value, config.dayDurationSeconds)
          replicator ! Replicator.Update(
            PacingConfigKey,
            LWWMap.empty[SiteId, PacingConfig],
            Replicator.WriteLocal,
            ddataResponseAdapter
          )(_.put(selfUniqueAddress, siteId, config))
          ctx.log.info("SiteEntity {} published pacing config to DData", siteId.value)
        }

        def publishAdProductBlocklist(blocklist: Set[AdProductCategoryId]): Unit = {
          val cached = CachedAdProductBlocklist(blocklist)
          replicator ! Replicator.Update(
            AdProductBlocklistKey,
            LWWMap.empty[SiteId, CachedAdProductBlocklist],
            Replicator.WriteLocal,
            ddataResponseAdapter
          )(_.put(selfUniqueAddress, siteId, cached))
          ctx.log.info("SiteEntity {} published ad product blocklist to DData ({} categories)", siteId.value, blocklist.size)
        }

        def publishVerifiedHost(host: Option[String]): Unit = {
          host match {
            case Some(h) =>
              replicator ! Replicator.Update(
                VerifiedHostKey,
                LWWMap.empty[SiteId, String],
                Replicator.WriteLocal,
                ddataResponseAdapter
              )(_.put(selfUniqueAddress, siteId, h))
              ctx.log.info("SiteEntity {} published verified host '{}' to DData", siteId.value, h)
            case None =>
              ctx.log.debug("SiteEntity {} no verified host to publish", siteId.value)
          }
        }

        /** Remove this site's entry from the verified-host DData map. AdServer
          * subscribers see the change as VerifiedHostUpdated(None), which
          * closes the serve gate cluster-wide.
          */
        def removeVerifiedHost(): Unit = {
          replicator ! Replicator.Update(
            VerifiedHostKey,
            LWWMap.empty[SiteId, String],
            Replicator.WriteLocal,
            ddataResponseAdapter
          )(_.remove(selfUniqueAddress, siteId))
          ctx.log.info("SiteEntity {} removed verified host from DData", siteId.value)
        }

        /** Normalize a URL or domain to a lowercase host (including port if non-standard).
          * Strips scheme and path but keeps host:port.
          */
        def normalizeHost(urlOrDomain: String): String = {
          val d = urlOrDomain.trim.toLowerCase
          val noScheme = if (d.startsWith("http://")) d.drop(7)
                         else if (d.startsWith("https://")) d.drop(8)
                         else d
          noScheme.takeWhile(c => c != '/' && c != '?')
        }

        /** Get the full host (with port) from the site's seed URL, falling back to domain */
        def siteHost(config: SiteConfig): String =
          normalizeHost(config.seedUrl)

        def registerCategories(config: SiteConfig): Unit = {
          val siteCategories = config.taxonomyIds.map(CategoryId(_))
          categoryRegistry ! CategoryRegistry.RegisterPublisherCategories(siteId, siteCategories)
        }

        def refreshDemandCategories(): Unit = {
          given Timeout = Timeout(10.seconds)
          ctx.pipeToSelf(
            campaignDirectory.ask[CampaignDirectory.AllCategoriesResult](CampaignDirectory.GetAllCategories(_)).map(_.categories)
          ) {
            case Success(cats) => DemandCategoriesRefreshed(cats)
            case Failure(ex)   => DemandCategoriesRefreshFailed(ex)
          }
        }

        def deriveSlots(config: SiteConfig): List[AdSlotSpec] =
          if (config.slots.nonEmpty)
            config.slots.map { slot =>
              val sz = AdSize(slot.width, slot.height)
              // TODO multi-size source — see fluid-creatives refactor.
              // AdSlotConfig today carries one (width,height); when it
              // grows a per-template size list, source declaredSizes
              // from there.
              AdSlotSpec(
                slotId        = SlotId(slot.slotId),
                declaredSizes = List(sz),
                computedSize  = sz,
                prior         = slot.prior,
                floorOverride = slot.floorOverride,
              )
            }
          else {
            // Fallback for sites created before slot config was added
            val sz = AdSize(300, 250)
            List(
              AdSlotSpec(
                slotId        = SlotId("slot-1"),
                declaredSizes = List(sz),
                computedSize  = sz,
              )
            )
          }

        def setupFromConfig(config: SiteConfig): Unit = {
          val normalizedIds = config.taxonomyIds.map(TieredCategory.normalize)
          val changed = config.taxonomyIds.diff(normalizedIds)
          if (changed.nonEmpty)
            ctx.log.warn("SiteEntity {} normalized IAB 1.0 IDs to Content Taxonomy 2.1: {}",
              siteId.value, changed.mkString(", "))
          initializeAssistant()
          registerCategories(config.copy(taxonomyIds = normalizedIds))
        }

        // Command handler for DurableStateBehavior
        def commandHandler(state: State, command: Command): Effect[State] =
          command match {
            case Register(publisherId, replyTo) =>
              if (state.isRegistered) {
                // Already registered - just reply (idempotent)
                ctx.log.info("SiteEntity {} already registered", siteId.value)
                Effect.none.thenReply(replyTo)(_ => Registered(siteId))
              } else {
                ctx.log.info("SiteEntity {} registering for publisher {}", siteId.value, publisherId.value)
                val newState = state.withPublisherId(publisherId)
                Effect.persist(newState).thenReply(replyTo)(_ => Registered(siteId))
              }

            case Initialize(config, replyTo) =>
              if (state.isInitialized) {
                // Already initialized — reply idempotently WITHOUT touching
                // state. Persisting the incoming config here would let a
                // re-create replace the live SiteConfig (a slot-less create
                // payload wipes slots/taxonomy); config changes must go
                // through UpdateConfig.
                ctx.log.info("SiteEntity {} already initialized, ignoring re-Initialize", siteId.value)
                Effect.none.thenReply(replyTo)(_ => Initialized(siteId))
              } else {
                ctx.log.info(
                  "SiteEntity {} initializing for publisher {}, scheduling crawl with cron: {}",
                  siteId.value,
                  config.publisherId.value,
                  config.cronSchedule
                )
                val token = VerificationToken.generate()
                val newState = state.withPublisherId(config.publisherId).withConfig(config).withVerificationToken(token)
                ctx.log.info("SiteEntity {} generated verification token, awaiting domain verification", siteId.value)
                Effect
                  .persist(newState)
                  .thenRun(_ => setupFromConfig(config))
                  .thenReply(replyTo)(_ => Initialized(siteId))
              }

            case UpdateConfig(config, replyTo) =>
              ctx.log.info("SiteEntity {} updating configuration", siteId.value)
              val newState = state.withConfig(config)
              Effect
                .persist(newState)
                .thenRun(_ => setupFromConfig(config))
                .thenReply(replyTo)(_ => ConfigUpdated(siteId))

            case GetConfig(replyTo) =>
              Effect.none.thenReply(replyTo)(_ => ConfigResult(state.config))

            case GetPageClassifications(replyTo) =>
              Effect.none.thenReply(replyTo)(_ => PageClassificationsResult(state.pageClassifications))

            case UpdatePacingConfig(config, replyTo) =>
              ctx.log.info("SiteEntity {} updating pacing config: dayDurationSeconds={}",
                siteId.value, config.dayDurationSeconds)
              val newState = state.withPacingConfig(config)
              Effect
                .persist(newState)
                .thenRun { _ =>
                  publishPacingConfig(config.copy(bidWeight = state.bidWeight, floorCpm = state.floorCpm.toDouble))
                  // Reschedule floor CPM observation with scaled interval
                  val ds = config.dayDurationSeconds
                  val interval = if (ds < 86400) math.max(5, (ds.toDouble / 86400.0 * 900).toInt).seconds else 15.minutes
                  timers.startTimerAtFixedRate("floor-cpm-observation", FloorCpmObservationTick, interval)
                  ctx.log.info("SiteEntity {} floor observation interval rescaled to {}", siteId.value, interval)
                }
                .thenReply(replyTo)(_ => PacingConfigUpdated(siteId))

            case GetPacingConfig(replyTo) =>
              Effect.none.thenReply(replyTo)(_ => state.pacingConfig)

            case UpdateFloorCpm(cpm, replyTo) =>
              ctx.log.info("SiteEntity {} updating floor CPM to {}", siteId.value, cpm.toDouble)
              floorSweepOptimizer.foreach(_.setInitialFloor(cpm.toDouble))
              val newState = state.withFloorCpm(cpm)
              Effect
                .persist(newState)
                .thenRun { _ =>
                  auctioneerRef ! AuctioneerEntity.UpdateFloorCpm(cpm)
                  publishPacingConfig(state.pacingConfig.copy(bidWeight = state.bidWeight, floorCpm = cpm.toDouble))
                }
                .thenReply(replyTo)(_ => FloorCpmUpdated(siteId, cpm))

            case GetFloorCpm(replyTo) =>
              Effect.none.thenReply(replyTo)(_ => state.floorCpm)

            case GetCategoryFloors(replyTo) =>
              // Mirror of the observation-timer interval derivation (env
              // override, else scaled from the pacing day) so dashboards can
              // convert the sweep's tick counters into wall-clock estimates.
              val obsIntervalSecs = sys.env.get("FLOOR_OBSERVATION_INTERVAL_SECONDS")
                .flatMap(s => scala.util.Try(s.trim.toInt).toOption)
                .filter(_ > 0)
                .getOrElse {
                  val daySeconds = state.pacingConfig.dayDurationSeconds
                  if (daySeconds < 86400) math.max(5, (daySeconds.toDouble / 86400.0 * 900).toInt)
                  else 900
                }
              val sweepStates = floorSweepOptimizerByCategory.map { case (cat, opt) =>
                val snap = opt.snapshot()
                cat -> promovolve.proto.site.CategorySweepState(
                  phase                 = snap.phase,
                  cursor                = snap.cursor,
                  candidateCount        = snap.candidates.size,
                  ticksThisCandidate    = snap.ticksThisCandidate,
                  ticksPerCandidate     = floorSweepConfig.ticksPerCandidate,
                  exploitTicksRemaining = snap.exploitTicksRemaining,
                  currentFloor          = snap.currentFloor,
                )
              }
              Effect.none.thenReply(replyTo)(_ =>
                promovolve.proto.site.CategoryFloors(
                  floors       = state.floorCpmByCategory.map { case (k, v) => k -> v.toDouble },
                  // Live demand context (ephemeral, from auction reports):
                  // lets the dashboard distinguish a floor pegged to a lone
                  // bid (1), a sweep-governed competitive one (2+), and a
                  // historical row nobody bids into anymore (absent).
                  bidderCounts = categoryBidInfo.map { case (k, (n, _)) => k -> n },
                  observedBids = categoryBidInfo.map { case (k, (_, b)) => k -> b },
                  sweepStates  = sweepStates,
                  observationIntervalSeconds = obsIntervalSecs,
                ))

            case GetFloorSweepEvidence(replyTo) =>
              // Returns the LIVE snapshot from the in-memory optimizer. The
              // persisted `state.floorSweepSnapshot` is only refreshed
              // periodically (when the optimizer's state changes
              // meaningfully); the dashboard wants what's true now.
              Effect.none.thenReply(replyTo)(_ =>
                FloorSweepEvidenceResponse(floorSweepOptimizer.map(_.snapshot())))

            case GetMinFloorCpm(replyTo) =>
              Effect.none.thenReply(replyTo)(_ => state.minFloorCpm)

            case UpdateMinFloorCpm(cpm, replyTo) =>
              ctx.log.info("SiteEntity {} updating min floor CPM to {}", siteId.value, cpm.toDouble)
              val newState = state.withMinFloorCpm(cpm)
              // If current floor is below the new minimum, raise it
              val adjustedState = if (newState.floorCpm < cpm) newState.withFloorCpm(cpm) else newState
              Effect
                .persist(adjustedState)
                .thenRun { _ =>
                  floorSweepOptimizer.foreach(_.setMinFloor(cpm.toDouble))
                  if (adjustedState.floorCpm != state.floorCpm) {
                    auctioneerRef ! AuctioneerEntity.UpdateFloorCpm(adjustedState.floorCpm)
                    publishPacingConfig(state.pacingConfig.copy(bidWeight = adjustedState.bidWeight, floorCpm = adjustedState.floorCpm.toDouble))
                  }
                }
                .thenReply(replyTo)(_ => MinFloorCpmUpdated(siteId, cpm))

            case AuctioneerStarted =>
              // The auctioneer's admin slot-floor map is in-memory only.
              // Re-arm it from persisted slot config on every auctioneer
              // (re)start — without this, an auctioneer-only restart
              // (shard rebalance, crash) forgets every admin override and
              // re-admits bids below them until the next edit or
              // SiteEntity restart. An empty map is sent too: it clears
              // overrides that were removed while the auctioneer was down.
              val adminMap = state.config.map(_.slots).getOrElse(Nil)
                .flatMap(s => s.floorOverride.map(cpm => SlotId(s.slotId) -> cpm))
                .toMap
              ctx.log.info(
                "SiteEntity {} re-arming restarted auctioneer with {} admin slot floors",
                siteId.value, adminMap.size)
              auctioneerRef ! AuctioneerEntity.UpdateAdminSlotFloors(adminMap)
              Effect.none

            case UpdateSlotFloorOverride(slotId, floor, replyTo) =>
              state.config match {
                case None =>
                  Effect.none.thenReply(replyTo)(_ => SlotFloorOverrideUpdated(siteId, slotId, floor))
                case Some(cfg) =>
                  val byId   = cfg.slots.map(s => s.slotId -> s).toMap
                  val target = byId.get(slotId)
                  target match {
                    case None =>
                      // Slot hasn't been discovered yet — refuse silently
                      // (don't fabricate slot config; that would let admin
                      // set floors for typos that never resolve).
                      ctx.log.warn(
                        "SiteEntity {} UpdateSlotFloorOverride: slotId={} not found in config; ignoring",
                        siteId.value, slotId
                      )
                      Effect.none.thenReply(replyTo)(_ => SlotFloorOverrideUpdated(siteId, slotId, None))
                    case Some(existing) =>
                      val updated   = existing.copy(floorOverride = floor)
                      val newSlots  = cfg.slots.map(s => if (s.slotId == slotId) updated else s)
                      val newConfig = cfg.copy(slots = newSlots)
                      Effect
                        .persist(state.withConfig(newConfig))
                        .thenRun { _ =>
                          // Push the full admin-overrides map to the
                          // auctioneer. Empty values mean "no admin
                          // override" — those slots fall back to RL /
                          // prior at scoring time.
                          val adminMap = newSlots
                            .flatMap(s => s.floorOverride.map(cpm => SlotId(s.slotId) -> cpm))
                            .toMap
                          auctioneerRef ! AuctioneerEntity.UpdateAdminSlotFloors(adminMap)
                        }
                        .thenReply(replyTo)(_ => SlotFloorOverrideUpdated(siteId, slotId, floor))
                  }
              }

            case GetSlots(replyTo) =>
              val slots = state.config.map(_.slots).getOrElse(Nil)
              Effect.none.thenReply(replyTo)(_ => SlotsResult(slots))

            case UpdateBidWeight(weight, replyTo) =>
              val clamped = math.max(0.1, math.min(1.0, weight))
              ctx.log.info("SiteEntity {} updating bid weight to {}", siteId.value, clamped)
              val newState = state.withBidWeight(clamped)
              Effect
                .persist(newState)
                .thenRun(_ => publishPacingConfig(state.pacingConfig.copy(bidWeight = clamped, floorCpm = state.floorCpm.toDouble)))
                .thenReply(replyTo)(_ => BidWeightUpdated(siteId, clamped))

            case GetBidWeight(replyTo) =>
              Effect.none.thenReply(replyTo)(_ => state.bidWeight)


            case UpdateAcceptsFillerTraffic(accept, replyTo) =>
              ctx.log.info("SiteEntity {} set acceptsFillerTraffic={}", siteId.value, accept)
              Effect
                .persist(state.withAcceptsFillerTraffic(accept))
                .thenReply(replyTo)(_ => AcceptsFillerTrafficUpdated(siteId, accept))

            case GetAcceptsFillerTraffic(replyTo) =>
              Effect.none.thenReply(replyTo)(_ => state.acceptsFillerTraffic)

            case ForceVerifyHost(host, replyTo) =>
              ctx.log.info("SiteEntity {} force-verifying host '{}' (dev/test)", siteId.value, host)
              val newState = state.withVerifiedHost(host)
              Effect.persist(newState)
                .thenRun { _ =>
                  publishVerifiedHost(Some(host))
                }
                .thenReply(replyTo)(_ => VerificationSucceeded(siteId, host))

            // ---------- Ad Product Blocklist Commands ----------
            case BlockAdProductCategories(categories, replyTo) =>
              val newCategories = categories -- state.adProductBlocklist
              if (newCategories.nonEmpty) {
                val newState = state.blockAdProducts(newCategories)
                Effect
                  .persist(newState)
                  .thenRun(_ => publishAdProductBlocklist(newState.adProductBlocklist))
                  .thenReply(replyTo)(_ => AdProductCategoriesBlocked(siteId, newCategories))
              } else {
                Effect.none.thenReply(replyTo)(_ => AdProductCategoriesBlocked(siteId, Set.empty))
              }

            case UnblockAdProductCategories(categories, replyTo) =>
              val toRemove = categories.intersect(state.adProductBlocklist)
              if (toRemove.nonEmpty) {
                val newState = state.unblockAdProducts(toRemove)
                Effect
                  .persist(newState)
                  .thenRun(_ => publishAdProductBlocklist(newState.adProductBlocklist))
                  .thenReply(replyTo)(_ => AdProductCategoriesUnblocked(siteId, toRemove))
              } else {
                Effect.none.thenReply(replyTo)(_ => AdProductCategoriesUnblocked(siteId, Set.empty))
              }

            case GetAdProductBlocklist(replyTo) =>
              Effect.none.thenReply(replyTo)(_ => AdProductBlocklistResponse(siteId, state.adProductBlocklist))

            // ---------- Site Verification ----------

            case GetVerificationToken(replyTo) =>
              state.config match {
                case Some(config) =>
                  state.verificationToken match {
                    case Some(token) =>
                      Effect.none.thenReply(replyTo)(_ => VerificationTokenFound(siteId, token, siteHost(config)))
                    case None =>
                      // Generate token on-demand for sites created before verification was added
                      val token = VerificationToken.generate()
                      ctx.log.info("SiteEntity {} generating verification token on-demand", siteId.value)
                      Effect
                        .persist(state.withVerificationToken(token))
                        .thenReply(replyTo)(_ => VerificationTokenFound(siteId, token, siteHost(config)))
                  }
                case None =>
                  Effect.none.thenReply(replyTo)(_ => VerificationTokenNotAvailable(siteId))
              }

            case GetVerificationStatus(replyTo) =>
              Effect.none.thenReply(replyTo)(_ => VerificationStatusResult(
                siteId, state.isVerified, state.verifiedHost, state.verificationToken.map(_.value)
              ))

            case RequestVerification(replyTo) =>
              (state.verificationToken, state.config) match {
                case (Some(token), Some(config)) =>
                  val host = siteHost(config)
                  val expected = SiteEntity.verificationRecord(token.value)
                  val candidates = SiteEntity.verificationCandidates(host)
                  ctx.log.info("SiteEntity {} verifying host '{}' via {}", siteId.value, host, candidates.mkString(", "))
                  val http = Http(ctx.system.classicSystem)

                  // Fetch one candidate, following up to `hopsLeft` redirects
                  // (WP hosts commonly 301 http→https or www→apex). Returns the
                  // 200 body (Right) or a failure reason (Left). Redirects to
                  // internal literals are refused when the site host is public.
                  def fetchFollowing(uri: Uri, hopsLeft: Int): Future[Either[String, String]] =
                    http.singleRequest(HttpRequest(method = HttpMethods.GET, uri = uri)).flatMap { response =>
                      response.status match {
                        case StatusCodes.OK =>
                          Unmarshal(response.entity).to[String].map(Right(_))
                        case status if status.isRedirection && hopsLeft > 0 =>
                          response.entity.discardBytes()
                          response.header[headers.Location] match {
                            case Some(loc) if loc.uri.scheme.nonEmpty =>
                              val next = loc.uri
                              val nextHost = next.authority.host.address.toLowerCase
                              if (!SiteEntity.isPrivateHost(host) && SiteEntity.isBlockedRedirectTarget(nextHost))
                                Future.successful(Left(s"Refused redirect to non-public host '$nextHost'"))
                              else
                                fetchFollowing(next, hopsLeft - 1)
                            case Some(_) =>
                              Future.successful(Left(s"HTTP ${status.intValue()} with relative Location (not followed)"))
                            case None =>
                              Future.successful(Left(s"HTTP ${status.intValue()} without Location"))
                          }
                        case status =>
                          response.entity.discardBytes()
                          Future.successful(Left(s"HTTP ${status.intValue()}"))
                      }
                    }

                  // Try each candidate URL in order; the first 200 whose body
                  // carries the token wins. `lastErr` surfaces the most recent
                  // failure if none succeed.
                  def attempt(remaining: List[String], lastErr: String): Future[VerificationCheckResult] =
                    remaining match {
                      case Nil =>
                        Future.successful(VerificationCheckResult(success = false, host, Some(lastErr), replyTo))
                      case url :: rest =>
                        fetchFollowing(Uri(url), 3).flatMap {
                          case Right(body) if body.trim.contains(expected) =>
                            Future.successful(VerificationCheckResult(success = true, host, None, replyTo))
                          case Right(body) =>
                            attempt(rest, s"Token mismatch: expected '$expected', got '${body.trim.take(100)}'")
                          case Left(err) =>
                            attempt(rest, err)
                        }.recoverWith { case ex =>
                          attempt(rest, s"Connection failed: ${ex.getMessage}")
                        }
                    }

                  // DNS TXT fallback — for hosts that can't serve the
                  // `.well-known` file (locked-down managed hosting, e.g.
                  // lower-tier WordPress.com). Blocking JNDI lookup on the
                  // built-in blocking-IO dispatcher so it never stalls the
                  // entity dispatcher.
                  val blockingEc: ExecutionContext =
                    ctx.system.classicSystem.dispatchers.lookup("pekko.actor.default-blocking-io-dispatcher")
                  val dnsName = SiteEntity.dnsVerificationName(host)
                  def dnsCheck(): Future[Either[String, Unit]] =
                    Future {
                      if (SiteEntity.dnsRecordMatches(SiteEntity.lookupTxt(dnsName), expected)) Right(())
                      else Left(s"no TXT at $dnsName containing '$expected'")
                    }(blockingEc).recover {
                      case ex => Left(s"lookup failed for $dnsName: ${ex.getMessage}")
                    }

                  // Verify via the HTTP file first; if that fails, fall back to
                  // the DNS TXT record. Succeed if EITHER method passes.
                  val verifyF: Future[VerificationCheckResult] =
                    attempt(candidates, "No verification candidates").flatMap {
                      case ok if ok.success => Future.successful(ok)
                      case httpFail =>
                        dnsCheck().map {
                          case Right(_) =>
                            VerificationCheckResult(success = true, host, None, replyTo)
                          case Left(dnsErr) =>
                            VerificationCheckResult(success = false, host,
                              Some(s"HTTP: ${httpFail.reason.getOrElse("failed")}; DNS: $dnsErr"), replyTo)
                        }
                    }

                  ctx.pipeToSelf(verifyF) {
                    case Success(result) => result
                    case Failure(ex) =>
                      VerificationCheckResult(success = false, host, Some(s"Verification failed: ${ex.getMessage}"), replyTo)
                  }
                  Effect.none
                case _ =>
                  Effect.none.thenReply(replyTo)(_ => VerificationNotReady(siteId, "Site not initialized or no verification token"))
              }

            case VerificationCheckResult(true, host, _, replyTo) =>
              ctx.log.info("SiteEntity {} verification SUCCEEDED for host '{}'", siteId.value, host)
              val newState = state.withVerifiedHost(host)
              Effect
                .persist(newState)
                .thenRun { _ =>
                  publishVerifiedHost(Some(host))
                }
                .thenReply(replyTo)(_ => VerificationSucceeded(siteId, host))

            case VerificationCheckResult(false, _, reason, replyTo) =>
              ctx.log.warn("SiteEntity {} verification FAILED: {}", siteId.value, reason.getOrElse("unknown"))
              Effect.none.thenReply(replyTo)(_ => VerificationFailed(siteId, reason.getOrElse("Verification failed")))

            // ---------- Floor CPM Optimization ----------
            case AuctionOutcomeReport(outcome, category) =>
              category match {
                // Site-wide aggregate (legacy): feeds the site optimizer.
                case None => floorSweepOptimizer.foreach(_.recordAuctionOutcome(outcome))
                // Per-category report: feeds that category's optimizer AND
                // records (bidderCount, bid) for the bid-derived monopoly path.
                case Some(c) =>
                  categoryOptimizer(c, state).recordAuctionOutcome(outcome)
                  categoryBidInfo = categoryBidInfo.updated(c, (outcome.totalBidders, outcome.maxObservedCpm))
              }
              Effect.none

            case ImpressionServed(revenueDollars, category, slotId) =>
              // Admin-overridden slots are excluded from floor learning:
              // they clear against the human-set override regardless of the
              // sweep's active candidate, so their revenue arrives no matter
              // what floor is being measured — counting it credits the
              // candidate with revenue it didn't cause and biases the argmax
              // toward floors the sweep-governed inventory can't support.
              // Revenue dashboards are unaffected (they read tracking_events).
              val servedUnderOverride = slotId.exists(sid =>
                state.config.exists(_.slots.exists(s => s.slotId == sid && s.floorOverride.isDefined)))
              if (!servedUnderOverride) {
                // Attribution alignment: the SITE-WIDE sweep is only credited
                // for serves it actually priced — pages whose category has NO
                // learned floor (or no category at all: filler). Serves gated
                // by a per-category floor used to be credited here too, which
                // decoupled the site sweep's measurements from its lever:
                // every candidate scored the same category-floored revenue,
                // the profile went flat, and the argmax parked at the range
                // minimum forever (observed live 2026-07-06 pinned at $1.00
                // while all real traffic was category-priced).
                val pricedByFallback = category.forall(c => !state.floorCpmByCategory.contains(c))
                if (pricedByFallback) {
                  floorSweepOptimizer.foreach(_.recordServedImpression(revenueDollars))
                }
                category.foreach(c => categoryOptimizer(c, state).recordServedImpression(revenueDollars))
              }
              Effect.none

            case BudgetExhaustedServe =>
              floorSweepOptimizer.foreach(_.recordBudgetExhausted())
              Effect.none

            case FloorCpmObservationTick =>
              floorSweepOptimizer match {
                case Some(sweep) =>
                  val hour = java.time.LocalTime.now().getHour
                  val floorBefore = sweep.currentFloorCpm
                  val observeResult = sweep.observe()

                  recordFloorObservation(FloorObservation(
                    ts                = java.time.Instant.now(),
                    hour              = hour,
                    trafficShape      = if (cachedTrafficWarmedUp) cachedTrafficRatio else 0.0,
                    floorBefore       = floorBefore,
                    floorAfter        = sweep.currentFloorCpm,
                    epsilon           = sweep.epsilon,
                    observed          = observeResult.isDefined,
                    trainingLoss      = None,
                    slotOverrideCount = 0,
                  ))

                  // Advance the per-category optimizers in lock-step with the
                  // site-level sweep and fold their updates into the same
                  // persist (shadow mode logs + persists; it does not push).
                  val (catFloors, catSnaps, catDecisions) = observeAllCategories(state)

                  observeResult match {
                    case Some(FloorSweepOptimizer.ObserveResult(newFloor, completedCycle)) =>
                      val cpm = CPM(newFloor)
                      ctx.log.info(
                        "SiteEntity {} sweep floor CPM update: floor={} progress={}",
                        siteId.value,
                        f"$newFloor%.4f",
                        f"${sweep.epsilon}%.3f",
                      )
                      val newState = state
                        .withFloorCpm(cpm)
                        .withFloorSweepSnapshot(sweep.snapshot())
                        .withCategoryFloors(catFloors, catSnaps)
                        .withRecentFloorObservations(recentFloorObservations)
                      Effect.persist(newState).thenRun { _ =>
                        auctioneerRef ! AuctioneerEntity.UpdateFloorCpm(cpm)
                        // Enforce mode: push the learned per-category floors to
                        // the auctioneer (bid collection) — it forwards them to
                        // AdServer on CandidatesCollected for serve-time pricing.
                        auctioneerRef ! AuctioneerEntity.UpdateCategoryFloors(
                            newState.floorCpmByCategory.map { case (k, v) => CategoryId(k) -> v }
                          )
                        publishPacingConfig(state.pacingConfig.copy(bidWeight = state.bidWeight, floorCpm = cpm.toDouble))
                        // Journal completed-cycle decisions AFTER the persist:
                        // a crash before the persist replays Init, which
                        // re-derives the same payload — writing pre-persist
                        // would double-write. Site-wide (None) + per-category.
                        completedCycle.foreach(d => journalDecision(None, d))
                        catDecisions.foreach { case (cat, d) => journalDecision(Some(cat), d) }
                      }
                    case None =>
                      // Exploit phase tick — persist the updated snapshot
                      // anyway so phase counters survive restart.
                      val ns = state
                        .withFloorSweepSnapshot(sweep.snapshot())
                        .withCategoryFloors(catFloors, catSnaps)
                        .withRecentFloorObservations(recentFloorObservations)
                      Effect.persist(ns).thenRun { _ =>
                        // Keep the auctioneer's per-category map fresh between
                        // site-floor changes (per-category sweeps drift too).
                        auctioneerRef ! AuctioneerEntity.UpdateCategoryFloors(
                            ns.floorCpmByCategory.map { case (k, v) => CategoryId(k) -> v }
                          )
                        catDecisions.foreach { case (cat, d) => journalDecision(Some(cat), d) }
                      }
                  }

                case None =>
                  Effect.none
              }

            case GetRecentFloorObservations(limit, replyTo) =>
              Effect.none.thenReply(replyTo)(_ => FloorObservationsResult(recentFloorObservations.take(math.max(0, limit))))

            case ResetFloorAgent(replyTo) =>
              ctx.log.warn(
                "SiteEntity {} resetting floor optimizer (admin action) — discarding persisted snapshot",
                siteId.value,
              )
              // Wipe the optimizer and rebuild it in-place so the change
              // takes effect on the next observation tick without an actor
              // restart.
              val fresh = new FloorSweepOptimizer(siteId, floorSweepConfig)
              fresh.setInitialFloor(state.floorCpm.toDouble)
              fresh.setMinFloor(state.minFloorCpm.toDouble)
              floorSweepOptimizer = Some(fresh)
              floorSweepOptimizerByCategory = Map.empty
              recentFloorObservations = Vector.empty
              val newState = state.copy(
                floorSweepSnapshot           = None,
                floorCpmByCategory           = Map.empty,
                floorSweepSnapshotByCategory = Map.empty,
                recentFloorObservations     = Vector.empty,
              )
              Effect.persist(newState).thenReply(replyTo)(_ => FloorAgentReset(siteId))

            case TrafficRatioUpdated(ratio, isWarmedUp) =>
              cachedTrafficRatio = ratio
              cachedTrafficWarmedUp = isWarmedUp
              Effect.none

            case IgnoreDDataResponse =>
              Effect.none // Ignore DData update responses

            case Shutdown =>
              ctx.log.info("SiteEntity {} shutting down", siteId.value)
              Effect.stop()

            case Delete =>
              // Real deletion (from PublisherEntity.DeleteSite): clear the
              // durable state so the siteId is re-creatable from scratch, and
              // drop the verified host from DData so serving stops. A bare
              // Shutdown used to leave both behind — a "deleted" site kept
              // serving and its orphaned config shadowed any re-create.
              ctx.log.info("SiteEntity {} deleting durable state", siteId.value)
              removeVerifiedHost()
              Effect.persist(State.empty(siteId)).thenStop()

            case ClassifyUrl(url, text, _section, slots, replyTo) =>
              // On-demand, serve-triggered classification. Text is supplied by
              // the ad tag (live page DOM) instead of a crawl. Single-flight on
              // the normalized url so a traffic burst on a new page fires ONE
              // Gemini call. The serve path only sends this on a ServeIndex miss.
              val key = UrlNormalizer.normalize(url)
              val ready = assistant.isDefined && demandCategories.isDefined
              ClassifyDecision.decide(pendingClassifications.contains(key), ready) match {
                case ClassifyDecision.Accept =>
                  pendingClassifications = pendingClassifications + key
                  // Slot geometry from the live page (extractSlots). Empty is
                  // fine — ContentAnalyzed falls back to the configured slots.
                  // Fold the rendered-position signals into a SlotPrior here —
                  // this is the crawl-free replacement for the crawler's prior
                  // (same SlotPrior.computeQualityScore, signals from the live
                  // page instead of headless Playwright).
                  val discoveredSlots =
                    slots.iterator.map { s =>
                      val q = SlotPrior.computeQualityScore(
                        aboveFold          = s.aboveFold,
                        initialViewability = s.viewability,
                        region             = s.region,
                        textDensity        = s.textDensity,
                      )
                      AdSlotConfig(
                        slotId = s.slotId,
                        width  = s.width,
                        height = s.height,
                        prior  = Some(SlotPrior(qualityScore = q, aboveFold = s.aboveFold, region = s.region)),
                      )
                    }.toList
                  triggerClassification(url, text, discoveredSlots)
                  replyTo ! ClassifyAck(accepted = true, reason = ClassifyDecision.Accept.reason)
                  Effect.none
                case other =>
                  replyTo ! ClassifyAck(accepted = other.accepted, reason = other.reason)
                  Effect.none
              }

            case ContentAnalyzed(url, text, selections, discoveredSlots) =>
              // Release the single-flight slot (no-op for crawler-path urls).
              pendingClassifications = pendingClassifications - UrlNormalizer.normalize(url)
              ctx.log.info(
                "SiteEntity {} content analyzed for {}: {} categories",
                siteId,
                url,
                selections.size
              )
              ctx.log.debug(
                "SiteEntity {} selections: {}",
                siteId,
                selections.mkString(", ")
              )
              val categoryScores = selections.categoryScores
                .map { case (catId, conf) => TieredCategory.normalize(catId.value) -> conf.value }

              val slots = if (discoveredSlots.nonEmpty)
                discoveredSlots.map { slot =>
                  val sz = AdSize(slot.width, slot.height)
                  // TODO multi-size source — DetectedSlot reports a
                  // single observed size; declaredSizes mirrors it
                  // until the detector grows a candidate list.
                  AdSlotSpec(
                    slotId        = SlotId(slot.slotId),
                    declaredSizes = List(sz),
                    computedSize  = sz,
                    prior         = slot.prior,
                    floorOverride = slot.floorOverride,
                  )
                }
              else
                deriveSlots(state.config.get)

              val classifiedAt = Instant.now
              val persistedEntry = ClassificationEntry(
                categories   = categoryScores,
                slots        = slots.iterator.map(PersistedSlot.from).toVector,
                classifiedAt = classifiedAt.toEpochMilli,
              )
              // Activate request-detected slots into the site inventory so the
              // dashboard's Slots table + per-slot floor overrides populate from
              // real traffic — this replaces the crawler's old slot discovery.
              // Union by slotId: existing rows keep their learned prior / admin
              // floor override and only refresh geometry; unseen slots are added.
              val activatedConfig: Option[SiteConfig] =
                if (discoveredSlots.nonEmpty) state.config.map { cfg =>
                  val seen = cfg.slots.iterator.map(_.slotId).toSet
                  val refreshed = cfg.slots.map { e =>
                    discoveredSlots
                      .find(_.slotId == e.slotId)
                      .fold(e)(d =>
                        // Refresh geometry + the freshly-measured prior; keep
                        // the admin/RL floor override (prior is auto, override
                        // is human/learned).
                        e.copy(width = d.width, height = d.height, prior = d.prior.orElse(e.prior))
                      )
                  }
                  val added = discoveredSlots.filterNot(d => seen.contains(d.slotId))
                  cfg.copy(slots = refreshed ++ added)
                } else None
              def withActivated(s: State): State =
                activatedConfig.fold(s)(s.withConfig)

              val newStateOpt: Option[State] =
                if (categoryScores.isEmpty) {
                  // Gemini honestly said "no match" — none of the
                  // advertisers' demand categories describe this page.
                  // Route to the filler auction so opted-in campaigns
                  // (bidOnUnmatchedContext=true) get a shot at the slot.
                  // The auctioneer will find nobody willing if the pool
                  // is empty, in which case the slot legitimately stays
                  // unfilled rather than getting a forced-fit creative.
                  if (state.acceptsFillerTraffic) {
                    ctx.log.info(
                      "SiteEntity {} routing {} to filler auction (no contextual match)",
                      siteId, url
                    )
                    auctioneerRef ! AuctioneerEntity.FillerAuctionRequested(
                      url   = URL(url),
                      slots = slots,
                      ts    = classifiedAt
                    )
                    // Persist the filler entry too — Reevaluate / restart
                    // both rely on lastPage having an entry for the URL,
                    // and an empty categoryScores map is how the
                    // auctioneer tells filler pages apart from
                    // category-classified ones.
                    Some(withActivated(state).withClassification(url, persistedEntry))
                  } else {
                    ctx.log.info(
                      "SiteEntity {} dropping {} (no contextual match, filler traffic disabled)",
                      siteId, url
                    )
                    None
                  }
                } else {
                  auctioneerRef ! AuctioneerEntity.PageCategoriesClassified(
                    url            = URL(url),
                    categoryScores = categoryScores,
                    slots          = slots,
                    ts             = classifiedAt
                  )
                  Some(withActivated(state).withClassification(url, persistedEntry))
                }
              newStateOpt match {
                case Some(s) => Effect.persist(s)
                case None    => Effect.none
              }

            case FailedToAnalyzeContent(url, ex) =>
              // Release the single-flight slot so a later serve can retry.
              pendingClassifications = pendingClassifications - UrlNormalizer.normalize(url)
              ctx.log.error("SiteEntity {} taxonomy analysis failed for {}: {}", siteId.value, url, ex.getMessage)
              Effect.none

            case RefreshDemandCategories =>
              refreshDemandCategories()
              Effect.none

            case DemandCategoriesRefreshed(categories) =>
              val catIds = categories.map(_.value).toSet
              val next   = if (catIds.nonEmpty) Some(catIds) else None
              demandCategories = next
              ctx.log.info("SiteEntity {} demand categories refreshed: {} active",
                siteId.value, catIds.size)
              // Persist the last-known set so a restart recovers it instead of
              // None — avoids skipping classification during the cold-start
              // window while CampaignDirectory is unavailable. Persist on change
              // only, to keep the write rate near zero.
              if (next != state.demandCategories) Effect.persist(state.copy(demandCategories = next))
              else Effect.none

            case DemandCategoriesRefreshFailed(ex) =>
              ctx.log.warn("SiteEntity {} failed to refresh demand categories: {}, retrying in 5s",
                siteId.value, ex.getMessage)
              timers.startSingleTimer("retry-demand", RefreshDemandCategories, 5.seconds)
              Effect.none

          }

        DurableStateBehavior[Command, State](
          persistenceId  = PersistenceId.ofUniqueId(s"site-${siteId.value}"),
          emptyState     = State.empty(siteId),
          commandHandler = commandHandler
        ).receiveSignal { case (state, org.apache.pekko.persistence.typed.state.RecoveryCompleted) =>
          // On recovery, re-initialize from persisted config.
          // Hydrate the in-memory demand cache from persisted state so reads
          // and classification use the last-known set without asking the
          // singleton during its cold-start window.
          demandCategories = state.demandCategories
          state.config.foreach { config =>
            ctx.log.info(
              "SiteEntity {} recovered, resuming with config for publisher {}",
              siteId.value,
              config.publisherId.value
            )
            setupFromConfig(config)
          }
          // Publish pacing config, ad product blocklist, and verified host to DData on recovery
          publishPacingConfig(state.pacingConfig.copy(bidWeight = state.bidWeight, floorCpm = state.floorCpm.toDouble))
          publishAdProductBlocklist(state.adProductBlocklist)
          publishVerifiedHost(state.verifiedHost)
          // Replay admin per-slot floor overrides to AuctioneerEntity so
          // they survive restarts. RL overrides are transient and will
          // be recomputed at the next observation window.
          state.config.foreach { cfg =>
            val adminMap = cfg.slots
              .flatMap(s => s.floorOverride.map(cpm => SlotId(s.slotId) -> cpm))
              .toMap
            if (adminMap.nonEmpty)
              auctioneerRef ! AuctioneerEntity.UpdateAdminSlotFloors(adminMap)
          }
          // Replay persisted page classifications to AuctioneerEntity so the
          // auctioneer's `lastPage` is repopulated before the cluster sees
          // serve traffic — PeriodicReauction has something to chew on
          // without waiting for on-demand re-classification.
          if (state.pageClassifications.nonEmpty) {
            auctioneerRef ! AuctioneerEntity.RestoreClassifications(state.pageClassifications)
            ctx.log.info(
              "SiteEntity {} replayed {} page classifications to auctioneer",
              siteId.value, state.pageClassifications.size: java.lang.Integer,
            )
          }
          // Restore the floor CPM sweep optimizer. Restored from the
          // persisted `state.floorSweepSnapshot` if present, else starts
          // fresh.
          val sweep = new FloorSweepOptimizer(siteId, floorSweepConfig)
          sweep.setInitialFloor(state.floorCpm.toDouble)
          sweep.setMinFloor(state.minFloorCpm.toDouble)
          state.floorSweepSnapshot match {
            case Some(snap) =>
              sweep.restore(snap)
              ctx.log.info("SiteEntity {} restored sweep optimizer from persisted snapshot", siteId.value)
            case None =>
              ctx.log.info("SiteEntity {} sweep optimizer starts fresh", siteId.value)
          }
          floorSweepOptimizer = Some(sweep)
          // Hydrate the observation history (the per-site decision-log
          // dashboard) from persisted state — it survives restarts now;
          // each observation tick folds the working var back into the
          // persisted state.
          recentFloorObservations = state.recentFloorObservations
          // Per-category optimizers. Rebuilt
          // from persisted per-category snapshots, mirroring the site-level
          // restore above. Empty when the mode was never enabled.
          if (state.floorSweepSnapshotByCategory.nonEmpty) {
            state.floorSweepSnapshotByCategory.foreach { case (cat, snap) =>
              val opt = new FloorSweepOptimizer(siteId, floorSweepConfig)
              opt.setInitialFloor(state.floorCpmByCategory.getOrElse(cat, state.floorCpm).toDouble)
              opt.setMinFloor(state.minFloorCpm.toDouble)
              opt.restore(snap)
              floorSweepOptimizerByCategory = floorSweepOptimizerByCategory.updated(cat, opt)
            }
            ctx.log.info("SiteEntity {} restored {} per-category sweep optimizers (mode={})",
              siteId.value, state.floorSweepSnapshotByCategory.size: java.lang.Integer, "enforce")
          }
          // Floor observation interval. Two sources, in priority order:
          //   1. FLOOR_OBSERVATION_INTERVAL_SECONDS env override — explicit
          //      decoupling from day duration; lets us run real-day pacing
          //      (revenue counters accumulate over 24h) while keeping sweep
          //      cycles fast for observation.
          //   2. Auto-scale with `dayDurationSeconds` (default) — keeps the
          //      familiar "real day → 15 min ticks, fast sim-day → ~5s ticks"
          //      behavior.
          val daySeconds = state.pacingConfig.dayDurationSeconds
          val floorObsInterval = sys.env.get("FLOOR_OBSERVATION_INTERVAL_SECONDS")
            .flatMap(s => scala.util.Try(s.trim.toInt).toOption)
            .filter(_ > 0)
            .map(_.seconds)
            .getOrElse {
              if (daySeconds < 86400) {
                val scaled = math.max(5, (daySeconds.toDouble / 86400.0 * 900).toInt) // 900s = 15 min
                scaled.seconds
              } else 15.minutes
            }
          timers.startTimerAtFixedRate("floor-cpm-observation", FloorCpmObservationTick, floorObsInterval)
          ctx.log.info("SiteEntity {} floor CPM sweep optimizer initialized (floor={}, obsInterval={})",
            siteId.value, state.floorCpm.toDouble, floorObsInterval)

          // Start periodic demand category refresh
          timers.startTimerWithFixedDelay("refresh-demand", RefreshDemandCategories, 5.minutes)
          refreshDemandCategories()
          // STRIPPED: on-recovery bootstrap crawl. Under on-demand classification
          // we do NOT proactively crawl — a recovered site's lastPage is already
          // repopulated from persisted pageClassifications via RestoreClassifications
          // (above); anything not yet classified is filled lazily when a real
          // visitor hits the page (serve -> needText -> /v1/classify-page). This is
          // what stops every api restart from re-crawling the whole site.
          // See docs/design/ON_DEMAND_CLASSIFICATION.md.
        }
      }
      }

  // ---------- Protocol ----------
  sealed trait Command extends promovolve.CborSerializable

  // ---------- Internal ----------
  private sealed trait Internal extends Command

  /** Internal: DData update response (ignored) */
  private case object IgnoreDDataResponse extends Internal

  /** Internal: periodic floor CPM optimization observation tick */
  private case object FloorCpmObservationTick extends Internal

  /** Internal: traffic ratio updated via DData from AdServer */
  private case class TrafficRatioUpdated(ratio: Double, isWarmedUp: Boolean) extends Internal

  /** Auction outcome report from AuctioneerEntity (for floor CPM optimization).
    * `category = None` is the site-wide aggregate (feeds the site optimizer);
    * `Some(cat)` is a per-category report (feeds that category's optimizer
    * only). */
  /** Sent by AuctioneerEntity from its setup block on every (re)start.
    * Tell+tell handshake: SiteEntity replies with a fresh
    * UpdateAdminSlotFloors push built from persisted slot config, because
    * the auctioneer's override map is volatile and SiteEntity otherwise
    * re-pushes it only on ITS OWN restart or on edits.
    */
  case object AuctioneerStarted extends Command

  final case class AuctionOutcomeReport(
      outcome: FloorSweepOptimizer.AuctionOutcome,
      category: Option[String] = None,
  ) extends Command

  /** Served impression report from LearningEventLog (actual post-pacing
    * revenue). `category` is the winning creative's category, used to route
    * revenue to the per-category optimizer; the site optimizer ignores it. */
  final case class ImpressionServed(
      revenueDollars: Double,
      category: Option[String] = None,
      // Slot the impression served on. Lets the handler exclude
      // admin-overridden slots from floor LEARNING — their clearing price
      // is human-set, not governed by the sweep's candidate floor, so
      // their revenue would otherwise be credited to whatever candidate
      // happens to be under measurement. None (legacy senders) = counted.
      slotId: Option[String] = None,
  ) extends Command

  /** Budget exhaustion signal from AdServer (serve attempt denied due to budget) */
  case object BudgetExhaustedServe extends Command

  /** Crawler-derived prior for a slot — feeds into the per-slot
    * effective floor before any auction data is available. Refined
    * over time by the floor-CPM RL agent via `floorOverride` below.
    *
    *   qualityScore (0..1) is a weighted combination of crawl signals
    *   (above-fold, viewability, region, text density). Drives a
    *   ±50% multiplier on the site-level floor.
    */
  final case class SlotPrior(
      qualityScore: Double,
      aboveFold: Boolean,
      region: String,
  ) extends promovolve.CborSerializable

  object SlotPrior {

    /** Region quality weights — `article`/`main` are premium, sidebar
      * mid, chrome (footer/nav) discounted. `unknown` is neutral. */
    private def regionWeight(region: String): Double = region match {
      case "article" | "main" => 1.0
      case "header"           => 0.7
      case "aside"            => 0.6
      case "unknown"          => 0.5
      case "footer" | "nav"   => 0.2
      case _                  => 0.5
    }

    /** Compose a qualityScore in [0,1] from crawl-time signals. */
    def computeQualityScore(
        aboveFold: Boolean,
        initialViewability: Double,
        region: String,
        textDensity: Double,
    ): Double = {
      val foldScore = if (aboveFold) 1.0 else 0.0
      val v         = initialViewability.max(0.0).min(1.0)
      val r         = regionWeight(region)
      val d         = textDensity.max(0.0).min(1.0)
      // Weights sum to 1.0; tunable knobs.
      (0.35 * foldScore) + (0.25 * v) + (0.25 * r) + (0.15 * d)
    }
  }

  /** Ad slot declared on a site.
    *
    *   prior         — crawler-derived; written only by the crawler ingest path
    *   floorOverride — RL-learned or manual; written only by RL/admin code
    *
    * Both are Option so existing journaled slots deserialize with
    * defaults and the system falls through to the site-level floor.
    */
  final case class AdSlotConfig(
      slotId: String,
      width: Int,
      height: Int,
      prior: Option[SlotPrior] = None,
      floorOverride: Option[CPM] = None,
  ) extends promovolve.CborSerializable

  /** Per-slot effective floor: explicit override wins; otherwise the
    * site floor is scaled by the crawler prior's qualityScore into a
    * 0.5x..1.5x band; absent a prior, fall through to the site floor. */
  def effectiveFloor(slot: AdSlotConfig, siteFloor: CPM): CPM =
    slot.floorOverride match {
      case Some(cpm) => cpm
      case None =>
        slot.prior match {
          case Some(p) => siteFloor * (0.5 + p.qualityScore)
          case None    => siteFloor
        }
    }

  /** Configuration for a site's crawler and taxonomy analysis */
  final case class SiteConfig(
      publisherId: PublisherId,
      domain: String,
      seedUrl: String,
      cronSchedule: String, // Quartz cron expression, e.g. "0 0 2 * * ?" for 2am daily
      maxDepth: Int,
      concurrency: Int,
      hostRegex: String,
      targetElements: List[String],
      taxonomyIds: Set[String], // IAB taxonomy category IDs this site targets
      slots: List[AdSlotConfig] = Nil // Ad slots on this site
  ) extends promovolve.CborSerializable

  /** Persisted classification for one page — survives cluster restart so
    * AuctioneerEntity's in-memory `lastPage` cache can be repopulated
    * without re-crawling. Mirrors the tuple stored in AuctioneerEntity:
    *   (categoryScores, slots, classifiedAt)
    *
    * `categories` is empty for filler-classified pages (Gemini matched
    * no demand category) — AuctioneerEntity uses the empty map as the
    * filler-vs-categorised distinguisher during Reevaluate. */
  final case class ClassificationEntry(
      categories: Map[String, Double],
      slots: Vector[PersistedSlot],
      classifiedAt: Long,
  ) extends CborSerializable

  /** Persistent shape of an AdSlotSpec. Keeps width/height as separate
    * Ints rather than `AdSize` (a `(Int, Int)` opaque tuple) so Jackson
    * round-trips cleanly without depending on tuple support. */
  final case class PersistedSlot(
      slotId: String,
      width: Int,
      height: Int,
      declaredSizes: Vector[PersistedAdSize],
      prior: Option[SlotPrior],
      floorOverride: Option[CPM],
  ) extends CborSerializable

  final case class PersistedAdSize(width: Int, height: Int) extends CborSerializable

  object PersistedSlot {
    def from(spec: AdSlotSpec): PersistedSlot = PersistedSlot(
      slotId        = spec.slotId.value,
      width         = spec.computedSize.width,
      height        = spec.computedSize.height,
      declaredSizes = spec.declaredSizes.iterator.map(sz => PersistedAdSize(sz.width, sz.height)).toVector,
      prior         = spec.prior,
      floorOverride = spec.floorOverride,
    )
  }

  extension (slot: PersistedSlot) {
    def toAdSlotSpec: AdSlotSpec = AdSlotSpec(
      slotId        = SlotId(slot.slotId),
      declaredSizes = slot.declaredSizes.iterator.map(s => AdSize(s.width, s.height)).toList,
      computedSize  = AdSize(slot.width, slot.height),
      prior         = slot.prior,
      floorOverride = slot.floorOverride,
    )
  }

  /** Bound on the per-site persisted classification map. Matches
    * `AuctioneerEntity.Settings.maxPageCacheSize` — there's no value in
    * persisting more than the auctioneer would hold in memory. */
  val MaxPersistedClassifications: Int = 10000

  /** Pacing configuration for a site's ad serving.
    * Converted to SpendRatioPacing by AdServer when applied.
    */
  final case class PacingConfig(
      windowSeconds: Int = 60,             // Deprecated: no longer used by SpendRatioPacing
      testThrottleOverride: Option[Double] = None,  // For testing: fixed throttle probability (0.0-1.0)
      dayDurationSeconds: Int = 86400,     // Simulated day length (default: 24h = 86400s). Set lower for testing.
      warmupMode: Boolean = false,         // When true, record traffic patterns but don't serve ads. Use for learning traffic shape before campaign start.
      bidWeight: Double = 0.5,             // Scoring exponent: score = CTR × CPM^α. 0.3=discovery, 0.5=balanced, 0.7=revenue.
      floorCpm: Double = 0.50,            // Current floor CPM (synced to AdServer for serve-time filtering)
  ) extends CborSerializable

  /** On-demand, serve-triggered page classification. Text is extracted by the
    * ad tag in the live page (no crawl) and supplied here. Single-flighted by
    * normalized url inside the handler; the serve path only sends this on a
    * ServeIndex miss. See docs/design/ON_DEMAND_CLASSIFICATION.md. */
  final case class ClassifyUrl(
      url: String,
      text: String,
      section: Option[String],
      slots: Vector[ClassifySlot],
      replyTo: ActorRef[ClassifyAck],
  ) extends Command

  /** Slot geometry + rendered-position signals observed in the live page DOM
    * (the same signals crawler.js extractSlots produced, now measured in the
    * visitor's real viewport). The server folds these into a SlotPrior. They
    * default to neutral so a geometry-only request still classifies. */
  final case class ClassifySlot(
      slotId: String,
      width: Int,
      height: Int,
      aboveFold: Boolean = false,
      viewability: Double = 0.0,
      region: String = "unknown",
      textDensity: Double = 0.0,
  ) extends promovolve.CborSerializable

  /** Ack for ClassifyUrl. `accepted=false` with reason in {in_flight,not_ready}
    * means no Gemini call was fired (coalesced or site not ready yet). */
  final case class ClassifyAck(accepted: Boolean, reason: String) extends promovolve.CborSerializable

  /** Pure single-flight + readiness decision for ClassifyUrl, extracted so the
    * precedence is unit-testable. Pending takes precedence over readiness so a
    * coalesced request never spuriously reports not_ready. NOT serialized (only
    * the resulting ClassifyAck crosses the wire), so case objects are safe. */
  object ClassifyDecision {
    sealed trait Decision { def accepted: Boolean; def reason: String }
    case object Accept   extends Decision { val accepted = true;  val reason = "accepted"  }
    case object InFlight extends Decision { val accepted = false; val reason = "in_flight" }
    case object NotReady extends Decision { val accepted = false; val reason = "not_ready" }

    def decide(alreadyPending: Boolean, ready: Boolean): Decision =
      if (alreadyPending) InFlight
      else if (!ready) NotReady
      else Accept
  }

  /** Register site with publisher (minimal initialization, called by PublisherEntity.RegisterSite) */
  final case class Register(publisherId: PublisherId, replyTo: ActorRef[Registered]) extends Command

  final case class Registered(siteId: SiteId) extends promovolve.CborSerializable

  /** Initialize site with config (called after publisher registers the site) */
  final case class Initialize(config: SiteConfig, replyTo: ActorRef[Initialized]) extends Command

  final case class Initialized(siteId: SiteId) extends promovolve.CborSerializable

  /** Update site configuration */
  final case class UpdateConfig(config: SiteConfig, replyTo: ActorRef[ConfigUpdated]) extends Command

  final case class ConfigUpdated(siteId: SiteId) extends promovolve.CborSerializable

  /** Get current site configuration */
  final case class GetConfig(replyTo: ActorRef[ConfigResult]) extends Command
  final case class ConfigResult(config: Option[SiteConfig]) extends promovolve.CborSerializable

  /** Update pacing configuration */
  final case class UpdatePacingConfig(config: PacingConfig, replyTo: ActorRef[PacingConfigUpdated]) extends Command

  final case class PacingConfigUpdated(siteId: SiteId) extends promovolve.CborSerializable

  /** Get current pacing configuration */
  final case class GetPacingConfig(replyTo: ActorRef[PacingConfig]) extends Command

  /** Update floor CPM (minimum bid price) for this site */
  final case class UpdateFloorCpm(cpm: CPM, replyTo: ActorRef[FloorCpmUpdated]) extends Command
  final case class FloorCpmUpdated(siteId: SiteId, floorCpm: CPM) extends promovolve.CborSerializable

  /** Get current floor CPM */
  final case class GetFloorCpm(replyTo: ActorRef[CPM]) extends Command

  /** Get the current per-category floors. Keyed by
    * category id; empty when no per-category sweeps have run. Used by the
    * approval/serving endpoint so "below floor" is judged against the floor a
    * creative actually competes at, not the single site-wide floor. */
  /** Get per-category floor overrides.
    * Reply is a protobuf message, NOT a bare Map/tuple: specialized Scala
    * tuples (Tuple2$mcDD$sp) silently deserialize to null under
    * jackson-cbor, and a null message crashes Artery's whole inbound stream
    * (heartbeats included) — the root cause of the 2026-06/07 multi-node
    * self-down cascades, caught live on GKE and pinned by
    * LPWorkerSerializationSpec. Schema-defined protos make that failure
    * unrepresentable. Proto: modules/core/src/main/protobuf/site_protocol.proto */
  final case class GetCategoryFloors(replyTo: ActorRef[promovolve.proto.site.CategoryFloors]) extends Command

  /** Get the live evidence table from the sweep optimizer. The snapshot
    * is None only before recovery has installed the optimizer. */
  final case class GetFloorSweepEvidence(
      replyTo: ActorRef[FloorSweepEvidenceResponse],
  ) extends Command

  final case class FloorSweepEvidenceResponse(
      snapshot: Option[FloorSweepOptimizer.Snapshot],
  ) extends promovolve.CborSerializable

  /** Get publisher's minimum floor CPM */
  final case class GetMinFloorCpm(replyTo: ActorRef[CPM]) extends Command

  /** Update publisher's minimum floor CPM (RL agent cannot go below this) */
  final case class UpdateMinFloorCpm(cpm: CPM, replyTo: ActorRef[MinFloorCpmUpdated]) extends Command
  final case class MinFloorCpmUpdated(siteId: SiteId, minFloorCpm: CPM) extends promovolve.CborSerializable

  /** Admin escape hatch: set or clear the explicit floor for one slot.
    * `floor = None` clears the override and lets the slot fall back to
    * the RL-learned override (if any) or the prior-scaled site floor.
    * Admin floors take precedence over RL floors at auction time.
    */
  final case class UpdateSlotFloorOverride(
      slotId: String,
      floor: Option[CPM],
      replyTo: ActorRef[SlotFloorOverrideUpdated],
  ) extends Command
  final case class SlotFloorOverrideUpdated(siteId: SiteId, slotId: String, floor: Option[CPM]) extends promovolve.CborSerializable

  /** List slots known on this site (crawler-discovered + admin-set).
    * Returns the persisted AdSlotConfig list. */
  /** Get slot configs. Reply is a wrapper case class, NOT a bare List — bare
    * collections have no serializer binding, so a cross-node ask fails at the
    * sender and times out the caller (this one sits on the SERVE path). */
  final case class GetSlots(replyTo: ActorRef[SlotsResult]) extends Command
  final case class SlotsResult(slots: List[AdSlotConfig]) extends promovolve.CborSerializable

  /** Per-page IAB classifications (URL → categories + slots discovered on
    * that page). Surfaced read-only so the dashboard can show which content
    * category each ad unit matched. */
  final case class GetPageClassifications(
      replyTo: ActorRef[PageClassificationsResult]
  ) extends Command
  final case class PageClassificationsResult(byUrl: Map[String, ClassificationEntry]) extends promovolve.CborSerializable

  /** Update bid weight (scoring exponent) for this site */
  final case class UpdateBidWeight(weight: Double, replyTo: ActorRef[BidWeightUpdated]) extends Command
  final case class BidWeightUpdated(siteId: SiteId, bidWeight: Double) extends promovolve.CborSerializable

  /** Get current bid weight */
  final case class GetBidWeight(replyTo: ActorRef[Double]) extends Command


  /** Toggle whether this site accepts filler-auction traffic
    * (creatives that won on pages with no contextual match). */
  final case class UpdateAcceptsFillerTraffic(accept: Boolean, replyTo: ActorRef[AcceptsFillerTrafficUpdated]) extends Command
  final case class AcceptsFillerTrafficUpdated(siteId: SiteId, acceptsFillerTraffic: Boolean) extends promovolve.CborSerializable

  /** Get current filler-traffic opt-in state */
  final case class GetAcceptsFillerTraffic(replyTo: ActorRef[Boolean]) extends Command

  /** One snapshot of what the floor-CPM sweep optimizer did during a single
    * 15-minute observation window. Held in an in-memory ring buffer per
    * SiteEntity for production observability — the dashboard reads
    * recent entries via `GetRecentFloorObservations`. Not persisted;
    * survives only until the actor restarts. That's deliberate — long-
    * term retention belongs in a journal/projection, not the entity. */
  final case class FloorObservation(
      ts: java.time.Instant,
      hour: Int,
      trafficShape: Double,
      floorBefore: Double,
      floorAfter: Double,
      epsilon: Double,
      observed: Boolean,          // false = gate skipped this window
      trainingLoss: Option[Double],
      slotOverrideCount: Int,
  ) extends promovolve.CborSerializable

  /** Fetch the last `limit` floor observations (most-recent first).
    * Used by the per-site decision-log dashboard widget. */
  final case class GetRecentFloorObservations(limit: Int, replyTo: ActorRef[FloorObservationsResult]) extends Command
  final case class FloorObservationsResult(observations: Vector[FloorObservation]) extends promovolve.CborSerializable

  /** Admin escape hatch: wipe the persisted floor-agent snapshot and
    * force-reload the shipped warm-start default. Use when a site's
    * agent has drifted to something pathological and we need a clean
    * restart. */
  final case class ResetFloorAgent(replyTo: ActorRef[FloorAgentReset]) extends Command
  final case class FloorAgentReset(siteId: SiteId) extends promovolve.CborSerializable

  /** Force-set verified host (for dev/testing only — bypasses HTTP verification check) */
  final case class ForceVerifyHost(host: String, replyTo: ActorRef[VerificationSucceeded]) extends Command

  // ---------- Ad Product Blocklist Commands ----------
  /** Block ad product categories from serving on this site */
  final case class BlockAdProductCategories(
      categories: Set[AdProductCategoryId],
      replyTo: ActorRef[AdProductCategoriesBlocked]
  ) extends Command

  final case class AdProductCategoriesBlocked(siteId: SiteId, blocked: Set[AdProductCategoryId]) extends promovolve.CborSerializable

  /** Unblock ad product categories for this site */
  final case class UnblockAdProductCategories(
      categories: Set[AdProductCategoryId],
      replyTo: ActorRef[AdProductCategoriesUnblocked]
  ) extends Command

  final case class AdProductCategoriesUnblocked(siteId: SiteId, unblocked: Set[AdProductCategoryId]) extends promovolve.CborSerializable

  // ---------- Site Verification Commands ----------
  /** Get the verification token for this site (publisher places it at /.well-known/promovolve.txt) */
  final case class GetVerificationToken(replyTo: ActorRef[VerificationTokenResult]) extends Command
  sealed trait VerificationTokenResult extends promovolve.CborSerializable
  final case class VerificationTokenFound(siteId: SiteId, token: VerificationToken, domain: String) extends VerificationTokenResult
  final case class VerificationTokenNotAvailable(siteId: SiteId) extends VerificationTokenResult

  /** Trigger HTTP-file verification check */
  final case class RequestVerification(replyTo: ActorRef[VerificationResult]) extends Command
  sealed trait VerificationResult extends promovolve.CborSerializable
  final case class VerificationSucceeded(siteId: SiteId, host: String) extends VerificationResult
  final case class VerificationFailed(siteId: SiteId, reason: String) extends VerificationResult
  final case class VerificationNotReady(siteId: SiteId, reason: String) extends VerificationResult

  /** Get current verification status */
  final case class GetVerificationStatus(replyTo: ActorRef[VerificationStatusResult]) extends Command
  final case class VerificationStatusResult(
      siteId: SiteId,
      verified: Boolean,
      verifiedHost: Option[String],
      token: Option[String]
  ) extends promovolve.CborSerializable

  /** Get current ad product blocklist */
  final case class GetAdProductBlocklist(replyTo: ActorRef[AdProductBlocklistResponse]) extends Command

  final case class AdProductBlocklistResponse(siteId: SiteId, categories: Set[AdProductCategoryId]) extends promovolve.CborSerializable

  // ---------- State ----------
  final case class State(
      siteId: SiteId,
      publisherId: Option[PublisherId],
      config: Option[SiteConfig],
      pacingConfig: PacingConfig = PacingConfig.default,
      adProductBlocklist: Set[AdProductCategoryId] = Set.empty,
      verificationToken: Option[VerificationToken] = None,
      verifiedHost: Option[String] = None, // normalized host, set after successful verification
      floorCpm: CPM = CPM(0.50), // current floor CPM (managed by RL agent)
      minFloorCpm: CPM = CPM(0.10), // publisher-set minimum floor (RL agent cannot go below this)
      bidWeight: Double = 0.5, // scoring exponent: score = CTR × CPM^α (0.3=discovery, 0.5=balanced, 0.7=revenue)
      // Sweep-optimizer state, persisted so phase counters and learned
      // floor survive an actor restart.
      floorSweepSnapshot: Option[FloorSweepOptimizer.Snapshot] = None,
      // Per-category floor optimization (always on).
      // One FloorSweepOptimizer runs per serving category; these maps
      // persist the learned floor + sweep snapshot per category so they
      // survive restart, mirroring the site-wide pair above. Empty for
      // sites that never enabled per-category mode — old persisted states
      // deserialize with empty maps (no migration), and the serve/auction
      // paths fall back to the site-wide `floorCpm`.
      floorCpmByCategory: Map[String, CPM] = Map.empty,
      floorSweepSnapshotByCategory: Map[String, FloorSweepOptimizer.Snapshot] = Map.empty,
      // When true, pages with no contextual match are routed to the
      // filler auction (opted-in campaigns bid directly). When false,
      // the slot stays empty. Default true preserves existing behavior;
      // publishers flip this off if they'd never approve filler
      // creatives and don't want them in their approval queue.
      acceptsFillerTraffic: Boolean = true,
      // Persisted page classifications, used to repopulate
      // AuctioneerEntity's in-memory `lastPage` cache on cluster
      // restart. Without this, every restart triggers a bootstrap
      // crawl per site → thundering herd into Playwright + Gemini.
      // Map[url -> ClassificationEntry]. Default empty for pre-fix
      // sites; bootstrap crawl still runs in that case.
      pageClassifications: Map[String, ClassificationEntry] = Map.empty,
      // Last-known demand category set, persisted so a restart serves it
      // immediately instead of None — which would skip classification until
      // CampaignDirectory answers a refresh (the cold-start window). The
      // in-memory `demandCategories` var is the working cache: hydrated from
      // this on recovery, written back on each refresh. Default None for
      // pre-fix states (deserialize with no migration).
      demandCategories: Option[Set[String]] = None,
      // Recent floor observations (the per-site decision-log dashboard),
      // persisted so the history survives restarts — it used to be
      // in-memory only, and every api rollout wiped the page. Capped at
      // FloorObservationCap (100); each observation tick persists state
      // anyway (sweep snapshot), so the history rides the same write.
      // The in-memory `recentFloorObservations` var is the working
      // cache: hydrated from this on recovery, folded back in on each
      // tick's persist. Default empty for pre-fix states.
      recentFloorObservations: Vector[FloorObservation] = Vector.empty,
  ) extends CborSerializable {
    def isRegistered: Boolean            = publisherId.isDefined
    def isInitialized: Boolean           = config.isDefined
    def isVerified: Boolean              = verifiedHost.isDefined
    def withPublisherId(p: PublisherId): State = copy(publisherId = Some(p))
    def withConfig(c: SiteConfig): State = copy(config = Some(c))

    /** After a clean full crawl, mirror `config.slots` to exactly what was
      * seen: drop any slot whose `slotId` is not in `seen`. Returns the
      * (possibly unchanged) state paired with the slotIds that were removed,
      * so the caller can decide whether to persist and what to log. No-op
      * when there is no config or nothing to prune. */
    def prunedToSeenSlots(seen: Set[String]): (State, List[String]) =
      config match {
        case Some(cfg) =>
          val removed = cfg.slots.filterNot(s => seen.contains(s.slotId)).map(_.slotId)
          if (removed.isEmpty) (this, Nil)
          else (withConfig(cfg.copy(slots = cfg.slots.filter(s => seen.contains(s.slotId)))), removed)
        case None => (this, Nil)
      }
    def withPacingConfig(c: PacingConfig): State = copy(pacingConfig = c)
    def withVerificationToken(t: VerificationToken): State = copy(verificationToken = Some(t))
    def withVerifiedHost(host: String): State = copy(verifiedHost = Some(host))
    def blockAdProducts(cats: Set[AdProductCategoryId]): State = copy(adProductBlocklist = adProductBlocklist ++ cats)
    def unblockAdProducts(cats: Set[AdProductCategoryId]): State = copy(adProductBlocklist = adProductBlocklist -- cats)
    def withFloorCpm(cpm: CPM): State = copy(floorCpm = cpm)
    def withMinFloorCpm(cpm: CPM): State = copy(minFloorCpm = cpm)
    def withBidWeight(w: Double): State = copy(bidWeight = w)
    def withFloorSweepSnapshot(snap: FloorSweepOptimizer.Snapshot): State = copy(floorSweepSnapshot = Some(snap))
    def withRecentFloorObservations(obs: Vector[FloorObservation]): State = copy(recentFloorObservations = obs)
    /** Bulk-merge a tick's per-category floor + snapshot updates. */
    def withCategoryFloors(
        floors: Map[String, CPM],
        snaps: Map[String, FloorSweepOptimizer.Snapshot],
    ): State =
      copy(
        floorCpmByCategory           = floorCpmByCategory ++ floors,
        floorSweepSnapshotByCategory = floorSweepSnapshotByCategory ++ snaps,
      )
    def withAcceptsFillerTraffic(accept: Boolean): State = copy(acceptsFillerTraffic = accept)

    /** Insert/replace a classification entry; if the map would exceed
      * `MaxPersistedClassifications`, evict the oldest by classifiedAt
      * first. Matches the eviction policy AuctioneerEntity uses for
      * its in-memory `lastPage`. */
    def withClassification(url: String, entry: ClassificationEntry): State = {
      val withoutOldest =
        if (pageClassifications.size >= MaxPersistedClassifications && !pageClassifications.contains(url))
          pageClassifications.minByOption(_._2.classifiedAt) match {
            case Some((oldUrl, _)) => pageClassifications.removed(oldUrl)
            case None              => pageClassifications
          }
        else pageClassifications
      copy(pageClassifications = withoutOldest.updated(url, entry))
    }
  }

  private case object RefreshDemandCategories extends Internal
  private case class DemandCategoriesRefreshed(categories: Vector[CategoryId]) extends Internal
  private case class DemandCategoriesRefreshFailed(ex: Throwable) extends Internal

  private case class ContentAnalyzed(url: String, text: String, selections: List[IABTaxonomy.Selection], discoveredSlots: List[AdSlotConfig])
      extends Internal

  private case class FailedToAnalyzeContent(url: String, exception: Throwable) extends Internal

  object PacingConfig {
    val default: PacingConfig = PacingConfig()
  }

  /** Shutdown this site (called when site is deleted from publisher) */
  case object Shutdown extends Command

  /** Delete this site's durable state + DData footprint (from
    * PublisherEntity.DeleteSite). Unlike Shutdown, the siteId becomes
    * re-creatable from scratch afterwards.
    */
  case object Delete extends Command

  object State {
    def empty(siteId: SiteId): State = State(siteId, None, None, PacingConfig.default)
  }

  /** Async result of HTTP-file verification check */
  private case class VerificationCheckResult(
      success: Boolean,
      host: String,
      reason: Option[String],
      replyTo: ActorRef[VerificationResult]
  ) extends Internal
}