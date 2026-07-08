package promovolve.publisher.delivery

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.ddata.typed.scaladsl.{DistributedData, Replicator}
import org.apache.pekko.cluster.ddata.{LWWMap, LWWMapKey, SelfUniqueAddress}
import promovolve.publisher.{CandidateView, ServeView}
import promovolve.*

import scala.concurrent.duration.*

/** Replicated, in-memory index for the /serve hot path (typed DData + LWWMap buckets).
  * - Per-publisher keyspace (derived from composite key `pub|slot`).
  * - Bucketing keeps CRDT deltas small.
  * - Put: WriteLocal (fast) ; Remove: WriteMajority + retry (safer takedown).
  * - Periodic TTL sweep removes expired entries.
  */
object ServeIndexDData {

  // ------- Tunables -------
  private val Buckets             = 32 // power-of-2 recommended (32/64)
  private val MajorityTimeout     = 800.millis // timeout for WriteMajority acks
  private val MaxRemoveRetries    = 5 // retry attempts for Remove
  private val InitialRetryBackoff = 200.millis // backoff base for Remove retries
  private val SweepInterval       = 2.minutes // periodic TTL sweep
  private val MaxKeysRemovePerRun = 500 // bound per-bucket removals per sweep

  // ------- Behavior -------
  def apply(): Behavior[Cmd] = Behaviors.setup { ctx =>
    given node: SelfUniqueAddress = DistributedData(ctx.system).selfUniqueAddress

    Behaviors.withTimers { timers =>
      // Track seen namespaces locally (not replicated) for sweeping
      var knownNS: Set[String] = Set.empty

      // run periodic sweeps
      timers.startTimerAtFixedRate(Sweep, SweepInterval)

      DistributedData.withReplicatorMessageAdapter[Cmd, LWWMap[String, ServeView]] { adapter =>

        /** Shared helper for bucket-scan removal. Scans all entries in a bucket,
          * filters candidates by the predicate, and updates or removes entries.
          */
        def handleBucketRemoval(
            ns: String,
            b: Int,
            rsp: Replicator.GetResponse[LWWMap[String, ServeView]],
            label: String,
            shouldRemove: CandidateView => Boolean
        ): Behavior[Cmd] = {
          rsp match {
            case g @ Replicator.GetSuccess(key) if key == mapKey(ns, b) =>
              val m = g.get(key)
              val now = System.currentTimeMillis()
              m.entries.foreach { case (k, view) =>
                val remaining = view.candidates.filterNot(shouldRemove)
                if (remaining.size < view.candidates.size) {
                  val removed = view.candidates.size - remaining.size
                  if (remaining.isEmpty) {
                    ctx.log.info("Removing {} from key={} ({} candidates removed)", label, k, removed: java.lang.Integer)
                    adapter.askUpdate(
                      askRef => Replicator.Update(
                        mapKey(ns, b), LWWMap.empty[String, ServeView], Replicator.WriteLocal, askRef
                      )(_.remove(node, k)),
                      updateRsp => UpdateAck(ns, b, k, updateRsp)
                    )
                  } else {
                    ctx.log.info("Removing {} from key={} ({} removed, {} remaining)", label, k, removed: java.lang.Integer, remaining.size: java.lang.Integer)
                    adapter.askUpdate(
                      askRef => Replicator.Update(
                        mapKey(ns, b), LWWMap.empty[String, ServeView], Replicator.WriteLocal, askRef
                      )(_.put(node, k, view.copy(candidates = remaining, version = now))),
                      updateRsp => UpdateAck(ns, b, k, updateRsp)
                    )
                  }
                }
              }
              Behaviors.same
            case _: Replicator.NotFound[?] => Behaviors.same
            case other =>
              ctx.log.warn("BucketRemoval({}): bucket {} unexpected: {}", label, b: java.lang.Integer, other.getClass.getSimpleName)
              Behaviors.same
          }
        }

        Behaviors.receiveMessage {

          // --- Writes ---
          case Put(k, v) =>
            val ns = pubOf(k)
            val b  = bucketOf(k)
            knownNS += ns
            ctx.log.info("ServeIndex Put: key={} candidates={}", k, v.candidates.size)
            adapter.askUpdate(
              askRef =>
                Replicator.Update(
                  mapKey(ns, b),
                  LWWMap.empty[String, ServeView],
                  Replicator.WriteLocal, // fast path
                  askRef
                )(_.put(node, k, v)),
              rsp => UpdateAck(ns, b, k, rsp) // optional: for metrics
            )
            Behaviors.same

          case Append(k, candidate, ttlMs) =>
            val ns  = pubOf(k)
            val b   = bucketOf(k)
            val now = System.currentTimeMillis()
            knownNS += ns
            ctx.log.info(
              "ServeIndex Append: key={} creativeId={} landingDomain='{}' adProductCategory={}",
              k, candidate.creativeId, candidate.landingDomain, candidate.adProductCategory
            )
            adapter.askUpdate(
              askRef =>
                Replicator.Update(
                  mapKey(ns, b),
                  LWWMap.empty[String, ServeView],
                  Replicator.WriteLocal, // fast path
                  askRef
                )(map => {
                  // Read current state, append candidate, write back (atomic within DData)
                  val existingView = map.get(k)
                  val updatedView = existingView match {
                    case Some(view) =>
                      // Check if candidate already exists (deduplication by creativeId)
                      if (view.candidates.exists(_.creativeId == candidate.creativeId)) {
                        view // Already approved, no change
                      } else {
                        // Append new candidate to existing list
                        view.copy(
                          candidates  = view.candidates :+ candidate,
                          version     = now, // Update version timestamp
                          expiresAtMs = now + ttlMs // Refresh TTL
                        )
                      }
                    case None =>
                      // First candidate for this slot
                      ServeView(
                        candidates  = Vector(candidate),
                        version     = now,
                        expiresAtMs = now + ttlMs
                      )
                  }
                  map.put(node, k, updatedView)
                }),
              rsp => UpdateAck(ns, b, k, rsp)
            )
            Behaviors.same

          case Remove(k) =>
            val ns = pubOf(k)
            val b  = bucketOf(k)
            knownNS += ns
            ctx.log.info("ServeIndex Remove: key={}", k)
            adapter.askUpdate(
              askRef =>
                Replicator.Update(
                  mapKey(ns, b),
                  LWWMap.empty[String, ServeView],
                  Replicator.WriteMajority(MajorityTimeout), // safer path
                  askRef
                )(_.remove(node, k)),
              rsp => UpdateAck(ns, b, k, rsp)
            )
            Behaviors.same

          case UpdateCpm(k, campaignCpms) =>
            if (campaignCpms.isEmpty) {
              Behaviors.same
            } else {
              val ns  = pubOf(k)
              val b   = bucketOf(k)
              val now = System.currentTimeMillis()
              knownNS += ns
              adapter.askUpdate(
                askRef =>
                  Replicator.Update(
                    mapKey(ns, b),
                    LWWMap.empty[String, ServeView],
                    Replicator.WriteLocal, // fast path (CPM update is best-effort)
                    askRef
                  )(map => {
                    map.get(k) match {
                      case Some(view) =>
                        // Update CPM for matching campaigns
                        val updatedCandidates = view.candidates.map { candidate =>
                          campaignCpms.get(candidate.campaignId) match {
                            case Some(newCpm) => candidate.copy(cpm = newCpm)
                            case None         => candidate
                          }
                        }
                        val updatedView = view.copy(
                          candidates = updatedCandidates,
                          version    = now // Bump version to indicate update
                        )
                        map.put(node, k, updatedView)
                      case None =>
                        // No existing entry - nothing to update
                        map
                    }
                  }),
                rsp => UpdateAck(ns, b, k, rsp)
              )
              Behaviors.same
            }

          case FilterByCreativeIds(k, validCreativeIds) =>
            if (validCreativeIds.isEmpty) {
              // No valid creatives - remove the entire entry
              ctx.self ! Remove(k)
              Behaviors.same
            } else {
              val ns  = pubOf(k)
              val b   = bucketOf(k)
              val now = System.currentTimeMillis()
              knownNS += ns
              adapter.askUpdate(
                askRef =>
                  Replicator.Update(
                    mapKey(ns, b),
                    LWWMap.empty[String, ServeView],
                    Replicator.WriteLocal, // fast path (filtering is best-effort)
                    askRef
                  )(map => {
                    map.get(k) match {
                      case Some(view) =>
                        // Filter out candidates not in the valid set
                        val filteredCandidates =
                          view.candidates.filter(c => validCreativeIds.contains(c.creativeId))
                        if (filteredCandidates.isEmpty) {
                          // All candidates filtered out - remove the entry
                          map.remove(node, k)
                        } else if (filteredCandidates.size < view.candidates.size) {
                          // Some candidates filtered - update with remaining
                          val updatedView = view.copy(
                            candidates = filteredCandidates,
                            version    = now
                          )
                          map.put(node, k, updatedView)
                        } else {
                          // No change needed
                          map
                        }
                      case None =>
                        // No existing entry - nothing to filter
                        map
                    }
                  }),
                rsp => UpdateAck(ns, b, k, rsp)
              )
              Behaviors.same
            }

          case RemoveCampaignFromKey(k, campaignId, keepCreativeIds) =>
            val ns  = pubOf(k)
            val b   = bucketOf(k)
            val now = System.currentTimeMillis()
            knownNS += ns
            ctx.log.info(
              "ServeIndex RemoveCampaignFromKey: key={} campaignId={} keep={}",
              k, campaignId.value, keepCreativeIds.size
            )
            adapter.askUpdate(
              askRef =>
                Replicator.Update(
                  mapKey(ns, b),
                  LWWMap.empty[String, ServeView],
                  Replicator.WriteLocal,
                  askRef
                )(map => {
                  map.get(k) match {
                    case Some(view) =>
                      // Keep a candidate iff it isn't this campaign's OR it is a
                      // pinned creative we were told to retain.
                      val filtered = view.candidates.filter(c =>
                        c.campaignId != campaignId || keepCreativeIds.contains(c.creativeId)
                      )
                      if (filtered.isEmpty) map.remove(node, k)
                      else if (filtered.size < view.candidates.size)
                        map.put(node, k, view.copy(candidates = filtered, version = now))
                      else map
                    case None => map
                  }
                }),
              rsp => UpdateAck(ns, b, k, rsp)
            )
            Behaviors.same

          case RemoveCreativeFromKey(k, creativeId) =>
            val ns  = pubOf(k)
            val b   = bucketOf(k)
            val now = System.currentTimeMillis()
            knownNS += ns
            ctx.log.info("ServeIndex RemoveCreativeFromKey: key={} creativeId={}", k, creativeId.value)
            adapter.askUpdate(
              askRef =>
                Replicator.Update(
                  mapKey(ns, b),
                  LWWMap.empty[String, ServeView],
                  Replicator.WriteLocal,
                  askRef
                )(map => {
                  map.get(k) match {
                    case Some(view) =>
                      val filtered = view.candidates.filterNot(_.creativeId == creativeId)
                      if (filtered.isEmpty) map.remove(node, k)
                      else if (filtered.size < view.candidates.size)
                        map.put(node, k, view.copy(candidates = filtered, version = now))
                      else map
                    case None => map
                  }
                }),
              rsp => UpdateAck(ns, b, k, rsp)
            )
            Behaviors.same

          case RemoveAdvertiserFromKey(k, advertiserId) =>
            val ns  = pubOf(k)
            val b   = bucketOf(k)
            val now = System.currentTimeMillis()
            knownNS += ns
            ctx.log.info("ServeIndex RemoveAdvertiserFromKey: key={} advertiserId={}", k, advertiserId.value)
            adapter.askUpdate(
              askRef =>
                Replicator.Update(
                  mapKey(ns, b),
                  LWWMap.empty[String, ServeView],
                  Replicator.WriteLocal,
                  askRef
                )(map => {
                  map.get(k) match {
                    case Some(view) =>
                      val filtered = view.candidates.filterNot(_.advertiserId == advertiserId)
                      if (filtered.isEmpty) map.remove(node, k)
                      else if (filtered.size < view.candidates.size)
                        map.put(node, k, view.copy(candidates = filtered, version = now))
                      else map
                    case None => map
                  }
                }),
              rsp => UpdateAck(ns, b, k, rsp)
            )
            Behaviors.same

          case RefreshTTLForKey(k, newTtlMs) =>
            val ns  = pubOf(k)
            val b   = bucketOf(k)
            val now = System.currentTimeMillis()
            val newExpiresAt = now + newTtlMs
            knownNS += ns
            ctx.log.info("ServeIndex RefreshTTLForKey: key={} newTtlMs={}", k, newTtlMs)
            adapter.askUpdate(
              askRef =>
                Replicator.Update(
                  mapKey(ns, b),
                  LWWMap.empty[String, ServeView],
                  Replicator.WriteLocal,
                  askRef
                )(map => {
                  map.get(k) match {
                    case Some(view) =>
                      map.put(node, k, view.copy(expiresAtMs = newExpiresAt, version = now))
                    case None => map
                  }
                }),
              rsp => UpdateAck(ns, b, k, rsp)
            )
            Behaviors.same

          case RemoveByDomains(siteId, blockedDomains) =>
            // Remove candidates with landing domains in the blocklist across ALL keys for this site
            // Two-phase approach: First Get all buckets to find affected keys, then issue targeted removes
            if (blockedDomains.isEmpty) {
              Behaviors.same
            } else {
              val ns = siteId
              knownNS += ns
              ctx.log.info("Removing creatives with blocked domains {} from site {}", blockedDomains.mkString(","), siteId)
              // Query all buckets to find keys with blocked domains
              (0 until Buckets).foreach { bucket =>
                adapter.askGet(
                  askRef => Replicator.Get(mapKey(ns, bucket), Replicator.ReadLocal, askRef),
                  rsp => GetBucketForDomainRemoval(ns, bucket, blockedDomains, rsp)
                )
              }
              Behaviors.same
            }

          case RemoveByAdProductCategories(siteId, blockedCategories) =>
            // Remove candidates with ad product categories in the blocklist across ALL keys for this site
            // Two-phase approach: First Get all buckets to find affected keys, then issue targeted removes
            if (blockedCategories.isEmpty) {
              Behaviors.same
            } else {
              val ns = siteId
              knownNS += ns
              ctx.log.info("Removing creatives with blocked ad product categories {} from site {}", blockedCategories.map(_.value).mkString(","), siteId)
              // Query all buckets to find keys with blocked categories
              (0 until Buckets).foreach { bucket =>
                adapter.askGet(
                  askRef => Replicator.Get(mapKey(ns, bucket), Replicator.ReadLocal, askRef),
                  rsp => GetBucketForCategoryRemoval(ns, bucket, blockedCategories, rsp)
                )
              }
              Behaviors.same
            }

          case RemoveBelowFloor(siteId, floorCpm) =>
            val ns = siteId
            knownNS += ns
            ctx.log.info("Removing creatives below floor ${} from site {}", floorCpm, siteId)
            (0 until Buckets).foreach { bucket =>
              adapter.askGet(
                askRef => Replicator.Get(mapKey(ns, bucket), Replicator.ReadLocal, askRef),
                rsp => GetBucketForFloorRemoval(ns, bucket, floorCpm, rsp)
              )
            }
            Behaviors.same

          case RemoveCreativeBySite(siteId, creativeId) =>
            val ns = siteId
            knownNS += ns
            ctx.log.info("Removing creative {} from all slots for site {}", creativeId.value, siteId)
            (0 until Buckets).foreach { bucket =>
              adapter.askGet(
                askRef => Replicator.Get(mapKey(ns, bucket), Replicator.ReadLocal, askRef),
                rsp => GetBucketForCreativeRemoval(ns, bucket, creativeId, rsp)
              )
            }
            Behaviors.same

          case RemoveCampaignBySite(siteId, campaignId) =>
            val ns = siteId
            knownNS += ns
            ctx.log.info("Removing campaign {} from all slots for site {}", campaignId.value, siteId)
            (0 until Buckets).foreach { bucket =>
              adapter.askGet(
                askRef => Replicator.Get(mapKey(ns, bucket), Replicator.ReadLocal, askRef),
                rsp => GetBucketForCampaignRemoval(ns, bucket, campaignId, rsp)
              )
            }
            Behaviors.same

          case RemoveAdvertiserBySite(siteId, advertiserId) =>
            val ns = siteId
            knownNS += ns
            ctx.log.info("Removing advertiser {} from all slots for site {}", advertiserId.value, siteId)
            (0 until Buckets).foreach { bucket =>
              adapter.askGet(
                askRef => Replicator.Get(mapKey(ns, bucket), Replicator.ReadLocal, askRef),
                rsp => GetBucketForAdvertiserRemoval(ns, bucket, advertiserId, rsp)
              )
            }
            Behaviors.same

          // --- Reads ---
          case Get(k, replyTo) =>
            val ns = pubOf(k)
            val b  = bucketOf(k)
            knownNS += ns
            adapter.askGet(
              askRef => Replicator.Get(mapKey(ns, b), Replicator.ReadLocal, askRef),
              rsp => GetResult(ns, b, k, rsp, replyTo)
            )
            Behaviors.same

          case GetKeysBySite(siteId, replyTo) =>
            // Spawn aggregator to collect results from all buckets
            val aggregator = ctx.spawnAnonymous(KeysAggregator(siteId, replyTo, Buckets))
            // Set timeout for aggregator
            ctx.scheduleOnce(3.seconds, aggregator, KeysAggregator.Timeout)
            // Query all buckets for this siteId namespace
            knownNS += siteId
            (0 until Buckets).foreach { bucket =>
              adapter.askGet(
                askRef => Replicator.Get(mapKey(siteId, bucket), Replicator.ReadLocal, askRef),
                rsp => GetBucketForKeys(siteId, bucket, rsp, replyTo, aggregator)
              )
            }
            Behaviors.same

          // --- Internal handling (acks + retries + sweeps + get results) ---
          case internal: Internal =>
            internal match {

              // Handle update acks (Put/Remove)
              case UpdateAck(ns, b, k, rsp) =>
                rsp match {
                  case _: Replicator.UpdateSuccess[?] =>
                    ctx.log.info("ServeIndex UpdateAck: SUCCESS for key={}", k)
                    Behaviors.same

                  case timeout: Replicator.UpdateTimeout[?] =>
                    ctx.log.warn("ServeIndex UpdateAck: TIMEOUT for key={}", k)
                    // Only meaningful for Remove (WriteMajority). Schedule retry.
                    timers.startSingleTimer(
                      s"retry-remove-$ns-$b-$k",
                      RetryRemove(ns, b, k, attempt = 1),
                      backoff(1)
                    )
                    Behaviors.same

                  case _: Replicator.ModifyFailure[?] =>
                    // Update function threw; log and stop retrying
                    ctx.log.error(
                      "ServeIndexDData ModifyFailure ns={} bucket={} key={}",
                      ns,
                      b: java.lang.Integer,
                      k
                    )
                    Behaviors.same

                  case _: Replicator.StoreFailure[?] =>
                    // Rare unless backing store configured; consider retry
                    timers.startSingleTimer(
                      s"retry-remove-$ns-$b-$k",
                      RetryRemove(ns, b, k, attempt = 1),
                      backoff(1)
                    )
                    Behaviors.same

                  case _: Replicator.UpdateDataDeleted[?] =>
                    // Data was deleted; nothing to update
                    ctx.log.debug(
                      "ServeIndexDData UpdateDataDeleted ns={} bucket={} key={}",
                      ns,
                      b: java.lang.Integer,
                      k
                    )
                    Behaviors.same
                }

              // Scheduled retry for Remove with exponential backoff
              case RetryRemove(ns, b, k, attempt) =>
                if (attempt > MaxRemoveRetries) {
                  ctx.log.warn(
                    "ServeIndexDData Remove gave up after {} attempts ns={} bucket={} key={}",
                    attempt - 1,
                    ns,
                    b,
                    k
                  )
                  Behaviors.same
                } else {
                  adapter.askUpdate(
                    askRef =>
                      Replicator.Update(
                        mapKey(ns, b),
                        LWWMap.empty[String, ServeView],
                        Replicator.WriteMajority(MajorityTimeout),
                        askRef
                      )(_.remove(node, k)),
                    rsp => UpdateAck(ns, b, k, rsp)
                  )
                  // schedule next retry; will be a no-op if previous succeeded quickly
                  timers.startSingleTimer(
                    s"retry-remove-$ns-$b-$k",
                    RetryRemove(ns, b, k, attempt + 1),
                    backoff(attempt + 1)
                  )
                  Behaviors.same
                }

              // TTL sweep driver
              case Sweep =>
                knownNS.foreach { ns =>
                  (0 until Buckets).foreach { bucket =>
                    adapter.askGet(
                      askRef => Replicator.Get(mapKey(ns, bucket), Replicator.ReadLocal, askRef),
                      rsp => GetBucketForSweep(ns, bucket, rsp)
                    )
                  }
                }
                Behaviors.same

              // TTL sweep evaluator per bucket
              case GetBucketForSweep(ns, b, rsp) =>
                rsp match {
                  case g @ Replicator.GetSuccess(key) if key == mapKey(ns, b) =>
                    val m   = g.get(key)
                    val now = System.currentTimeMillis()
                    val expired = m.entries.iterator
                      .collect { case (k, v) if v.expiresAtMs <= now => k }
                      .take(MaxKeysRemovePerRun)
                      .toList

                    if (expired.nonEmpty) {
                      ctx.log.info("ServeIndex TTL sweep: removing {} expired entries from ns={} bucket={}: {}",
                        expired.size, ns, b: java.lang.Integer, expired.mkString(", "))
                    }

                    expired.foreach { k =>
                      adapter.askUpdate(
                        askRef =>
                          Replicator.Update(
                            mapKey(ns, b),
                            LWWMap.empty[String, ServeView],
                            Replicator.WriteLocal,
                            askRef
                          )(_.remove(node, k)),
                        rsp => UpdateAck(ns, b, k, rsp)
                      )
                    }
                    Behaviors.same

                  case _ =>
                    Behaviors.same
                }

              // Complete gets
              case GetResult(ns, b, k, rsp, replyTo) =>
                val now = System.currentTimeMillis()
                val out = rsp match {
                  case g @ Replicator.GetSuccess(key) if key == mapKey(ns, b) =>
                    val rawView = g.get(key).get(k)
                    rawView match {
                      case Some(view) if view.expiresAtMs <= now =>
                        ctx.log.info(
                          "ServeIndex Get: key={} EXPIRED (expiresAtMs={} now={} expiredByMs={})",
                          k, view.expiresAtMs, now, now - view.expiresAtMs
                        )
                        None
                      case Some(view) =>
                        ctx.log.info(
                          "ServeIndex Get: key={} FOUND with {} candidates (expiresIn={}ms)",
                          k, view.candidates.size, view.expiresAtMs - now
                        )
                        Some(view)
                      case None =>
                        ctx.log.info("ServeIndex Get: key={} NOT FOUND in bucket (bucket exists but key missing)", k)
                        None
                    }
                  case _: Replicator.NotFound[?] =>
                    ctx.log.info("ServeIndex Get: key={} BUCKET NOT FOUND (namespace never written)", k)
                    None
                  case other =>
                    ctx.log.warn("ServeIndex Get: key={} unexpected response: {}", k, other.getClass.getSimpleName)
                    None
                }
                replyTo ! out
                Behaviors.same

              // Complete GetKeysBySite bucket queries for ad product category removal
              case GetBucketForCategoryRemoval(ns, b, blockedCategories, rsp) =>
                rsp match {
                  case g @ Replicator.GetSuccess(key) if key == mapKey(ns, b) =>
                    val m = g.get(key)
                    val now = System.currentTimeMillis()
                    ctx.log.debug("GetBucketForCategoryRemoval: bucket={} has {} entries", b: java.lang.Integer, m.entries.size: java.lang.Integer)
                    // Find keys that have candidates with blocked categories
                    m.entries.foreach { case (k, view) =>
                      val blocked = view.candidates.filter(c => c.adProductCategory.exists(blockedCategories.contains))
                      if (blocked.nonEmpty) {
                        ctx.log.info("Found {} candidates with blocked categories in key={}", blocked.size: java.lang.Integer, k)
                        val remaining = view.candidates.filterNot(c => c.adProductCategory.exists(blockedCategories.contains))
                        if (remaining.isEmpty) {
                          // Remove entire entry
                          ctx.log.info("Removing entire entry for key={}", k)
                          adapter.askUpdate(
                            askRef =>
                              Replicator.Update(
                                mapKey(ns, b),
                                LWWMap.empty[String, ServeView],
                                Replicator.WriteLocal,
                                askRef
                              )(_.remove(node, k)),
                            updateRsp => UpdateAck(ns, b, k, updateRsp)
                          )
                        } else {
                          // Update with remaining candidates
                          ctx.log.info("Updating entry for key={} with {} remaining candidates", k, remaining.size: java.lang.Integer)
                          val updatedView = view.copy(candidates = remaining, version = now)
                          adapter.askUpdate(
                            askRef =>
                              Replicator.Update(
                                mapKey(ns, b),
                                LWWMap.empty[String, ServeView],
                                Replicator.WriteLocal,
                                askRef
                              )(_.put(node, k, updatedView)),
                            updateRsp => UpdateAck(ns, b, k, updateRsp)
                          )
                        }
                      }
                    }
                    Behaviors.same
                  case _: Replicator.NotFound[?] =>
                    ctx.log.debug("GetBucketForCategoryRemoval: bucket {} not found (empty)", b: java.lang.Integer)
                    Behaviors.same
                  case other =>
                    ctx.log.warn("GetBucketForCategoryRemoval: bucket {} unexpected response: {}", b: java.lang.Integer, other.getClass.getSimpleName)
                    Behaviors.same
                }

              // Complete GetKeysBySite bucket queries for domain removal
              case GetBucketForDomainRemoval(ns, b, blockedDomains, rsp) =>
                rsp match {
                  case g @ Replicator.GetSuccess(key) if key == mapKey(ns, b) =>
                    val m = g.get(key)
                    val now = System.currentTimeMillis()
                    ctx.log.info("GetBucketForDomainRemoval: bucket={} has {} entries", b: java.lang.Integer, m.entries.size: java.lang.Integer)
                    // Find keys that have candidates with blocked domains
                    m.entries.foreach { case (k, view) =>
                      val blocked = view.candidates.filter(c => blockedDomains.contains(c.landingDomain))
                      if (blocked.nonEmpty) {
                        ctx.log.info("Found {} candidates with blocked domains in key={}", blocked.size: java.lang.Integer, k)
                        val remaining = view.candidates.filterNot(c => blockedDomains.contains(c.landingDomain))
                        if (remaining.isEmpty) {
                          // Remove entire entry
                          ctx.log.info("Removing entire entry for key={}", k)
                          adapter.askUpdate(
                            askRef =>
                              Replicator.Update(
                                mapKey(ns, b),
                                LWWMap.empty[String, ServeView],
                                Replicator.WriteLocal,
                                askRef
                              )(_.remove(node, k)),
                            updateRsp => UpdateAck(ns, b, k, updateRsp)
                          )
                        } else {
                          // Update with remaining candidates
                          ctx.log.info("Updating entry for key={} with {} remaining candidates", k, remaining.size: java.lang.Integer)
                          val updatedView = view.copy(candidates = remaining, version = now)
                          adapter.askUpdate(
                            askRef =>
                              Replicator.Update(
                                mapKey(ns, b),
                                LWWMap.empty[String, ServeView],
                                Replicator.WriteLocal,
                                askRef
                              )(_.put(node, k, updatedView)),
                            updateRsp => UpdateAck(ns, b, k, updateRsp)
                          )
                        }
                      }
                    }
                    Behaviors.same
                  case _: Replicator.NotFound[?] =>
                    ctx.log.debug("GetBucketForDomainRemoval: bucket {} not found (empty)", b: java.lang.Integer)
                    Behaviors.same
                  case other =>
                    ctx.log.warn("GetBucketForDomainRemoval: bucket {} unexpected response: {}", b: java.lang.Integer, other.getClass.getSimpleName)
                    Behaviors.same
                }

              case GetBucketForFloorRemoval(ns, b, floorCpm, rsp) =>
                rsp match {
                  case g @ Replicator.GetSuccess(key) if key == mapKey(ns, b) =>
                    val m = g.get(key)
                    val now = System.currentTimeMillis()
                    m.entries.foreach { case (k, view) =>
                      val belowFloor = view.candidates.filter(_.cpm.toDouble < floorCpm)
                      if (belowFloor.nonEmpty) {
                        val remaining = view.candidates.filter(_.cpm.toDouble >= floorCpm)
                        ctx.log.info("Floor purge: key={} removed={} remaining={} floor=${}",
                          k, belowFloor.size: java.lang.Integer, remaining.size: java.lang.Integer, floorCpm)
                        if (remaining.isEmpty) {
                          adapter.askUpdate(
                            askRef => Replicator.Update(mapKey(ns, b), LWWMap.empty[String, ServeView], Replicator.WriteLocal, askRef)(_.remove(node, k)),
                            updateRsp => UpdateAck(ns, b, k, updateRsp)
                          )
                        } else {
                          val updatedView = view.copy(candidates = remaining, version = now)
                          adapter.askUpdate(
                            askRef => Replicator.Update(mapKey(ns, b), LWWMap.empty[String, ServeView], Replicator.WriteLocal, askRef)(_.put(node, k, updatedView)),
                            updateRsp => UpdateAck(ns, b, k, updateRsp)
                          )
                        }
                      }
                    }
                    Behaviors.same
                  case _: Replicator.NotFound[?] => Behaviors.same
                  case _ => Behaviors.same
                }

              case GetBucketForCreativeRemoval(ns, b, creativeId, rsp) =>
                handleBucketRemoval(ns, b, rsp, s"creative ${creativeId.value}",
                  _.creativeId == creativeId)

              case GetBucketForCampaignRemoval(ns, b, campaignId, rsp) =>
                handleBucketRemoval(ns, b, rsp, s"campaign ${campaignId.value}",
                  _.campaignId == campaignId)

              case GetBucketForAdvertiserRemoval(ns, b, advertiserId, rsp) =>
                handleBucketRemoval(ns, b, rsp, s"advertiser ${advertiserId.value}",
                  _.advertiserId == advertiserId)

              // Complete GetKeysBySite bucket queries
              case GetBucketForKeys(siteId, b, rsp, _, aggregator) =>
                val now = System.currentTimeMillis()
                val keys: Vector[String] = rsp match {
                  case g @ Replicator.GetSuccess(key) if key == mapKey(siteId, b) =>
                    // Extract keys that match siteId prefix and are not expired
                    val prefix = s"$siteId|"
                    g.get(key).entries.iterator
                      .filter { case (k, v) => k.startsWith(prefix) && v.expiresAtMs > now }.flatMap { case (k, _) =>
                        // Parse "siteId|slotId" -> slotId
                        val parts = k.split("\\|", 2)
                        if (parts.length == 2) Some(parts(1))
                        else None
                      }
                      .toVector
                  case _ => Vector.empty
                }
                aggregator ! KeysAggregator.BucketResult(b, keys)
                Behaviors.same
            }
        }
      }
    }
  }

  /** Composite key format is "pub|slot". Extract publisher namespace. */
  private inline def pubOf(k: String): String = {
    val i = k.indexOf('|')
    if (i > 0) k.substring(0, i) else "default"
  }

  /** Spread across buckets; rotate-left distributes better than a plain shift. */
  private inline def bucketOf(k: String): Int =
    java.lang.Integer.rotateLeft(k.hashCode, 13) & (Buckets - 1)

  /** Per-publisher LWWMap key to allow isolation/purges. */
  private inline def mapKey(pub: String, b: Int): LWWMapKey[String, ServeView] =
    LWWMapKey[String, ServeView](s"serve-views-$pub-$b")

  private def backoff(attempt: Int): FiniteDuration =
    InitialRetryBackoff * math.pow(2.0, (attempt - 1).toDouble) match {
      case d: FiniteDuration => d.min(5.seconds)
      case _ => 5.seconds // fallback in case of infinite duration
    }

  // ------- Public protocol -------
  sealed trait Cmd extends CborSerializable

  // ------- Internal (adapter replies & scheduled housekeeping) -------
  private sealed trait Internal extends Cmd

  final case class Put(k: String, v: ServeView) extends Cmd

  final case class Append(k: String, candidate: CandidateView, ttlMs: Long) extends Cmd

  final case class Remove(k: String) extends Cmd

  final case class Get(k: String, replyTo: ActorRef[Option[ServeView]]) extends Cmd

  /** Update CPM for existing candidates by campaignId. Used when campaign CPM changes
    * but new creatives are pending approval - keeps existing entries but with correct price.
    */
  final case class UpdateCpm(k: String, campaignCpms: Map[CampaignId, CPM]) extends Cmd

  /** Filter ServeIndex entries to only keep candidates with creativeIds in the given set.
    * Removes stale entries when a creative is removed from a campaign but new creatives are pending.
    * If no candidates remain after filtering, the entire entry is removed.
    */
  final case class FilterByCreativeIds(k: String, validCreativeIds: Set[CreativeId]) extends Cmd

  /** Filter out candidates from one campaign within a specific key (O(1) bucket lookup).
    *
    * `keepCreativeIds` exempts specific creatives from removal: a candidate is
    * KEPT iff it doesn't belong to `campaignId` OR its creativeId is in
    * `keepCreativeIds`. Used by topic-narrow eviction so a reader-pinned
    * creative survives a category drop on a still-served page (default empty =
    * drop ALL of the campaign's candidates, preserving the original behavior). */
  final case class RemoveCampaignFromKey(
      k: String,
      campaignId: CampaignId,
      keepCreativeIds: Set[CreativeId] = Set.empty
  ) extends Cmd

  /** Filter out one creative within a specific key (O(1) bucket lookup). */
  final case class RemoveCreativeFromKey(k: String, creativeId: CreativeId) extends Cmd

  /** Filter out candidates from one advertiser within a specific key (O(1) bucket lookup). */
  final case class RemoveAdvertiserFromKey(k: String, advertiserId: AdvertiserId) extends Cmd

  /** Refresh TTL for a specific key (O(1) bucket lookup). */
  final case class RefreshTTLForKey(k: String, newTtlMs: Long) extends Cmd

  /** Remove all candidates with landing domains in the blocklist across all keys for a site.
    * Used when publisher adds domains to their blocklist - immediately removes affected creatives.
    * If no candidates remain after filtering, the entire entry is removed.
    */
  final case class RemoveByDomains(siteId: String, blockedDomains: Set[String]) extends Cmd

  /** Remove all candidates with ad product categories in the blocklist across all keys for a site.
    * Used when publisher adds ad product categories to their blocklist - immediately removes affected creatives.
    * If no candidates remain after filtering, the entire entry is removed.
    */
  final case class RemoveByAdProductCategories(siteId: String, blockedCategories: Set[AdProductCategoryId]) extends Cmd

  /** Remove all candidates below the given floor CPM across all keys for a site.
    * Used when floor CPM increases — immediately removes creatives that no longer qualify.
    */
  final case class RemoveBelowFloor(siteId: String, floorCpm: Double) extends Cmd

  /** Remove a creative from all slots for a site. Scans all buckets. */
  final case class RemoveCreativeBySite(siteId: String, creativeId: CreativeId) extends Cmd

  /** Remove a campaign from all slots for a site. Scans all buckets. */
  final case class RemoveCampaignBySite(siteId: String, campaignId: CampaignId) extends Cmd

  /** Remove an advertiser from all slots for a site. Scans all buckets. */
  final case class RemoveAdvertiserBySite(siteId: String, advertiserId: AdvertiserId) extends Cmd

  /** Get all keys (slotIds) for a given siteId.
    * Returns a list of slotId strings that have entries in the ServeIndex.
    */
  final case class GetKeysBySite(siteId: String, replyTo: ActorRef[SiteKeys]) extends Cmd

  final case class SiteKeys(siteId: String, keys: Vector[String]) extends CborSerializable // slotIds

  private final case class UpdateAck(
      ns: String,
      bucket: Int,
      k: String,
      rsp: Replicator.UpdateResponse[LWWMap[String, ServeView]]
  ) extends Internal

  private final case class GetResult(
      ns: String,
      bucket: Int,
      k: String,
      rsp: Replicator.GetResponse[LWWMap[String, ServeView]],
      replyTo: ActorRef[Option[ServeView]]
  ) extends Internal

  // ------- Helpers -------

  private final case class RetryRemove(ns: String, bucket: Int, k: String, attempt: Int)
      extends Internal

  private final case class GetBucketForSweep(
      ns: String,
      bucket: Int,
      rsp: Replicator.GetResponse[LWWMap[String, ServeView]]
  ) extends Internal

  private final case class GetBucketForDomainRemoval(
      ns: String,
      bucket: Int,
      blockedDomains: Set[String],
      rsp: Replicator.GetResponse[LWWMap[String, ServeView]]
  ) extends Internal

  private final case class GetBucketForCategoryRemoval(
      ns: String,
      bucket: Int,
      blockedCategories: Set[AdProductCategoryId],
      rsp: Replicator.GetResponse[LWWMap[String, ServeView]]
  ) extends Internal

  private final case class GetBucketForFloorRemoval(
      ns: String,
      bucket: Int,
      floorCpm: Double,
      rsp: Replicator.GetResponse[LWWMap[String, ServeView]]
  ) extends Internal

  private final case class GetBucketForCreativeRemoval(
      ns: String,
      bucket: Int,
      creativeId: CreativeId,
      rsp: Replicator.GetResponse[LWWMap[String, ServeView]]
  ) extends Internal

  private final case class GetBucketForCampaignRemoval(
      ns: String,
      bucket: Int,
      campaignId: CampaignId,
      rsp: Replicator.GetResponse[LWWMap[String, ServeView]]
  ) extends Internal

  private final case class GetBucketForAdvertiserRemoval(
      ns: String,
      bucket: Int,
      advertiserId: AdvertiserId,
      rsp: Replicator.GetResponse[LWWMap[String, ServeView]]
  ) extends Internal

  private final case class GetBucketForKeys(
      siteId: String,
      bucket: Int,
      rsp: Replicator.GetResponse[LWWMap[String, ServeView]],
      replyTo: ActorRef[SiteKeys],
      aggregator: ActorRef[KeysAggregator.BucketResult]
  ) extends Internal

  private case object Sweep extends Internal

  // Internal aggregator for GetKeysBySite (collects results from all buckets)
  private object KeysAggregator {
    def apply(
        siteId: String,
        replyTo: ActorRef[SiteKeys],
        totalBuckets: Int
    ): Behavior[Msg] = collecting(siteId, replyTo, totalBuckets, 0, Vector.empty)

    private def collecting(
        siteId: String,
        replyTo: ActorRef[SiteKeys],
        totalBuckets: Int,
        received: Int,
        accumulated: Vector[String]
    ): Behavior[Msg] = Behaviors.receive { (ctx, msg) =>
      msg match {
        case BucketResult(_, keys) =>
          val newReceived = received + 1
          val newAccumulated = accumulated ++ keys
          if (newReceived >= totalBuckets) {
            replyTo ! SiteKeys(siteId, newAccumulated)
            Behaviors.stopped
          } else {
            collecting(siteId, replyTo, totalBuckets, newReceived, newAccumulated)
          }
        case Timeout =>
          // Return whatever we have so far
          replyTo ! SiteKeys(siteId, accumulated)
          Behaviors.stopped
      }
    }

    sealed trait Msg

    final case class BucketResult(bucket: Int, keys: Vector[String]) extends Msg

    case object Timeout extends Msg
  }
}
