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

/** Actor-based OpenAI client using the Batch API.
  *
  * Features:
  * - Uses json_schema response_format for reliable structured output
  * - Queues requests until batch threshold or timer fires
  * - Submits batches asynchronously (50% cost savings)
  * - Polls for completion with exponential backoff
  * - Routes results back to original callers
  *
  * OpenAI Batch API flow:
  * 1. Upload JSONL file with requests
  * 2. Create batch with file ID
  * 3. Poll for completion
  * 4. Download results from output file
  *
  * @param apiKey OpenAI API key
  * @param model Model to use (default: gpt-4o)
  * @param batchThreshold Submit batch when this many requests queued (default: 100)
  * @param submitInterval Submit batch after this duration even if threshold not met (default: 5 minutes)
  * @param pollInterval Initial poll interval for batch status (default: 30 seconds)
  * @param maxPollInterval Maximum poll interval with backoff (default: 5 minutes)
  */
object BatchOpenAIClient {

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

  /** Internal: File uploaded successfully */
  private case class FileUploaded(fileId: String, pendingRequests: Map[String, PendingRequest]) extends Command

  /** Internal: File upload failed */
  private case class FileUploadFailed(error: Throwable, pendingRequests: Map[String, PendingRequest]) extends Command

  /** Internal: Batch creation response */
  private case class BatchCreated(batchId: String, pendingRequests: Map[String, PendingRequest]) extends Command

  /** Internal: Batch creation failed */
  private case class BatchCreateFailed(error: Throwable, pendingRequests: Map[String, PendingRequest]) extends Command

  /** Internal: Batch status response */
  private case class BatchStatusReceived(batchId: String, status: BatchStatus, outputFileId: Option[String]) extends Command

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
    case Validating, InProgress, Finalizing, Completed, Failed, Expired, Cancelling, Cancelled
  }

  private case class BatchResult(
      customId: String,
      success: Boolean,
      content: Option[JsObject],
      error: Option[String]
  )

  private case class InFlightBatch(
      batchId: String,
      requests: Map[String, PendingRequest],
      pollAttempt: Int = 0
  )

  // --- Config ---
  case class Config(
      model: String = "gpt-4o",
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
        new BatchOpenAIClient(apiKey, config, ctx, timers, buffer).idle()
      }
    }
  }
}

private class BatchOpenAIClient(
    apiKey: String,
    config: BatchOpenAIClient.Config,
    ctx: ActorContext[BatchOpenAIClient.Command],
    timers: TimerScheduler[BatchOpenAIClient.Command],
    buffer: StashBuffer[BatchOpenAIClient.Command]
) {
  import BatchOpenAIClient.*

  private val http = Http(ctx.system.classicSystem)
  private val filesApiUrl = "https://api.openai.com/v1/files"
  private val batchApiUrl = "https://api.openai.com/v1/batches"
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

    case FileUploaded(fileId, requests) =>
      log.info("File {} uploaded with {} requests, creating batch", fileId, requests.size)
      createBatch(fileId, requests)
      Behaviors.same

    case FileUploadFailed(error, requests) =>
      log.error("Failed to upload batch file: {}", error.getMessage)
      requests.values.foreach { req =>
        req.replyTo ! AssessmentFailed(req.creativeId, s"File upload failed: ${error.getMessage}")
      }
      Behaviors.same

    case BatchCreated(batchId, requests) =>
      log.info("Batch {} created with {} requests", batchId, requests.size)
      inFlightBatches += (batchId -> InFlightBatch(batchId, requests))
      timers.startSingleTimer(PollBatchTimer(batchId), config.pollInterval)
      Behaviors.same

    case BatchCreateFailed(error, requests) =>
      log.error("Failed to create batch: {}", error.getMessage)
      requests.values.foreach { req =>
        req.replyTo ! AssessmentFailed(req.creativeId, s"Batch creation failed: ${error.getMessage}")
      }
      Behaviors.same

    case PollBatchTimer(batchId) =>
      inFlightBatches.get(batchId).foreach { _ =>
        pollBatchStatus(batchId)
      }
      Behaviors.same

    case BatchStatusReceived(batchId, status, outputFileId) =>
      inFlightBatches.get(batchId).foreach { batch =>
        status match {
          case BatchStatus.Completed =>
            outputFileId match {
              case Some(fileId) =>
                log.info("Batch {} completed, fetching results from file {}", batchId, fileId)
                fetchBatchResults(batchId, fileId)
              case None =>
                log.error("Batch {} completed but no output file ID", batchId)
                failBatch(batchId, "Batch completed without output file")
            }
          case BatchStatus.Failed =>
            log.error("Batch {} failed", batchId)
            failBatch(batchId, "Batch processing failed")
          case BatchStatus.Expired =>
            log.error("Batch {} expired", batchId)
            failBatch(batchId, "Batch expired before completion")
          case BatchStatus.Cancelled =>
            log.warn("Batch {} was cancelled", batchId)
            failBatch(batchId, "Batch was cancelled")
          case BatchStatus.Validating | BatchStatus.InProgress | BatchStatus.Finalizing | BatchStatus.Cancelling =>
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
              result.content.foreach { content =>
                try {
                  val assessment = parseAssessmentFromJson(content)
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

    // Step 1: Upload JSONL file
    val jsonlContent = buildJsonlContent(requests)
    uploadFile(jsonlContent, requests)
  }

  private def uploadFile(content: String, requests: Map[String, PendingRequest]): Unit = {
    import org.apache.pekko.http.scaladsl.model.Multipart

    val boundary = s"----BatchOpenAI${System.currentTimeMillis()}"
    val formData = Multipart.FormData(
      Multipart.FormData.BodyPart.Strict(
        "purpose",
        HttpEntity("batch")
      ),
      Multipart.FormData.BodyPart.Strict(
        "file",
        HttpEntity(ContentTypes.`application/octet-stream`, content.getBytes("UTF-8")),
        Map("filename" -> "batch_requests.jsonl")
      )
    )

    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = filesApiUrl,
      headers = List(RawHeader("Authorization", s"Bearer $apiKey")),
      entity = formData.toEntity(boundary)
    )

    ctx.pipeToSelf(http.singleRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.OK | StatusCodes.Created =>
          Unmarshal(response.entity).to[String].map { body =>
            val fileId = extractFileId(body)
            (fileId, requests)
          }
        case status =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            scala.concurrent.Future.failed(new RuntimeException(s"File upload error $status: $body"))
          }
      }
    }) {
      case Success((fileId, reqs)) => FileUploaded(fileId, reqs)
      case Failure(e) => FileUploadFailed(e, requests)
    }
  }

  private def createBatch(fileId: String, requests: Map[String, PendingRequest]): Unit = {
    val body = JsObject(
      "input_file_id" -> JsString(fileId),
      "endpoint" -> JsString("/v1/chat/completions"),
      "completion_window" -> JsString("24h")
    ).compactPrint

    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = batchApiUrl,
      headers = List(RawHeader("Authorization", s"Bearer $apiKey")),
      entity = HttpEntity(ContentTypes.`application/json`, body)
    )

    ctx.pipeToSelf(http.singleRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.OK | StatusCodes.Created =>
          Unmarshal(response.entity).to[String].map { body =>
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
      headers = List(RawHeader("Authorization", s"Bearer $apiKey"))
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

  private def fetchBatchResults(batchId: String, outputFileId: String): Unit = {
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = s"$filesApiUrl/$outputFileId/content",
      headers = List(RawHeader("Authorization", s"Bearer $apiKey"))
    )

    ctx.pipeToSelf(http.singleRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.OK =>
          Unmarshal(response.entity).to[String].map(parseResultsJsonl)
        case status =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            scala.concurrent.Future.failed(new RuntimeException(s"File download error $status: $body"))
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
    val exponentialDelay = config.pollInterval * math.pow(1.5, attempt).toLong
    val jitter = (Random.nextInt(10) + 1).seconds
    val total = exponentialDelay + jitter
    if (total > config.maxPollInterval) config.maxPollInterval else total
  }

  // --- JSON Building ---

  private def buildJsonlContent(requests: Map[String, PendingRequest]): String = {
    requests.map { case (customId, req) =>
      buildSingleRequest(customId, req).compactPrint
    }.mkString("\n")
  }

  private def buildSingleRequest(customId: String, req: PendingRequest): JsObject = {
    val base64Image = Base64.getEncoder.encodeToString(req.imageBytes)
    val imageUrl = s"data:${req.mimeType};base64,$base64Image"
    val prompt = buildPrompt(req.context)

    // JSON schema for structured output
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
              "description" -> JsString("Overall content safety score from 0.0 to 1.0")
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
              "description" -> JsString("IAB Content Taxonomy category codes detected")
            ),
            "category_confidence" -> JsObject(
              "type" -> JsString("number"),
              "description" -> JsString("Confidence in detected categories from 0.0 to 1.0")
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

    JsObject(
      "custom_id" -> JsString(customId),
      "method" -> JsString("POST"),
      "url" -> JsString("/v1/chat/completions"),
      "body" -> JsObject(
        "model" -> JsString(config.model),
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
    )
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
- IAB20: Travel
- IAB25: Non-Standard Content (IAB25-3: Pornography, IAB25-5: Gambling - sensitive)
- IAB26: Illegal Content

Be strict about safety. Flag anything potentially inappropriate for general audiences.
Provide your analysis in the required JSON format."""
  }

  // --- JSON Parsing ---

  private def extractFileId(body: String): String =
    Try {
      body.parseJson.asJsObject.fields("id") match {
        case JsString(id) => id
        case _ => throw new RuntimeException("File ID not a string")
      }
    }.getOrElse(throw new RuntimeException(s"Could not extract file ID from: $body"))

  private def extractBatchId(body: String): String =
    Try {
      body.parseJson.asJsObject.fields("id") match {
        case JsString(id) => id
        case _ => throw new RuntimeException("Batch ID not a string")
      }
    }.getOrElse(throw new RuntimeException(s"Could not extract batch ID from: $body"))

  private def parseBatchStatus(body: String): (BatchStatus, Option[String]) = {
    val json = body.parseJson.asJsObject

    val status = json.fields.get("status").collect {
      case JsString("validating") => BatchStatus.Validating
      case JsString("in_progress") => BatchStatus.InProgress
      case JsString("finalizing") => BatchStatus.Finalizing
      case JsString("completed") => BatchStatus.Completed
      case JsString("failed") => BatchStatus.Failed
      case JsString("expired") => BatchStatus.Expired
      case JsString("cancelling") => BatchStatus.Cancelling
      case JsString("cancelled") => BatchStatus.Cancelled
    }.getOrElse(throw new RuntimeException(s"Unknown batch status in: $body"))

    val outputFileId = json.fields.get("output_file_id").collect {
      case JsString(id) => id
    }

    (status, outputFileId)
  }

  private def parseResultsJsonl(body: String): List[BatchResult] = {
    body.split("\n").filter(_.nonEmpty).map { line =>
      Try {
        val json = line.parseJson.asJsObject

        val customId = json.fields.get("custom_id").collect {
          case JsString(id) => id
        }.getOrElse("")

        val responseObj = json.fields.get("response").map(_.asJsObject)
        val statusCode = responseObj.flatMap(_.fields.get("status_code")).collect {
          case JsNumber(n) => n.toInt
        }

        val errorObj = json.fields.get("error").map(_.asJsObject)

        if (statusCode.contains(200)) {
          // Extract content from response body
          val content = for {
            response <- responseObj
            body <- response.fields.get("body").map(_.asJsObject)
            choices <- body.fields.get("choices").collect { case JsArray(arr) => arr }
            firstChoice <- choices.headOption.map(_.asJsObject)
            message <- firstChoice.fields.get("message").map(_.asJsObject)
            contentStr <- message.fields.get("content").collect { case JsString(s) => s }
            contentJson <- Try(contentStr.parseJson.asJsObject).toOption
          } yield contentJson

          BatchResult(customId, success = true, content, None)
        } else {
          val errorMsg = errorObj.flatMap(_.fields.get("message")).collect {
            case JsString(msg) => msg
          }.getOrElse(s"HTTP $statusCode")

          BatchResult(customId, success = false, None, Some(errorMsg))
        }
      }.getOrElse(BatchResult("", success = false, None, Some(s"Failed to parse result line: ${line.take(100)}")))
    }.toList
  }

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
      model = config.model
    )
  }
}
