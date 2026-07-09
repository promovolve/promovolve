package promovolve.publisher

import slick.jdbc.PostgresProfile.api.*
import spray.json.*

import java.time.Instant
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.*

/**
 * Status of a creative.
 *
 * Draft means the advertiser saved a work-in-progress design from the
 * designer. Drafts skip CreativeProcessor (no banner render, no
 * Gemini verify) and are never eligible for delivery. Promoted to
 * Active when the advertiser clicks Publish.
 */
enum CreativeStatus {
  case Draft, Active, Paused, Deleted
}

object CreativeStatus {
  def fromString(s: String): CreativeStatus = s.toLowerCase match {
    case "draft"   => Draft
    case "active"  => Active
    case "paused"  => Paused
    case "deleted" => Deleted
    case _         => Active // Default to active for unknown values
  }
}

/**
 * Creative metadata linking an image to a campaign with verification results.
 *
 * Image fields (s3Key, mime, width, height) are denormalized from ImageAsset
 * for fast serving without joins.
 */
final case class Creative(
    creativeId: String, // ULID
    imageHash: String, // FK to ImageAsset
    advertiserId: String,
    campaignId: String,
    name: String,
    landingUrl: String,
    landingDomain: String,
    createdAt: Instant,
    // Denormalized from ImageAsset for fast serving
    s3Key: String,
    mime: String,
    width: Int,
    height: Int,
    // Magazine creative pages (JSON for expandable-magazine-banner)
    pagesJson: Option[String] = None,
    // Banner-level config (JSON for the banner's `config` attribute):
    // expandAnimation, expandDurationMs, font, etc. Persisted next to
    // pagesJson because they're a pair — both are opaque blobs from
    // the designer that the banner element consumes verbatim. Nullable
    // for legacy rows; null on read = banner uses its hardcoded
    // defaults (today's behavior).
    bannerConfigJson: Option[String] = None,
    // Verification (from Gemini, per-campaign)
    matchConfidence: Option[Double] = None, // 0.0-1.0
    verificationReason: Option[String] = None, // "Travel imagery detected"
    declaredCategory: Option[String] = None, // Campaign's adProductCategory
    // Safety flags (from Gemini)
    adultContent: Boolean = false,
    violence: Boolean = false,
    hateSpeech: Boolean = false,
    safetyScore: Option[Double] = None, // 0.0 = unsafe, 1.0 = safe
    // Suggested content categories (IAB Content Taxonomy 3.0 IDs from Gemini)
    suggestedContentCategories: List[String] = Nil,
    // LP text snapshot captured at authoring time — the full concatenated
    // text of the landing-page sections the advertiser extracted. Used by
    // Gemini category verification instead of the rendered banner PNG
    // (text is denser signal and avoids base64-image payload bloat).
    lpTextSnapshot: Option[String] = None,
    // Status
    status: CreativeStatus = CreativeStatus.Active
) {

  /** True if creative has been verified */
  def isVerified: Boolean = matchConfidence.isDefined

  /** True if any safety flag is set */
  def isSafetyBlocked: Boolean = adultContent || violence || hateSpeech

  /** True if creative is active (not paused) */
  def isActive: Boolean = status == CreativeStatus.Active

  /** True if creative can participate in auctions (active and not safety blocked) */
  def canParticipate: Boolean = isActive && !isSafetyBlocked
}

/** Storage for creative metadata (one per campaign assignment). */
trait CreativeRepo {
  def put(creative: Creative): Future[Unit]
  def get(creativeId: String): Future[Option[Creative]]
  def getByImageHash(hash: String): Future[Vector[Creative]]
  def getByCampaign(campaignId: String): Future[Vector[Creative]]
  def updateVerification(
      creativeId: String,
      confidence: Double,
      reason: String,
      adultContent: Boolean = false,
      violence: Boolean = false,
      hateSpeech: Boolean = false,
      safetyScore: Option[Double] = None,
      suggestedContentCategories: List[String] = Nil
  ): Future[Unit]
  def updateStatus(creativeId: String, status: CreativeStatus): Future[Unit]
  def delete(creativeId: String): Future[Unit]

  /** Find rich creatives that need banner rendering (have pages JSON but empty s3_key). */
  def getPendingRender(): Future[Vector[Creative]]
}

/** PostgreSQL-backed implementation using Slick. */
final class SlickCreativeRepo(db: slick.jdbc.JdbcBackend#Database)(using ec: ExecutionContext)
    extends CreativeRepo {

  import promovolve.SlickMappers.given

  private val creatives = TableQuery[CreativeTable]

  // Custom mapper for CreativeStatus <-> String
  private given statusMapper: BaseColumnType[CreativeStatus] =
    MappedColumnType.base[CreativeStatus, String](
      status => status.toString,
      str => CreativeStatus.fromString(str)
    )

  // Custom mapper for List[String] <-> JSON text (stored as TEXT in DB)
  private given stringListMapper: BaseColumnType[List[String]] =
    MappedColumnType.base[List[String], String](
      list => if (list.isEmpty) "[]" else JsArray(list.map(JsString(_))*).compactPrint,
      json =>
        scala.util.Try(json.parseJson) match {
          case scala.util.Success(JsArray(elements)) => elements.collect { case JsString(s) => s }.toList
          case _                                     => Nil
        }
    )

  def ensureSchema(): Unit = {
    val createTableSql = sql"""
      CREATE TABLE IF NOT EXISTS creative (
        creative_id VARCHAR(32) PRIMARY KEY,
        image_hash VARCHAR(64) NOT NULL,
        advertiser_id VARCHAR(32) NOT NULL,
        campaign_id VARCHAR(32) NOT NULL,
        name VARCHAR(256),
        landing_url TEXT,
        landing_domain VARCHAR(256),
        created_at TIMESTAMP NOT NULL,
        -- Denormalized from image_asset for fast serving
        s3_key VARCHAR(512) NOT NULL,
        mime VARCHAR(64) NOT NULL,
        width INT NOT NULL,
        height INT NOT NULL,
        -- Verification
        match_confidence DOUBLE PRECISION,
        verification_reason TEXT,
        declared_category VARCHAR(64),
        -- Safety flags
        adult_content BOOLEAN NOT NULL DEFAULT FALSE,
        violence BOOLEAN NOT NULL DEFAULT FALSE,
        hate_speech BOOLEAN NOT NULL DEFAULT FALSE,
        safety_score DOUBLE PRECISION,
        -- Suggested content categories (JSON array as text)
        suggested_content_categories TEXT,
        -- Status
        status VARCHAR(16) NOT NULL DEFAULT 'Active'
      )
    """.asUpdate

    val createHashIndexSql = sql"""
      CREATE INDEX IF NOT EXISTS idx_creative_image_hash ON creative (image_hash)
    """.asUpdate

    val createCampaignIndexSql = sql"""
      CREATE INDEX IF NOT EXISTS idx_creative_campaign ON creative (campaign_id)
    """.asUpdate

    val addSuggestedCategoriesCol = sql"""
      ALTER TABLE creative ADD COLUMN IF NOT EXISTS suggested_content_categories TEXT
    """.asUpdate

    val addPagesJsonCol = sql"""
      ALTER TABLE creative ADD COLUMN IF NOT EXISTS pages_json TEXT
    """.asUpdate

    val addLpTextSnapshotCol = sql"""
      ALTER TABLE creative ADD COLUMN IF NOT EXISTS lp_text_snapshot TEXT
    """.asUpdate

    val addBannerConfigJsonCol = sql"""
      ALTER TABLE creative ADD COLUMN IF NOT EXISTS banner_config_json TEXT
    """.asUpdate

    Await.result(
      db.run(createTableSql >> createHashIndexSql >> createCampaignIndexSql >> addSuggestedCategoriesCol >>
        addPagesJsonCol >> addLpTextSnapshotCol >> addBannerConfigJsonCol), 10.seconds)
  }

  override def put(creative: Creative): Future[Unit] = {
    val upsert = creatives.insertOrUpdate(creative)
    db.run(upsert).map(_ => ())
  }

  override def get(creativeId: String): Future[Option[Creative]] = {
    val query = creatives.filter(_.creativeId === creativeId).result.headOption
    db.run(query)
  }

  override def getByImageHash(hash: String): Future[Vector[Creative]] = {
    val query = creatives.filter(_.imageHash === hash).result
    db.run(query).map(_.toVector)
  }

  override def getByCampaign(campaignId: String): Future[Vector[Creative]] = {
    val query =
      creatives.filter(c => c.campaignId === campaignId && c.status =!= (CreativeStatus.Deleted: CreativeStatus)).result
    db.run(query).map(_.toVector)
  }

  override def updateVerification(
      creativeId: String,
      confidence: Double,
      reason: String,
      adultContent: Boolean = false,
      violence: Boolean = false,
      hateSpeech: Boolean = false,
      safetyScore: Option[Double] = None,
      suggestedContentCategories: List[String] = Nil
  ): Future[Unit] = {
    val update = creatives
      .filter(_.creativeId === creativeId)
      .map(c =>
        (c.matchConfidence, c.verificationReason, c.adultContent, c.violence, c.hateSpeech, c.safetyScore,
          c.suggestedContentCategories))
      .update((Some(confidence), Some(reason), adultContent, violence, hateSpeech, safetyScore,
        suggestedContentCategories))
    db.run(update).map(_ => ())
  }

  override def updateStatus(creativeId: String, status: CreativeStatus): Future[Unit] = {
    val update = creatives
      .filter(_.creativeId === creativeId)
      .map(_.status)
      .update(status)
    db.run(update).map(_ => ())
  }

  override def delete(creativeId: String): Future[Unit] = {
    updateStatus(creativeId, CreativeStatus.Deleted)
  }

  override def getPendingRender(): Future[Vector[Creative]] = {
    val query = creatives.filter(c =>
      c.mime === "application/json+expandable" &&
      c.s3Key === "" &&
      c.status =!= (CreativeStatus.Deleted: CreativeStatus)
    ).result
    db.run(query).map(_.toVector)
  }

  private class CreativeTable(tag: Tag) extends Table[Creative](tag, "creative") {
    def creativeId = column[String]("creative_id", O.PrimaryKey)
    def imageHash = column[String]("image_hash")
    def advertiserId = column[String]("advertiser_id")
    def campaignId = column[String]("campaign_id")
    def name = column[String]("name")
    def landingUrl = column[String]("landing_url")
    def landingDomain = column[String]("landing_domain")
    def createdAt = column[Instant]("created_at")
    // Denormalized from image_asset
    def s3Key = column[String]("s3_key")
    def mime = column[String]("mime")
    def width = column[Int]("width")
    def height = column[Int]("height")
    // Magazine creative pages
    def pagesJson = column[Option[String]]("pages_json")
    // Banner-level config (animation, duration, font, etc.)
    def bannerConfigJson = column[Option[String]]("banner_config_json")
    // Verification
    def matchConfidence = column[Option[Double]]("match_confidence")
    def verificationReason = column[Option[String]]("verification_reason")
    def declaredCategory = column[Option[String]]("declared_category")
    // Safety flags
    def adultContent = column[Boolean]("adult_content", O.Default(false))
    def violence = column[Boolean]("violence", O.Default(false))
    def hateSpeech = column[Boolean]("hate_speech", O.Default(false))
    def safetyScore = column[Option[Double]]("safety_score")
    // Suggested content categories
    def suggestedContentCategories = column[List[String]]("suggested_content_categories", O.Default(Nil))
    // LP text snapshot (full extracted text, fed to Gemini verify)
    def lpTextSnapshot = column[Option[String]]("lp_text_snapshot")
    // Status
    def status = column[CreativeStatus]("status", O.Default(CreativeStatus.Active))

    // Index on image_hash for dedup lookups
    def hashIdx = index("idx_creative_image_hash", imageHash)
    // Index on campaign_id for campaign lookups
    def campaignIdx = index("idx_creative_campaign", campaignId)

    // Nested tuple projection — Creative has 24 fields, past Tuple22,
    // so we split into two tuples and map manually via <>.
    def * =
      (
        (creativeId, imageHash, advertiserId, campaignId, name, landingUrl, landingDomain, createdAt, s3Key, mime,
          width),
        (height, pagesJson, bannerConfigJson, matchConfidence, verificationReason, declaredCategory, adultContent,
          violence, hateSpeech, safetyScore, suggestedContentCategories, lpTextSnapshot, status)
      ) <> (
        {
          case ((cid, ih, aid, cid2, nm, lu, ld, ca, sk, mm, w),
                (h, pj, bcj, mc, vr, dc, ac, vi, hs, ss, scc, lts, st)) =>
            Creative(cid, ih, aid, cid2, nm, lu, ld, ca, sk, mm, w, h, pj, bcj, mc, vr, dc, ac, vi, hs, ss, scc, lts,
              st)
        },
        (c: Creative) =>
          Some((
            (c.creativeId, c.imageHash, c.advertiserId, c.campaignId, c.name, c.landingUrl, c.landingDomain,
              c.createdAt, c.s3Key, c.mime, c.width),
            (c.height, c.pagesJson, c.bannerConfigJson, c.matchConfidence, c.verificationReason, c.declaredCategory,
              c.adultContent, c.violence, c.hateSpeech, c.safetyScore, c.suggestedContentCategories, c.lpTextSnapshot,
              c.status)
          ))
      )
  }
}
