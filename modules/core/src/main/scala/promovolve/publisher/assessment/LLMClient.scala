package promovolve.publisher.assessment

import promovolve.publisher.AssessmentResult

import scala.concurrent.Future

/** Abstraction over multimodal LLM APIs for creative assessment. */
trait LLMClient {

  /** Assess a creative image and return structured results.
    *
    * @param imageBytes Raw image bytes
    * @param mimeType   MIME type (e.g. "image/png", "image/jpeg")
    * @param context    Additional context about the creative
    * @return Future containing assessment results
    */
  def assessCreative(
      imageBytes: Array[Byte],
      mimeType: String,
      context: AssessmentContext
  ): Future[AssessmentResult]

  /** Model identifier for audit trail */
  def modelId: String
}

/** Context provided to the LLM for better assessment */
final case class AssessmentContext(
    advertiserId: String,
    creativeId: String,
    width: Int,
    height: Int,
    declaredAdProductCategory: Option[String] = None
)