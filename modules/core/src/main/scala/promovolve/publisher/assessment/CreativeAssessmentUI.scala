package promovolve.publisher.assessment

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.scaladsl.Sink
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import scala.io.Source
import java.util.Base64
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.*
import scala.util.{ Failure, Success }

/**
 * Simple standalone UI for testing creative category verification.
 *
 * Run with: sbt "core/runMain promovolve.publisher.assessment.CreativeAssessmentUI"
 *
 * Then open http://localhost:9090 in your browser.
 */
object CreativeAssessmentUI {

  /** Simplified result for category matching */
  case class CategoryMatchResult(
      matchConfidence: Double,
      detectedCategories: List[String],
      explanation: String
  )

  /** API response */
  case class AssessmentResponse(
      creativeId: String,
      status: String,
      declaredCategory: Option[String],
      matchConfidence: Option[Double],
      detectedCategories: List[String],
      explanation: Option[String],
      error: Option[String],
      requestJson: Option[String] = None,
      responseJson: Option[String] = None
  )

  given RootJsonFormat[AssessmentResponse] = jsonFormat9(AssessmentResponse.apply)

  // Category for autocomplete
  case class CategoryOption(id: String, name: String, fullPath: String)
  given RootJsonFormat[CategoryOption] = jsonFormat3(CategoryOption.apply)

  // Load categories from TSV
  private lazy val allCategories: Vector[CategoryOption] = {
    val source = Source.fromURL(getClass.getResource("/iab_content_taxonomy/3_0.tsv"))
    try {
      // Two header lines: relational-id banner + column names
      source.getLines().drop(2).flatMap { line =>
        val columns = line.split("\t").map(_.trim)
        if (columns.length >= 4) {
          val id = columns(0)
          val name = columns(2)
          val tiers = columns.drop(3).takeWhile(_.nonEmpty)
          val fullPath = if (tiers.nonEmpty) tiers.mkString(" > ") else name
          Some(CategoryOption(id, name, fullPath))
        } else None
      }.toVector
    } finally source.close()
  }

  /** Result with captured JSON payloads for debugging */
  case class DebugResult(
      result: CategoryMatchResult,
      requestJson: String,
      responseJson: String
  )

  /** Gemini client that verifies category matching */
  class CategoryVerificationClient(
      apiKey: String,
      model: String = "gemini-2.5-flash"
  )(using system: ActorSystem[?], ec: ExecutionContext) {

    private val http = Http(system.toClassic)

    private def apiUrl: String =
      s"https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

    def verify(
        imageBytes: Array[Byte],
        mimeType: String,
        declaredCategory: Option[String]
    ): Future[DebugResult] = {
      val base64Image = Base64.getEncoder.encodeToString(imageBytes)
      val requestBody = buildRequestBody(base64Image, mimeType, declaredCategory)
      val requestJsonPretty = requestBody.parseJson.prettyPrint

      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = apiUrl,
        entity = HttpEntity(ContentTypes.`application/json`, requestBody)
      )

      println(s"Sending request to Gemini API...")

      val requestFuture = http.singleRequest(request).flatMap { response =>
        println(s"Gemini response status: ${response.status}")
        Unmarshal(response.entity).to[String].map { responseBody =>
          println(s"Gemini response body length: ${responseBody.length}")
          val responseJsonPretty = try {
            responseBody.parseJson.prettyPrint
          } catch {
            case _: Exception => responseBody
          }

          if (response.status != StatusCodes.OK) {
            println(s"Gemini API error: $responseBody")
            throw new RuntimeException(s"Gemini API error ${response.status}: $responseBody")
          }

          val result = parseResponse(responseBody, declaredCategory)
          DebugResult(result, requestJsonPretty, responseJsonPretty)
        }
      }

      // Race between the request and a timeout
      val timeoutFuture = after(60.seconds, system.classicSystem.scheduler) {
        Future.failed(new RuntimeException("Gemini API request timed out after 60 seconds"))
      }

      Future.firstCompletedOf(Seq(requestFuture, timeoutFuture)).recover { case ex =>
        println(s"Gemini request failed: ${ex.getMessage}")
        throw ex
      }
    }

    private def buildRequestBody(base64Image: String, mimeType: String, declaredCategory: Option[String]): String = {
      val prompt = declaredCategory match {
        case Some(cat) =>
          s"""Look at this advertisement image.
             |
             |The advertiser claims this ad belongs to category: $cat
             |
             |Your task:
             |1. Determine what categories this image actually represents (use IAB Content Taxonomy codes like IAB1, IAB2, IAB20, etc.)
             |2. Rate how well the image matches the declared category "$cat" on a scale of 0.0 to 1.0
             |3. Explain your reasoning briefly
             |
             |Common IAB categories:
             |- IAB1: Arts & Entertainment
             |- IAB2: Automotive
             |- IAB7: Health & Fitness
             |- IAB8: Food & Drink
             |- IAB17: Sports
             |- IAB18: Style & Fashion
             |- IAB19: Technology
             |- IAB20: Travel
             |- IAB22: Shopping
             |
             |IMPORTANT: Always provide a response. If you're unsure, use 0.5 for match_confidence.""".stripMargin

        case None =>
          """Look at this advertisement image.
            |
            |Your task:
            |1. Determine what categories this image represents (use IAB Content Taxonomy codes like IAB1, IAB2, IAB20, etc.)
            |2. Since no category was declared, set match_confidence to 0.5 (neutral)
            |3. Describe what you see in the image
            |
            |IMPORTANT: Always provide a response.""".stripMargin
      }

      val safetySettings = JsArray(
        JsObject("category" -> JsString("HARM_CATEGORY_HARASSMENT"), "threshold" -> JsString("BLOCK_NONE")),
        JsObject("category" -> JsString("HARM_CATEGORY_HATE_SPEECH"), "threshold" -> JsString("BLOCK_NONE")),
        JsObject("category" -> JsString("HARM_CATEGORY_SEXUALLY_EXPLICIT"), "threshold" -> JsString("BLOCK_NONE")),
        JsObject("category" -> JsString("HARM_CATEGORY_DANGEROUS_CONTENT"), "threshold" -> JsString("BLOCK_NONE"))
      )

      val body = JsObject(
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
          "maxOutputTokens" -> JsNumber(512),
          "responseMimeType" -> JsString("application/json"),
          "responseSchema" -> JsObject(
            "type" -> JsString("object"),
            "properties" -> JsObject(
              "match_confidence" -> JsObject(
                "type" -> JsString("number"),
                "description" -> JsString(
                  "How well the image matches the declared category (0.0 = no match, 1.0 = perfect match). Use 0.5 if no category declared.")
              ),
              "detected_categories" -> JsObject(
                "type" -> JsString("array"),
                "items" -> JsObject("type" -> JsString("string")),
                "description" -> JsString("IAB category codes that best describe this image (e.g. IAB20, IAB8)")
              ),
              "explanation" -> JsObject(
                "type" -> JsString("string"),
                "description" ->
                JsString("Brief explanation of why the image does or doesn't match the declared category")
              )
            ),
            "required" -> JsArray(
              JsString("match_confidence"),
              JsString("detected_categories"),
              JsString("explanation")
            )
          )
        )
      )

      body.compactPrint
    }

    private def parseResponse(body: String, declaredCategory: Option[String]): CategoryMatchResult = {
      val json = body.parseJson.asJsObject
      val text = json.fields("candidates")
        .convertTo[JsArray].elements.head.asJsObject
        .fields("content").asJsObject
        .fields("parts")
        .convertTo[JsArray].elements.head.asJsObject
        .fields("text")
        .convertTo[String]

      val input = text.parseJson.asJsObject

      def getField(key: String): Option[JsValue] = {
        val camelCase = key.split("_").zipWithIndex.map { case (s, i) =>
          if (i == 0) s else s.capitalize
        }.mkString
        input.fields.get(key).orElse(input.fields.get(camelCase))
      }

      val matchConfidence = getField("match_confidence")
        .collect { case JsNumber(n) => n.toDouble }
        .getOrElse(if (declaredCategory.isDefined) 0.5 else 0.5)

      val detectedCategories = getField("detected_categories")
        .collect { case JsArray(items) => items.collect { case JsString(s) => s }.toList }
        .getOrElse(Nil)

      val explanation = getField("explanation")
        .collect { case JsString(s) => s }
        .getOrElse("No explanation provided")

      CategoryMatchResult(matchConfidence, detectedCategories, explanation)
    }
  }

  def main(args: Array[String]): Unit = {
    val apiKey = sys.env.getOrElse("GEMINI_API_KEY", {
        println("Warning: GEMINI_API_KEY not set. Assessment will fail.")
        ""
      })

    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "creative-assessment-ui")
    given ExecutionContext = system.executionContext

    val client = new CategoryVerificationClient(apiKey)
    val routes = createRoutes(client)

    val bindingFuture = Http().newServerAt("0.0.0.0", 9090).bind(routes)

    bindingFuture.onComplete {
      case Success(binding) =>
        println(s"Creative Assessment UI running at http://localhost:${binding.localAddress.getPort}")
        println("Open this URL in your browser to upload and assess creatives.")
      case Failure(ex) =>
        println(s"Failed to start server: ${ex.getMessage}")
        system.terminate()
    }

    scala.concurrent.Await.result(system.whenTerminated, scala.concurrent.duration.Duration.Inf)
  }

  private def createRoutes(
      client: CategoryVerificationClient
  )(using system: ActorSystem[?], ec: ExecutionContext): Route = {
    concat(
      path("") {
        get {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, htmlPage))
        }
      },

      path("api" / "assess") {
        post {
          parameter("category".optional) { categoryParam =>
            entity(as[Multipart.FormData]) { formData =>
              val processedF = formData.parts.runWith(Sink.head).flatMap { part =>
                part.entity.dataBytes.runWith(Sink.fold(Array.emptyByteArray)(_ ++ _.toArray)).flatMap { imageBytes =>
                  val mime = part.entity.contentType.toString()
                  val categoryOpt = categoryParam.filter(_.nonEmpty)
                  val creativeId = s"test-${System.currentTimeMillis()}"

                  println(s"Form received: imageSize=${imageBytes.length}, mime=$mime, category=$categoryOpt")

                  client.verify(imageBytes, mime, categoryOpt)
                    .map { debugResult =>
                      val maskedRequest = debugResult.requestJson.replaceAll(
                        """"data"\s*:\s*"[A-Za-z0-9+/=]{100,}"""",
                        """"data": "[BASE64_IMAGE_DATA]""""
                      )
                      AssessmentResponse(
                        creativeId = creativeId,
                        status = "assessed",
                        declaredCategory = categoryOpt,
                        matchConfidence = Some(debugResult.result.matchConfidence),
                        detectedCategories = debugResult.result.detectedCategories,
                        explanation = Some(debugResult.result.explanation),
                        error = None,
                        requestJson = Some(maskedRequest),
                        responseJson = Some(debugResult.responseJson)
                      )
                    }
                    .recover { case ex =>
                      AssessmentResponse(
                        creativeId = creativeId,
                        status = "failed",
                        declaredCategory = categoryOpt,
                        matchConfidence = None,
                        detectedCategories = Nil,
                        explanation = None,
                        error = Some(ex.getMessage),
                        requestJson = None,
                        responseJson = None
                      )
                    }
                }
              }

              onComplete(processedF) {
                case Success(response) =>
                  complete(HttpEntity(ContentTypes.`application/json`, response.toJson.prettyPrint))
                case Failure(ex) =>
                  complete(StatusCodes.InternalServerError, s"Assessment failed: ${ex.getMessage}")
              }
            }
          }
        }
      },

      path("api" / "categories") {
        get {
          parameter("q".optional) { query =>
            val filtered = query match {
              case Some(q) if q.nonEmpty =>
                val lowerQ = q.toLowerCase
                allCategories.filter { cat =>
                  cat.id.toLowerCase.contains(lowerQ) ||
                  cat.name.toLowerCase.contains(lowerQ) ||
                  cat.fullPath.toLowerCase.contains(lowerQ)
                }.take(20)
              case _ =>
                allCategories.take(50)
            }
            complete(HttpEntity(ContentTypes.`application/json`, filtered.toJson.compactPrint))
          }
        }
      }
    )
  }

  private val htmlPage: String =
    """<!DOCTYPE html>
      |<html lang="en">
      |<head>
      |  <meta charset="UTF-8">
      |  <meta name="viewport" content="width=device-width, initial-scale=1.0">
      |  <title>Creative Category Verification</title>
      |  <style>
      |    * { box-sizing: border-box; }
      |    body {
      |      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      |      max-width: 800px;
      |      margin: 0 auto;
      |      padding: 20px;
      |      background: #f5f5f5;
      |    }
      |    h1 { color: #333; margin-bottom: 10px; }
      |    .subtitle { color: #666; margin-bottom: 30px; }
      |    .upload-area {
      |      border: 2px dashed #ccc;
      |      border-radius: 8px;
      |      padding: 40px;
      |      text-align: center;
      |      background: white;
      |      cursor: pointer;
      |      transition: border-color 0.3s;
      |    }
      |    .upload-area:hover { border-color: #007bff; }
      |    .upload-area.dragover { border-color: #007bff; background: #f0f7ff; }
      |    .upload-area input { display: none; }
      |    .preview { max-width: 300px; margin: 20px auto; display: none; }
      |    .preview img { max-width: 100%; border-radius: 4px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
      |    .btn {
      |      background: #007bff;
      |      color: white;
      |      border: none;
      |      padding: 12px 24px;
      |      border-radius: 4px;
      |      cursor: pointer;
      |      font-size: 16px;
      |      margin-top: 20px;
      |    }
      |    .btn:hover { background: #0056b3; }
      |    .btn:disabled { background: #ccc; cursor: not-allowed; }
      |    .spinner {
      |      display: none;
      |      width: 20px;
      |      height: 20px;
      |      border: 2px solid #fff;
      |      border-top-color: transparent;
      |      border-radius: 50%;
      |      animation: spin 1s linear infinite;
      |      margin-left: 10px;
      |      vertical-align: middle;
      |    }
      |    @keyframes spin { to { transform: rotate(360deg); } }
      |    .results {
      |      margin-top: 30px;
      |      background: white;
      |      border-radius: 8px;
      |      padding: 20px;
      |      display: none;
      |    }
      |    .match-score {
      |      text-align: center;
      |      padding: 30px;
      |      border-radius: 8px;
      |      margin-bottom: 20px;
      |    }
      |    .match-score.high { background: #d4edda; }
      |    .match-score.medium { background: #fff3cd; }
      |    .match-score.low { background: #f8d7da; }
      |    .match-score .score { font-size: 48px; font-weight: bold; }
      |    .match-score .label { font-size: 14px; color: #666; margin-top: 5px; }
      |    .section { margin-bottom: 20px; }
      |    .section h3 { margin-bottom: 10px; color: #333; }
      |    .category-tag {
      |      display: inline-block;
      |      background: #e9ecef;
      |      padding: 6px 12px;
      |      border-radius: 20px;
      |      margin: 4px;
      |      font-size: 14px;
      |    }
      |    .explanation {
      |      background: #f8f9fa;
      |      padding: 15px;
      |      border-radius: 4px;
      |      line-height: 1.6;
      |    }
      |    .json-section { margin-top: 20px; }
      |    .json-section h3 { cursor: pointer; }
      |    .json-section h3:hover { color: #007bff; }
      |    .json-section h3::before { content: '▶ '; font-size: 12px; }
      |    .json-section h3.expanded::before { content: '▼ '; }
      |    .json-content {
      |      display: none;
      |      background: #1e1e1e;
      |      color: #d4d4d4;
      |      padding: 15px;
      |      border-radius: 4px;
      |      font-family: monospace;
      |      font-size: 12px;
      |      overflow-x: auto;
      |      max-height: 300px;
      |      overflow-y: auto;
      |      white-space: pre;
      |    }
      |    .json-content.visible { display: block; }
      |    .category-input { margin: 20px 0; }
      |    .category-input label { display: block; font-weight: 600; margin-bottom: 8px; }
      |    .category-input input {
      |      width: 100%;
      |      padding: 10px;
      |      border: 1px solid #ccc;
      |      border-radius: 4px;
      |      font-size: 14px;
      |    }
      |    .suggestions {
      |      position: absolute;
      |      top: 100%;
      |      left: 0;
      |      right: 0;
      |      background: white;
      |      border: 1px solid #ccc;
      |      border-top: none;
      |      border-radius: 0 0 4px 4px;
      |      max-height: 250px;
      |      overflow-y: auto;
      |      z-index: 1000;
      |      display: none;
      |    }
      |    .suggestion-item {
      |      padding: 10px;
      |      cursor: pointer;
      |      border-bottom: 1px solid #eee;
      |    }
      |    .suggestion-item:hover { background: #f0f7ff; }
      |    .suggestion-item strong { color: #007bff; }
      |  </style>
      |</head>
      |<body>
      |  <h1>Creative Category Verification</h1>
      |  <p class="subtitle">Upload an image and verify if it matches the declared category</p>
      |
      |  <div class="upload-area" id="dropzone">
      |    <input type="file" id="fileInput" accept="image/*">
      |    <p>Drag & drop an image here, or click to select</p>
      |  </div>
      |
      |  <div class="preview" id="preview">
      |    <img id="previewImg" src="" alt="Preview">
      |  </div>
      |
      |  <div class="category-input">
      |    <label>Declared Category (optional)</label>
      |    <div style="position: relative;">
      |      <input type="text" id="categoryInput" placeholder="Search IAB categories...">
      |      <input type="hidden" id="categoryId">
      |      <div class="suggestions" id="suggestions"></div>
      |    </div>
      |    <p style="color: #666; font-size: 12px; margin-top: 5px;">
      |      Select a category to verify if the image matches
      |    </p>
      |  </div>
      |
      |  <div style="text-align: center;">
      |    <button class="btn" id="assessBtn" disabled>
      |      Verify Category
      |      <span class="spinner" id="spinner"></span>
      |    </button>
      |  </div>
      |
      |  <div class="results" id="results">
      |    <div class="match-score" id="matchScore">
      |      <div class="score" id="scoreValue">-</div>
      |      <div class="label">Match Confidence</div>
      |    </div>
      |
      |    <div class="section">
      |      <h3>Detected Categories</h3>
      |      <div id="detectedCategories"></div>
      |    </div>
      |
      |    <div class="section">
      |      <h3>Explanation</h3>
      |      <div class="explanation" id="explanation"></div>
      |    </div>
      |
      |    <div class="json-section">
      |      <h3 id="requestToggle" onclick="toggleJson('request')">Request JSON</h3>
      |      <pre class="json-content" id="requestJson"></pre>
      |    </div>
      |
      |    <div class="json-section">
      |      <h3 id="responseToggle" onclick="toggleJson('response')">Response JSON</h3>
      |      <pre class="json-content" id="responseJson"></pre>
      |    </div>
      |  </div>
      |
      |  <script>
      |    const dropzone = document.getElementById('dropzone');
      |    const fileInput = document.getElementById('fileInput');
      |    const preview = document.getElementById('preview');
      |    const previewImg = document.getElementById('previewImg');
      |    const assessBtn = document.getElementById('assessBtn');
      |    const spinner = document.getElementById('spinner');
      |    const results = document.getElementById('results');
      |    const categoryInput = document.getElementById('categoryInput');
      |    const categoryId = document.getElementById('categoryId');
      |    const suggestions = document.getElementById('suggestions');
      |
      |    let selectedFile = null;
      |    let debounceTimer = null;
      |
      |    // Category autocomplete
      |    categoryInput.addEventListener('input', () => {
      |      clearTimeout(debounceTimer);
      |      debounceTimer = setTimeout(async () => {
      |        const query = categoryInput.value.trim();
      |        if (query.length < 1) {
      |          suggestions.style.display = 'none';
      |          return;
      |        }
      |        try {
      |          const response = await fetch('/api/categories?q=' + encodeURIComponent(query));
      |          const categories = await response.json();
      |          if (categories.length > 0) {
      |            suggestions.innerHTML = categories.map(cat =>
      |              `<div class="suggestion-item" data-id="${cat.id}" data-name="${cat.name}">
      |                 <strong>${cat.id}</strong>: ${cat.fullPath}
      |               </div>`
      |            ).join('');
      |            suggestions.style.display = 'block';
      |          } else {
      |            suggestions.style.display = 'none';
      |          }
      |        } catch (e) {
      |          console.error('Failed to fetch categories:', e);
      |        }
      |      }, 200);
      |    });
      |
      |    suggestions.addEventListener('click', (e) => {
      |      const item = e.target.closest('.suggestion-item');
      |      if (item) {
      |        categoryId.value = item.dataset.id;
      |        categoryInput.value = item.dataset.id + ': ' + item.dataset.name;
      |        suggestions.style.display = 'none';
      |      }
      |    });
      |
      |    document.addEventListener('click', (e) => {
      |      if (!e.target.closest('.category-input')) {
      |        suggestions.style.display = 'none';
      |      }
      |    });
      |
      |    // File handling
      |    dropzone.addEventListener('click', () => fileInput.click());
      |    dropzone.addEventListener('dragover', (e) => {
      |      e.preventDefault();
      |      dropzone.classList.add('dragover');
      |    });
      |    dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragover'));
      |    dropzone.addEventListener('drop', (e) => {
      |      e.preventDefault();
      |      dropzone.classList.remove('dragover');
      |      handleFile(e.dataTransfer.files[0]);
      |    });
      |    fileInput.addEventListener('change', () => handleFile(fileInput.files[0]));
      |
      |    function handleFile(file) {
      |      if (!file || !file.type.startsWith('image/')) return;
      |      selectedFile = file;
      |      const reader = new FileReader();
      |      reader.onload = (e) => {
      |        previewImg.src = e.target.result;
      |        preview.style.display = 'block';
      |      };
      |      reader.readAsDataURL(file);
      |      assessBtn.disabled = false;
      |    }
      |
      |    // Submit
      |    assessBtn.addEventListener('click', async () => {
      |      if (!selectedFile) return;
      |
      |      assessBtn.disabled = true;
      |      spinner.style.display = 'inline-block';
      |      results.style.display = 'none';
      |
      |      const formData = new FormData();
      |      formData.append('file', selectedFile);
      |
      |      let url = '/api/assess';
      |      if (categoryId.value) {
      |        url += '?category=' + encodeURIComponent(categoryId.value);
      |      }
      |
      |      try {
      |        const response = await fetch(url, { method: 'POST', body: formData });
      |        if (!response.ok) throw new Error(await response.text());
      |        const data = await response.json();
      |        displayResults(data);
      |      } catch (error) {
      |        alert('Verification failed: ' + error.message);
      |      } finally {
      |        assessBtn.disabled = false;
      |        spinner.style.display = 'none';
      |      }
      |    });
      |
      |    function displayResults(data) {
      |      results.style.display = 'block';
      |
      |      // Match score
      |      const scoreEl = document.getElementById('matchScore');
      |      const scoreValue = document.getElementById('scoreValue');
      |      const confidence = data.matchConfidence || 0;
      |      const pct = Math.round(confidence * 100);
      |      scoreValue.textContent = pct + '%';
      |
      |      scoreEl.className = 'match-score ' + (pct >= 70 ? 'high' : pct >= 40 ? 'medium' : 'low');
      |
      |      // Detected categories
      |      const categoriesEl = document.getElementById('detectedCategories');
      |      categoriesEl.innerHTML = data.detectedCategories.length > 0
      |        ? data.detectedCategories.map(c => `<span class="category-tag">${c}</span>`).join('')
      |        : '<em>No categories detected</em>';
      |
      |      // Explanation
      |      document.getElementById('explanation').textContent = data.explanation || 'No explanation provided';
      |
      |      // JSON payloads
      |      document.getElementById('requestJson').textContent = data.requestJson || 'Not available';
      |      document.getElementById('responseJson').textContent = data.responseJson || 'Not available';
      |
      |      results.scrollIntoView({ behavior: 'smooth' });
      |    }
      |
      |    function toggleJson(type) {
      |      const content = document.getElementById(type + 'Json');
      |      const toggle = document.getElementById(type + 'Toggle');
      |      content.classList.toggle('visible');
      |      toggle.classList.toggle('expanded');
      |    }
      |  </script>
      |</body>
      |</html>
      |""".stripMargin
}
