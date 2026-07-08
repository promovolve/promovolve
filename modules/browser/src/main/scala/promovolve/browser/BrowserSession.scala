package promovolve.browser

import com.microsoft.playwright.*
import com.microsoft.playwright.options.{LoadState, WaitUntilState, Proxy as PlaywrightProxy}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, PostStop}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.Promise
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Random, Success, Try}

/** Single Playwright + Chromium instance pinned to one thread.
  *
  * Playwright Java's driver is single-threaded — all API calls must
  * originate from the thread that called `Playwright.create()`. We
  * satisfy that by hosting each `BrowserSession` on a `PinnedDispatcher`
  * (configured at `browser-session-dispatcher` in application.conf),
  * so the actor lifecycle and every `receiveMessage` callback execute
  * on the same thread.
  *
  * One session = one Chromium process. The system-wide
  * [[BrowserSessionPool]] keeps a small fixed number of sessions
  * (configured at `promovolve.browser.browser-pool.size`) and routes
  * `Render` requests across them. Per-crawl concurrency from
  * [[PlaywrightCrawler]] is now an in-flight cap against the shared
  * pool rather than a count of browsers.
  *
  * Lazy initialization: Playwright + Browser are launched on the
  * first `Render`, not at `Behaviors.setup`. Idle sessions cost
  * roughly nothing; only sessions actually serving traffic hold a
  * Chromium process.
  */
object BrowserSession {

  // -- Protocol --

  sealed trait Command

  /** Per-crawl options. Travel with each `Render` because a single
    * session serves multiple crawls (different domains/selectors). */
  final case class CrawlOpts(
      domain: String,
      hostRegex: String,
      clickSelector: Option[String],
  )

  /** Scrape one URL and reply with [[PageScrapedResult]]. */
  final case class Render(
      url: String,
      targetElements: Array[String],
      depth: Int,
      opts: CrawlOpts,
      replyTo: ActorRef[PageScrapedResult],
      attempt: Int = 0,
  ) extends Command

  /** Run arbitrary `Browser => T` work on the session's pinned thread.
    * The function executes synchronously inside the actor's receive
    * (already on the pinned thread, satisfying Playwright Java's
    * single-thread invariant) and the result is delivered via the
    * supplied Promise. Encoded with existential `Any` because Pekko
    * Typed sealed traits cannot be polymorphic — call the typed
    * helper [[BrowserSessionPool.submit]] rather than constructing
    * this directly. */
  final case class Submit(
      work: Browser => Any,
      promise: Promise[Any],
  ) extends Command

  /** Eagerly launch Playwright + Chromium now, instead of on the first
    * Render/Submit. Sent once at pool startup when warm-on-start is enabled
    * so the first real crawl/LP job doesn't pay the cold Chromium launch.
    * Best-effort: a launch failure (e.g. Chromium not installed locally) is
    * logged and the session simply falls back to lazy init on first use. */
  case object Warmup extends Command

  /** Graceful shutdown (closes Chromium). PostStop also runs cleanup. */
  case object Stop extends Command

  // -- Replies --

  /** Crawl-time position/visibility signals for a detected slot.
    * Feeds into the per-slot floor CPM prior (see SiteEntity.SlotPrior). */
  final case class SlotPositionSignals(
      yTop: Int,                      // absolute y from document top, px
      docHeight: Int,                 // document scroll height, px
      aboveFold: Boolean,             // intersects initial viewport
      initialViewability: Double,     // 0..1, slot area in initial viewport
      region: String,                 // article|main|aside|footer|header|nav|unknown
      textDensity: Double,            // 0..1, nearby text-block density
  )

  final case class DetectedSlot(
      slotId: String,
      width: Int,
      height: Int,
      position: Option[SlotPositionSignals] = None,
  )

  final case class PageScrapedResult(
      url: String,
      contents: Seq[String],
      links: Seq[PageLink],
      detectedSlots: Seq[DetectedSlot],
      depth: Int,
      status: Int,
  ) extends promovolve.CborSerializable

  /** A discovered link: target URL + anchor text. Was a `(String, String)`
    * tuple; promoted to a case class so it serializes cleanly across the
    * cluster (the crawl now runs on dedicated crawler nodes). */
  final case class PageLink(url: String, text: String)

  /** Non-HTML response (PDF, image, etc.) — terminal failure, no retry. */
  private[browser] class NonHtmlContentException(message: String) extends RuntimeException(message)

  // -- Settings --

  final case class Settings(
      contextRotationEvery: Int = 5,
      navigationTimeoutMs: Int  = 15000,
      maxRetries: Int           = 5,
  )

  // -- Behavior --

  def apply(
      sessionId: Int,
      proxyConfig: Option[ProxyProviderConf],
      settings: Settings = Settings(),
  ): Behavior[Command] = Behaviors.setup { ctx =>
    val log = LoggerFactory.getLogger(s"promovolve.BrowserSession.$sessionId")

    // All of these are accessed only on the pinned thread.
    var playwright: Playwright       = null
    var browser: Browser             = null
    var browserContext: BrowserContext = null
    var stealthScriptPath: Path      = null
    var initScriptPath: Path         = null
    var successCount: Int            = 0

    def resolveResource(resourcePath: String, prefix: String): Path = {
      val stream = Option(getClass.getResourceAsStream(resourcePath))
        .getOrElse(throw new IllegalArgumentException(s"Resource not found: $resourcePath"))
      try {
        val content = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
        val tmp = Files.createTempFile(prefix, ".js")
        tmp.toFile.deleteOnExit()
        Files.writeString(tmp, content)
        tmp
      } finally stream.close()
    }

    def newContext(): BrowserContext = {
      val opts = new Browser.NewContextOptions()
        .setUserAgent(
          // Match a current real Chrome — bot managers flag stale majors.
          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )
        .setViewportSize(1280, 800)
        .setLocale("en-US")
        .setTimezoneId("America/New_York")
      val c = browser.newContext(opts)
      // Stealth must run before crawler init: addInitScript fires in
      // registration order on every navigation.
      c.addInitScript(stealthScriptPath)
      c.addInitScript(initScriptPath)
      c
    }

    def ensureBrowser(): Unit = {
      if (playwright == null) {
        log.info("Initializing Playwright + Chromium (proxy={})",
          proxyConfig.map(_.provider).getOrElse("none"))
        playwright = Playwright.create()
        // Stealth launch profile shared across crawl + LP work.
        //   --disable-blink-features=AutomationControlled strips the
        //   `navigator.webdriver=true` signal that bot managers read
        //   before deeper fingerprinting.
        //   --headless=new uses the new headless Chrome mode (full
        //   Chrome binary, much harder to fingerprint than the
        //   legacy headless-shell).
        //   --disable-features=IsolateOrigins,site-per-process keeps
        //   site isolation off so cross-frame access works for SPA
        //   shells that span subdomains.
        //   Strip --enable-automation from defaults — it leaks CDP.
        // In a container (CHROMIUM_NO_SANDBOX=true) Chromium can't use
        // its namespace sandbox as root, and /dev/shm is tiny — add the
        // two flags that make headless Chrome start there. Off by
        // default so local/dev launches keep the hardened profile.
        val baseArgs = {
          val core = new java.util.ArrayList[String](java.util.List.of(
            "--disable-blink-features=AutomationControlled",
            "--disable-features=IsolateOrigins,site-per-process",
            "--headless=new",
          ))
          if (sys.env.get("CHROMIUM_NO_SANDBOX").contains("true")) {
            core.add("--no-sandbox")
            core.add("--disable-dev-shm-usage")
          }
          core
        }
        val launchOpts = new BrowserType.LaunchOptions()
          .setHeadless(true)
          .setArgs(baseArgs)
          .setIgnoreDefaultArgs(java.util.List.of("--enable-automation"))
        proxyConfig.foreach { p =>
          launchOpts.setProxy(
            new PlaywrightProxy(p.server)
              .setUsername(p.username).setPassword(p.password),
          )
        }
        browser = playwright.chromium().launch(launchOpts)
        stealthScriptPath = resolveResource("/stealth.js", "stealth-")
        initScriptPath    = resolveResource("/crawler.js", "crawler-init-")
        browserContext    = newContext()
      }
    }

    def rotateContextIfNeeded(): Unit = {
      successCount += 1
      if (successCount >= settings.contextRotationEvery) {
        log.info("Rotating BrowserContext after {} successful scrapes", successCount)
        try browserContext.close() catch { case _: Exception => }
        browserContext = newContext()
        successCount = 0
      }
    }

    def shutdown(): Unit = {
      log.info("BrowserSession {} closing Chromium", sessionId)
      if (browserContext != null) try browserContext.close() catch { case _: Exception => }
      if (browser != null)        try browser.close()        catch { case _: Exception => }
      if (playwright != null)     try playwright.close()     catch { case _: Exception => }
    }

    Behaviors.withTimers[Command] { timers =>
      Behaviors.receiveMessage[Command] {
        case r @ Render(url, targetElements, depth, opts, replyTo, attempt) =>
          ensureBrowser()
          browserContext.clearCookies()
          val page = browserContext.newPage()

          val domainRegex = s"(?:https?:\\/\\/(?:.+\\.)?${opts.domain.replace(".", "\\.")})?"
          val blockedTypes: Set[String] = {
            val base = Set("image", "font", "media")
            if (opts.clickSelector.isDefined) base else base + "stylesheet"
          }

          Try {
            page.route(
              "**",
              route => {
                val req = route.request()
                val resourceType = req.resourceType()
                val rurl = req.url()
                val isPromovolveAd =
                  rurl.contains("promovolve-bootstrap") ||
                    rurl.contains("/v1/serve/") ||
                    rurl.contains("/v1/imp") ||
                    rurl.contains("/v1/click") ||
                    rurl.contains("/v1/cta") ||
                    rurl.contains("/v1/dogear-event")
                if (blockedTypes.contains(resourceType) || isPromovolveAd) route.abort()
                else route.resume()
              },
            )

            val response = page.navigate(
              url,
              new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(settings.navigationTimeoutMs),
            )
            val resp = Option(response)
              .getOrElse(throw new RuntimeException(s"No response received for $url"))
            if (resp.status() >= 400)
              throw new RuntimeException(
                s"Bad response status ${resp.status()} for $url: ${resp.statusText()}",
              )

            val contentType = Option(resp.headerValue("content-type")).getOrElse("")
            if (!contentType.contains("text/html") && !contentType.contains("application/xhtml"))
              throw new NonHtmlContentException(
                s"Non-HTML content-type for $url: $contentType",
              )

            page.waitForLoadState(LoadState.DOMCONTENTLOADED)
            opts.clickSelector.foreach { selector =>
              Option(page.querySelector(selector))
                .foreach(el => if (el.isVisible) el.click())
            }
            targetElements.map(target => extractTextAndLinks(page, domainRegex + opts.hostRegex, target))
          } match {
            case Success(results) =>
              val detectedSlots = extractSlots(page)
              page.close()
              val texts: Seq[String] = results.map(_._1).toSeq
              val links: Seq[PageLink] = results.flatMap(_._2).toSeq
              replyTo ! PageScrapedResult(url, texts, links, detectedSlots, depth, 1)
              rotateContextIfNeeded()
              Behaviors.same

            case Failure(_: NonHtmlContentException) =>
              page.close()
              replyTo ! PageScrapedResult(url, Seq.empty, Seq.empty, Seq.empty, depth, 0)
              Behaviors.same

            case Failure(e) =>
              page.close()
              log.warn("Error processing {}: {}", url, e.getMessage)
              if (attempt >= settings.maxRetries) {
                log.warn("Max retries reached for {}. Giving up.", url)
                replyTo ! PageScrapedResult(url, Seq.empty, Seq.empty, Seq.empty, depth, 0)
              } else {
                val delay = (Random.nextInt(5) + 1).seconds
                timers.startSingleTimer(r.copy(attempt = attempt + 1), delay)
              }
              Behaviors.same
          }

        case Submit(work, promise) =>
          ensureBrowser()
          // Runs on the actor's pinned thread → satisfies Playwright's
          // single-thread invariant. Caller's `work` owns its context
          // and page lifecycle; the session lends only the Browser.
          try {
            val result = work(browser)
            promise.success(result)
          } catch {
            case t: Throwable =>
              log.warn("Submit work failed: {}", t.getMessage)
              promise.failure(t)
          }
          Behaviors.same

        case Warmup =>
          // Pre-launch Chromium so the first real job is warm. Never crash
          // the session on a warmup failure — fall back to lazy init.
          try ensureBrowser()
          catch {
            case t: Throwable =>
              log.warn("BrowserSession {} warmup failed ({}); will init lazily on first use",
                sessionId, t.getMessage)
          }
          Behaviors.same

        case Stop =>
          Behaviors.stopped
      }.receiveSignal {
        case (_, PostStop) =>
          shutdown()
          Behaviors.same
      }
    }
  }

  // -- Page-script helpers (run on the page; safe to call from the pinned thread). --

  private def extractTextAndLinks(
      page: Page,
      regexString: String,
      target: String,
  ): (String, Seq[PageLink]) = {
    val result = page.evaluate("extractContent", Array(regexString, target))
    val resultMap = result.asInstanceOf[java.util.Map[String, Any]].asScala
    val text = resultMap("text").toString
    val links = resultMap("links")
      .asInstanceOf[java.util.List[java.util.Map[String, Any]]].asScala
      .map { linkObj =>
        val m = linkObj.asScala
        PageLink(m("href").toString, m("text").toString)
      }
      .toSeq
    (text, links)
  }

  private def extractSlots(page: Page): Seq[DetectedSlot] = {
    val result = page.evaluate("extractSlots")
    result.asInstanceOf[java.util.List[java.util.Map[String, Any]]].asScala
      .flatMap { slotObj =>
        val m = slotObj.asScala
        for {
          slotId <- m.get("slotId").map(_.toString).filter(_.nonEmpty)
          width  <- m.get("width").map(_.toString.toDouble.toInt).filter(_ > 0)
          height <- m.get("height").map(_.toString.toDouble.toInt).filter(_ > 0)
        } yield {
          // Position signals are best-effort; if any required field is
          // missing or unparseable we drop the whole block rather than
          // mixing partial data into the prior. JS will normally emit
          // all of these, but resilience matters when crawling pages
          // with hostile layouts or fault-injected JS.
          val position = for {
            yTop      <- m.get("yTop").map(_.toString.toDouble.toInt)
            docHeight <- m.get("docHeight").map(_.toString.toDouble.toInt).filter(_ > 0)
            aboveFold <- m.get("aboveFold").map(_.toString.toBoolean)
            viewability <- m.get("initialViewability").map(_.toString.toDouble)
            region    <- m.get("region").map(_.toString).filter(_.nonEmpty)
            density   <- m.get("textDensity").map(_.toString.toDouble)
          } yield SlotPositionSignals(
            yTop = yTop,
            docHeight = docHeight,
            aboveFold = aboveFold,
            initialViewability = viewability.max(0.0).min(1.0),
            region = region,
            textDensity = density.max(0.0).min(1.0),
          )
          DetectedSlot(slotId, width, height, position)
        }
      }
      .toSeq
  }
}
