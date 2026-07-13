package promovolve

import codec.{ OpaqueCodec, OpaqueJsonSupport, OpaqueOrdering }
import promovolve.taxonomy.IABTaxonomy
import spray.json.*

import java.time.Instant
import scala.annotation.targetName

// ========== Opaque Types: Domain IDs ==========

opaque type Name = String
opaque type AdvertiserId = String
opaque type PublisherId = String
opaque type SiteId = String
opaque type CampaignId = String
opaque type CreativeId = String
opaque type CategoryId = String
opaque type AdProductCategoryId = String
opaque type SlotId = String
opaque type FlushId = String
opaque type VerificationToken = String
opaque type URL = String
opaque type CPM = BigDecimal
opaque type Confidence = Double
opaque type Weight = Double
opaque type Budget = BigDecimal
opaque type Spend = BigDecimal
opaque type AdSize = (Int, Int)

// ========== Opaque Types: Value Objects ==========

enum SelState {
  case Pending, Approved, Rejected
}

final case class Candidate(
    creativeId: CreativeId,
    campaignId: CampaignId,
    advertiserId: AdvertiserId,
    cpm: CPM, // RL-shaded bid (for auction ranking)
    category: CategoryId,
    creativeHash: String = "",
    landingDomain: String = "",
    preApproved: Boolean = false, // Already approved for this publisher (skip pending queue)
    adProductCategory: Option[AdProductCategoryId] = None, // IAB Ad Product Taxonomy 2.0 category
    maxCpm: CPM = CPM.zero // advertiser's max CPM (for ServeIndex/Thompson Sampling)
)

final case class Selection(
    publisherId: SiteId,
    url: URL,
    slotId: SlotId,
    ordered: Vector[Candidate], // top-1 first
    idx: Int, // pointer to current candidate
    state: SelState,
    createdAt: Instant,
    expiresAt: Instant
) {
  def current: Candidate = ordered(idx)

  def promoteNext: Option[Selection] =
    if (idx + 1 < ordered.size) Some(copy(idx = idx + 1, state = SelState.Pending)) else None
}

object Name {
  inline def empty: Name = Name("Undefined")

  inline def apply(value: String): Name = value
  extension (name: Name) {
    inline def value: String = name
  }

  given OpaqueCodec[Name, String] = OpaqueCodec.fromEvidence
  given Ordering[Name] = OpaqueOrdering.derived
  given JsonFormat[Name] = OpaqueJsonSupport.opaqueStringJsonFormat
}

object AdvertiserId {
  inline def apply(value: String): AdvertiserId = value

  extension (id: AdvertiserId) {
    inline def value: String = id
  }

  given OpaqueCodec[AdvertiserId, String] = OpaqueCodec.fromEvidence
  given Ordering[AdvertiserId] = OpaqueOrdering.derived
  given JsonFormat[AdvertiserId] = OpaqueJsonSupport.opaqueStringJsonFormat
}

object PublisherId {
  inline def apply(value: String): PublisherId = value

  extension (id: PublisherId) {
    inline def value: String = id
  }

  given OpaqueCodec[PublisherId, String] = OpaqueCodec.fromEvidence
  given Ordering[PublisherId] = OpaqueOrdering.derived
  given JsonFormat[PublisherId] = OpaqueJsonSupport.opaqueStringJsonFormat
}

object SiteId {
  inline def apply(value: String): SiteId = value

  extension (id: SiteId) {
    inline def value: String = id

    /** FNV-1a hash for Bloom filter operations */
    inline def hash: Long = {
      var h = 0xCBF29CE484222325L // FNV offset basis
      val bytes = id.getBytes("UTF-8")
      var i = 0
      while (i < bytes.length) {
        h ^= (bytes(i) & 0xFF).toLong
        h *= 0x100000001B3L // FNV prime
        i += 1
      }
      h
    }
  }

  given OpaqueCodec[SiteId, String] = OpaqueCodec.fromEvidence
  given Ordering[SiteId] = OpaqueOrdering.derived
  given JsonFormat[SiteId] = OpaqueJsonSupport.opaqueStringJsonFormat
}

object CampaignId {
  inline def apply(value: String): CampaignId = value

  extension (id: CampaignId) {
    inline def value: String = id
  }

  given OpaqueCodec[CampaignId, String] = OpaqueCodec.fromEvidence
  given Ordering[CampaignId] = OpaqueOrdering.derived
  given JsonFormat[CampaignId] = OpaqueJsonSupport.opaqueStringJsonFormat
}

object CreativeId {
  inline def apply(value: String): CreativeId = value

  extension (id: CreativeId) {
    inline def value: String = id
  }

  given OpaqueCodec[CreativeId, String] = OpaqueCodec.fromEvidence
  given JsonFormat[CreativeId] = OpaqueJsonSupport.opaqueStringJsonFormat
}

object CategoryId {
  inline def apply(value: String): CategoryId = value

  extension (id: CategoryId) {
    inline def value: String = id
  }

  /**
   * Sentinel category used to route filler auctions (pages where
   * Gemini found no match against the demand pool). Campaigns with
   * `bidOnUnmatchedContext = true` are invited to bid on requests
   * carrying this id — not because the page is about "filler", but
   * because the category bid check has a carve-out for it. Keep the
   * value prefixed with `__` so it sorts out of the way and can't
   * collide with any real IAB numeric id.
   */
  val Filler: CategoryId = "__filler__"

  given OpaqueCodec[CategoryId, String] = OpaqueCodec.fromEvidence
  given Ordering[CategoryId] = OpaqueOrdering.derived
  given JsonFormat[CategoryId] = OpaqueJsonSupport.opaqueStringJsonFormat
}

object AdProductCategoryId {
  inline def apply(value: String): AdProductCategoryId = value

  extension (id: AdProductCategoryId) {
    inline def value: String = id
  }

  given OpaqueCodec[AdProductCategoryId, String] = OpaqueCodec.fromEvidence
  given Ordering[AdProductCategoryId] = OpaqueOrdering.derived
  given JsonFormat[AdProductCategoryId] = OpaqueJsonSupport.opaqueStringJsonFormat
}

object SlotId {
  inline def apply(value: String): SlotId = value

  extension (id: SlotId) {
    inline def value: String = id
  }

  given OpaqueCodec[SlotId, String] = OpaqueCodec.fromEvidence
  given JsonFormat[SlotId] = OpaqueJsonSupport.opaqueStringJsonFormat
}

object VerificationToken {
  inline def apply(value: String): VerificationToken = value

  def generate(): VerificationToken = java.util.UUID.randomUUID().toString

  extension (t: VerificationToken) {
    inline def value: String = t
  }

  given OpaqueCodec[VerificationToken, String] = OpaqueCodec.fromEvidence
  given JsonFormat[VerificationToken] = OpaqueJsonSupport.opaqueStringJsonFormat
}

object FlushId {
  inline def apply(value: String): FlushId = value

  /**
   * Generate a unique flush ID from campaign, epoch day, a per-actor-
   * incarnation nonce, and sequence. The incarnation nonce ensures flushIds
   * minted after a restart never collide with pre-restart ones (flushSeq
   * resets to 0 on restart) — otherwise the advertiser dedupes them and
   * freezes its spendToday.
   */
  inline def generate(campaignId: CampaignId, epochDay: Long, incarnation: String, seq: Long): FlushId =
    s"${campaignId.value}:$epochDay:$incarnation:$seq"

  extension (id: FlushId) {
    inline def value: String = id
  }

  given OpaqueCodec[FlushId, String] = OpaqueCodec.fromEvidence
  given JsonFormat[FlushId] = OpaqueJsonSupport.opaqueStringJsonFormat
}

object URL {
  inline def apply(value: String): URL = value

  extension (url: URL) {
    inline def value: String = url
    def domain: Option[String] =
      try {
        val uri = new java.net.URI(url)
        Option(uri.getHost)
      } catch {
        case _: Exception => None
      }
  }

  given OpaqueCodec[URL, String] = OpaqueCodec.fromEvidence
  given JsonFormat[URL] = OpaqueJsonSupport.opaqueStringJsonFormat
}

object CPM {
  inline def apply(value: BigDecimal): CPM = value
  inline def apply(value: Double): CPM = BigDecimal(value)
  inline def apply(value: Int): CPM = BigDecimal(value)
  inline def zero: CPM = BigDecimal(0)

  inline def max(x: CPM, y: CPM): CPM = if (x.underlying > y.underlying) x else y
  inline def min(x: CPM, y: CPM): CPM = if (x.underlying < y.underlying) x else y

  extension (cpm: CPM) {
    inline def underlying: BigDecimal = cpm
    inline def value: BigDecimal = cpm
    inline def toDouble: Double = cpm.toDouble
    inline def +(other: CPM): CPM = cpm + other
    inline def -(other: CPM): CPM = cpm - other
    inline def *(factor: BigDecimal): CPM = cpm * factor
    @targetName("cpmTimesDouble")
    inline def *(factor: Double): CPM = cpm * BigDecimal(factor)
    inline def /(divisor: BigDecimal): CPM = cpm / divisor
    @targetName("cpmDivDouble")
    inline def /(divisor: Double): CPM = cpm / BigDecimal(divisor)
    inline def compare(other: CPM): Int = cpm.compare(other)

    inline def >=(other: CPM): Boolean = cpm >= other
    inline def <=(other: CPM): Boolean = cpm <= other
    inline def >(other: CPM): Boolean = cpm > other
    inline def <(other: CPM): Boolean = cpm < other
  }

  given OpaqueCodec[CPM, BigDecimal] = OpaqueCodec.fromEvidence
  given Ordering[CPM] = OpaqueOrdering.derived
  given JsonFormat[CPM] = OpaqueJsonSupport.opaqueBigDecimalJsonFormat
}

object Confidence {
  inline def apply(value: Double): Confidence = value.max(0.0).min(1.0)
  inline def zero: Confidence = 0.0
  inline def one: Confidence = 1.0

  extension (conf: Confidence) {
    inline def value: Double = conf
    inline def *(other: Confidence): Confidence = conf * other
    inline def +(other: Confidence): Confidence = (conf + other).min(1.0)
  }

  given OpaqueCodec[Confidence, Double] = OpaqueCodec.fromEvidence
  given Ordering[Confidence] = OpaqueOrdering.derived
}

object Weight {
  inline def apply(value: Double): Weight = value.max(0.0)
  inline def zero: Weight = 0.0
  inline def one: Weight = 1.0

  extension (w: Weight) {
    inline def value: Double = w
    inline def *(other: Weight): Weight = w * other
    inline def +(other: Weight): Weight = w + other
    inline def /(divisor: Double): Weight = w / divisor
  }

  given OpaqueCodec[Weight, Double] = OpaqueCodec.fromEvidence
  given Ordering[Weight] = OpaqueOrdering.derived
}

// ========== Extensions ==========

extension (selections: List[IABTaxonomy.Selection]) {
  def categoryScores: Map[CategoryId, Confidence] =
    selections.map(s => CategoryId(s.id) -> Confidence(s.confidence)).toMap
}

// BigDecimal extensions for cross-type operations with CPM
extension (bd: BigDecimal) {
  @targetName("bigDecimalGteCpm")
  inline def >=(cpm: CPM): Boolean = bd >= cpm.underlying
  @targetName("bigDecimalLtCpm")
  inline def <(cpm: CPM): Boolean = bd < cpm.underlying
  @targetName("bigDecimalMinusCpm")
  inline def -(cpm: CPM): BigDecimal = bd - cpm.underlying
  @targetName("bigDecimalDivCpm")
  inline def /(cpm: CPM): Double = bd.toDouble / cpm.toDouble
}

// ========== Domain Models ==========

object Budget {
  given OpaqueCodec[Budget, BigDecimal] = OpaqueCodec.fromEvidence

  inline def apply(value: BigDecimal): Budget = value.max(BigDecimal(0))
  inline def apply(value: Double): Budget = BigDecimal(value).max(BigDecimal(0))
  inline def apply(value: Int): Budget = BigDecimal(value)
  inline def zero: Budget = BigDecimal(0)

  extension (b: Budget) {
    inline def underlying: BigDecimal = b
    inline def value: BigDecimal = b
    inline def toDouble: Double = b.toDouble
    inline def +(other: Budget): Budget = Budget(b + other)
    inline def -(spend: Spend): Budget = Budget(b - spend.underlying)
    @targetName("budgetMinusCpm")
    inline def -(cpm: CPM): BigDecimal = b - cpm.underlying // For probability calculations
    inline def remaining(spend: Spend): Budget = Budget(b - spend.underlying)
    inline def <=(other: Budget): Boolean = b <= other
    inline def >=(other: Budget): Boolean = b >= other
    inline def >(spend: Spend): Boolean = b > spend.underlying
    inline def hasRemaining(spend: Spend): Boolean = b > spend.underlying
    // Cross-type comparisons with CPM (for budget throttling)
    @targetName("budgetGteCpm")
    inline def >=(cpm: CPM): Boolean = b >= cpm.underlying
    @targetName("budgetLtCpm")
    inline def <(cpm: CPM): Boolean = b < cpm.underlying
    @targetName("budgetDivCpm")
    inline def /(cpm: CPM): Double = b.toDouble / cpm.toDouble
  }
}

object Spend {
  given OpaqueCodec[Spend, BigDecimal] = OpaqueCodec.fromEvidence

  inline def apply(value: BigDecimal): Spend = value.max(BigDecimal(0))
  inline def apply(value: Double): Spend = BigDecimal(value).max(BigDecimal(0))
  inline def apply(value: Int): Spend = BigDecimal(value)
  inline def zero: Spend = BigDecimal(0)

  extension (s: Spend) {
    inline def underlying: BigDecimal = s
    inline def value: BigDecimal = s
    inline def toDouble: Double = s.toDouble
    inline def +(other: Spend): Spend = Spend(s + other)
    @targetName("addAmount")
    inline def +(amount: BigDecimal): Spend = Spend(s + amount)
    inline def <=(budget: Budget): Boolean = s <= budget.underlying
    inline def >=(budget: Budget): Boolean = s >= budget.underlying
    inline def <(budget: Budget): Boolean = s < budget.underlying
    inline def withinBudget(budget: Budget): Boolean = s <= budget.underlying
    // Spend-to-Spend comparisons
    @targetName("gtSpend")
    inline def >(other: Spend): Boolean = s > other.underlying
    @targetName("ltSpend")
    inline def <(other: Spend): Boolean = s < other.underlying
    @targetName("gteSpend")
    inline def >=(other: Spend): Boolean = s >= other.underlying
    @targetName("lteSpend")
    inline def <=(other: Spend): Boolean = s <= other.underlying
    inline def isPositive: Boolean = s > BigDecimal(0)
  }
}

object AdSize {
  // IAB Standard Ad Sizes
  val MediumRectangle: AdSize = (300, 250)
  val Leaderboard: AdSize = (728, 90)
  val WideSkyscraper: AdSize = (160, 600)
  val MobileBanner: AdSize = (320, 50)
  val Billboard: AdSize = (970, 250)
  val HalfPage: AdSize = (300, 600)
  val LargeMobileRectangle: AdSize = (320, 100)

  inline def apply(width: Int, height: Int): AdSize = (width, height)

  inline def fromTuple(tuple: (Int, Int)): AdSize = tuple

  extension (size: AdSize) {
    inline def width: Int = size._1
    inline def height: Int = size._2
    inline def tuple: (Int, Int) = size
    inline def aspectRatio: Double = size._1.toDouble / size._2.toDouble
    inline def area: Int = size._1 * size._2
    inline def isPortrait: Boolean = size._2 > size._1
    inline def isLandscape: Boolean = size._1 > size._2
    inline def isSquare: Boolean = size._1 == size._2
  }
}

// ========== Domain Events ==========

/**
 * Domain events for campaign lifecycle and state changes.
 *
 * These events trigger re-auctions when the campaign landscape changes:
 * - Campaign lifecycle (created/paused/resumed/deleted)
 * - CPM changes (affects ranking and threshold filtering)
 * - Budget state (exhausted/reset affects eligibility)
 */

/** Base trait for all campaign-related events */
sealed trait CampaignEvent extends CborSerializable {
  def campaignId: CampaignId
  def timestamp: Instant
}

/** Base trait for budget-related events (campaign or advertiser level) */
sealed trait BudgetEvent extends CborSerializable {
  def timestamp: Instant
}

/** Campaign went live (first time or after being paused) */
final case class CampaignStarted(
    campaignId: CampaignId,
    categories: Set[CategoryId],
    maxCpm: CPM,
    timestamp: Instant
) extends CampaignEvent

/**
 * Campaign paused by advertiser (temporarily inactive).
 * Extends BudgetEvent so it flows through the budget topic to all AdServers.
 */
final case class CampaignPaused(
    campaignId: CampaignId,
    timestamp: Instant
) extends CampaignEvent with BudgetEvent

/** Campaign ad product category changed — remove stale creatives from all sites */
final case class CampaignAdProductChanged(
    campaignId: CampaignId,
    timestamp: Instant
) extends CampaignEvent with BudgetEvent

/** Campaign resumed after being paused */
final case class CampaignResumed(
    campaignId: CampaignId,
    timestamp: Instant
) extends CampaignEvent

/** Campaign permanently deleted */
final case class CampaignDeleted(
    campaignId: CampaignId,
    timestamp: Instant
) extends CampaignEvent

/**
 * Campaign max CPM updated
 *
 * Triggers re-auction if change is significant (> 5%)
 * - Affects auction ranking (who wins)
 * - Affects threshold filtering (who qualifies)
 * - Affects revenue (what publisher earns)
 */
final case class CpmUpdated(
    campaignId: CampaignId,
    oldCpm: CPM,
    newCpm: CPM,
    timestamp: Instant
) extends CampaignEvent {
  def percentChange: Double = ((newCpm.value - oldCpm.value).abs / oldCpm.value).toDouble
}

// ---------- Campaign-level budget events ----------

/**
 * Campaign daily budget exhausted (campaign becomes ineligible)
 *
 * Triggers re-auction immediately - campaign can no longer bid
 */
final case class CampaignBudgetExhausted(
    campaignId: CampaignId,
    timestamp: Instant
) extends BudgetEvent

/**
 * Campaign daily budget reset (typically at midnight)
 *
 * Campaign becomes eligible again, should re-enter auctions
 */
final case class CampaignBudgetReset(
    campaignId: CampaignId,
    newBudget: Budget,
    timestamp: Instant
) extends BudgetEvent

/**
 * Campaign reached its scheduled endAt and was auto-flipped to Ended.
 * Distinct from a manual pause — the campaign won't auto-resume even
 * if the user clears the end date later (they'd need to explicitly
 * Activate it).
 */
final case class CampaignEnded(
    campaignId: CampaignId,
    advertiserId: AdvertiserId,
    timestamp: Instant
) extends BudgetEvent

/**
 * Campaign spend update (pushed periodically for pacing)
 *
 * Published by CampaignEntity after each FlushBuffer (~500ms or 20 events).
 * AdServer subscribes to maintain local spend cache, eliminating per-request queries.
 */
final case class SpendUpdate(
    campaignId: CampaignId,
    advertiserId: AdvertiserId,
    dailyBudget: Budget,
    todaySpend: Spend,
    dayStart: Instant,
    timestamp: Instant
) extends BudgetEvent

// ---------- Advertiser-level budget events ----------

/**
 * Advertiser daily budget exhausted (all campaigns become ineligible)
 *
 * Triggers re-auction immediately - all advertiser's campaigns stop bidding
 */
final case class AdvertiserBudgetExhausted(
    advertiserId: AdvertiserId,
    timestamp: Instant
) extends BudgetEvent

/**
 * Advertiser daily budget reset (typically at midnight)
 *
 * All advertiser's campaigns become eligible again
 */
final case class AdvertiserBudgetReset(
    advertiserId: AdvertiserId,
    newBudget: Budget,
    timestamp: Instant
) extends BudgetEvent

/**
 * Advertiser status changed to Suspended or Closed
 *
 * All advertiser's creatives should be removed from ServeIndex
 */
final case class AdvertiserSuspended(
    advertiserId: AdvertiserId,
    timestamp: Instant
) extends BudgetEvent

// ---------- Creative-level events ----------

/**
 * Creative status changed (paused or reactivated)
 *
 * When paused: triggers removal from ServeIndex
 * When reactivated: triggers re-auction for the campaign
 */
final case class CreativeStatusChanged(
    creativeId: CreativeId,
    advertiserId: AdvertiserId,
    campaignId: CampaignId,
    isActive: Boolean,
    timestamp: Instant
) extends BudgetEvent

// ---------- Publisher-level events (SSE notifications) ----------

/**
 * Pending creatives queued for publisher approval
 *
 * Published by AdServer when new creatives are queued.
 * PendingEventHub subscribes to broadcast via SSE to publisher dashboards.
 */
final case class PendingCreativesQueued(
    siteId: SiteId,
    url: URL,
    slotId: SlotId,
    count: Int,
    topCreativeId: CreativeId,
    timestamp: Instant
) extends BudgetEvent

/**
 * A creative skipped the approval queue via the site's auto-approve trust
 * (same campaign / landing registrable-domain as an earlier manual approval).
 *
 * Published by AdServer at candidate-partition time; PendingEventHub
 * broadcasts it so an open approval page refreshes live.
 */
final case class CreativeAutoApproved(
    siteId: SiteId,
    url: URL,
    slotId: SlotId,
    creativeId: CreativeId,
    campaignId: CampaignId,
    timestamp: Instant
) extends BudgetEvent
