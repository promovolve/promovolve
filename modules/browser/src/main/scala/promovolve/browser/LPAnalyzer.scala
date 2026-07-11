package promovolve.browser

import com.microsoft.playwright.*
import com.microsoft.playwright.options.{ LoadState, WaitUntilState }
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem }
import org.slf4j.LoggerFactory
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Image extracted from a landing page section. */
final case class LPImage(
    src: String,
    width: Int,
    height: Int,
    alt: String
)

object LPImage {
  given RootJsonFormat[LPImage] = jsonFormat4(LPImage.apply)
}

/** Content section extracted from a landing page. */
final case class LPSection(
    heading: String,
    text: String,
    images: Vector[LPImage]
)

object LPSection {
  given RootJsonFormat[LPSection] = jsonFormat3(LPSection.apply)
}

/** Result of LP structural analysis. */
final case class LPAnalysisResult(
    url: String,
    sections: Vector[LPSection],
    // Dominant background color of the LP, sampled from the body /
    // html element during analysis. Optional — page may return no
    // resolvable colour (transparent body, etc.). Format: CSS hex or
    // rgb() string suitable for direct use in creative backgrounds.
    dominantColor: Option[String] = None,
    // Dominant body text colour of the LP, sampled from the first
    // paragraph/main/body element. Applied verbatim to creative text
    // so the typography feels like a continuation of the LP.
    textColor: Option[String] = None,
    // Brand palette of the LP — up to ~6 #rrggbb swatches ordered by
    // visual prominence. Seeds the creative's brand kit so the ad reads
    // as an extension of the LP. Empty when extraction found nothing.
    palette: Vector[String] = Vector.empty,
    // Font faces used by the LP (heading family first, then body).
    // Seeds the brand-kit font list. Empty when extraction failed.
    fonts: Vector[String] = Vector.empty
)

object LPAnalysisResult {
  given RootJsonFormat[LPAnalysisResult] = jsonFormat6(LPAnalysisResult.apply)
}

/**
 * Raw bytes of an LP image, captured during analysis from the one
 * browser context that could actually fetch it (it solved the
 * origin's bot manager). Carried OUT-OF-BAND from [[LPAnalysisResult]]
 * — that record is JSON-serialized, so bytes must never live on it.
 * The consumer (API layer) stores these once and rewrites the section
 * `src` to the resulting CDN URL, so the protected origin is never
 * re-fetched server-side. `mime` is the response content-type.
 */
final case class LPCapturedImage(bytes: Array[Byte], mime: String)

object LPAnalyzer {

  /**
   * Per-image cap on captured bytes. Skips oversized assets (hero
   * videos mislabelled as images, giant source PNGs) so a single page
   * can't balloon the out-of-band map.
   */
  val MaxCapturedImageBytes: Int = 8 * 1024 * 1024

  /**
   * Per-image floor on captured bytes. Skips icons, tracking pixels and
   * sprite chips — never referenced as section imagery, but each still
   * costs a blocking `body()` download.
   */
  val MinCapturedImageBytes: Int = 3 * 1024

  /**
   * Hard cap on how many image bodies one analysis will download.
   * Heavy pages (news homepages) emit hundreds of image responses, and
   * blocking on `body()` for every one is what made analysis run for
   * minutes and time out. Referenced section images are a few dozen, well
   * under this; anything past the cap falls back to og:image / re-fetch.
   */
  val MaxCapturedImages: Int = 200

  // Match a real, current Chrome. Bot managers flag stale majors.
  val UserAgent: String =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

  /**
   * Infer (locale, tz, Accept-Language) from the LP URL so the
   * browser context's locale matches the content being scraped.
   * Mismatched locales (en-US context hitting /jp/ja-jp/) are a
   * strong bot signal.
   */
  def inferLocaleTZ(url: String): (String, String, String) = {
    val u = scala.util.Try(new java.net.URI(url)).toOption
    val host = u.flatMap(x => Option(x.getHost)).getOrElse("").toLowerCase
    val path = u.flatMap(x => Option(x.getPath)).getOrElse("").toLowerCase
    val combined = s"$host$path"
    if (combined.contains("/jp/") || combined.contains("/ja-") || host.endsWith(".jp"))
      ("ja-JP", "Asia/Tokyo", "ja-JP,ja;q=0.9,en;q=0.8")
    else if (combined.contains("/kr/") || combined.contains("/ko-") || host.endsWith(".kr"))
      ("ko-KR", "Asia/Seoul", "ko-KR,ko;q=0.9,en;q=0.8")
    else if (combined.contains("/cn/") || combined.contains("/zh-") || host.endsWith(".cn"))
      ("zh-CN", "Asia/Shanghai", "zh-CN,zh;q=0.9,en;q=0.8")
    else if (combined.contains("/de/") || combined.contains("/de-") || host.endsWith(".de"))
      ("de-DE", "Europe/Berlin", "de-DE,de;q=0.9,en;q=0.8")
    else if (combined.contains("/fr/") || combined.contains("/fr-") || host.endsWith(".fr"))
      ("fr-FR", "Europe/Paris", "fr-FR,fr;q=0.9,en;q=0.8")
    else
      ("en-US", "America/New_York", "en-US,en;q=0.9")
  }
}

/**
 * Analyzes a single landing page using Playwright to extract content sections with images.
 *
 * Unlike the crawler (which blocks images and extracts flat text), this renders the full
 * page including images and extracts structured sections — each with heading, text, and
 * associated images from the DOM.
 *
 * Borrows a Browser from the shared [[BrowserSessionPool]] via
 * [[BrowserSessionPool.submit]] — the work executes on the pool
 * session's pinned thread, so Playwright's single-thread invariant
 * is satisfied without LPAnalyzer owning its own Playwright + Chromium.
 */
final class LPAnalyzer(
    bannerScriptUrl: String,
    browserPool: ActorRef[BrowserSessionPool.Command]
)(using system: ActorSystem[?]) {

  private val log = LoggerFactory.getLogger(getClass)
  private given ExecutionContext = system.executionContext

  /** Resolve a JS resource to a temp file on disk (Playwright needs a Path). */
  private def resolveResourceToTemp(resource: String, prefix: String): Path = {
    val stream = Option(getClass.getResourceAsStream(resource))
      .getOrElse(throw new IllegalArgumentException(s"Resource not found: $resource"))
    try {
      val content = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      val tmp = Files.createTempFile(prefix, ".js")
      tmp.toFile.deleteOnExit()
      Files.writeString(tmp, content)
      tmp
    } finally stream.close()
  }

  private val stealthScriptPath: Path = resolveResourceToTemp("/stealth.js", "stealth-")
  private val initScriptPath: Path = resolveResourceToTemp("/lp-analyzer.js", "lp-analyzer-")

  /**
   * Analyze a landing page URL and extract content sections with images.
   * The Playwright work is dispatched to a pool session; the archive.org
   * fallback runs on `system.executionContext` if the direct nav 4xx/5xxs.
   */
  def analyze(
      url: String,
      strategy: String = "heading",
      // Fired once, early — as soon as the page has loaded and settled but
      // BEFORE the (slow) section extraction — with a viewport PNG of the
      // LP. Lets the caller surface a preview while the rest runs. Never
      // affects the analysis result; failures here are swallowed.
      onScreenshot: Array[Byte] => Unit = _ => ()
  ): Future[(LPAnalysisResult, Map[String, LPCapturedImage])] = {
    log.info("LPAnalyzer: starting analysis of {} (strategy={})", url, strategy)
    BrowserSessionPool.submit(browserPool)(browser => analyzeDirect(browser, url, strategy, onScreenshot))
      .recover {
        case e: RuntimeException if Option(e.getMessage).exists(_.startsWith("HTTP ")) =>
          log.info("LPAnalyzer: direct nav failed ({}), falling back to archive.org for {}",
            e.getMessage, url)
          // The archive.org fallback fetches via plain HTTP and keeps
          // only og:image URLs — no captured bytes to carry.
          val res = archiveOrgFallback(url).getOrElse {
            log.info("LPAnalyzer: archive.org fallback empty for {}, returning empty result", url)
            LPAnalysisResult(url, Vector.empty, None, None)
          }
          (res, Map.empty[String, LPCapturedImage])
      }
  }

  /**
   * Playwright-driven analysis. Runs on the pool session's pinned
   * thread (caller routes via [[BrowserSessionPool.submit]]). Throws
   * `RuntimeException("HTTP nnn …")` when the nav response is an
   * error, so [[analyze]] can decide whether to fall back.
   */
  private def analyzeDirect(
      browser: Browser,
      url: String,
      strategy: String,
      onScreenshot: Array[Byte] => Unit
  ): (LPAnalysisResult, Map[String, LPCapturedImage]) = {
    val (locale, timezone, acceptLanguage) = LPAnalyzer.inferLocaleTZ(url)
    // Real Chrome 131 always sends these client hints. Their
    // absence (or mismatch with the UA) is a strong bot signal for
    // Akamai / Cloudflare bot managers.
    val extraHeaders = new java.util.HashMap[String, String]()
    extraHeaders.put("Accept-Language", acceptLanguage)
    extraHeaders.put("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
    extraHeaders.put("sec-ch-ua-mobile", "?0")
    extraHeaders.put("sec-ch-ua-platform", "\"macOS\"")
    val context = browser.newContext(
      new Browser.NewContextOptions()
        .setUserAgent(LPAnalyzer.UserAgent)
        .setViewportSize(1280, 800)
        .setLocale(locale)
        .setTimezoneId(timezone)
        .setExtraHTTPHeaders(extraHeaders)
    )

    try {
      // stealth patches must run BEFORE extractor — addInitScript
      // ordering is preserved by Playwright.
      context.addInitScript(stealthScriptPath)
      context.addInitScript(initScriptPath)
      val page = context.newPage()

      try {
        // Block media but NOT images or fonts. Fonts are allowed through so
        // the hero screenshot renders in the page's real typography (we await
        // document.fonts.ready before shooting); images we need for capture.
        page.route("**",
          route => {
            val resourceType = route.request().resourceType()
            if (resourceType == "media") route.abort()
            else route.resume()
          })

        // Track asset URLs that the CDN (typically Akamai on asset
        // subdomains) rejects. The main nav may succeed while a
        // pile of image/asset requests 403/502 — we swap those src
        // refs for og:image after extraction.
        val failedAssetUrls = scala.collection.mutable.Set.empty[String]
        // Capture image bytes as they arrive on THIS context — the
        // one that beat the origin's bot manager (Akamai _abck etc.).
        // The plain server-side re-fetch in import-urls gets tarpitted
        // by those same hosts, so we keep the bytes we already have and
        // hand them out-of-band. Single-threaded with the rest of
        // analyzeDirect (Playwright events fire on the pinned pool
        // thread), so the mutable map needs no synchronisation.
        val capturedImages = scala.collection.mutable.Map.empty[String, LPCapturedImage]
        page.onResponse { resp =>
          val s = resp.status()
          if (s == 403 || s == 502 || s == 503 || s == 504) {
            failedAssetUrls += resp.url()
          } else if (s >= 200 && s < 300 && capturedImages.size < LPAnalyzer.MaxCapturedImages
            && !capturedImages.contains(resp.url())) {
            val contentType = Option(resp.headers().get("content-type")).getOrElse("")
            val resourceType = scala.util.Try(resp.request().resourceType()).toOption.getOrElse("")
            if (resourceType == "image" || contentType.startsWith("image/")) {
              // Skip icons/pixels and oversized assets BEFORE the blocking
              // body() download, using the declared length when present —
              // a heavy page emits hundreds of image responses and
              // downloading all of them is what blew the time budget.
              val declaredLen =
                scala.util.Try(Option(resp.headers().get("content-length")).map(_.toLong)).toOption.flatten
              val inRange =
                declaredLen.forall(l => l >= LPAnalyzer.MinCapturedImageBytes && l <= LPAnalyzer.MaxCapturedImageBytes)
              if (inRange) {
                // body() blocks until the response body is downloaded;
                // wrap defensively — aborted/streaming responses throw.
                scala.util.Try(resp.body()).foreach { b =>
                  if (b != null && b.length >= LPAnalyzer.MinCapturedImageBytes &&
                    b.length <= LPAnalyzer.MaxCapturedImageBytes) {
                    val mime =
                      if (contentType.startsWith("image/")) contentType.takeWhile(_ != ';').trim
                      else "image/*"
                    capturedImages.update(resp.url(), LPCapturedImage(b, mime))
                  }
                }
              }
            }
          }
        }

        // Akamai-fronted SSR (e.g. ASICS PWA) often 502s the very
        // first navigation because `_abck` is unsolved — the edge
        // still sets the cookie though. Retry once: wait for the
        // cookie to solve, then navigate again.
        val navOpts = new Page.NavigateOptions()
          .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
          .setTimeout(15000)
        var response = page.navigate(url, navOpts)
        if (response != null && response.status() >= 500) {
          log.info("LPAnalyzer: first nav {} for {}, waiting for _abck then retrying",
            response.status(), url)
          try {
            page.waitForFunction(
              "() => /_abck=[^;]*~[0-9]+~/.test(document.cookie)",
              null,
              new Page.WaitForFunctionOptions().setTimeout(8000)
            )
          } catch { case _: Exception => () }
          response = page.navigate(url, navOpts)
        }

        Option(response).foreach { resp =>
          if (resp.status() >= 400)
            throw new RuntimeException(s"HTTP ${resp.status()} for $url")
        }

        // Wait for images to load, but don't wait for network idle (analytics/chat never stop)
        try {
          page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(10000))
        } catch {
          case _: Exception => log.info("LPAnalyzer: load timeout for {}, proceeding with partial content", url)
        }

        // Akamai Bot Manager sets `_abck=...~-1~...` on first response
        // and mutates it to a solved form once its JS challenge runs.
        // If we start extracting before the cookie resolves, some SSR
        // backends (e.g. the PWA Lambda in front of SFCC) refuse
        // follow-up requests and return 502 upstream. Cheap to wait —
        // if the cookie isn't present the predicate is immediately
        // true.
        try {
          page.waitForFunction(
            "() => !document.cookie.match(/_abck=[^;]*~-1~/)",
            null,
            new Page.WaitForFunctionOptions().setTimeout(8000)
          )
        } catch {
          case _: Exception => log.info("LPAnalyzer: _abck unresolved after 8s for {}, proceeding", url)
        }

        // Force layout + lazy-load settle. Many sites apply CSS classes or
        // attach background-images via JS after LOAD, and IntersectionObserver
        // lazy loaders only fire once the element enters the viewport — a
        // single jump to the bottom SKIPS everything in the middle. Ported
        // from pekko-dast's navScroll (loop-until-stable + NETWORKIDLE wait)
        // with stepping added: walk DOWN one viewport at a time so the IO
        // fires for every element passed, waiting for NETWORKIDLE after each
        // step so the fetched images actually settle (not a fixed guess).
        // Stop once we've reached the bottom and the document stopped growing
        // (two consecutive stable reads), capped to bound the time. Then
        // scroll back to the top so getBoundingClientRect coords are from-top
        // and the hero screenshot below is above-the-fold.
        try {
          def docHeight(): Double = page.evaluate("() => document.documentElement.scrollHeight") match {
            case n: java.lang.Number => n.doubleValue()
            case _                   => 0.0
          }
          def viewport(): Double = page.evaluate("() => window.innerHeight") match {
            case n: java.lang.Number => n.doubleValue()
            case _                   => 800.0
          }
          val vp = viewport()
          val step = math.max(vp * 0.9, 400.0)
          var y = 0.0
          var lastH = -1.0
          var stable = 0
          var i = 0
          while (i < 12 && stable < 2) {
            y += step
            page.evaluate(s"() => window.scrollTo(0, ${y.toInt})")
            try page.waitForLoadState(
                LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(1200)
              )
            catch { case _: Exception => () }
            val h = docHeight()
            if (y >= h - vp && h <= lastH + 1) stable += 1 else stable = 0
            lastH = h
            i += 1
          }
          page.evaluate("() => new Promise(r => { window.scrollTo(0, 0); setTimeout(r, 200); })")
        } catch {
          case _: Exception => ()
        }

        // Early preview: the page has loaded + lazy-images settled and
        // we're scrolled back to the top, so a viewport screenshot now is
        // the recognisable above-the-fold hero. Hand it to the caller
        // before the slow extraction so a preview can render while the
        // rest of the analysis continues. Best-effort — never let a
        // screenshot failure derail the analysis.
        try {
          // Let web fonts finish loading so the screenshot paints the page's
          // real typography rather than fallback glyphs. Best-effort — cap the
          // wait so a never-resolving font load can't stall the preview.
          try
            page.evaluate(
              "() => Promise.race([document.fonts.ready, new Promise(r => setTimeout(r, 2000))])")
          catch { case _: Exception => () }
          val shot = page.screenshot(new Page.ScreenshotOptions()
            .setType(com.microsoft.playwright.options.ScreenshotType.PNG))
          if (shot != null && shot.nonEmpty) onScreenshot(shot)
        } catch {
          case e: Exception => log.info("LPAnalyzer: preview screenshot failed for {}: {}", url, e.getMessage)
        }

        // Run both extraction strategies and log comparison
        val headingResult = page.evaluate("extractSections('heading')")
        val imageResult = page.evaluate("extractSections('image')")
        val headingSections = parseSections(headingResult)
        val imageSections = parseSections(imageResult)

        def logSections(label: String, secs: Vector[LPSection]): Unit = {
          val totalImages = secs.map(_.images.size).sum
          log.info("{} strategy: {} sections, {} images", label, secs.size, totalImages)
          secs.zipWithIndex.foreach { case (s, i) =>
            val heading = if (s.heading.nonEmpty) s.heading.take(60) else "(no heading)"
            val textPreview = s.text.take(80).replace("\n", " ")
            val imgSummary = s.images.map(img => s"${img.width}x${img.height}").mkString(", ")
            log.info("  {} {}: [{}] \"{}\" — {} imgs [{}]",
              label, i + 1, heading, textPreview, s.images.size, imgSummary)
          }
        }

        logSections("HEADING", headingSections)
        logSections("IMAGE", imageSections)

        val sections = strategy match {
          case "image"   => imageSections
          case "heading" => headingSections
          case _         => // "auto": try heading, fall back to image if too few
            if (headingSections.size >= 3) headingSections
            else {
              log.info("Heading strategy found only {} sections, falling back to image strategy", headingSections.size)
              imageSections
            }
        }
        log.info("Using {} strategy — returning {} sections",
          if (strategy == "auto" || strategy == "heading") { if (sections eq headingSections) "heading" else "image" }
          else strategy,
          sections.size)

        // Sample the LP's dominant background + text colours so
        // the creative can use them verbatim — the creative is
        // presented as an extension of the LP, so colour scheme
        // must match.
        val dominantColor = scala.util.Try {
          val raw = page.evaluate("extractDominantColor()")
          Option(raw).map(_.toString).filter(_.nonEmpty)
        }.toOption.flatten
        val textColor = scala.util.Try {
          val raw = page.evaluate("extractTextColor()")
          Option(raw).map(_.toString).filter(_.nonEmpty)
        }.toOption.flatten
        log.info("LP colours: bg={}, text={}",
          dominantColor.getOrElse("(none)"), textColor.getOrElse("(none)"))

        // Brand palette + font faces — seed the creative's brand kit
        // so it matches the LP. Both extractors return JSON-string
        // arrays; parse defensively (default empty on any failure).
        def parseStringArray(js: String): Vector[String] =
          scala.util.Try {
            val raw = page.evaluate(js)
            Option(raw).map(_.toString).filter(_.nonEmpty) match {
              case Some(s) => s.parseJson.convertTo[Vector[String]]
              case None    => Vector.empty
            }
          }.toOption.getOrElse(Vector.empty)
        val palette = parseStringArray("extractPalette()")
        val fonts = parseStringArray("extractFonts()")
        log.info("LP brand kit: palette={}, fonts={}",
          if (palette.isEmpty) "(none)" else palette.mkString(","),
          if (fonts.isEmpty) "(none)" else fonts.mkString(","))

        // Canonical hero fallback: og:image / twitter:image / JSON-LD
        // Product.image. Used to replace image srcs that the CDN
        // rejected during load — those URLs will 502 again at the
        // consumer side, so we swap them for the page's own
        // canonical hero while we still have the DOM open.
        val ogImage = scala.util.Try {
          val raw = page.evaluate("extractOgImage()")
          Option(raw).map(_.toString).filter(_.nonEmpty)
        }.toOption.flatten

        val rewrittenSections =
          if (failedAssetUrls.isEmpty) sections
          else {
            var swapped = 0
            var dropped = 0
            val out = sections.map { sec =>
              val imgs = sec.images.flatMap { img =>
                if (!failedAssetUrls.contains(img.src)) Some(img)
                else ogImage match {
                  case Some(og) =>
                    swapped += 1
                    // Dims no longer apply since src changed — zero
                    // them so downstream knows to re-measure.
                    Some(img.copy(src = og, width = 0, height = 0))
                  case None =>
                    dropped += 1
                    None
                }
              }
              sec.copy(images = imgs)
            }
            log.info("LP asset rewrite: {} failed urls → {} swapped to og:image, {} dropped (og:image {})",
              failedAssetUrls.size, swapped, dropped,
              ogImage.getOrElse("(none)"))
            out
          }

        log.info("LPAnalyzer: captured bytes for {} image responses on {}",
          capturedImages.size, url)
        (LPAnalysisResult(url, rewrittenSections, dominantColor, textColor, palette, fonts), capturedImages.toMap)
      } finally {
        page.close()
      }
    } finally {
      context.close()
    }
  }

  /**
   * Fallback path: fetch `web.archive.org/web/2/<url>` via plain
   * Java HTTP and build a minimal LPAnalysisResult from the page's
   * og:image / og:title / og:description. Used when direct
   * navigation via Playwright is rejected (403/502 etc). Returns
   * None when the archive has no snapshot or the HTML parse yields
   * nothing usable.
   */
  private def archiveOrgFallback(targetUrl: String): Option[LPAnalysisResult] = {
    val archiveUrl = s"https://web.archive.org/web/2/$targetUrl"
    val client = java.net.http.HttpClient.newBuilder()
      .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
      .connectTimeout(java.time.Duration.ofSeconds(10))
      .build()
    val req = java.net.http.HttpRequest.newBuilder()
      .uri(java.net.URI.create(archiveUrl))
      .header("User-Agent", LPAnalyzer.UserAgent)
      .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
      .timeout(java.time.Duration.ofSeconds(15))
      .GET()
      .build()
    val resp = try client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
    catch {
      case e: Exception =>
        log.info("LPAnalyzer: archive.org fetch failed for {}: {}", targetUrl, e.getMessage)
        return None
    }
    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
      log.info("LPAnalyzer: archive.org returned {} for {}", resp.statusCode(), targetUrl)
      return None
    }
    val html = resp.body()
    val ogImage = extractMetaContent(html, "og:image")
      .orElse(extractMetaContent(html, "twitter:image"))
    val ogTitle = extractMetaContent(html, "og:title").orElse(extractTitleTag(html))
    val ogDescription = extractMetaContent(html, "og:description")
      .orElse(extractMetaContent(html, "description"))

    if (ogImage.isEmpty && ogTitle.isEmpty && ogDescription.isEmpty) None
    else {
      val images = ogImage.toVector.map(src => LPImage(src, 0, 0, ""))
      val section = LPSection(
        heading = ogTitle.getOrElse(""),
        text = ogDescription.getOrElse(""),
        images = images
      )
      log.info("LPAnalyzer: archive.org fallback hit for {} — title={} img={}",
        targetUrl, ogTitle.getOrElse("(none)"), ogImage.getOrElse("(none)"))
      Some(LPAnalysisResult(targetUrl, Vector(section), None, None))
    }
  }

  /**
   * Read a `<meta property="name" content="...">` (or `name="name"`)
   * from raw HTML without pulling in a full parser. Handles both
   * `property`/`name` attribute placements and attribute orderings.
   */
  private def extractMetaContent(html: String, prop: String): Option[String] = {
    val q = java.util.regex.Pattern.quote(prop)
    val contentFirst = s"""(?is)<meta[^>]+content\\s*=\\s*["']([^"']+)["'][^>]+(?:property|name)\\s*=\\s*["']$q["']""".r
    val nameFirst = s"""(?is)<meta[^>]+(?:property|name)\\s*=\\s*["']$q["'][^>]+content\\s*=\\s*["']([^"']+)["']""".r
    nameFirst.findFirstMatchIn(html).map(_.group(1).trim).filter(_.nonEmpty)
      .orElse(contentFirst.findFirstMatchIn(html).map(_.group(1).trim).filter(_.nonEmpty))
  }

  private def extractTitleTag(html: String): Option[String] = {
    val re = """(?is)<title[^>]*>([^<]+)</title>""".r
    re.findFirstMatchIn(html).map(_.group(1).trim).filter(_.nonEmpty)
  }

  private def parseSections(raw: Any): Vector[LPSection] = {
    raw match {
      case list: java.util.List[?] =>
        list.asScala.flatMap {
          case map: java.util.Map[?, ?] =>
            val m = map.asInstanceOf[java.util.Map[String, Any]]
            val heading = Option(m.get("heading")).map(_.toString).getOrElse("")
            val text = Option(m.get("text")).map(_.toString).getOrElse("")
            val images = Option(m.get("images")) match {
              case Some(imgList: java.util.List[?]) =>
                imgList.asScala.flatMap {
                  case imgMap: java.util.Map[?, ?] =>
                    val im = imgMap.asInstanceOf[java.util.Map[String, Any]]
                    Some(LPImage(
                      src = Option(im.get("src")).map(_.toString).getOrElse(""),
                      width = toInt(im.get("width")),
                      height = toInt(im.get("height")),
                      alt = Option(im.get("alt")).map(_.toString).getOrElse("")
                    ))
                  case _ => None
                }.toVector
              case _ => Vector.empty
            }
            Some(LPSection(heading, text, images))
          case _ => None
        }.toVector
      case _ => Vector.empty
    }
  }

  private def toInt(v: Any): Int = v match {
    case n: java.lang.Number => n.intValue()
    case s: String           => Try(s.toInt).getOrElse(0)
    case _                   => 0
  }

  /**
   * Render a collapsed banner image from pages JSON using Playwright.
   *
   * Creates a minimal HTML page with the expandable-magazine-banner web component,
   * renders it at the specified size, and returns a PNG screenshot.
   *
   * @param pagesJson JSON string of banner pages
   * @param width     banner width (default 300)
   * @param height    banner height (default 250)
   * @return PNG image bytes
   */
  def renderBanner(pagesJson: String, width: Int = 300, height: Int = 250): Future[Array[Byte]] =
    BrowserSessionPool.submit(browserPool) { browser =>
      val context = browser.newContext(
        new Browser.NewContextOptions()
          .setViewportSize(width + 40, height + 40) // slight padding
      )

      try {
        val page = context.newPage()
        // Bound every page op (notably addScriptTag fetching bannerScriptUrl
        // over the network) so a slow/unreachable banner script fails fast
        // instead of stalling the render — and the session — indefinitely.
        page.setDefaultTimeout(15000)
        try {
          // 1. Set minimal HTML shell with styles
          val shell = s"""<!DOCTYPE html>
            |<html><head><meta charset="UTF-8">
            |<style>* { margin: 0; padding: 0; } body { background: white; width: ${width}px; }</style>
            |</head><body><div id="root"></div></body></html>""".stripMargin
          page.setContent(shell, new com.microsoft.playwright.Page.SetContentOptions().setTimeout(10000))

          // 2. Load the web component over HTTP from bannerScriptUrl. Same URL
          // that publishers' browsers and the dashboard use — one artifact,
          // one origin. Playwright's Chromium fetches it like any <script>.
          page.addScriptTag(new com.microsoft.playwright.Page.AddScriptTagOptions()
            .setUrl(bannerScriptUrl))

          // 3. Create the custom element via JS (avoids HTML escaping issues with pages JSON)
          val escapedJson = pagesJson
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
          page.evaluate(s"""() => {
            |  const el = document.createElement('expandable-magazine-banner');
            |  el.setAttribute('width', '$width');
            |  el.setAttribute('height', '$height');
            |  el.setAttribute('pages', '${escapedJson}');
            |  el.style.cssText = 'display:block;width:${width}px;height:${height}px;';
            |  document.getElementById('root').appendChild(el);
            |}""".stripMargin)

          page.waitForTimeout(2000) // let component render + gradients settle

          // 4. Screenshot the page at banner coordinates
          page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions()
            .setType(com.microsoft.playwright.options.ScreenshotType.PNG)
            .setClip(0, 0, width.toDouble, height.toDouble))
        } finally {
          page.close()
        }
      } finally {
        context.close()
      }
    }

  /**
   * No-op — LPAnalyzer no longer owns Playwright resources; the pool
   * manages browser lifecycle. Kept for backward source compatibility.
   */
  def close(): Unit = ()
}
