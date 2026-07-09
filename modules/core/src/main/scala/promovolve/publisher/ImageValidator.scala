package promovolve.publisher

import com.typesafe.config.Config
import scala.jdk.CollectionConverters.*

/**
 * Validates images against IAB LEAN Ad specifications.
 *
 * @see https://www.iab.com/wp-content/uploads/2019/04/IABNewAdPortfolio_LW_FixedSizeSpec.pdf
 */
trait ImageValidator {
  def validate(width: Int, height: Int, fileSize: Int): Either[ImageValidationError, Unit]
  def validateDimensions(width: Int, height: Int): Either[ImageValidationError, Unit]
  def validateFileSize(fileSize: Int): Either[ImageValidationError, Unit]
  def allowedSizes: Set[(Int, Int)]
  def maxFileSize: Int
}

sealed trait ImageValidationError {
  def message: String
}

object ImageValidationError {
  final case class InvalidDimensions(width: Int, height: Int, allowed: Set[(Int, Int)]) extends ImageValidationError {
    def message: String = s"Invalid dimensions: ${width}x${height}. Allowed IAB sizes: ${allowed.toSeq.sorted.map {
        case (w, h) => s"${w}x${h}"
      }.mkString(", ")}"
  }

  final case class FileTooLarge(size: Int, maxSize: Int) extends ImageValidationError {
    def message: String = s"Image file size must not exceed ${maxSize / 1024}KB. Got: ${size / 1024}KB"
  }
}

/**
 * IAB LEAN compliant image validator.
 *
 * Default configuration:
 * - Max file size: 50KB (initial load)
 * - Allowed sizes: IAB Standard Ad Unit sizes
 */
class IABImageValidator(
    val maxFileSize: Int,
    val allowedSizes: Set[(Int, Int)]
) extends ImageValidator {

  override def validate(width: Int, height: Int, fileSize: Int): Either[ImageValidationError, Unit] =
    for {
      _ <- validateDimensions(width, height)
      _ <- validateFileSize(fileSize)
    } yield ()

  override def validateDimensions(width: Int, height: Int): Either[ImageValidationError, Unit] =
    if (allowedSizes.contains((width, height))) Right(())
    else Left(ImageValidationError.InvalidDimensions(width, height, allowedSizes))

  override def validateFileSize(fileSize: Int): Either[ImageValidationError, Unit] =
    if (fileSize <= maxFileSize) Right(())
    else Left(ImageValidationError.FileTooLarge(fileSize, maxFileSize))
}

object IABImageValidator {

  /**
   * IAB Standard Ad Unit sizes
   * @see https://www.iab.com/guidelines/iab-new-ad-portfolio/
   */
  val StandardSizes: Set[(Int, Int)] = Set(
    // Desktop - Core
    (300, 250), // Medium Rectangle
    (728, 90), // Leaderboard
    (160, 600), // Wide Skyscraper
    (300, 600), // Half Page
    (970, 250), // Billboard
    (970, 90), // Large Leaderboard
    // Desktop - Additional
    (300, 1050), // Portrait
    (120, 600), // Skyscraper
    (468, 60), // Full Banner
    (234, 60), // Half Banner
    (336, 280), // Large Rectangle
    (250, 250), // Square
    (200, 200), // Small Square
    (180, 150), // Rectangle
    (125, 125), // Button
    (88, 31), // Micro Bar
    // Mobile
    (320, 50), // Mobile Leaderboard
    (320, 100), // Large Mobile Banner
    (300, 50), // Mobile Banner
    (320, 480), // Mobile Interstitial (portrait)
    (480, 320) // Mobile Interstitial (landscape)
  )

  /** Default max file size: 50KB (IAB LEAN initial load) */
  val DefaultMaxFileSize: Int = 50 * 1024

  /** Default validator with IAB LEAN settings */
  val Default: IABImageValidator = new IABImageValidator(
    maxFileSize = DefaultMaxFileSize,
    allowedSizes = StandardSizes
  )

  /** Create from config */
  def fromConfig(config: Config): IABImageValidator = {
    val c = config.getConfig("promovolve.image-limits")

    val sizes = if (c.hasPath("allowed-sizes")) {
      c.getStringList("allowed-sizes").asScala.map { s =>
        val Array(w, h) = s.split("x")
        (w.toInt, h.toInt)
      }.toSet
    } else {
      StandardSizes
    }

    new IABImageValidator(
      maxFileSize = c.getBytes("max-file-size").toInt,
      allowedSizes = sizes
    )
  }
}
