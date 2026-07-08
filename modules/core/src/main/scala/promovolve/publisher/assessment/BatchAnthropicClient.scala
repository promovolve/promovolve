package promovolve.publisher.assessment

import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory
import promovolve.publisher.AssessmentResult
import spray.json.*

import java.util.Base64
import scala.concurrent.duration.*
import scala.collection.mutable
import scala.util.{Failure, Random, Success, Try}

/** Actor-based Anthropic client using the Message Batches API.
  *
  * Features:
  * - Uses tool_use with JSON schema for reliable structured output
  * - Queues requests until batch threshold or timer fires
  * - Submits batches asynchronously (50% cost savings)
  * - Polls for completion with exponential backoff
  * - Routes results back to original callers
  *
  * @param apiKey Anthropic API key
  * @param model Model to use (default: claude-sonnet-4-20250514)
  * @param batchThreshold Submit batch when this many requests queued (default: 100)
  * @param submitInterval Submit batch after this duration even if threshold not met (default: 5 minutes)
  * @param pollInterval Initial poll interval for batch status (default: 30 seconds)
  * @param maxPollInterval Maximum poll interval with backoff (default: 5 minutes)
  */
object BatchAnthropicClient {

  private val log = LoggerFactory.getLogger(getClass)

  // --- Commands ---
  sealed trait Command

  /** Request assessment for a creative (queued for batch processing) */
  case class Assess(
      creativeId: String,
      imageBytes: Array[Byte],
      mimeType: String,
      context: AssessmentContext,
      replyTo: ActorRef[Response]
  ) extends Command

  /** Force submit current batch immediately */
  case object FlushBatch extends Command

  /** Internal: Timer fired to submit batch */
  private case object SubmitBatchTimer extends Command

  /** Internal: Timer fired to poll batch status */
  private case class PollBatchTimer(batchId: String) extends Command

  /** Internal: Batch creation response */
  private case class BatchCreated(batchId: String, pendingRequests: Map[String, PendingRequest]) extends Command

  /** Internal: Batch creation failed */
  private case class BatchCreateFailed(error: Throwable, pendingRequests: Map[String, PendingRequest]) extends Command

  /** Internal: Batch status response */
  private case class BatchStatusReceived(batchId: String, status: BatchStatus, resultsUrl: Option[String]) extends Command

  /** Internal: Batch results received */
  private case class BatchResultsReceived(batchId: String, results: List[BatchResult]) extends Command

  /** Internal: Batch operation failed */
  private case class BatchOperationFailed(batchId: String, error: Throwable) extends Command

  // --- Responses ---
  sealed trait Response
  case class AssessmentComplete(creativeId: String, result: AssessmentResult) extends Response
  case class AssessmentFailed(creativeId: String, error: String) extends Response

  // --- Internal types ---
  private case class PendingRequest(
      creativeId: String,
      imageBytes: Array[Byte],
      mimeType: String,
      context: AssessmentContext,
      replyTo: ActorRef[Response]
  )

  private enum BatchStatus {
    case InProgress, Canceling, Ended
  }

  private case class BatchResult(
      customId: String,
      success: Boolean,
      toolInput: Option[JsObject],
      error: Option[String]
  )

  private case class InFlightBatch(
      batchId: String,
      requests: Map[String, PendingRequest],
      pollAttempt: Int = 0
  )

  // --- Config ---
  case class Config(
      model: String = "claude-sonnet-4-20250514",
      batchThreshold: Int = 100,
      submitInterval: FiniteDuration = 5.minutes,
      pollInterval: FiniteDuration = 30.seconds,
      maxPollInterval: FiniteDuration = 5.minutes
  )

  def apply(
      apiKey: String,
      config: Config = Config()
  ): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      Behaviors.withStash(10000) { buffer =>
        new BatchAnthropicClient(apiKey, config, ctx, timers, buffer).idle()
      }
    }
  }
}

private class BatchAnthropicClient(
    apiKey: String,
    config: BatchAnthropicClient.Config,
    ctx: ActorContext[BatchAnthropicClient.Command],
    timers: TimerScheduler[BatchAnthropicClient.Command],
    buffer: StashBuffer[BatchAnthropicClient.Command]
) {
  import BatchAnthropicClient.*

  private val http = Http(ctx.system.classicSystem)
  private val batchApiUrl = "https://api.anthropic.com/v1/messages/batches"
  private given ec: scala.concurrent.ExecutionContext = ctx.executionContext
  private given mat: org.apache.pekko.stream.Materializer =
    org.apache.pekko.stream.Materializer(ctx.system)

  // Queue of pending requests
  private val pendingQueue: mutable.Map[String, PendingRequest] = mutable.Map.empty

  // In-flight batches being polled
  private val inFlightBatches: mutable.Map[String, InFlightBatch] = mutable.Map.empty

  /** Idle state - accepting new requests, no batch in progress */
  def idle(): Behavior[Command] = Behaviors.receiveMessage {
    case Assess(creativeId, imageBytes, mimeType, context, replyTo) =>
      pendingQueue += (creativeId -> PendingRequest(creativeId, imageBytes, mimeType, context, replyTo))
      log.debug("Queued assessment for creative {}, queue size: {}", creativeId, pendingQueue.size)

      // Start submit timer on first request
      if (pendingQueue.size == 1) {
        timers.startSingleTimer(SubmitBatchTimer, config.submitInterval)
      }

      // Check if threshold reached
      if (pendingQueue.size >= config.batchThreshold) {
        submitBatch()
      }
      Behaviors.same

    case FlushBatch =>
      if (pendingQueue.nonEmpty) {
        submitBatch()
      }
      Behaviors.same

    case SubmitBatchTimer =>
      if (pendingQueue.nonEmpty) {
        submitBatch()
      }
      Behaviors.same

    case BatchCreated(batchId, requests) =>
      log.info("Batch {} created with {} requests", batchId, requests.size)
      inFlightBatches += (batchId -> InFlightBatch(batchId, requests))
      timers.startSingleTimer(PollBatchTimer(batchId), config.pollInterval)
      Behaviors.same

    case BatchCreateFailed(error, requests) =>
      log.error("Failed to create batch: {}", error.getMessage)
      // Notify all callers of failure
      requests.values.foreach { req =>
        req.replyTo ! AssessmentFailed(req.creativeId, s"Batch creation failed: ${error.getMessage}")
      }
      Behaviors.same

    case PollBatchTimer(batchId) =>
      inFlightBatches.get(batchId).foreach { batch =>
        pollBatchStatus(batchId)
      }
      Behaviors.same

    case BatchStatusReceived(batchId, status, resultsUrl) =>
      inFlightBatches.get(batchId).foreach { batch =>
        status match {
          case BatchStatus.Ended =>
            resultsUrl match {
              case Some(url) =>
                log.info("Batch {} ended, fetching results from {}", batchId, url)
                fetchBatchResults(batchId, url)
              case None =>
                log.error("Batch {} ended but no results URL", batchId)
                failBatch(batchId, "Batch ended without results URL")
            }
          case BatchStatus.InProgress | BatchStatus.Canceling =>
            // Schedule next poll with backoff
            val nextAttempt = batch.pollAttempt + 1
            val delay = calculatePollBackoff(nextAttempt)
            log.debug("Batch {} still {}, polling again in {}", batchId, status, delay)
            inFlightBatches += (batchId -> batch.copy(pollAttempt = nextAttempt))
            timers.startSingleTimer(PollBatchTimer(batchId), delay)
        }
      }
      Behaviors.same

    case BatchResultsReceived(batchId, results) =>
      inFlightBatches.get(batchId).foreach { batch =>
        log.info("Batch {} completed with {} results", batchId, results.size)
        results.foreach { result =>
          batch.requests.get(result.customId).foreach { req =>
            if (result.success) {
              result.toolInput.foreach { input =>
                try {
                  val assessment = parseAssessmentFromInput(input)
                  req.replyTo ! AssessmentComplete(req.creativeId, assessment)
                } catch {
                  case e: Exception =>
                    log.error("Failed to parse assessment for {}: {}", req.creativeId, e.getMessage)
                    req.replyTo ! AssessmentFailed(req.creativeId, s"Parse error: ${e.getMessage}")
                }
              }
            } else {
              req.replyTo ! AssessmentFailed(req.creativeId, result.error.getOrElse("Unknown error"))
            }
          }
        }
        inFlightBatches -= batchId
      }
      Behaviors.same

    case BatchOperationFailed(batchId, error) =>
      log.error("Batch {} operation failed: {}", batchId, error.getMessage)
      failBatch(batchId, error.getMessage)
      Behaviors.same
  }

  private def submitBatch(): Unit = {
    timers.cancel(SubmitBatchTimer)
    val requests = pendingQueue.toMap
    pendingQueue.clear()

    log.info("Submitting batch with {} requests", requests.size)

    val requestBody = buildBatchRequestBody(requests)
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = batchApiUrl,
      headers = List(
        RawHeader("x-api-key", apiKey),
        RawHeader("anthropic-version", "2023-06-01")
      ),
      entity = HttpEntity(ContentTypes.`application/json`, requestBody)
    )

    ctx.pipeToSelf(http.singleRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.OK | StatusCodes.Created =>
          Unmarshal(response.entity).to[String].map { body =>
            // Extract batch ID from response
            val batchId = extractBatchId(body)
            (batchId, requests)
          }
        case status =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            scala.concurrent.Future.failed(new RuntimeException(s"Batch API error $status: $body"))
          }
      }
    }) {
      case Success((batchId, reqs)) => BatchCreated(batchId, reqs)
      case Failure(e) => BatchCreateFailed(e, requests)
    }
  }

  private def pollBatchStatus(batchId: String): Unit = {
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = s"$batchApiUrl/$batchId",
      headers = List(
        RawHeader("x-api-key", apiKey),
        RawHeader("anthropic-version", "2023-06-01")
      )
    )

    ctx.pipeToSelf(http.singleRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.OK =>
          Unmarshal(response.entity).to[String].map(parseBatchStatus)
        case status =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            scala.concurrent.Future.failed(new RuntimeException(s"Batch status error $status: $body"))
          }
      }
    }) {
      case Success(result: (BatchStatus, Option[String])) =>
        BatchStatusReceived(batchId, result._1, result._2)
      case Failure(e) => BatchOperationFailed(batchId, e)
    }
  }

  private def fetchBatchResults(batchId: String, url: String): Unit = {
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = url,
      headers = List(
        RawHeader("x-api-key", apiKey),
        RawHeader("anthropic-version", "2023-06-01")
      )
    )

    ctx.pipeToSelf(http.singleRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.OK =>
          Unmarshal(response.entity).to[String].map(parseResultsJsonl)
        case status =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            scala.concurrent.Future.failed(new RuntimeException(s"Batch results error $status: $body"))
          }
      }
    }) {
      case Success(results: List[BatchResult @unchecked]) =>
        BatchResultsReceived(batchId, results)
      case Failure(e) => BatchOperationFailed(batchId, e)
    }
  }

  private def failBatch(batchId: String, error: String): Unit = {
    inFlightBatches.get(batchId).foreach { batch =>
      batch.requests.values.foreach { req =>
        req.replyTo ! AssessmentFailed(req.creativeId, error)
      }
      inFlightBatches -= batchId
    }
  }

  private def calculatePollBackoff(attempt: Int): FiniteDuration = {
    // Exponential backoff with jitter, capped at maxPollInterval
    val exponentialDelay = config.pollInterval * math.pow(1.5, attempt).toLong
    val jitter = (Random.nextInt(10) + 1).seconds
    val total = exponentialDelay + jitter
    if (total > config.maxPollInterval) config.maxPollInterval else total
  }

  // --- JSON Building with tool_use ---

  private def buildBatchRequestBody(requests: Map[String, PendingRequest]): String = {
    // Define the assessment tool with JSON schema (same as AnthropicClient)
    val assessmentTool = JsObject(
      "name" -> JsString("assess_creative"),
      "description" -> JsString("Output the creative assessment results with safety scores, content flags, and detected categories"),
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
            "description" -> JsString("IAB Content Taxonomy category codes detected in the creative")
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

    val batchRequests = requests.map { case (customId, req) =>
      val base64Image = Base64.getEncoder.encodeToString(req.imageBytes)
      val prompt = buildPrompt(req.context)

      JsObject(
        "custom_id" -> JsString(customId),
        "params" -> JsObject(
          "model" -> JsString(config.model),
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
                  "media_type" -> JsString(req.mimeType),
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
      )
    }.toVector

    JsObject("requests" -> JsArray(batchRequests)).compactPrint
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
- IAB8: Food & Drink (IAB8-5: Cocktails/Beer, IAB8-18: Wine)
- IAB9: Hobbies & Interests (IAB9-7: Gambling)
- IAB25: Non-Standard Content (IAB25-3: Pornography, IAB25-5: Gambling - sensitive)
- IAB26: Illegal Content

Be strict about safety. Flag anything potentially inappropriate for general audiences.
Use the assess_creative tool to provide your analysis."""
  }

  // --- JSON Parsing ---

  private def extractBatchId(body: String): String =
    Try {
      body.parseJson.asJsObject.fields("id") match {
        case JsString(id) => id
        case _ => throw new RuntimeException("Batch ID not a string")
      }
    }.getOrElse(throw new RuntimeException(s"Could not extract batch ID from: $body"))

  private def parseBatchStatus(body: String): (BatchStatus, Option[String]) = {
    val json = body.parseJson.asJsObject

    val status = json.fields.get("processing_status").collect {
      case JsString("in_progress") => BatchStatus.InProgress
      case JsString("canceling") => BatchStatus.Canceling
      case JsString("ended") => BatchStatus.Ended
    }.getOrElse(throw new RuntimeException(s"Unknown batch status in: $body"))

    val resultsUrl = json.fields.get("results_url").collect {
      case JsString(url) => url
    }

    (status, resultsUrl)
  }

  private def parseResultsJsonl(body: String): List[BatchResult] = {
    body.split("\n").filter(_.nonEmpty).map { line =>
      Try {
        val json = line.parseJson.asJsObject

        val customId = json.fields.get("custom_id").collect {
          case JsString(id) => id
        }.getOrElse("")

        val resultObj = json.fields.get("result").map(_.asJsObject)

        val resultType = resultObj.flatMap(_.fields.get("type")).collect {
          case JsString(t) => t
        }

        resultType match {
          case Some("succeeded") =>
            // Extract tool_use input from the message content
            val toolInput = for {
              result <- resultObj
              message <- result.fields.get("message").map(_.asJsObject)
              content <- message.fields.get("content").collect { case JsArray(arr) => arr }
              toolUseBlock <- content.find { elem =>
                elem.asJsObject.fields.get("type").contains(JsString("tool_use"))
              }
              input <- toolUseBlock.asJsObject.fields.get("input").map(_.asJsObject)
            } yield input

            BatchResult(customId, success = true, toolInput, None)

          case Some(errorType) =>
            val errorMsg = resultObj.flatMap(_.fields.get("error")).flatMap(_.asJsObject.fields.get("message")).collect {
              case JsString(msg) => msg
            }.getOrElse(errorType)

            BatchResult(customId, success = false, None, Some(errorMsg))

          case None =>
            BatchResult(customId, success = false, None, Some("Unknown result format"))
        }
      }.getOrElse(BatchResult("", success = false, None, Some(s"Failed to parse result line: ${line.take(100)}")))
    }.toList
  }

  private def parseAssessmentFromInput(input: JsObject): AssessmentResult = {
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
      model = config.model
    )
  }
}
