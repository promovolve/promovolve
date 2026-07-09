package promovolve.auction

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRef, Behavior }
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import promovolve.taxonomy.{ AffinityRegistryDData, ContentProductAffinityEntity }

import scala.concurrent.duration.FiniteDuration

/**
 * Short-lived actor that scores content-product affinities to inform auction
 * candidate expansion.
 *
 * Flow:
 * 1. For each content category, lookup AffinityRegistryDData for known ad product pairs
 * 2. Quote ContentProductAffinityEntity for each pair (scatter-gather)
 * 3. Filter by minAffinityScore — this surfaces (content, adProduct) pairs the
 *    system has actually learned from impression/click behaviour
 *
 * Historically step 4 mapped surviving ad products back to *other* content
 * categories via the static IAB Content↔AdProduct bridge. That bridge has been
 * removed: it was sparse, frequently asserted the wrong neighbourhood (e.g.
 * "Beer ad product" → only "Alcohol" content), and silently filled the auction
 * with categories no advertiser actually targeted. Reverse expansion will be
 * reintroduced once AffinityRegistryDData carries an adProduct→contentSet
 * inverse index — at which point "what other content categories does a
 * high-affinity ad product appear with?" becomes a real, learned answer.
 *
 * Until then this actor returns an empty expansion set, and the auctioneer
 * runs with its original (LLM-classified) candidate list.
 *
 * Self-terminates after replying or on timeout.
 */
object AffinityExpander {

  sealed trait Msg
  private case class RegistryResult(contentCategory: String, pairs: AffinityRegistryDData.AffinityPairs) extends Msg
  private case class AffinityQuoted(quoted: ContentProductAffinityEntity.Quoted) extends Msg
  private case object Timeout extends Msg

  final case class AffinityExpansionResult(expandedCategories: Set[String])

  def apply(
      contentCategories: Set[String],
      affinityRegistry: ActorRef[AffinityRegistryDData.Cmd],
      sharding: ClusterSharding,
      minAffinityScore: Double,
      maxExpansion: Int,
      replyTo: ActorRef[AffinityExpansionResult],
      timeout: FiniteDuration
  ): Behavior[Msg] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      timers.startSingleTimer(Timeout, timeout)

      // Phase 1: Lookup affinity registry for known pairs
      val registryAdapter = ctx.messageAdapter[AffinityRegistryDData.AffinityPairs](pairs =>
        RegistryResult(pairs.contentCategory, pairs)
      )
      contentCategories.foreach { cat =>
        affinityRegistry ! AffinityRegistryDData.Lookup(cat, registryAdapter)
      }

      collectRegistryResults(
        contentCategories = contentCategories,
        remaining = contentCategories.size,
        allPairs = Map.empty,
        sharding = sharding,
        minAffinityScore = minAffinityScore,
        maxExpansion = maxExpansion,
        replyTo = replyTo
      )
    }
  }

  /** Phase 1: Collect registry lookup results */
  private def collectRegistryResults(
      contentCategories: Set[String],
      remaining: Int,
      allPairs: Map[String, Set[String]], // contentCat → Set[adProductCat]
      sharding: ClusterSharding,
      minAffinityScore: Double,
      maxExpansion: Int,
      replyTo: ActorRef[AffinityExpansionResult]
  ): Behavior[Msg] = Behaviors.receive { (ctx, msg) =>
    msg match {
      case RegistryResult(contentCat, pairs) =>
        val updated = allPairs + (contentCat -> pairs.adProductCategories)
        val newRemaining = remaining - 1
        if (newRemaining <= 0) {
          // All registry lookups done — start quoting affinity entities
          val pairsToQuote = updated.flatMap { case (cc, apcs) =>
            apcs.map(apc => (cc, apc))
          }.toSet
          if (pairsToQuote.isEmpty) {
            replyTo ! AffinityExpansionResult(Set.empty)
            Behaviors.stopped
          } else {
            val quotedAdapter = ctx.messageAdapter[ContentProductAffinityEntity.Quoted](AffinityQuoted(_))
            pairsToQuote.foreach { case (cc, apc) =>
              val entityId = s"$cc|$apc"
              sharding.entityRefFor(ContentProductAffinityEntity.TypeKey, entityId)
                .tell(ContentProductAffinityEntity.Quote(quotedAdapter))
            }
            collectQuotes(
              contentCategories = contentCategories,
              remaining = pairsToQuote.size,
              scored = Vector.empty,
              minAffinityScore = minAffinityScore,
              maxExpansion = maxExpansion,
              replyTo = replyTo
            )
          }
        } else {
          collectRegistryResults(contentCategories, newRemaining, updated, sharding, minAffinityScore, maxExpansion,
            replyTo)
        }

      case Timeout =>
        replyTo ! AffinityExpansionResult(Set.empty)
        Behaviors.stopped

      case _ => Behaviors.same
    }
  }

  /** Phase 2: Collect affinity scores and compute expansion */
  private def collectQuotes(
      contentCategories: Set[String],
      remaining: Int,
      scored: Vector[(String, Double)], // (adProductCategory, sampledCTR)
      minAffinityScore: Double,
      maxExpansion: Int,
      replyTo: ActorRef[AffinityExpansionResult]
  ): Behavior[Msg] = Behaviors.receive { (ctx, msg) =>
    msg match {
      case AffinityQuoted(quoted) =>
        val newScored = scored :+ (quoted.adProductCategory, quoted.weight)
        val newRemaining = remaining - 1
        if (newRemaining <= 0) {
          // All quotes collected — compute expansion
          val expanded = computeExpansion(contentCategories, newScored, minAffinityScore, maxExpansion)
          ctx.log.info(
            "Affinity expansion: {} pairs scored, {} above threshold, {} new categories",
            newScored.size,
            newScored.count(_._2 >= minAffinityScore),
            expanded.size
          )
          replyTo ! AffinityExpansionResult(expanded)
          Behaviors.stopped
        } else {
          collectQuotes(contentCategories, newRemaining, newScored, minAffinityScore, maxExpansion, replyTo)
        }

      case Timeout =>
        // Use whatever we have so far
        val expanded = computeExpansion(contentCategories, scored, minAffinityScore, maxExpansion)
        replyTo ! AffinityExpansionResult(expanded)
        Behaviors.stopped

      case _ => Behaviors.same
    }
  }

  /**
   * Compute the expansion set.
   *
   * Returns empty until a reverse-affinity (adProduct → contentSet) lookup
   * lands in AffinityRegistryDData. See the class doc for the deletion of
   * the previous static-bridge expansion.
   */
  private def computeExpansion(
      existingCategories: Set[String],
      scored: Vector[(String, Double)],
      minAffinityScore: Double,
      maxExpansion: Int
  ): Set[String] = Set.empty
}
