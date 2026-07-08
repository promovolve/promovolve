package promovolve.taxonomy

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory
import promovolve.{CategoryId, Confidence, GeminiRateLimiter}
import spray.json.*

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Try

/** IAB Taxonomy classifier using LLM.
  *
  * Supports multiple providers:
  *   - OpenAI (gpt-4o-mini) - default
  *   - Anthropic (claude-3-haiku)
  *   - Google Gemini (gemini-2.0-flash) - cheapest
  *
  * Uses Pekko HTTP for all providers.
  */
class IABTaxonomy(
    provider: IABTaxonomy.Provider,
    rateLimiter: Option[ActorRef[GeminiRateLimiter.Command]] = None
)(using system: ActorSystem[?], ec: ExecutionContext) {

  import IABTaxonomy.*

  private val logger = LoggerFactory.getLogger(getClass)
  private val http = Http(system.classicSystem)

  // Circuit breaker: after 5 consecutive failures, open for 30s before retrying
  private val breaker = org.apache.pekko.pattern.CircuitBreaker(
    scheduler = system.classicSystem.scheduler,
    maxFailures = 5,
    callTimeout = 15.seconds,
    resetTimeout = 30.seconds
  ).onOpen(logger.warn(s"Circuit breaker OPEN for ${provider.name} — failing fast"))
   .onHalfOpen(logger.info(s"Circuit breaker HALF-OPEN for ${provider.name} — testing"))
   .onClose(logger.info(s"Circuit breaker CLOSED for ${provider.name} — recovered"))

  // Classification is demand-INDEPENDENT: a page is classified against the FULL
  // IAB taxonomy (its intrinsic topic); demand is intersected later at the
  // auction (ancestor-expanded). `fallbackCategories` does NOT influence the
  // classification — it is used ONLY when the LLM call fails (see `.recover`
  // below) to seed a broad low-confidence pool so the auction isn't starved.
  // (Renamed from the old `categoryOverride`, which looked like it controlled
  // classification but only fed this fallback.)
  def analyzeTaxonomy(
      url: String,
      text: String,
      fallbackCategories: Set[String] = Set.empty,
  ): Future[List[Selection]] = {
    val candidates = buildTaxonomyCandidates()
    val prompt = buildPrompt(url, text, candidates)
    val validIds = candidates.keySet

    val (apiUrl, headers, body) = provider match {
      case p: Provider.OpenAI    => buildOpenAIRequest(p, prompt)
      case p: Provider.Anthropic => buildAnthropicRequest(p, prompt)
      case p: Provider.Gemini    => buildGeminiRequest(p, prompt)
    }

    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = apiUrl,
      headers = headers,
      entity = HttpEntity(ContentTypes.`application/json`, body)
    )

    // Acquire a rate limit token before calling Gemini; other providers pass through
    val rateLimitGate: Future[Unit] = (provider, rateLimiter) match {
      case (_: Provider.Gemini, Some(limiter)) => GeminiRateLimiter.acquire(limiter)
      case _ => Future.successful(())
    }

    def callOnce(): Future[scala.util.Either[(Int, String, Option[FiniteDuration]), List[Selection]]] = {
      http
        .singleRequest(request)
        .flatMap { response =>
          // Parse Retry-After up front while we still have the headers.
          // Google sends integer seconds; RFC 7231 also permits HTTP-date
          // but Vertex/AI Studio don't. Integer-only is fine.
          val retryAfter: Option[FiniteDuration] = response.headers
            .find(_.lowercaseName == "retry-after")
            .flatMap(h => h.value.trim.toIntOption)
            .map(_.seconds)
          Unmarshal(response.entity).to[String].map { responseBody =>
            if (response.status.isSuccess()) {
              logger.debug(s"${provider.name} response for $url: $responseBody")
              val selections = provider match {
                case _: Provider.OpenAI    => parseOpenAIResponse(responseBody)
                case _: Provider.Anthropic => parseAnthropicResponse(responseBody)
                case _: Provider.Gemini    => parseGeminiResponse(responseBody)
              }
              val (valid, invalid) = selections.partition(s => validIds.contains(s.id))
              if (invalid.nonEmpty) {
                logger.warn(s"${provider.name} returned category IDs not in candidate set for $url: ${invalid.map(_.id).mkString(", ")} — filtered out")
              }
              logger.info(s"${provider.name} classified $url: ${valid.map(s => s"${s.id}(${s.confidence})").mkString(", ")}")
              Right(valid)
            } else {
              Left((response.status.intValue, responseBody, retryAfter))
            }
          }
        }
    }

    // Retry policy:
    //  * 5xx → short exponential backoff (transient flake, usually a few seconds is enough).
    //  * 429 → long exponential backoff. RESOURCE_EXHAUSTED means the provider-side quota
    //    window is empty; hammering every 500ms just burns more budget without recovering.
    //    5s base with 2^(attempt-1) growth gives 5/10/20/40s windows, matching the typical
    //    quota reset period for Gemini.
    //  * If the server sent a Retry-After header, use that verbatim (with a small jitter)
    //    instead of our exponential — it's authoritative about when the quota frees up.
    def withRetry(attempt: Int, maxAttempts: Int): Future[List[Selection]] = {
      callOnce().flatMap {
        case Right(sel) => Future.successful(sel)
        case Left((status, body, retryAfter)) if (status == 429 || status >= 500) && attempt < maxAttempts =>
          val baseMs = if (status == 429) 5000L else 500L
          val expMs = baseMs * (1L << (attempt - 1)) // 429: 5s,10s,20s,40s | 5xx: 500ms,1s,2s,4s
          val jitterMs = scala.util.Random.nextLong(expMs / 2)
          val fallbackDelayMs = expMs + jitterMs
          val (delayMs, source) = retryAfter match {
            case Some(ra) => (ra.toMillis + scala.util.Random.nextLong(1000L), "server Retry-After")
            case None     => (fallbackDelayMs, "exponential backoff")
          }
          logger.warn(
            s"${provider.name} $status for $url (attempt $attempt/$maxAttempts) — retrying in ${delayMs}ms ($source). Body: $body"
          )
          val p = scala.concurrent.Promise[List[Selection]]()
          system.classicSystem.scheduler.scheduleOnce(
            scala.concurrent.duration.FiniteDuration(delayMs, scala.concurrent.duration.MILLISECONDS)
          )(p.completeWith(withRetry(attempt + 1, maxAttempts)))
          p.future
        case Left((status, body, _)) =>
          logger.error(s"${provider.name} API error $status for $url: $body")
          // Carry the full response body in the exception so the breaker's
          // recover block can surface it — otherwise only "API error: 429"
          // makes it out and the actual provider message (rate-limit detail,
          // quota info, etc.) gets swallowed.
          throw new RuntimeException(s"${provider.name} API error $status: $body")
      }
    }

    rateLimitGate
      .flatMap { _ =>
        breaker.withCircuitBreaker(withRetry(attempt = 1, maxAttempts = 4))
      }
      .recover { case e: Exception =>
        // When the LLM fails (rate limit exhausted, breaker open, etc.)
        // we don't want the auction to stall on an unclassified page —
        // downstream treats empty category scores as "no demand
        // matches" and the slot stays empty. Fall back to a uniform
        // low-confidence match over the demand categories passed in
        // so the auction sees a broad pool instead of nothing.
        val fallbackConfidence = 0.25
        val fallback = fallbackCategories.toList.map { cid =>
          Selection(cid, fallbackConfidence)
        }
        logger.error(
          s"IABTaxonomy ${provider.name} error for $url: ${e.getMessage} " +
            s"— falling back to ${fallback.size} demand categories @ conf=$fallbackConfidence",
          e
        )
        fallback
      }
  }

  // ========== Request Builders ==========

  private def buildOpenAIRequest(p: Provider.OpenAI, prompt: String): (String, List[HttpHeader], String) = {
    val jsonSchema = JsObject(
      "type" -> JsString("json_schema"),
      "json_schema" -> JsObject(
        "name" -> JsString("taxonomy_classification"),
        "strict" -> JsTrue,
        "schema" -> JsObject(
          "type" -> JsString("object"),
          "properties" -> JsObject(
            "selected_taxonomy_ids" -> JsObject(
              "type" -> JsString("array"),
              "items" -> JsObject(
                "type" -> JsString("object"),
                "properties" -> JsObject(
                  "id" -> JsObject("type" -> JsString("string")),
                  "confidence" -> JsObject("type" -> JsString("number"))
                ),
                "required" -> JsArray(JsString("id"), JsString("confidence")),
                "additionalProperties" -> JsFalse
              )
            )
          ),
          "required" -> JsArray(JsString("selected_taxonomy_ids")),
          "additionalProperties" -> JsFalse
        )
      )
    )

    val body = JsObject(
      "model" -> JsString(p.model),
      "messages" -> JsArray(JsObject(
        "role" -> JsString("user"),
        "content" -> JsString(prompt)
      )),
      "temperature" -> JsNumber(0.1),
      "max_tokens" -> JsNumber(1024),
      "response_format" -> jsonSchema
    ).compactPrint

    (
      "https://api.openai.com/v1/chat/completions",
      List(RawHeader("Authorization", s"Bearer ${p.apiKey}")),
      body
    )
  }

  private def buildAnthropicRequest(p: Provider.Anthropic, prompt: String): (String, List[HttpHeader], String) = {
    // Anthropic uses tool_use for structured output
    val taxonomyTool = JsObject(
      "name" -> JsString("classify_taxonomy"),
      "description" -> JsString("Output the taxonomy classification results"),
      "input_schema" -> JsObject(
        "type" -> JsString("object"),
        "properties" -> JsObject(
          "selected_taxonomy_ids" -> JsObject(
            "type" -> JsString("array"),
            "items" -> JsObject(
              "type" -> JsString("object"),
              "properties" -> JsObject(
                "id" -> JsObject("type" -> JsString("string"), "description" -> JsString("IAB category ID")),
                "confidence" -> JsObject("type" -> JsString("number"), "description" -> JsString("Confidence score 0.0-1.0"))
              ),
              "required" -> JsArray(JsString("id"), JsString("confidence"))
            )
          )
        ),
        "required" -> JsArray(JsString("selected_taxonomy_ids"))
      )
    )

    val body = JsObject(
      "model" -> JsString(p.model),
      "max_tokens" -> JsNumber(1024),
      "tools" -> JsArray(taxonomyTool),
      "tool_choice" -> JsObject("type" -> JsString("tool"), "name" -> JsString("classify_taxonomy")),
      "messages" -> JsArray(JsObject(
        "role" -> JsString("user"),
        "content" -> JsString(prompt)
      ))
    ).compactPrint

    (
      "https://api.anthropic.com/v1/messages",
      List(
        RawHeader("x-api-key", p.apiKey),
        RawHeader("anthropic-version", "2023-06-01"),
        RawHeader("content-type", "application/json")
      ),
      body
    )
  }

  private def buildGeminiRequest(p: Provider.Gemini, prompt: String): (String, List[HttpHeader], String) = {
    val body = JsObject(
      "contents" -> JsArray(
        JsObject("parts" -> JsArray(JsObject("text" -> JsString(prompt))))
      ),
      "generationConfig" -> JsObject(
        "temperature" -> JsNumber(0.1),
        // 2.5 Flash includes "thinking" tokens in the output budget.
        // Classification doesn't benefit from chain-of-thought (the
        // answer is a short list of IDs) so we disable thinking via
        // thinkingBudget=0. The budget bump from 1024 to 4096 is
        // belt-and-suspenders for longer category lists; without
        // disabling thinking, 1024 was getting exhausted by reasoning
        // tokens and the JSON came back truncated mid-field.
        "maxOutputTokens" -> JsNumber(4096),
        "responseMimeType" -> JsString("application/json"),
        "thinkingConfig" -> JsObject(
          "thinkingBudget" -> JsNumber(0)
        )
      )
    ).compactPrint

    (
      s"https://generativelanguage.googleapis.com/v1beta/models/${p.model}:generateContent?key=${p.apiKey}",
      List.empty,
      body
    )
  }

  // ========== Response Parsers ==========

  private def parseOpenAIResponse(body: String): List[Selection] =
    Try {
      import DefaultJsonProtocol.*
      val json = body.parseJson.asJsObject
      val content = json.fields("choices")
        .convertTo[JsArray].elements.head.asJsObject
        .fields("message").asJsObject
        .fields("content")
        .convertTo[String]
      parseSelections(content)
    }.recover { case e: Exception =>
      logger.error(s"Failed to parse OpenAI response: ${e.getMessage}")
      List.empty
    }.get

  private def parseAnthropicResponse(body: String): List[Selection] =
    Try {
      import DefaultJsonProtocol.*
      val json = body.parseJson.asJsObject
      val contentArray = json.fields("content").convertTo[JsArray]

      // Find the tool_use block (when using tool_choice)
      val toolUseBlock = contentArray.elements.find { elem =>
        elem.asJsObject.fields.get("type").contains(JsString("tool_use"))
      }

      toolUseBlock match {
        case Some(block) =>
          // Tool use response: input contains the structured data directly
          val input = block.asJsObject.fields("input").asJsObject
          input.fields.get("selected_taxonomy_ids") match {
            case Some(JsArray(items)) => items.flatMap(parseSelection).toList
            case _ => List.empty
          }
        case None =>
          // Fallback: try to parse as text response
          val textBlock = contentArray.elements.find { elem =>
            elem.asJsObject.fields.get("type").contains(JsString("text"))
          }
          textBlock.map { block =>
            val text = block.asJsObject.fields("text").convertTo[String]
            parseSelections(text)
          }.getOrElse(List.empty)
      }
    }.recover { case e: Exception =>
      logger.error(s"Failed to parse Anthropic response: ${e.getMessage}")
      List.empty
    }.get

  private def parseGeminiResponse(body: String): List[Selection] =
    Try {
      import DefaultJsonProtocol.*
      val json = body.parseJson.asJsObject
      val text = json.fields("candidates")
        .convertTo[JsArray].elements.head.asJsObject
        .fields("content").asJsObject
        .fields("parts")
        .convertTo[JsArray].elements.head.asJsObject
        .fields("text")
        .convertTo[String]
      parseSelections(text)
    }.recover { case e: Exception =>
      logger.error(s"Failed to parse Gemini response: ${e.getMessage}")
      List.empty
    }.get

  // ========== Common Parsing ==========

  private def parseSelections(content: String): List[Selection] =
    Try {
      // Accept both shapes: the documented wrapped object
      // `{"selected_taxonomy_ids": [...]}` and Gemini's occasional
      // bare-array shortcut `[...]`. Flash-class models sometimes
      // drop the wrapper when the schema isn't enforced at the
      // provider level; the bare array still has everything we
      // need, so there's no reason to throw away the classification.
      content.parseJson match {
        case obj: JsObject =>
          obj.fields
            .get("selected_taxonomy_ids")
            .collect { case JsArray(items) => items.flatMap(parseSelection).toList }
            .getOrElse(List.empty)
        case JsArray(items) =>
          items.flatMap(parseSelection).toList
        case _ =>
          List.empty
      }
    }.recover { case e: Exception =>
      logger.warn(s"Failed to parse JSON selections: ${e.getMessage}. Content: ${content.take(200)}")
      List.empty
    }.get

  private def parseSelection(item: JsValue): Option[Selection] =
    Try {
      val obj = item.asJsObject.fields
      for {
        id <- obj.get("id").collect { case JsString(s) if s.nonEmpty => s }
        confidence = obj.get("confidence").collect { case JsNumber(n) => n.toDouble }.getOrElse(0.5)
      } yield Selection(id, confidence)
    }.toOption.flatten

  // ========== Helpers ==========

  /** Build the LLM's candidate set.
    *
    * Previously this was demand-constrained: descendants of the union of all
    * advertisers' declared categories. That was unsafe — when demand is
    * narrow (e.g. a single Pilates advertiser → demand = {225 Fitness}), the
    * LLM was forced to pick from {225 Fitness, 226 Participant Sports, 227
    * Running} for every page, including unrelated baseball / soccer / keiba
    * pages. The closest-in-the-set won, and the Pilates campaign ended up
    * bidding on every ad unit on the site.
    *
    * The honest classification path is: show the LLM the full IAB 3.0
    * topical taxonomy, let it return whatever genuinely matches (including
    * empty), and intersect with demand downstream at the auction fan-out
    * (which already does ancestor expansion). Always the full taxonomy.
    */
  private def buildTaxonomyCandidates(): Map[String, String] =
    TieredCategory.getAll
      .map(cat => cat.id -> cat.toString)
      .toMap

  private def buildPrompt(url: String, text: String, candidates: Map[String, String]): String = {
    val categoryList = candidates.map { case (id, desc) => s"- $id: $desc" }.mkString("\n")
    val truncatedText = if (text.length > MaxContentLength) text.take(MaxContentLength) + "..." else text

    s"""Below is a web page. Which IAB Content Taxonomy 3.0 categories is this page genuinely about?
Pick the most specific applicable nodes (a leaf like "Baseball (545)" is better than its tier-1 parent "Sports (483)" when the page is specifically about baseball). Return at most 3 and only those with high confidence — if nothing genuinely fits, return an empty array. Do not stretch matches.

### Categories (id: name -> path):
$categoryList

### Page ($url):
$truncatedText

### Respond with a single JSON object in this exact shape:
{"selected_taxonomy_ids": [{"id": "545", "confidence": 0.92}]}

If nothing matches:
{"selected_taxonomy_ids": []}"""
  }

  def close(): Unit = () // No resources to close with Pekko HTTP
}

object IABTaxonomy extends DefaultJsonProtocol {

  private val MaxContentLength = 8000

  /** LLM Provider configuration */
  sealed trait Provider {
    def name: String
    def model: String
  }

  object Provider {
    case class OpenAI(apiKey: String, model: String = "gpt-4o-mini") extends Provider {
      val name = "OpenAI"
    }

    case class Anthropic(apiKey: String, model: String = "claude-3-haiku-20240307") extends Provider {
      val name = "Anthropic"
    }

    case class Gemini(apiKey: String, model: String = "gemini-2.0-flash") extends Provider {
      val name = "Gemini"
    }

    /** Create provider from environment variables (cheapest first). */
    def fromEnv(): Provider =
      sys.env.get("GEMINI_API_KEY").map(Gemini(_))
        .orElse(sys.env.get("OPENAI_API_KEY").map(OpenAI(_)))
        .orElse(sys.env.get("ANTHROPIC_API_KEY").map(Anthropic(_)))
        .getOrElse(throw new IllegalStateException(
          "None of GEMINI_API_KEY, OPENAI_API_KEY, or ANTHROPIC_API_KEY environment variables are set"
        ))
  }

  /** Classification result with category ID and confidence score */
  case class Selection(id: String, confidence: Double) {
    def categoryScores: Map[CategoryId, Confidence] =
      Map(CategoryId(id) -> Confidence(confidence))
  }

  object Selection {
    given jsonFormat: RootJsonFormat[Selection] = jsonFormat2(Selection.apply)
  }

  extension (selections: List[Selection]) {
    def categoryScores: Map[CategoryId, Confidence] =
      selections.map(s => CategoryId(s.id) -> Confidence(s.confidence)).toMap
  }

  // Backwards compatibility
  def apply(apiKey: String)(using ActorSystem[?], ExecutionContext): IABTaxonomy =
    new IABTaxonomy(Provider.OpenAI(apiKey))
}
