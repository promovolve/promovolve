package promovolve.llm

import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem }
import org.apache.pekko.http.scaladsl.model.*
import promovolve.GeminiRateLimiter
import spray.json.*

import scala.concurrent.{ ExecutionContext, Future }

/**
 * Every Gemini call goes through here (#432): the URL and API key, the
 * request body, the model-generation quirks (thinking settings), the shared
 * rate limiter, and [[LlmHttp]]'s retry policy. Prompts and answer parsing
 * stay with each call site — they are tuned and measured per use.
 */
final class GeminiGateway(
    apiKey: String,
    rateLimiter: Option[ActorRef[GeminiRateLimiter.Command]] = None
)(using system: ActorSystem[?], ec: ExecutionContext) {

  import GeminiGateway.*

  private val http = new LlmHttp

  /** Final status and raw response body; the caller parses the candidates and handles non-2xx. */
  def generate(model: String, parts: Seq[Part], config: Config, tag: String): Future[LlmHttp.Reply] =
    http.send(
      HttpRequest(HttpMethods.POST, url(model, apiKey),
        entity = HttpEntity(ContentTypes.`application/json`, requestBody(model, parts, config))),
      tag,
      acquire = () => rateLimiter.fold(Future.unit)(GeminiRateLimiter.acquire)
    )
}

object GeminiGateway {

  enum Part {
    case Text(text: String)
    case InlineData(mimeType: String, base64: String)
  }

  /**
   * @param json
   *   Ask for a JSON response (`responseMimeType`).
   * @param minimalThinking
   *   Turn thinking off as far as the model generation allows. For short
   *   structured answers: 2.5 models count thinking against
   *   `maxOutputTokens` and cut the answer off (#429).
   * @param safetyFiltersOff
   *   Don't let Gemini's own filters block the answer: the caller judges
   *   safety itself (campaign classification returns safety flags).
   */
  final case class Config(
      temperature: Double,
      maxOutputTokens: Int,
      json: Boolean = false,
      minimalThinking: Boolean = false,
      safetyFiltersOff: Boolean = false
  )

  def url(model: String, apiKey: String): String =
    s"https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

  def requestBody(model: String, parts: Seq[Part], config: Config): String = {
    val partsJson = parts.map {
      case Part.Text(text)                 => JsObject("text" -> JsString(text))
      case Part.InlineData(mimeType, data) =>
        JsObject("inline_data" -> JsObject("mime_type" -> JsString(mimeType), "data" -> JsString(data)))
    }
    val generationConfig = JsObject(
      Map[String, JsValue](
        "temperature" -> JsNumber(config.temperature),
        "maxOutputTokens" -> JsNumber(config.maxOutputTokens)
      )
      ++ Option.when(config.json)("responseMimeType" -> JsString("application/json"))
      ++ Option.when(config.minimalThinking)(thinkingConfig(model)).flatten
    )
    JsObject(
      Map[String, JsValue](
        "contents" -> JsArray(JsObject("parts" -> JsArray(partsJson.toVector))),
        "generationConfig" -> generationConfig
      ) ++ Option.when(config.safetyFiltersOff)("safetySettings" -> SafetyFiltersOff)
    ).compactPrint
  }

  /**
   * 2.5-family models include "thinking" tokens in the output budget;
   * classification doesn't benefit from chain-of-thought (the answer is
   * a short list of IDs), so thinking is disabled via thinkingBudget=0.
   * The 3.5 generation RETIRED that field — sending it is a hard 400
   * ("Request contains an invalid argument", found 2026-08-27 probing
   * gemini-3.5-flash-lite) — and replaced it with thinkingLevel, which
   * the 2.5 family in turn does not accept. So the config is chosen by
   * model generation, and an unknown future generation gets NEITHER:
   * a model's own default thinking beats a guaranteed 400.
   */
  def thinkingConfig(model: String): Option[(String, JsValue)] =
    if (model.startsWith("gemini-2.5")) Some("thinkingConfig" -> JsObject("thinkingBudget" -> JsNumber(0)))
    else if (model.startsWith("gemini-3.5"))
      Some("thinkingConfig" -> JsObject("thinkingLevel" -> JsString("minimal")))
    else None

  private val SafetyFiltersOff = JsArray(
    Vector(
      "HARM_CATEGORY_HARASSMENT",
      "HARM_CATEGORY_HATE_SPEECH",
      "HARM_CATEGORY_SEXUALLY_EXPLICIT",
      "HARM_CATEGORY_DANGEROUS_CONTENT"
    ).map(c => JsObject("category" -> JsString(c), "threshold" -> JsString("BLOCK_NONE")))
  )
}
