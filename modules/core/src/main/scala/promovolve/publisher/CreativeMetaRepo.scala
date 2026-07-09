package promovolve.publisher

import promovolve.publisher.assessment.CategoryVerification.VerificationResult
import slick.jdbc.PostgresProfile.api.*

import java.time.Instant
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.*

/** Creative asset metadata saved at ingest (upload) time, with optional LLM assessment. */
final case class CreativeMeta(
    advertiserId: String,
    creativeId: String,
    s3Key: String, // e.g. "creatives/adv123/cr456/ab12cd.../banner.png"
    hash: String, // SHA-256 (or similar) used to build immutable keys
    mime: String,
    width: Int,
    height: Int,
    landingDomain: String,
    // --- LLM Assessment fields (populated async after upload) ---
    assessedAt: Option[Instant] = None,
    assessmentStatus: String = "pending", // "pending", "assessed", "failed"
    safetyScore: Option[Double] = None,
    qualityScore: Option[Double] = None,
    adultContent: Boolean = false,
    violence: Boolean = false,
    hateSpeech: Boolean = false,
    detectedCategories: String = "", // Comma-separated IAB category codes
    suggestedCategory: Option[String] = None,
    extractedText: Option[String] = None,
    assessmentModel: Option[String] = None,
    assessmentError: Option[String] = None, // Error message if assessment failed
    // --- Category Verification fields (populated after assessment with adProductCategory) ---
    categoryVerificationStatus: Option[String] = None, // "Verified", "Mismatch", "Unverifiable", etc.
    categoryMatchScore: Option[Double] = None, // 0.0-1.0 match score
    categoryConfidence: Option[Double] = None, // LLM's confidence in category detection
    expectedCategories: String = "" // Comma-separated expected categories from mapping
) {

  /** True if LLM assessment completed successfully */
  def isAssessed: Boolean = assessmentStatus == "assessed"

  /** True if assessment was attempted but failed */
  def assessmentFailed: Boolean = assessmentStatus == "failed"

  /** True if creative can participate in auctions (assessed OR failed with permissive mode) */
  def canParticipate: Boolean = assessmentStatus != "pending" && !isSafetyBlocked

  /** True if creative is blocked due to safety flags */
  def isSafetyBlocked: Boolean = adultContent || violence || hateSpeech

  def shouldAutoApprove: Boolean =
    safetyScore.exists(_ >= CreativeMeta.AutoApproveThreshold) &&
    !adultContent && !violence && !hateSpeech

  def shouldAutoReject: Boolean =
    safetyScore.exists(_ < CreativeMeta.AutoRejectThreshold) ||
    adultContent || violence || hateSpeech

  /** Auto-reject for category mismatch only when LLM was very confident */
  def shouldAutoRejectForCategoryMismatch: Boolean =
    categoryVerificationStatus.contains("Mismatch") &&
    categoryConfidence.exists(_ >= 0.8) &&
    categoryMatchScore.exists(_ < 0.1)

  def categoryList: List[String] =
    if (detectedCategories.isEmpty) Nil
    else detectedCategories.split(",").toList

  def expectedCategoryList: List[String] =
    if (expectedCategories.isEmpty) Nil
    else expectedCategories.split(",").toList
}

object CreativeMeta {
  val AutoApproveThreshold = 0.95
  val AutoRejectThreshold = 0.30
}

/** LLM assessment result to be merged into CreativeMeta */
final case class AssessmentResult(
    safetyScore: Double,
    qualityScore: Double,
    adultContent: Boolean,
    violence: Boolean,
    hateSpeech: Boolean,
    detectedCategories: List[String],
    categoryConfidence: Double, // LLM's confidence in category detection (0.0-1.0)
    suggestedCategory: Option[String],
    extractedText: Option[String],
    model: String
)

/** Storage for creative asset metadata (location, integrity hash, etc). */
trait CreativeMetadataRepo {
  def put(meta: CreativeMeta): Future[Unit]
  def get(creativeId: String): Future[Option[CreativeMeta]]
  def getByHash(hash: String): Future[Option[CreativeMeta]]
  def updateAssessment(creativeId: String, result: AssessmentResult): Future[Unit]

  /** Update assessment with category verification results */
  def updateAssessmentWithVerification(
      creativeId: String,
      result: AssessmentResult,
      verification: VerificationResult
  ): Future[Unit]

  /**
   * Mark assessment as failed but allow creative to participate with neutral scores.
   * This is the permissive approach - don't block revenue due to LLM failures.
   */
  def markAssessmentFailed(creativeId: String, error: String): Future[Unit]
}

/** PostgreSQL-backed implementation using Slick. */
final class SlickCreativeMetadataRepo(db: Database)(using ec: ExecutionContext)
    extends CreativeMetadataRepo {

  import promovolve.SlickMappers.given

  private val creatives = TableQuery[CreativeMetaTable]

  def ensureSchema(): Unit = {
    import slick.jdbc.PostgresProfile.api.*

    // First create the table if it doesn't exist (without indexes)
    val createTableSql = sql"""
      CREATE TABLE IF NOT EXISTS creative_meta (
        advertiser_id VARCHAR(255) NOT NULL,
        creative_id VARCHAR(255) PRIMARY KEY,
        s3_key VARCHAR(1024) NOT NULL,
        hash VARCHAR(64) NOT NULL,
        mime VARCHAR(64) NOT NULL,
        width INT NOT NULL,
        height INT NOT NULL,
        landing_domain VARCHAR(255) NOT NULL,
        assessed_at TIMESTAMP,
        assessment_status VARCHAR(32) NOT NULL DEFAULT 'pending',
        safety_score DOUBLE PRECISION,
        quality_score DOUBLE PRECISION,
        adult_content BOOLEAN NOT NULL DEFAULT FALSE,
        violence BOOLEAN NOT NULL DEFAULT FALSE,
        hate_speech BOOLEAN NOT NULL DEFAULT FALSE,
        detected_categories TEXT NOT NULL DEFAULT '',
        suggested_category VARCHAR(64),
        extracted_text TEXT,
        assessment_model VARCHAR(64),
        assessment_error TEXT,
        category_verification_status VARCHAR(32),
        category_match_score DOUBLE PRECISION,
        category_confidence DOUBLE PRECISION,
        expected_categories TEXT NOT NULL DEFAULT ''
      )
    """.asUpdate

    // Then create the index if it doesn't exist (PostgreSQL 9.5+)
    val createIndexSql = sql"""
      CREATE INDEX IF NOT EXISTS idx_creative_meta_hash ON creative_meta (hash)
    """.asUpdate

    Await.result(db.run(createTableSql >> createIndexSql), 10.seconds)
  }

  override def put(meta: CreativeMeta): Future[Unit] = {
    val upsert = creatives.insertOrUpdate(meta)
    db.run(upsert).map(_ => ())
  }

  override def get(creativeId: String): Future[Option[CreativeMeta]] = {
    val query = creatives.filter(_.creativeId === creativeId).result.headOption
    db.run(query)
  }

  override def getByHash(hash: String): Future[Option[CreativeMeta]] = {
    val query = creatives.filter(_.hash === hash).result.headOption
    db.run(query)
  }

  override def updateAssessment(creativeId: String, result: AssessmentResult): Future[Unit] = {
    val now = Instant.now()
    val update = creatives
      .filter(_.creativeId === creativeId)
      .map(c =>
        (
          c.assessedAt, c.assessmentStatus, c.safetyScore, c.qualityScore,
          c.adultContent, c.violence, c.hateSpeech,
          c.detectedCategories, c.suggestedCategory, c.extractedText, c.assessmentModel
        ))
      .update((
        Some(now), "assessed", Some(result.safetyScore), Some(result.qualityScore),
        result.adultContent, result.violence, result.hateSpeech,
        result.detectedCategories.mkString(","), result.suggestedCategory,
        result.extractedText, Some(result.model)
      ))
    db.run(update).map(_ => ())
  }

  override def updateAssessmentWithVerification(
      creativeId: String,
      result: AssessmentResult,
      verification: VerificationResult
  ): Future[Unit] = {
    val now = Instant.now()
    val update = creatives
      .filter(_.creativeId === creativeId)
      .map(c =>
        (
          c.assessedAt, c.assessmentStatus, c.safetyScore, c.qualityScore,
          c.adultContent, c.violence, c.hateSpeech,
          c.detectedCategories, c.suggestedCategory, c.extractedText, c.assessmentModel,
          c.categoryVerificationStatus, c.categoryMatchScore, c.categoryConfidence, c.expectedCategories
        ))
      .update((
        Some(now), "assessed", Some(result.safetyScore), Some(result.qualityScore),
        result.adultContent, result.violence, result.hateSpeech,
        result.detectedCategories.mkString(","), result.suggestedCategory,
        result.extractedText, Some(result.model),
        Some(verification.statusString), Some(verification.matchScore),
        Some(verification.llmConfidence), verification.expectedCategories.mkString(",")
      ))
    db.run(update).map(_ => ())
  }

  override def markAssessmentFailed(creativeId: String, error: String): Future[Unit] = {
    val now = Instant.now()
    val update = creatives
      .filter(_.creativeId === creativeId)
      .map(c =>
        (
          c.assessedAt, c.assessmentStatus, c.assessmentError,
          c.safetyScore, c.qualityScore, c.categoryConfidence
        ))
      .update((
        Some(now), "failed", Some(error),
        Some(0.5), Some(0.5), Some(0.0) // Neutral scores
      ))
    db.run(update).map(_ => ())
  }

  private class CreativeMetaTable(tag: Tag) extends Table[CreativeMeta](tag, "creative_meta") {
    def creativeId = column[String]("creative_id", O.PrimaryKey)
    def advertiserId = column[String]("advertiser_id")
    def s3Key = column[String]("s3_key")
    def hash = column[String]("hash")
    def mime = column[String]("mime")
    def width = column[Int]("width")
    def height = column[Int]("height")
    def landingDomain = column[String]("landing_domain")
    // Assessment fields
    def assessedAt = column[Option[Instant]]("assessed_at")
    def assessmentStatus = column[String]("assessment_status", O.Default("pending"))
    def safetyScore = column[Option[Double]]("safety_score")
    def qualityScore = column[Option[Double]]("quality_score")
    def adultContent = column[Boolean]("adult_content", O.Default(false))
    def violence = column[Boolean]("violence", O.Default(false))
    def hateSpeech = column[Boolean]("hate_speech", O.Default(false))
    def detectedCategories = column[String]("detected_categories", O.Default(""))
    def suggestedCategory = column[Option[String]]("suggested_category")
    def extractedText = column[Option[String]]("extracted_text")
    def assessmentModel = column[Option[String]]("assessment_model")
    def assessmentError = column[Option[String]]("assessment_error")
    // Category verification fields
    def categoryVerificationStatus = column[Option[String]]("category_verification_status")
    def categoryMatchScore = column[Option[Double]]("category_match_score")
    def categoryConfidence = column[Option[Double]]("category_confidence")
    def expectedCategories = column[String]("expected_categories", O.Default(""))

    // Index on hash for dedup lookups
    def hashIdx = index("idx_creative_meta_hash", hash)

    // Split into two tuples to avoid 22-field limit
    private val basicFields = (
      advertiserId, creativeId, s3Key, hash, mime, width, height, landingDomain,
      assessedAt, assessmentStatus, safetyScore, qualityScore
    )
    private val contentFields = (
      adultContent, violence, hateSpeech, detectedCategories,
      suggestedCategory, extractedText, assessmentModel, assessmentError
    )
    private val verificationFields = (
      categoryVerificationStatus, categoryMatchScore, categoryConfidence, expectedCategories
    )

    def * = (basicFields, contentFields, verificationFields).<>(
      {
        case ((advId, crId, s3, h, m, w, ht, ld, at, as, ss, qs),
              (ac, v, hs, dc, sc, et, am, ae),
              (cvs, cms, cc, ec)) =>
          CreativeMeta(advId, crId, s3, h, m, w, ht, ld, at, as, ss, qs, ac, v, hs, dc, sc, et, am, ae, cvs, cms, cc,
            ec)
      },
      { (c: CreativeMeta) =>
        Some((
          (c.advertiserId, c.creativeId, c.s3Key, c.hash, c.mime, c.width, c.height, c.landingDomain,
            c.assessedAt, c.assessmentStatus, c.safetyScore, c.qualityScore),
          (c.adultContent, c.violence, c.hateSpeech, c.detectedCategories,
            c.suggestedCategory, c.extractedText, c.assessmentModel, c.assessmentError),
          (c.categoryVerificationStatus, c.categoryMatchScore, c.categoryConfidence, c.expectedCategories)
        ))
      }
    )
  }
}
