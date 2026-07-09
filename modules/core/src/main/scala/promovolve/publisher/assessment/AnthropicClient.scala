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
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.*
import scala.util.{ Random, Try }

/**
 * Anthropic Claude API client for creative assessment.
 *
 * Features:
 * - Uses tool_use with JSON schema for reliable structured output
 * - Retry with exponential backoff for transient errors (429, 5xx)
 * - Configurable max retries and base delay
 */
final class AnthropicClient(
    apiKey: String,
    model: String = "claude-sonnet-4-20250514",
    maxRetries: Int = 5,
    baseDelay: FiniteDuration = 1.second
)(using system: ActorSystem[?], ec: ExecutionContext) extends LLMClient {

  private val log = LoggerFactory.getLogger(getClass)
  private val http = Http(system.toClassic)
  private val apiUrl = "https://api.anthropic.com/v1/messages"
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
        RawHeader("x-api-key", apiKey),
        RawHeader("anthropic-version", "2023-06-01")
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
            "Anthropic API returned {} for creative {}, retrying in {} (attempt {}/{})",
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
            "Anthropic API returned {} for creative {} after {} retries, giving up",
            status.intValue(),
            context.creativeId,
            maxRetries
          )
          Future.failed(AnthropicApiError(status, s"Max retries ($maxRetries) exceeded", retryable = false))

        case status =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            log.error("Anthropic API error {} for creative {}: {}", status.intValue(), context.creativeId, body)
            Future.failed(AnthropicApiError(status, body, retryable = false))
          }
      }
    }.recoverWith {
      case e: AnthropicApiError      => Future.failed(e)
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

  /** Build request body with tool_use for structured output */
  private def buildRequestBody(base64Image: String, mimeType: String, context: AssessmentContext): String = {
    // Define the assessment tool with JSON schema
    val assessmentTool = JsObject(
      "name" -> JsString("assess_creative"),
      "description" ->
      JsString("Output the creative assessment results with safety scores, content flags, and detected categories"),
      "input_schema" -> JsObject(
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
            "description" ->
            JsString("IAB Content Taxonomy category codes detected in the creative (e.g., IAB2 for Automotive)")
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
        )
      )
    )

    val prompt = buildPrompt(context)

    val body = JsObject(
      "model" -> JsString(model),
      "max_tokens" -> JsNumber(1024),
      "tools" -> JsArray(assessmentTool),
      "tool_choice" -> JsObject("type" -> JsString("tool"), "name" -> JsString("assess_creative")),
      "messages" -> JsArray(JsObject(
        "role" -> JsString("user"),
        "content" -> JsArray(
          JsObject(
            "type" -> JsString("image"),
            "source" -> JsObject(
              "type" -> JsString("base64"),
              "media_type" -> JsString(mimeType),
              "data" -> JsString(base64Image)
            )
          ),
          JsObject(
            "type" -> JsString("text"),
            "text" -> JsString(prompt)
          )
        )
      ))
    )

    body.compactPrint
  }

  private def buildPrompt(context: AssessmentContext): String = {
    val declaredCategoryInfo = context.declaredAdProductCategory match {
      case Some(cat) =>
        s"\n\nThe advertiser declared this ad as category: $cat. Verify if the visual content matches this declaration."
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
Use the assess_creative tool to provide your analysis."""
  }

  /** Parse tool_use response to extract structured assessment */
  private def parseResponse(body: String): AssessmentResult =
    Try {
      import DefaultJsonProtocol.*
      val json = body.parseJson.asJsObject
      val contentArray = json.fields("content").convertTo[JsArray]

      // Find the tool_use block
      val toolUseBlock = contentArray.elements.find { elem =>
        elem.asJsObject.fields.get("type").contains(JsString("tool_use"))
      }.getOrElse(throw new RuntimeException(s"No tool_use block found in response: $body"))

      // Extract the input object which contains our structured data
      val input = toolUseBlock.asJsObject.fields("input").asJsObject
      parseAssessmentFromInput(input)
    }.recover { case e: Exception =>
      log.error(s"Failed to parse Anthropic tool_use response: ${e.getMessage}")
      throw new RuntimeException(s"Failed to parse assessment response: ${e.getMessage}", e)
    }.get

  /** Parse AssessmentResult from tool input object */
  private def parseAssessmentFromInput(input: JsObject): AssessmentResult = {
    def getDouble(key: String, default: Double = 0.5): Double =
      input.fields.get(key).collect { case JsNumber(n) => n.toDouble }.getOrElse(default)

    def getBoolean(key: String): Boolean =
      input.fields.get(key).collect { case JsBoolean(b) => b }.getOrElse(false)

    def getStringOrNull(key: String): Option[String] =
      input.fields.get(key).flatMap {
        case JsString(s) if s.nonEmpty => Some(s)
        case JsNull                    => None
        case _                         => None
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

/** Structured error for Anthropic API failures */
final case class AnthropicApiError(
    status: StatusCode,
    message: String,
    retryable: Boolean
) extends RuntimeException(s"Anthropic API error ${status.intValue()}: $message")
