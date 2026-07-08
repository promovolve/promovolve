package promovolve.publisher.assessment

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory
import promovolve.publisher.AssessmentResult
import spray.json.*

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Random, Try}

/** OpenAI API client for creative assessment.
  *
  * Features:
  * - Uses json_schema response_format for reliable structured output
  * - Supports gpt-4o (vision model) for image analysis
  * - Retry with exponential backoff for transient errors (429, 5xx)
  * - Configurable max retries and base delay
  */
final class OpenAIClient(
    apiKey: String,
    model: String = "gpt-4o",
    maxRetries: Int = 5,
    baseDelay: FiniteDuration = 1.second
)(using system: ActorSystem[?], ec: ExecutionContext) extends LLMClient {

  private val log = LoggerFactory.getLogger(getClass)
  private val http = Http(system.toClassic)
  private val apiUrl = "https://api.openai.com/v1/chat/completions"
  private val scheduler = system.toClassic.scheduler

  override def modelId: String = model

  override def assessCreative(
      imageBytes: Array[Byte],
      mimeType: String,
      context: AssessmentContext
  ): Future[AssessmentResult] =
    assessCreativeWithRetry(imageBytes, mimeType, context, attempt = 0)

  private def assessCreativeWithRetry(
      imageBytes: Array[Byte],
      mimeType: String,
      context: AssessmentContext,
      attempt: Int
  ): Future[AssessmentResult] = {

    val base64Image = Base64.getEncoder.encodeToString(imageBytes)
    val requestBody = buildRequestBody(base64Image, mimeType, context)

    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = apiUrl,
      headers = List(
        RawHeader("Authorization", s"Bearer $apiKey")
      ),
      entity = HttpEntity(ContentTypes.`application/json`, requestBody)
    )

    http.singleRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.OK =>
          Unmarshal(response.entity).to[String].map(parseResponse)

        case status if isRetryable(status) && attempt < maxRetries =>
          response.entity.discardBytes()
          val delay = calculateBackoff(attempt)
          log.warn(
            "OpenAI API returned {} for creative {}, retrying in {} (attempt {}/{})",
            status.intValue(),
            context.creativeId,
            delay,
            attempt + 1,
            maxRetries
          )
          org.apache.pekko.pattern.after(delay, scheduler)(
            assessCreativeWithRetry(imageBytes, mimeType, context, attempt + 1)
          )

        case status if isRetryable(status) =>
          response.entity.discardBytes()
          log.error(
            "OpenAI API returned {} for creative {} after {} retries, giving up",
            status.intValue(),
            context.creativeId,
            maxRetries
          )
          Future.failed(OpenAIApiError(status, s"Max retries ($maxRetries) exceeded", retryable = false))

        case status =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            log.error("OpenAI API error {} for creative {}: {}", status.intValue(), context.creativeId, body)
            Future.failed(OpenAIApiError(status, body, retryable = false))
          }
      }
    }.recoverWith {
      case e: OpenAIApiError => Future.failed(e)
      case e if attempt < maxRetries =>
        val delay = calculateBackoff(attempt)
        log.warn(
          "Request failed for creative {}: {}, retrying in {} (attempt {}/{})",
          context.creativeId,
          e.getMessage,
          delay,
          attempt + 1,
          maxRetries
        )
        org.apache.pekko.pattern.after(delay, scheduler)(
          assessCreativeWithRetry(imageBytes, mimeType, context, attempt + 1)
        )
      case e =>
        log.error("Request failed for creative {} after {} retries: {}", context.creativeId, maxRetries, e.getMessage)
        Future.failed(e)
    }
  }

  private def isRetryable(status: StatusCode): Boolean =
    status == StatusCodes.TooManyRequests ||
      status == StatusCodes.ServiceUnavailable ||
      status == StatusCodes.BadGateway ||
      status == StatusCodes.GatewayTimeout ||
      status.intValue() >= 500

  private def calculateBackoff(attempt: Int): FiniteDuration = {
    val exponentialDelay = baseDelay * math.pow(2, attempt).toLong
    val jitter = (Random.nextInt(5) + 1).seconds
    exponentialDelay + jitter
  }

  /** Build request body with json_schema response_format for structured output */
  private def buildRequestBody(base64Image: String, mimeType: String, context: AssessmentContext): String = {
    // OpenAI json_schema response format
    val jsonSchema = JsObject(
      "type" -> JsString("json_schema"),
      "json_schema" -> JsObject(
        "name" -> JsString("creative_assessment"),
        "strict" -> JsTrue,
        "schema" -> JsObject(
          "type" -> JsString("object"),
          "properties" -> JsObject(
            "safety_score" -> JsObject(
              "type" -> JsString("number"),
              "description" -> JsString("Overall content safety score from 0.0 to 1.0, where 1.0 is completely safe")
            ),
            "quality_score" -> JsObject(
              "type" -> JsString("number"),
              "description" -> JsString("Visual quality and professionalism score from 0.0 to 1.0")
            ),
            "adult_content" -> JsObject(
              "type" -> JsString("boolean"),
              "description" -> JsString("Whether the creative contains adult content")
            ),
            "violence" -> JsObject(
              "type" -> JsString("boolean"),
              "description" -> JsString("Whether the creative contains violent content")
            ),
            "hate_speech" -> JsObject(
              "type" -> JsString("boolean"),
              "description" -> JsString("Whether the creative contains hate speech")
            ),
            "detected_categories" -> JsObject(
              "type" -> JsString("array"),
              "items" -> JsObject("type" -> JsString("string")),
              "description" -> JsString("IAB Content Taxonomy category codes detected in the creative (e.g., IAB2 for Automotive)")
            ),
            "category_confidence" -> JsObject(
              "type" -> JsString("number"),
              "description" -> JsString("Confidence in the detected categories from 0.0 to 1.0")
            ),
            "suggested_category" -> JsObject(
              "type" -> JsArray(JsString("string"), JsString("null")),
              "description" -> JsString("Single best IAB category code, or null if uncertain")
            ),
            "extracted_text" -> JsObject(
              "type" -> JsArray(JsString("string"), JsString("null")),
              "description" -> JsString("Any text visible in the image, or null if none")
            )
          ),
          "required" -> JsArray(
            JsString("safety_score"),
            JsString("quality_score"),
            JsString("adult_content"),
            JsString("violence"),
            JsString("hate_speech"),
            JsString("detected_categories"),
            JsString("category_confidence"),
            JsString("suggested_category"),
            JsString("extracted_text")
          ),
          "additionalProperties" -> JsFalse
        )
      )
    )

    val prompt = buildPrompt(context)

    // OpenAI uses image_url with data URI for base64 images
    val imageUrl = s"data:$mimeType;base64,$base64Image"

    val body = JsObject(
      "model" -> JsString(model),
      "messages" -> JsArray(JsObject(
        "role" -> JsString("user"),
        "content" -> JsArray(
          JsObject(
            "type" -> JsString("image_url"),
            "image_url" -> JsObject(
              "url" -> JsString(imageUrl),
              "detail" -> JsString("high")
            )
          ),
          JsObject(
            "type" -> JsString("text"),
            "text" -> JsString(prompt)
          )
        )
      )),
      "temperature" -> JsNumber(0.1),
      "max_tokens" -> JsNumber(1024),
      "response_format" -> jsonSchema
    )

    body.compactPrint
  }

  private def buildPrompt(context: AssessmentContext): String = {
    val declaredCategoryInfo = context.declaredAdProductCategory match {
      case Some(cat) => s"\n\nThe advertiser declared this ad as category: $cat. Verify if the visual content matches this declaration."
      case None => ""
    }

    s"""Analyze this advertisement creative image. The ad is ${context.width}x${context.height} pixels.$declaredCategoryInfo

Use the IAB Content Taxonomy for categorization. Common categories include:
- IAB1: Arts & Entertainment
- IAB2: Automotive
- IAB3: Business
- IAB4: Careers
- IAB5: Education
- IAB6: Family & Parenting
- IAB7: Health & Fitness
- IAB8: Food & Drink (IAB8-5: Cocktails/Beer, IAB8-18: Wine)
- IAB9: Hobbies & Interests (IAB9-7: Gambling, IAB9-9: Card Games)
- IAB10: Home & Garden
- IAB11: Law, Government & Politics
- IAB12: News
- IAB13: Personal Finance (IAB13-12: Lottery)
- IAB14: Society
- IAB15: Science
- IAB16: Pets
- IAB17: Sports (IAB17-18: Horse Racing)
- IAB18: Style & Fashion
- IAB19: Technology & Computing
- IAB20: Travel
- IAB21: Real Estate
- IAB22: Shopping
- IAB23: Religion & Spirituality
- IAB25: Non-Standard Content (IAB25-3: Pornography, IAB25-5: Gambling - sensitive)
- IAB26: Illegal Content

Be strict about safety. Flag anything potentially inappropriate for general audiences.
Provide your analysis in the required JSON format."""
  }

  /** Parse response to extract structured assessment */
  private def parseResponse(body: String): AssessmentResult =
    Try {
      import DefaultJsonProtocol.*
      val json = body.parseJson.asJsObject
      val content = json.fields("choices")
        .convertTo[JsArray].elements.head.asJsObject
        .fields("message").asJsObject
        .fields("content")
        .convertTo[String]

      val assessmentJson = content.parseJson.asJsObject
      parseAssessmentFromJson(assessmentJson)
    }.recover { case e: Exception =>
      log.error(s"Failed to parse OpenAI response: ${e.getMessage}")
      throw new RuntimeException(s"Failed to parse assessment response: ${e.getMessage}", e)
    }.get

  /** Parse AssessmentResult from JSON object */
  private def parseAssessmentFromJson(input: JsObject): AssessmentResult = {
    def getDouble(key: String, default: Double = 0.5): Double =
      input.fields.get(key).collect { case JsNumber(n) => n.toDouble }.getOrElse(default)

    def getBoolean(key: String): Boolean =
      input.fields.get(key).collect { case JsBoolean(b) => b }.getOrElse(false)

    def getStringOrNull(key: String): Option[String] =
      input.fields.get(key).flatMap {
        case JsString(s) if s.nonEmpty => Some(s)
        case JsNull => None
        case _ => None
      }

    def getStringArray(key: String): List[String] =
      input.fields.get(key).collect {
        case JsArray(items) => items.collect { case JsString(s) => s }.toList
      }.getOrElse(Nil)

    AssessmentResult(
      safetyScore = getDouble("safety_score"),
      qualityScore = getDouble("quality_score"),
      adultContent = getBoolean("adult_content"),
      violence = getBoolean("violence"),
      hateSpeech = getBoolean("hate_speech"),
      detectedCategories = getStringArray("detected_categories"),
      categoryConfidence = getDouble("category_confidence"),
      suggestedCategory = getStringOrNull("suggested_category"),
      extractedText = getStringOrNull("extracted_text"),
      model = model
    )
  }
}

/** Structured error for OpenAI API failures */
final case class OpenAIApiError(
    status: StatusCode,
    message: String,
    retryable: Boolean
) extends RuntimeException(s"OpenAI API error ${status.intValue()}: $message")
