package promovolve.publisher

import promovolve.*

import java.net.URI

// ========== Opaque Types: Publisher-Specific ==========

opaque type CDNPath = URI
opaque type MimeType = String

/** Approval status for a creative on a publisher's site. */
enum ApprovalStatus {
  case Approved, Rejected
}

/** Single candidate with all metadata needed for rendering and tracking.
  *
  * @param categoryScore Thompson-sampled CTR from TaxonomyRankerEntity, used for
  *                      learned selection. Default 0.5 (neutral) if not yet scored.
  * @param adProductCategory IAB Ad Product Taxonomy 2.0 category (what the campaign is selling)
  */
final case class CandidateView(
    creativeId: CreativeId,
    campaignId: CampaignId,
    advertiserId: AdvertiserId,
    assetUrl: CDNPath,
    mime: MimeType,
    width: Int,
    height: Int,
    category: CategoryId,
    cpm: CPM,
    classifiedAtMs: Long,       // Epoch milliseconds when content was classified
    categoryScore: Double = 0.5, // Thompson-sampled CTR (0.5 = neutral prior)
    adProductCategory: Option[AdProductCategoryId] = None, // IAB Ad Product Taxonomy 2.0 category
    landingDomain: String = "",  // Landing page domain for domain blocking
    landingUrl: String = "",     // Full landing page URL for click-through
) extends CborSerializable

// ========== Domain Models ==========

/** All candidates for a slot. MAB selection happens at serve time from this pool. */
final case class ServeView(
    candidates: Vector[CandidateView], // ALL approved candidates for MAB selection
    version: Long, // e.g., auction timestamp or System.currentTimeMillis
    expiresAtMs: Long, // epoch millis; TTL for re-auction
    pageCategories: Set[String] = Set.empty // All content categories for this page (for affinity learning)
) extends CborSerializable

/** Where the asset will live for serving (used in approval responses). */
final case class AssetPointer(s3Key: String, cdnUri: URI) extends CborSerializable

object CDNPath {
  inline def apply(uri: URI): CDNPath = uri
  inline def apply(value: String): CDNPath = new URI(value)

  extension (path: CDNPath) {
    inline def uri: URI = path
    inline def value: String = path.toString
  }
}

object MimeType {
  inline def apply(value: String): MimeType = value
  inline def imageJpeg: MimeType = "image/jpeg"
  inline def imagePng: MimeType = "image/png"
  inline def imageGif: MimeType = "image/gif"
  inline def imageWebp: MimeType = "image/webp"
  inline def videoMp4: MimeType = "video/mp4"

  extension (mime: MimeType) {
    inline def value: String = mime
    inline def isImage: Boolean = mime.startsWith("image/")
    inline def isVideo: Boolean = mime.startsWith("video/")
  }
}

object Keys {

  /** Key format used across ServeIndex and /serve: pub|slot */
  inline def key(pub: SiteId, slot: SlotId): String =
    s"${pub.value}|${slot.value}"

  /** For backward compatibility with String-based APIs */
  inline def keyUnsafe(pub: String, slot: String): String =
    s"$pub|$slot"
}