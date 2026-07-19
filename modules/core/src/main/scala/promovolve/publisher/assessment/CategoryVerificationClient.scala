package promovolve.publisher.assessment

import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem }
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory
import promovolve.GeminiRateLimiter
import promovolve.taxonomy.{ AdProductTaxonomy, TieredCategory }
import spray.json.*

import java.util.Base64
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.*

/** Result of category verification and safety assessment */
final case class CategoryVerificationResult(
    matchConfidence: Double,
    reason: String,
    // Safety flags
    adultContent: Boolean = false,
    violence: Boolean = false,
    hateSpeech: Boolean = false,
    // Optional safety score (0.0 = unsafe, 1.0 = safe)
    safetyScore: Option[Double] = None,
    // Content categories this ad should target (IAB Content Taxonomy 3.0 IDs)
    suggestedContentCategories: List[String] = Nil
) {

  /** True if any safety flag is set */
  def isSafetyBlocked: Boolean = adultContent || violence || hateSpeech
}

/**
 * Gemini API client for category verification.
 *
 * Verifies if an image matches a declared IAB category.
 */
final class CategoryVerificationClient(
    apiKey: String,
    model: String = "gemini-2.5-flash",
    maxRetries: Int = 5,
    baseDelay: FiniteDuration = 1.second,
    rateLimiter: Option[ActorRef[GeminiRateLimiter.Command]] = None
)(using system: ActorSystem[?], ec: ExecutionContext) {

  private val log = LoggerFactory.getLogger(getClass)
  private val http = Http(system.toClassic)
  private val scheduler = system.toClassic.scheduler

  private def apiUrl: String =
    s"https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

  def verify(
      imageBytes: Array[Byte],
      mimeType: String,
      declaredCategory: Option[String]
  ): Future[CategoryVerificationResult] =
    verifyWithRetry(imageBytes, mimeType, declaredCategory, attempt = 0)

  /**
   * Text-based category verification — passes raw LP text instead of a
   * rendered banner image. Preferred path: LP text is denser signal than
   * a screenshot and avoids base64-encoding a full PNG into the request.
   */
  def verifyText(
      adText: String,
      declaredCategory: Option[String]
  ): Future[CategoryVerificationResult] =
    verifyTextWithRetry(adText, declaredCategory, attempt = 0)

  private def verifyTextWithRetry(
      adText: String,
      declaredCategory: Option[String],
      attempt: Int
  ): Future[CategoryVerificationResult] = {
    val requestBody = buildTextRequestBody(adText, declaredCategory)
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = apiUrl,
      entity = HttpEntity(ContentTypes.`application/json`, requestBody)
    )

    val gate: Future[Unit] = rateLimiter match {
      case Some(limiter) => GeminiRateLimiter.acquire(limiter)
      case None          => Future.successful(())
    }

    gate.flatMap { _ => http.singleRequest(request) }.flatMap { response =>
      response.status match {
        case StatusCodes.OK =>
          Unmarshal(response.entity).to[String].map(parseResponse(_, declaredCategory))
        case status if isRetryable(status) && attempt < maxRetries =>
          response.entity.discardBytes()
          val delay = calculateBackoff(attempt)
          log.warn("Gemini verifyText returned {}, retrying in {} (attempt {}/{})",
            status.intValue(), delay, attempt + 1, maxRetries)
          org.apache.pekko.pattern.after(delay, scheduler)(
            verifyTextWithRetry(adText, declaredCategory, attempt + 1)
          )
        case status =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            log.error("Gemini verifyText error {}: {}", status.intValue(), body)
            Future.failed(new RuntimeException(s"Gemini verifyText error $status: $body"))
          }
      }
    }
  }

  private def verifyWithRetry(
      imageBytes: Array[Byte],
      mimeType: String,
      declaredCategory: Option[String],
      attempt: Int
  ): Future[CategoryVerificationResult] = {
    val base64Image = Base64.getEncoder.encodeToString(imageBytes)
    val requestBody = buildRequestBody(base64Image, mimeType, declaredCategory)

    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = apiUrl,
      entity = HttpEntity(ContentTypes.`application/json`, requestBody)
    )

    // Acquire rate limit token before calling Gemini
    val gate: Future[Unit] = rateLimiter match {
      case Some(limiter) => GeminiRateLimiter.acquire(limiter)
      case None          => Future.successful(())
    }

    gate.flatMap { _ => http.singleRequest(request) }.flatMap { response =>
      response.status match {
        case StatusCodes.OK =>
          Unmarshal(response.entity).to[String].map(parseResponse(_, declaredCategory))

        case status if isRetryable(status) && attempt < maxRetries =>
          response.entity.discardBytes()
          val delay = calculateBackoff(attempt)
          log.warn("Gemini API returned {}, retrying in {} (attempt {}/{})",
            status.intValue(), delay, attempt + 1, maxRetries)
          org.apache.pekko.pattern.after(delay, scheduler)(
            verifyWithRetry(imageBytes, mimeType, declaredCategory, attempt + 1)
          )

        case status =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            log.error("Gemini API error {}: {}", status.intValue(), body)
            Future.failed(new RuntimeException(s"Gemini API error $status: $body"))
          }
      }
    }
  }

  private def isRetryable(status: StatusCode): Boolean =
    promovolve.llm.HttpRetryPolicy.isRetryable(status)

  /** Attempt to fix truncated JSON by closing open structures */
  private def fixTruncatedJson(json: String): String = {
    var result = json.trim

    // Count open brackets/braces
    var openBraces = 0
    var openBrackets = 0
    var inString = false
    var escape = false

    for (c <- result) {
      if (escape) {
        escape = false
      } else if (c == '\\' && inString) {
        escape = true
      } else if (c == '"' && !escape) {
        inString = !inString
      } else if (!inString) {
        c match {
          case '{' => openBraces += 1
          case '}' => openBraces -= 1
          case '[' => openBrackets += 1
          case ']' => openBrackets -= 1
          case _   =>
        }
      }
    }

    // If we're in the middle of a string, close it
    if (inString) {
      result = result + "\""
    }

    // If we're in the middle of an array element, try to close gracefully
    if (result.endsWith("\"IAB") || result.matches(".*\"IAB\\d*$")) {
      result = result.dropRight(result.length - result.lastIndexOf('"')) + "\"]"
      openBrackets -= 1
    }

    // Close any open brackets
    result = result + ("]" * math.max(0, openBrackets))

    // Add missing closing braces
    result = result + ("}" * math.max(0, openBraces))

    result
  }

  private def calculateBackoff(attempt: Int): FiniteDuration =
    promovolve.llm.HttpRetryPolicy.backoff(attempt, baseDelay)

  private def buildTextRequestBody(adText: String, declaredCategory: Option[String]): String = {
    // Show the full IAB 3.0 taxonomy (~700 nodes including descendants)
    // so the LLM can pick at any tier-level — tier-1 ("Sports") is fine
    // for broad ads, but a Pilates studio should get tier-2/3 ("Fitness
    // Activities", "Gyms and Health Clubs") so its campaign doesn't end
    // up bidding on every Baseball page via ancestor expansion.
    // Path included for hierarchy context.
    val contentCategoryList = TieredCategory.getAll
      .sortBy(c => (c.parent.isDefined, c.id.toIntOption.getOrElse(Int.MaxValue), c.id))
      .map { c =>
        val path = (TieredCategory.getAncestors(c.id).map(_.name) :+ c.name).mkString(" > ")
        s"  - ${c.id}: $path"
      }
      .mkString("\n")

    // Cap at 30k chars to keep the prompt reasonable — LPs with more text
    // tail off into boilerplate anyway (footers, site-wide nav links).
    val trimmed = if (adText.length > 30000) adText.take(30000) + "..." else adText

    val prompt = declaredCategory match {
      case Some(catId) =>
        val categoryInfo = AdProductTaxonomy.get(catId) match {
          case Some(cat) =>
            (List(cat.tier1) ++ cat.tier2.toList ++ cat.tier3.toList).mkString(" > ")
          case None => catId
        }
        s"""Analyze this advertisement's landing-page text for category match, targeting, and safety.

Landing page text:
<<<
$trimmed
>>>

This campaign's ad product category is: "$categoryInfo"

1. CATEGORY MATCH: Does this landing page match the campaign category "$categoryInfo"?
   Score match_confidence 0.0 to 1.0:
   - 1.0 = exact match (LP clearly promotes this category)
   - 0.7-0.9 = related/close match
   - 0.3-0.6 = loosely related
   - 0.0-0.2 = no match

2. CONTENT TARGETING: Given that this campaign sells "$categoryInfo" and based on the specific product described in the LP, which content page categories would reach users interested in this product?
   Available categories:
$contentCategoryList
   Select ONLY categories where a reader of that content would plausibly want this specific product (not "$categoryInfo" in general).
   Be selective — prefer fewer, highly relevant categories over many loosely related ones.
   You may also include subcategory IDs if more specific targeting is appropriate.

3. SAFETY CHECK: Flag any problematic content:
   - adult_content: nudity, sexually suggestive, pornographic
   - violence: graphic violence, weapons promoting harm, gore
   - hate_speech: discrimination, slurs, extremist symbols
   - safety_score: 0.0 (very unsafe) to 1.0 (completely safe)

JSON: {"match_confidence": 0.0-1.0, "reason": "brief explanation", "suggested_content_categories": ["category_id", ...], "adult_content": false, "violence": false, "hate_speech": false, "safety_score": 0.0-1.0}"""

      case None =>
        s"""Analyze this advertisement's landing-page text for targeting and safety.

Landing page text:
<<<
$trimmed
>>>

1. What is this LP promoting?

2. CONTENT TARGETING: Based on the LP content, which content categories should this ad target?
   Available categories:
$contentCategoryList
   Select ONLY categories where a reader would plausibly be interested in this specific product.
   Be selective — prefer fewer, highly relevant categories over many loosely related ones.

3. SAFETY CHECK: Flag any problematic content:
   - adult_content, violence, hate_speech (booleans)
   - safety_score: 0.0 (very unsafe) to 1.0 (completely safe)

JSON: {"match_confidence": 0.5, "reason": "description of product", "suggested_content_categories": ["category_id", ...], "adult_content": false, "violence": false, "hate_speech": false, "safety_score": 0.0-1.0}"""
    }

    val safetySettings = JsArray(
      JsObject("category" -> JsString("HARM_CATEGORY_HARASSMENT"), "threshold" -> JsString("BLOCK_NONE")),
      JsObject("category" -> JsString("HARM_CATEGORY_HATE_SPEECH"), "threshold" -> JsString("BLOCK_NONE")),
      JsObject("category" -> JsString("HARM_CATEGORY_SEXUALLY_EXPLICIT"), "threshold" -> JsString("BLOCK_NONE")),
      JsObject("category" -> JsString("HARM_CATEGORY_DANGEROUS_CONTENT"), "threshold" -> JsString("BLOCK_NONE"))
    )

    JsObject(
      "contents" -> JsArray(
        JsObject("parts" -> JsArray(JsObject("text" -> JsString(prompt))))
      ),
      "safetySettings" -> safetySettings,
      "generationConfig" -> JsObject(
        "temperature" -> JsNumber(0.1),
        "maxOutputTokens" -> JsNumber(2048),
        "responseMimeType" -> JsString("application/json")
      )
    ).compactPrint
  }

  private def buildRequestBody(base64Image: String, mimeType: String, declaredCategory: Option[String]): String = {
    // Full IAB 3.0 taxonomy with hierarchical path so the LLM can pick at
    // any tier-level. See buildTextRequestBody for the same approach + the
    // reasoning (tier-1-only caused over-broad targeting after ancestor
    // expansion at auction time).
    val contentCategoryList = TieredCategory.getAll
      .sortBy(c => (c.parent.isDefined, c.id.toIntOption.getOrElse(Int.MaxValue), c.id))
      .map { c =>
        val path = (TieredCategory.getAncestors(c.id).map(_.name) :+ c.name).mkString(" > ")
        s"  - ${c.id}: $path"
      }
      .mkString("\n")

    val prompt = declaredCategory match {
      case Some(catId) =>
        // Look up category name from Ad Product Taxonomy (not Content Taxonomy!)
        val categoryInfo = AdProductTaxonomy.get(catId) match {
          case Some(cat) =>
            // Build full path from tier hierarchy
            (List(cat.tier1) ++ cat.tier2.toList ++ cat.tier3.toList).mkString(" > ")
          case None =>
            catId // Fallback to just ID if not found in taxonomy
        }

        s"""Analyze this advertisement image for category match, targeting, and safety.

This campaign's ad product category is: "$categoryInfo"

1. CATEGORY MATCH: Does this image match the campaign category "$categoryInfo"?
   Score match_confidence 0.0 to 1.0:
   - 1.0 = exact match (ad clearly promotes this category)
   - 0.7-0.9 = related/close match
   - 0.3-0.6 = loosely related
   - 0.0-0.2 = no match

2. CONTENT TARGETING: Given that this campaign sells "$categoryInfo" and based on the specific product shown in the image, which content page categories would reach users interested in this product?
   Available categories:
$contentCategoryList
   Select ONLY categories where a reader of that content would plausibly want this specific product (not "$categoryInfo" in general).
   Be selective — prefer fewer, highly relevant categories over many loosely related ones.
   You may also include subcategory IDs if more specific targeting is appropriate.

3. SAFETY CHECK: Flag any problematic content:
   - adult_content: nudity, sexually suggestive, pornographic
   - violence: graphic violence, weapons promoting harm, gore
   - hate_speech: discrimination, slurs, extremist symbols
   - safety_score: 0.0 (very unsafe) to 1.0 (completely safe)

JSON: {"match_confidence": 0.0-1.0, "reason": "brief explanation", "suggested_content_categories": ["category_id", ...], "adult_content": false, "violence": false, "hate_speech": false, "safety_score": 0.0-1.0}"""

      case None =>
        s"""Analyze this advertisement image for targeting and safety.

1. What is this ad promoting?

2. CONTENT TARGETING: Based on what you see in the image, which content categories should this ad target?
   Available categories:
$contentCategoryList
   Select ONLY categories where a reader would plausibly be interested in this specific product.
   Be selective — prefer fewer, highly relevant categories over many loosely related ones.
   You may also include subcategory IDs if more specific targeting is appropriate.

3. SAFETY CHECK: Flag any problematic content:
   - adult_content: nudity, sexually suggestive, pornographic
   - violence: graphic violence, weapons promoting harm, gore
   - hate_speech: discrimination, slurs, extremist symbols
   - safety_score: 0.0 (very unsafe) to 1.0 (completely safe)

JSON: {"match_confidence": 0.5, "reason": "description of ad", "suggested_content_categories": ["category_id", ...], "adult_content": false, "violence": false, "hate_speech": false, "safety_score": 0.0-1.0}"""
    }

    val safetySettings = JsArray(
      JsObject("category" -> JsString("HARM_CATEGORY_HARASSMENT"), "threshold" -> JsString("BLOCK_NONE")),
      JsObject("category" -> JsString("HARM_CATEGORY_HATE_SPEECH"), "threshold" -> JsString("BLOCK_NONE")),
      JsObject("category" -> JsString("HARM_CATEGORY_SEXUALLY_EXPLICIT"), "threshold" -> JsString("BLOCK_NONE")),
      JsObject("category" -> JsString("HARM_CATEGORY_DANGEROUS_CONTENT"), "threshold" -> JsString("BLOCK_NONE"))
    )

    JsObject(
      "contents" -> JsArray(
        JsObject(
          "parts" -> JsArray(
            JsObject(
              "inline_data" -> JsObject(
                "mime_type" -> JsString(mimeType),
                "data" -> JsString(base64Image)
              )
            ),
            JsObject("text" -> JsString(prompt))
          )
        )
      ),
      "safetySettings" -> safetySettings,
      "generationConfig" -> JsObject(
        "temperature" -> JsNumber(0.1),
        "maxOutputTokens" -> JsNumber(2048),
        "responseMimeType" -> JsString("application/json")
      )
    ).compactPrint
  }

  private def parseResponse(body: String, declaredCategory: Option[String]): CategoryVerificationResult = {
    import DefaultJsonProtocol.*
    try {
      val json = body.parseJson.asJsObject

      // Check for blocked content or other issues
      val candidates = json.fields.get("candidates") match {
        case Some(JsArray(elements)) if elements.nonEmpty => elements
        case _                                            =>
          log.error("No candidates in Gemini response: {}", body.take(500))
          throw new RuntimeException("No candidates in response")
      }

      val candidate = candidates.head.asJsObject

      // Check finish reason
      val safetyBlocked = candidate.fields.get("finishReason") match {
        case Some(JsString("SAFETY")) =>
          log.warn("Gemini blocked response due to safety filters")
          true
        case Some(JsString("MAX_TOKENS")) =>
          log.warn("Gemini response truncated due to max tokens")
          false
        case _ => // OK
          false
      }

      if (safetyBlocked) {
        CategoryVerificationResult(0.5, "blocked by safety filter")
      } else {

        val content = candidate.fields.get("content") match {
          case Some(c) => c.asJsObject
          case None    =>
            log.error("No content in candidate: {}", candidate.prettyPrint.take(500))
            throw new RuntimeException("No content in candidate")
        }

        val parts = content.fields("parts").convertTo[JsArray].elements
        val text = parts.head.asJsObject.fields("text").convertTo[String]

        log.info("Gemini raw response text: {}", text)

        // Clean up the response - remove markdown code blocks if present
        val cleanedText = text.trim
          .stripPrefix("```json")
          .stripPrefix("```")
          .stripSuffix("```")
          .trim

        // Try to parse, with fallback for truncated responses
        val input = try {
          cleanedText.parseJson.asJsObject
        } catch {
          case _: Exception =>
            // Try to fix truncated JSON by closing open structures
            val fixed = fixTruncatedJson(cleanedText)
            log.warn("Attempting to fix truncated JSON: {}", fixed)
            fixed.parseJson.asJsObject
        }

        def getField(key: String): Option[JsValue] = {
          val camelCase = key.split("_").zipWithIndex.map { case (s, i) =>
            if (i == 0) s else s.capitalize
          }.mkString
          input.fields.get(key).orElse(input.fields.get(camelCase))
        }

        val matchConfidence = getField("match_confidence")
          .collect { case JsNumber(n) => n.toDouble }
          .getOrElse(0.5)

        val reason = getField("reason")
          .collect { case JsString(s) => s }
          .getOrElse("no reason provided")

        val adultContent = getField("adult_content")
          .collect { case JsBoolean(b) => b }
          .getOrElse(false)

        val violence = getField("violence")
          .collect { case JsBoolean(b) => b }
          .getOrElse(false)

        val hateSpeech = getField("hate_speech")
          .collect { case JsBoolean(b) => b }
          .getOrElse(false)

        val safetyScore = getField("safety_score")
          .collect { case JsNumber(n) => n.toDouble }

        val suggestedContentCategories = getField("suggested_content_categories")
          .collect { case JsArray(elements) =>
            elements.collect { case JsString(s) if s.nonEmpty => s }.toList
          }
          .getOrElse(Nil)

        if (adultContent || violence || hateSpeech) {
          log.warn("Safety flags detected: adult={}, violence={}, hate={}, score={}",
            adultContent, violence, hateSpeech, safetyScore)
        }

        if (suggestedContentCategories.nonEmpty) {
          log.info("Suggested content categories: {}", suggestedContentCategories.mkString(", "))
        }

        CategoryVerificationResult(
          matchConfidence = matchConfidence,
          reason = reason,
          adultContent = adultContent,
          violence = violence,
          hateSpeech = hateSpeech,
          safetyScore = safetyScore,
          suggestedContentCategories = suggestedContentCategories
        )
      }
    } catch {
      case e: Exception =>
        log.error("Failed to parse Gemini response: {}. Raw body: {}", e.getMessage, body.take(1000))
        throw new RuntimeException(s"Failed to parse response: ${e.getMessage}")
    }
  }
}
