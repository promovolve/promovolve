package promovolve.creative

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import scala.concurrent.{ ExecutionContext, Future }

/** Page content for the expandable magazine banner web component. */
final case class BannerPage(
    tag: String, // FEATURE, EXPERIENCE, PLAN
    headline: String,
    sub: String,
    body: String,
    accent: String, // hex color
    bg: String, // CSS linear-gradient
    imgEmoji: String, // single emoji (fallback when no image)
    caption: String,
    img: Option[String] = None, // CDN URL of extracted/uploaded image
    layout: Option[JsValue] = None, // editor-authored expanded layout (opaque JSON)
    banners: Option[JsValue] = None, // per-banner-size layouts (opaque JSON)
    designAspect: Option[String] = None, // e.g. "16/9"
    videoBg: Option[JsValue] = None, // full-bleed video background (opaque JSON)
    textureBg: Option[JsValue] = None // full-bleed image texture background (opaque JSON)
)

object BannerPage {
  given RootJsonFormat[BannerPage] = new RootJsonFormat[BannerPage] {
    def write(p: BannerPage): JsValue = {
      val fields = Map(
        "tag" -> JsString(p.tag),
        "headline" -> JsString(p.headline),
        "sub" -> JsString(p.sub),
        "body" -> JsString(p.body),
        "accent" -> JsString(p.accent),
        "bg" -> JsString(p.bg),
        "imgEmoji" -> JsString(p.imgEmoji),
        "caption" -> JsString(p.caption)
      ) ++
        p.img.map("img" -> JsString(_)) ++
        p.layout.map("layout" -> _) ++
        p.banners.map("banners" -> _) ++
        p.designAspect.map("designAspect" -> JsString(_)) ++
        p.videoBg.map("videoBg" -> _) ++
        p.textureBg.map("textureBg" -> _)
      JsObject(fields)
    }
    def read(v: JsValue): BannerPage = {
      val o = v.asJsObject.fields
      def str(key: String, fallbacks: String*): String =
        o.get(key).map(_.convertTo[String])
          .orElse(fallbacks.flatMap(k => o.get(k).map(_.convertTo[String])).headOption)
          .getOrElse("")
      BannerPage(
        tag = str("tag"),
        headline = str("headline"),
        sub = str("sub", "subtitle", "subheadline"),
        body = str("body", "text", "description"),
        accent = str("accent", "accentColor"),
        bg = str("bg", "background"),
        imgEmoji = str("imgEmoji", "emoji"),
        caption = str("caption"),
        img = o.get("img").orElse(o.get("image")).orElse(o.get("imageUrl")).collect {
          case JsString(s) if s.nonEmpty => s
        },
        layout = o.get("layout"),
        banners = o.get("banners"),
        designAspect = o.get("designAspect").collect { case JsString(s) => s },
        videoBg = o.get("videoBg"),
        textureBg = o.get("textureBg")
      )
    }
  }
}

/**
 * One page's role within a persuasion arc: the tag it carries and the
 * instruction synthesis must follow for that page.
 */
final case class ArcRole(tag: String, instruction: String)

/**
 * A persuasion arc the advertiser chooses for the booklet. Each of the 3
 * pages gets a SPECIFIC job, so synthesis writes purposeful copy (e.g. hook,
 * then proof, then call) instead of three arbitrary blurbs. This is the
 * authoritative source for the synthesis prompt; the Compose picker mirrors
 * the ids/labels. All arcs are oriented to drive the reader to the LP.
 */
final case class CreativeArc(id: String, label: String, roles: Vector[ArcRole])

object CreativeArc {
  val all: Vector[CreativeArc] = Vector(
    CreativeArc("hook-proof-call", "Hook → Proof → Call",
      Vector(
        ArcRole("HOOK",
          "Stop the scroll. One sharp, specific reason to care — a tension, a " +
          "surprising fact, or a curiosity gap drawn from the strongest material. " +
          "Make the reader want to see more. Do NOT summarise the product or pitch " +
          "yet, and do NOT ask for any action."),
        ArcRole("PROOF",
          "Earn the interest the hook created. The concrete substance that backs it " +
          "up — real products, named features, numbers, specifics from the source. " +
          "This page builds desire with evidence. No new hook, no call to action."),
        ArcRole("CALL",
          "Get the click. Name the offer and what the reader will find on the " +
          "landing page, with a nudge to go look now. This is the ONLY page that " +
          "asks for the action. Keep the offer and terms grounded in the source.")
      )),
    CreativeArc("problem-solution-offer", "Problem → Solution → Offer",
      Vector(
        ArcRole("PROBLEM",
          "Name the pain the reader feels — concrete and specific, in the source's " +
          "framing. Make them feel understood. No solution or pitch yet, no action."),
        ArcRole("SOLUTION",
          "Show how it is solved — the approach and the key features or benefits " +
          "that resolve the problem, grounded in the source. No call to action."),
        ArcRole("OFFER",
          "Present the deal and invite the reader to the landing page — what they " +
          "get, the terms, why now. The only page that asks for the action.")
      )),
    CreativeArc("feature-story-plan", "Feature → Story → Plan",
      Vector(
        ArcRole("FEATURE",
          "Lead with the product itself — what it is and its standout quality, from " +
          "the source. Concrete, not hypey. No call to action."),
        ArcRole("STORY",
          "Convey the experience or context — how it is used, the moment or feeling, " +
          "grounded in the source's details. No call to action."),
        ArcRole("PLAN",
          "Present pricing, plans, or the next step and invite the reader to the " +
          "landing page. The only page that asks for the action.")
      )),
    CreativeArc("tease-reveal-invite", "Tease → Reveal → Invite",
      Vector(
        ArcRole("TEASE",
          "Open a curiosity gap — hint at something worth discovering without " +
          "explaining it. Intrigue over information. No pitch, no action."),
        ArcRole("REVEAL",
          "Pay off the tease — reveal the product or offer and its most compelling " +
          "specifics from the source. Build desire. No call to action."),
        ArcRole("INVITE",
          "Invite the reader to come see more on the landing page — warm and " +
          "low-pressure, with a clear reason to click now.")
      ))
  )
  val default: CreativeArc = all.head
  def byId(id: String): CreativeArc = all.find(_.id == id).getOrElse(default)
}

/**
 * Rewrites pre-extracted LP sections into magazine-style banner pages via Gemini.
 *
 * This class handles ONLY the copy generation step. Section extraction (with images)
 * is done by LPAnalyzer (Playwright-based DOM extraction).
 */
final class LPExtractor(
    geminiApiKey: String,
    model: String = "gemini-2.5-flash",
    // Layout generation uses Gemini 3.1 Flash-Lite (preview) — newer
    // generation for slightly better structured output at a similar
    // cost to 2.5 Flash. Override with LAYOUT_MODEL env var.
    layoutModel: String = sys.env.getOrElse("LAYOUT_MODEL", "gemini-3.1-flash-lite-preview"),
    // Shared token bucket so LP extraction counts against the same
    // RPM budget as taxonomy classification and creative assessment.
    // None = ungated (only safe in isolated tests).
    rateLimiter: Option[org.apache.pekko.actor.typed.ActorRef[promovolve.GeminiRateLimiter.Command]] = None
)(using system: ActorSystem[?], ec: ExecutionContext) {

  private val log = LoggerFactory.getLogger(getClass)
  private val http = Http(system.toClassic)
  private given classicScheduler: org.apache.pekko.actor.Scheduler = system.toClassic.scheduler

  /**
   * Issue a Gemini request with the Vertex-aligned retry policy:
   * retry on 408/429/500/502/503/504 and on network-layer failures,
   * up to 5 attempts, capped exponential backoff with full jitter.
   * Reads the response body as a String and returns it with the
   * final status — callers decide how to parse the 2xx body and how
   * to surface non-2xx terminal responses.
   */
  private def callGeminiWithRetry(
      request: HttpRequest,
      tag: String,
      mdl: String
  ): Future[(StatusCode, String)] = {
    // Acquire a token before every attempt so each retry also counts
    // against the shared bucket — retries are real API calls and
    // skipping the gate on them would re-open the burst hole this
    // limiter exists to close.
    val op: () => Future[(StatusCode, String)] = () => {
      val gate: Future[Unit] = rateLimiter match {
        case Some(limiter) => promovolve.GeminiRateLimiter.acquire(limiter)
        case None          => Future.successful(())
      }
      gate.flatMap { _ =>
        val t0 = System.currentTimeMillis()
        http.singleRequest(request).flatMap { response =>
          Unmarshal(response.entity).to[String].map { body =>
            logGeminiResponse(tag, mdl, response.status.intValue, body, System.currentTimeMillis() - t0)
            (response.status, body)
          }
        }
      }
    }
    promovolve.llm.HttpRetryPolicy.withRetry[(StatusCode, String)](
      op = op,
      shouldRetry = { case (status, _) => promovolve.llm.HttpRetryPolicy.isRetryable(status) },
      onRetry = { (attempt, delay, outcome) =>
        val reason = outcome match {
          case Right((s, _)) => s"HTTP ${s.intValue()}"
          case Left(ex)      => s"exception ${ex.getClass.getSimpleName}: ${ex.getMessage}"
        }
        log.warn("[Gemini {}] {} — retrying in {} (attempt {}/5)", tag, reason, delay, attempt + 1)
      }
    )
  }

  // Full request/response logging toggled by GEMINI_LOG_FULL=1; otherwise
  // we log truncated previews (first/last 500 chars) to keep log noise
  // reasonable while still being useful for debugging prompt drift.
  private val logFull = sys.env.getOrElse("GEMINI_LOG_FULL", "0") == "1"
  private def preview(s: String, n: Int = 500): String =
    if (logFull || s.length <= n * 2) s
    else s.take(n) + s"\n…[${s.length - n * 2} chars]…\n" + s.takeRight(n)

  private def logGeminiRequest(tag: String, mdl: String, prompt: String): Unit =
    log.info("[Gemini {}] → {} ({} chars prompt)\n{}", tag, mdl, prompt.length, preview(prompt))

  private def logGeminiResponse(tag: String, mdl: String, status: Int, body: String, elapsedMs: Long): Unit =
    log.info("[Gemini {}] ← {} status={} {}ms ({} chars)\n{}", tag, mdl, status, elapsedMs, body.length, preview(body))

  private val rewritePrompt: String =
    """You are a magazine editor rewriting landing-page content into a
      |premium expandable banner ad. Each input section becomes one page.
      |REWRITE — never copy verbatim.
      |
      |Language:
      |- Write in the SAME language as the source sections. Do not
      |  translate into English unless the source is already English.
      |- Match the source register (casual ↔ formal, journalistic ↔ ad).
      |
      |Voice:
      |- Sound like an editor, not a marketer. No buzzwords
      |  ("unlock", "elevate", "revolutionary", "seamless", "unleash",
      |  "transform", "journey", "game-changing", "cutting-edge").
      |- Concrete over abstract. Specific numbers, named things, places.
      |- Confident, not hypey. No exclamation points unless the source
      |  itself is exclamatory.
      |
      |Field length (characters):
      |- headline: 10-30 chars (roughly 3-5 words / 6-14 JP chars).
      |  Evocative, not literal. Brand-specific, not generic.
      |- sub: 20-60 chars. Complements the headline, adds one fact.
      |- body: 80-220 chars, 1-2 tight sentences. Concrete details,
      |  not generic praise.
      |- caption: 10-40 chars. Short image caption voice.
      |
      |Design tokens:
      |- accent: brand-appropriate hex. Vary sensibly per page.
      |- bg: dark elegant gradient, hue varied per page.
      |  Format: linear-gradient(165deg,#1a1a1a 0%,#2d2518 40%,#1a1612 100%)
      |- imgEmoji: one emoji that captures the mood. Creative, not
      |  literal (not a camera for "photography").
      |
      |Output: a JSON ARRAY of page objects, one per section, in order.
      |Schema per page:
      |  {"headline":"...","sub":"...","body":"...",
      |   "accent":"#hex","bg":"linear-gradient(...)","imgEmoji":"emoji",
      |   "caption":"..."}
      |
      |Return ONLY the raw JSON array. No markdown fences, no
      |explanation, no preamble.
      |
      |Sections:
      |""".stripMargin

  /** Rewrite pre-extracted sections into magazine-style banner pages. */
  def rewriteSections(sections: Vector[(String, String)]): Future[Vector[BannerPage]] = {
    val sectionText = sections.zipWithIndex.map { case ((heading, text), i) =>
      s"--- Section ${i + 1} ---\nHeading: $heading\nContent: $text"
    }.mkString("\n\n")
    callGemini(rewritePrompt + sectionText)
  }

  // Upper bound on the corpus fed to synthesis. The whole-page text is bigger
  // than a single section, but Gemini handles large context fine; this just
  // bounds cost/latency. Truncation is a soft loss — the model still has the
  // strongest material, which usually leads.
  private val MaxCorpusChars = 16000

  /**
   * SYNTHESIS prompt — distill the WHOLE landing page into a fixed-length
   * editorial booklet of ORIGINAL ad copy, never reproducing the source.
   * Unlike [[rewritePrompt]] (one page per section, which leaks raw text on
   * failure), this decouples the creative from the page's structure and asks
   * the model to PRODUCE copy, so there is no source sentence to fall back to.
   * Grounding is mandatory: invent the phrasing, never the facts, or the copy
   * fails the downstream LP verification.
   */
  private def synthesizePrompt(arc: CreativeArc, brief: Option[String]): String = {
    val pageCount = arc.roles.size
    val structure = arc.roles.zipWithIndex
      .map { case (r, i) => s"- Page ${i + 1} — ${r.tag}: ${r.instruction}" }
      .mkString("\n")
    val tagList = arc.roles.zipWithIndex
      .map { case (r, i) => s"""page ${i + 1} = "${r.tag}"""" }
      .mkString(", ")
    // Advertiser free-text brief: trusted guidance that can set tone/angle and
    // add facts about their own product. Plain string (no stripMargin) since it
    // is interpolated into the margined template below.
    val cleanBrief = brief.map(_.trim).filter(_.nonEmpty)
    val briefBlock = cleanBrief.map(b =>
      "\n\nADVERTISER BRIEF — follow this closely across ALL pages. It is from the " +
      "advertiser and may set the tone, choose the angle, or add facts about their " +
      "OWN product. Apply it as follows:\n" +
      "- Tone / angle / theme guidance (e.g. \"warm playful voice\", \"lead with " +
      "sustainability\") must shape EVERY page — not just one. Each page expresses " +
      "it through its own role.\n" +
      "- A single concrete fact or offer (e.g. \"mention free shipping\") goes on " +
      "the ONE page where it fits best; do not force it onto every page.\n" +
      "Honour the brief unless it contradicts the page; any facts it states may be " +
      "treated as true and used like the source:\n\"" + b + "\"").getOrElse("")
    val briefSrc = if (cleanBrief.isDefined) " or in the advertiser brief above" else ""
    s"""You are a magazine editor creating a premium $pageCount-page expandable
       |banner ad from a brand's landing page, designed to make the reader click
       |through to that page. You are given the FULL relevant text below.
       |SYNTHESIZE the strongest creative from it — do NOT reproduce, quote, or
       |lightly paraphrase the source. Every sentence must be ORIGINAL ad copy: if
       |a reader compared your output to the page, no sentence should match.
       |
       |STRUCTURE — exactly $pageCount pages, each with a SPECIFIC role it MUST
       |fulfil. Write each page to do its job and nothing else:
       |$structure
       |A page that drifts from its role has failed — e.g. summarising the product
       |on the opening page, or asking for the action before the final page.
       |
       |Pull the best material for each page from ANYWHERE in the text; you are
       |NOT mirroring the page's section order or layout. Ignore navigation,
       |footers, cookie/consent notices, legal, and unrelated boilerplate.$briefBlock
       |
       |GROUNDING — the most important rule (validated to keep every cited
       |number verbatim-true to the source):
       |- Use ONLY facts that appear LITERALLY in the text below$briefSrc.
       |- Numbers, prices, percentages, dates, and proper names must be COPIED
       |  EXACTLY from the text. If a number is not in the text, do NOT state a
       |  number — make the point qualitatively instead ("trusted for years",
       |  not "20 years").
       |- Never estimate, round, infer, or invent a statistic. When unsure, omit.
       |- A fabricated fact fails downstream verification and is worse than a
       |  vaguer true one.
       |
       |Language:
       |- Write in the SAME language as the source. Do not translate to English
       |  unless the source is already English.
       |- Match the source register (casual ↔ formal, journalistic ↔ ad).
       |
       |Voice:
       |- Sound like an editor, not a marketer. No buzzwords ("unlock",
       |  "elevate", "revolutionary", "seamless", "unleash", "transform",
       |  "journey", "game-changing", "cutting-edge").
       |- Concrete over abstract. Specific numbers, named things, places.
       |- Confident, not hypey. No exclamation points unless the source is.
       |- Each page opens on a DIFFERENT angle — no two headlines sharing more
       |  than half their words.
       |
       |Field length (HARD upper limits — never exceed the max):
       |- headline: 10-30 chars (≈3-5 words / 6-14 JP chars). Evocative,
       |  brand-specific, not generic.
       |- sub: 20-60 chars. Complements the headline, adds one fact.
       |- body: 80-220 chars, 1-2 tight sentences. Concrete, not generic praise.
       |- caption: 10-40 chars. Short image-caption voice.
       |
       |Design tokens:
       |- accent: brand-appropriate hex. Vary sensibly per page.
       |- bg: dark elegant gradient, hue varied per page.
       |  Format: linear-gradient(165deg,#1a1a1a 0%,#2d2518 40%,#1a1612 100%)
       |- imgEmoji: one emoji that captures the mood. Creative, not literal.
       |
       |Output: a JSON ARRAY of EXACTLY $pageCount page objects, in the order
       |above. Each page MUST include its "tag" ($tagList). Schema per page:
       |  {"tag":"...","headline":"...","sub":"...","body":"...",
       |   "accent":"#hex","bg":"linear-gradient(...)","imgEmoji":"emoji",
       |   "caption":"..."}
       |
       |Return ONLY the raw JSON array. No markdown fences, no explanation.
       |
       |Landing-page text:
       |""".stripMargin
  }

  /**
   * Synthesize a fixed-length booklet from the WHOLE landing-page corpus
   * (all extracted section text concatenated) instead of one page per
   * section. Alternative to [[rewriteSections]] — produces original copy
   * grounded in the source and structurally cannot leak the source text.
   * Extraction already filters most boilerplate, so the concatenated section
   * bodies are a clean corpus to distill from.
   */
  def synthesizeSections(
      sections: Vector[(String, String)],
      arcId: String = CreativeArc.default.id,
      brief: Option[String] = None
  ): Future[Vector[BannerPage]] = {
    val arc = CreativeArc.byId(arcId)
    val corpus = sections
      .map { case (heading, text) =>
        if (heading.trim.nonEmpty) s"$heading\n$text" else text
      }
      .mkString("\n\n")
      .take(MaxCorpusChars)
    // Trailing reminder AFTER the corpus — empirically what kept every cited
    // number verbatim-grounded (recency: the constraint is the last thing the
    // model reads before generating).
    val reminder =
      "\n\nREMINDER: every number, price, percentage, date, and proper name " +
      "you write MUST appear verbatim in the landing-page text above. If it " +
      "does not, omit it and stay qualitative."
    callGemini(synthesizePrompt(arc, brief) + corpus + reminder)
  }

  /**
   * Ask Gemini which story arcs FIT this product, given its LP text, so the
   * picker offers only arcs that make sense (a Problem→Solution arc is wrong
   * for an indulgence product like sweets). Returns (best arc id, fitting arc
   * ids best-first, one-line reason). Falls back to ALL arcs on any failure so
   * the menu is never empty.
   */
  def recommendArcs(sections: Vector[(String, String)]): Future[(String, Vector[String], String)] = {
    val corpus = sections
      .map { case (heading, text) => if (heading.trim.nonEmpty) s"$heading\n$text" else text }
      .mkString("\n\n").take(MaxCorpusChars)
    val validIds = CreativeArc.all.map(_.id)
    val prompt =
      s"""You are choosing which ad "story arcs" fit a product, given its landing
         |page. Some arcs are clearly WRONG for some products — e.g. a
         |Problem→Solution arc makes no sense for an indulgence/desire product
         |(sweets, chocolate, beauty, perfume, fashion), which has no "problem" to
         |solve.
         |
         |Arcs — id: when it fits
         |- hook-proof-call: most products, especially where credibility or evidence persuades.
         |- problem-solution-offer: products that fix a clear pain (tools, services, B2B, health). NOT indulgence/desire products.
         |- feature-story-plan: showcase or lifestyle products where the experience matters.
         |- tease-reveal-invite: desire / brand / indulgence products (food, sweets, beauty, fashion, travel) sold on allure and curiosity.
         |
         |Keep only arcs that GENUINELY fit this product; drop ones that fight it.
         |Return ONLY raw JSON, no fences:
         |{"best":"<id>","fit":["<id>", ...best-first, only fitting arcs],"reason":"<one short sentence: why 'best' fits THIS product>"}
         |Valid ids: ${validIds.mkString(", ")}.
         |
         |Landing-page text:
         |$corpus
         |""".stripMargin
    callGeminiRaw(prompt, model, temperature = 0.3).map { js =>
      val o = js.asJsObject.fields
      def asStr(v: JsValue): Option[String] = v match { case JsString(s) => Some(s); case _ => None }
      val best = o.get("best").flatMap(asStr).filter(validIds.contains).getOrElse(CreativeArc.default.id)
      val fitRaw = o.get("fit") match {
        case Some(JsArray(a)) => a.flatMap(asStr).filter(validIds.contains).distinct
        case _                => Vector.empty
      }
      val fit = (if (fitRaw.contains(best)) fitRaw else best +: fitRaw) match {
        case Vector() => Vector(best)
        case v        => v
      }
      (best, fit, o.get("reason").flatMap(asStr).getOrElse(""))
    }.recover { case _ => (CreativeArc.default.id, CreativeArc.all.map(_.id), "") }
  }

  /**
   * Generate a layout descriptor (item positions, sizes, fonts, etc.) for a
   * banner page given its content and the target aspect ratio. Returned as
   * raw JSON; the caller converts to a typed model.
   *
   * `brandKitColors` and `templateSlotLine` are the LP-to-Creative pre-
   * generation choices threaded through from the dashboard pickers. Both
   * are advisory — Gemini's still free to deviate, but the prompt nudges
   * it toward the chosen palette and composition intent. None on either
   * means the field gets omitted from the prompt entirely (no "use these
   * colors: (none)" awkwardness).
   */
  def generateLayout(
      page: BannerPage,
      aspect: String, // "16/9" for expanded, "300/250" etc. for banner sizes
      mode: String, // "expanded" or "banner"
      brandKitColors: Vector[(String, String)] = Vector.empty,
      templateSlotLine: Option[String] = None
  ): Future[JsValue] = {
    val pageJson = page.toJson.compactPrint
    val brandLine =
      if (brandKitColors.isEmpty) ""
      else {
        val pairs = brandKitColors.map { case (name, value) => s"$name=$value" }.mkString(", ")
        s"\n\nBrand palette to favour: $pairs.\n" +
        "Use these colours for text fills, accent shapes, and CTA buttons " +
        "where they make sense; the page background remains as supplied."
      }
    val slotLine = templateSlotLine.filter(_.nonEmpty).map(s => s"\n\n$s").getOrElse("")
    val prompt = layoutPrompt(aspect, mode) +
      brandLine +
      slotLine +
      s"\n\nPage content:\n$pageJson\n\nReturn the layout JSON array:\n"
    // High temperature: this is the user-clickable Regenerate path,
    // so each call should yield a meaningfully different composition
    // rather than a tweaked copy of the previous output.
    callGeminiRaw(prompt, layoutModel, temperature = 0.95)
  }

  /**
   * Rewrite the page's tag/headline/sub/body to a fresh, faithful
   * phrasing. Anchored on `lpContext` (raw LP text snapshot captured
   * at extraction time) so the rewrite stays accurate to the source
   * — without it Gemini is free to invent claims, which fails LP
   * verification downstream. lpContext may be empty for direct-upload
   * creatives; in that case the prompt falls back to "rephrase the
   * existing copy faithfully without changing the meaning".
   *
   * Returns a raw JSON object the caller decodes into the four copy
   * fields. Temperature is high so back-to-back regenerates produce
   * meaningfully different phrasings, not near-duplicates.
   */
  def rewriteCopy(page: BannerPage, lpContext: String): Future[JsValue] = {
    val anchorBlock =
      if (lpContext.trim.nonEmpty)
        s"""LP source text (anchor — your rewrites must remain truthful
           |to this; do not invent claims absent here, but you SHOULD
           |draw on alternative angles, benefits, and phrasings present
           |in the source that the existing copy did not pick up):
           |$lpContext
           |""".stripMargin
      else
        "No LP source text available — generate plausible alternative phrasings that preserve the existing meaning. You may shift tone, voice, framing, and emphasis.\n"
    val currentJson = page.toJson.compactPrint
    val prompt =
      s"""You are an editor producing an ALTERNATIVE phrasing of one
         |page of a magazine-style banner ad. The page already has copy
         |in `tag`, `headline`, `sub`, `body`. The author has clicked
         |Regenerate because they want a meaningfully DIFFERENT draft —
         |not a paraphrase, not a synonym swap, but a new angle on the
         |same product. Two outputs that are 80%+ word-overlap with the
         |original have failed this task.
         |
         |Variation strategies (use one or combine):
         |- Lead with a different benefit (price → quality → speed → trust)
         |- Switch voice (declarative → question → command → testimonial)
         |- Switch register (formal → conversational → urgent → playful)
         |- Reframe the audience ("for X people" → "for the moments when…")
         |- Different sentence structure (statement → contrast → list)
         |The headline should not share more than half its words with
         |the original headline.
         |
         |Constraints:
         |- Stay truthful to the source: don't invent products, prices,
         |  or features. Stick to claims supported by the LP / existing copy.
         |- Match the language of the existing copy (Japanese stays
         |  Japanese, English stays English, etc.).
         |- Lengths roughly comparable to originals: don't balloon the
         |  headline into a paragraph or compress the body to two words.
         |- headline: short and punchy, the page's reason to exist.
         |- sub: one supporting sentence.
         |- body: 1-2 sentences expanding on the headline.
         |
         |Output:
         |- Return ONLY a raw JSON object with EXACTLY these three
         |  string keys at the TOP LEVEL: headline, sub, body.
         |- No wrapper object, no markdown fences, no commentary.
         |- All three keys are required (use empty string only if
         |  there is genuinely nothing to say for that field).
         |
         |$anchorBlock
         |Current copy (this is what to DIVERGE from — do not repeat):
         |$currentJson
         |
         |Return the JSON object now:
         |""".stripMargin
    callGeminiRaw(prompt, layoutModel, temperature = 1.0)
  }

  /**
   * Generate BOTH the PC 16:9 and Mobile 9:16 expanded layouts in one
   * Gemini call so the two variants read as a responsive pair — same
   * content, same hierarchy, reflowed for the aspect. Returns an
   * object with keys `pc` and `mobile`, each a layout array.
   */
  def generateLayoutPair(page: BannerPage): Future[JsValue] = {
    val pageJson = page.toJson.compactPrint
    val prompt = layoutPairPrompt +
      s"\n\nPage content:\n$pageJson\n\nReturn the JSON object:\n"
    callGeminiRaw(prompt, layoutModel)
  }

  private def layoutPrompt(aspect: String, mode: String): String = {
    // For "expanded" we need to distinguish PC (16:9 landscape) from
    // Mobile (9:16 portrait) — the prompt gates the composition. The
    // designer sends both as mode="expanded" because they share the
    // multi-page magazine surface; we differentiate by aspect ratio
    // here. Wide ⇒ side-by-side hero; tall ⇒ vertical stack.
    val modeDesc = mode match {
      case "banner" =>
        s"a COLLAPSED banner ad ($aspect). Tight placement. Only the most important elements — usually headline + small image. Body text often omitted entirely."
      case _ =>
        val (w, h) = aspect.split("/") match {
          case Array(a, b) => (a.trim.toIntOption.getOrElse(16), b.trim.toIntOption.getOrElse(9))
          case _           => (16, 9)
        }
        if (w >= h)
          s"the EXPANDED creative overlay ($aspect — landscape design surface). A magazine-style HERO composition with image and text arranged SIDE-BY-SIDE; lots of horizontal breathing room."
        else
          s"the EXPANDED creative overlay ($aspect — portrait design surface). A magazine-style VERTICAL STACK: image on top, headline/sub/body stacked below, content reading top-to-bottom. Do NOT lay out side-by-side; the canvas is too narrow."
    }
    s"""You are a senior graphic designer laying out content for $modeDesc
      |
      |Output a JSON ARRAY of layout items. Each item is an absolutely positioned element
      |inside a design container of the given aspect ratio. Positions and sizes are
      |percentages (0-100). The container is treated as a size-container; font-size is
      |interpreted as % of container height.
      |
      |Item schema (shared):
      |- type: "text" | "image" | "rect" | "circle"
      |- left, top: 0-100 (% of container width / height)
      |- width, height: 0-100 (% of container width / height). Omit for circle.
      |- rotation: degrees (optional, for decorative flair)
      |- opacity: 0..1 (optional, base opacity). Pair with animationTo.opacity=1 for a fade-in.
      |- animationTo: optional tween target. Object with any of:
      |    { left, top, rotation, scale, opacity, duration, delay, easing }
      |    Values are the END state; the item starts at its base state and
      |    animates to these over `duration` seconds after `delay` seconds.
      |    easing is any CSS timing-function ("linear", "ease-in-out", etc.);
      |    default is a smooth ease-out curve.
      |
      |Type-specific:
      |- text: text (string; the page's headline/sub/body literally),
      |    fontSize (% of container height; headlines 6-12, body 2.5-4),
      |    fontFamily ("sans-serif" | "serif" | "monospace" | "Georgia" | "Helvetica Neue"),
      |    fontWeight ("normal" | "bold"), textAlign ("left" | "center" | "right"),
      |    color (hex), textFit ("clip" | "shrink" — only when height is set).
      |- image: src (string URL, use page.img if present), borderRadius (% of container min dim).
      |- rect: fill (hex), stroke (hex).
      |- circle: radius (% of container min dim), fill, stroke.
      |
      |Rules:
      |- Use the page's own `headline`, `sub`, `body`, `img` values in the item text/src.
      |- Prefer legible layouts: generous breathing room, clear hierarchy.
      |- Ad placements like 728/90, 320/50 are tiny — keep items minimal and readable.
      |- Tall placements like 160/600 stack vertically; wide placements lay out horizontally.
      |- TEXT ITEMS MUST NOT OVERLAP each other. Each text bounding box
      |  (left, top, width, height-implied-from-fontSize) must be visually
      |  separated from every other text item — overlapping text becomes
      |  unreadable. The image item MAY sit behind text only when text
      |  has a contrasting background or sufficient color contrast.
      |- EVERY text color MUST contrast with page.bg. Dark backgrounds
      |  (rgb < 128 average) → light text (#ffffff for headlines,
      |  #d1d5db for sub, #9ca3af for body). Light backgrounds → dark
      |  text (#0a0a0b / #374151 / #6b7280). page.accent is for small
      |  decorative accents only; never use it for headline/sub/body. Same-color-as-bg
      |  text is invisible and a hard fail — the post-processor will
      |  override it but you should not produce it in the first place.
      |- Don't go outside 0-100.
      |- Include image item if page.img is non-empty. Leave out otherwise.
      |- Include the headline always. Include sub/body only if they fit.
      |  Do NOT add a category/tag eyebrow label or any CTA/"Read More"
      |  button — the whole ad is the link, and `tag` is an internal
      |  category, not creative copy. Headline + body + image only.
      |- Decorative rect/circle elements are optional; use sparingly.
      |- For entrance animations, set opacity=0 on the base and animationTo.opacity=1
      |  (optionally stagger via delay) so items fade in. Skip animationTo for
      |  static items to keep motion purposeful, not decorative.
      |
      |Composition variety — pick ONE archetype per call (don't default
      |to the same arrangement every time; an editor regenerating wants
      |a meaningfully DIFFERENT design, not a tweaked copy):
      |  Wide aspects (≥1:1):
      |    A. Image right 40-50%, text column left.
      |    B. Image left 40-50%, text column right.
      |    C. Image full-bleed background (left:0,top:0,width:100,height:100),
      |       text overlay anchored bottom-left or bottom-right; consider
      |       a translucent rect behind text for legibility.
      |    D. Image as small inset (top corner), oversized headline as
      |       the visual hero, body below.
      |    E. Text-only editorial: no image, oversized serif headline
      |       centered, sub + body stacked, decorative rect/circle accent.
      |  Tall aspects (<1:1):
      |    F. Image top 35-45%, text stacked below.
      |    G. Image full-bleed background, text stacked over (with rect
      |       backing for legibility).
      |    H. Text-first: small image at bottom, oversized headline up
      |       top, body in the middle.
      |    I. Image bottom 35-45%, text stacked above.
      |Vary fontFamily, fontWeight, textAlign, accent placement, and
      |rotation across calls so back-to-back regenerates feel genuinely
      |distinct.
      |
      |Return ONLY a raw JSON array of items. No markdown fences, no explanation.
      |""".stripMargin
  }

  private val layoutPairPrompt: String =
    """You are a senior graphic designer laying out ONE magazine-style
      |banner creative in TWO aspect ratios simultaneously:
      |  - "pc":     16:9 expanded overlay (landscape hero composition)
      |  - "mobile": 9:16 expanded overlay (portrait reflow of the same content)
      |
      |Treat the two layouts as ONE responsive creative — same content,
      |same visual hierarchy, same type scale intent — reflowed per
      |aspect. Rules:
      |  - Every element that appears in PC must also appear in Mobile
      |    (and only those). Same count of text items, same image count.
      |  - PC arranges side-by-side; Mobile stacks top-to-bottom.
      |    Image-beside-text in PC becomes image-above-text in Mobile.
      |  - Keep type scale intent — if headline is 10× body in PC, keep
      |    that ratio in Mobile even though the absolute numbers shift.
      |  - A user flipping between the two should recognize them as the
      |    same design, not two different creatives.
      |
      |Item schema (shared):
      |- type: "text" | "image" | "rect" | "circle"
      |- left, top: 0-100 (% of container width / height)
      |- width, height: 0-100 (% of container width / height). Omit for circle.
      |- rotation: degrees (optional)
      |- opacity: 0..1 (optional base opacity; pair with animationTo.opacity=1 for fade-in)
      |- animationTo: optional tween target — any of:
      |    { left, top, rotation, scale, opacity, duration, delay, easing }
      |
      |Type-specific fields:
      |- text: REFERENCE a page field by name via `field` instead of
      |    copying strings. Use field="headline" | "sub" | "body"
      |    so the renderer reads the live value and the copy can't drift.
      |    Never emit a literal `text` string (no labels, no buttons).
      |    Other text props: fontSize (% of container height; headlines
      |    6-12, body 2.5-4), fontFamily, fontWeight,
      |    textAlign, color, textFit,
      |    writingMode ("horizontal-tb" | "vertical-rl"),
      |    direction ("ltr" | "rtl").
      |  Language hints:
      |   - For decorative Japanese headlines/tags, consider writingMode="vertical-rl".
      |   - For Arabic/Hebrew/Farsi content, set direction="rtl" and
      |     prefer textAlign="right" so the layout reads correctly.
      |- image: src (page.img), borderRadius.
      |- rect: fill, stroke.
      |- circle: radius, fill, stroke.
      |
      |Rules:
      |- Use page's own `headline`, `sub`, `body`, `img` values LITERALLY.
      |- Always include the headline. Include sub/body only if they fit the aspect.
      |- Do NOT add a category/tag eyebrow label and do NOT add any CTA or
      |  "Read More" button: the whole ad is the link (a tap anywhere
      |  navigates), so a button is dead weight, and `tag` is an internal
      |  category, not creative copy. Headline + body + image only.
      |- Include image item if page.img is non-empty, otherwise omit.
      |- Legible layouts with breathing room. Don't go outside 0-100.
      |- PC (16:9): hero composition, room for all content side-by-side.
      |- Mobile (9:16): vertical stack, content reads top-to-bottom.
      |  HARD CONSTRAINTS for Mobile (the canvas is portrait; side-by-side
      |  compositions DO NOT FIT):
      |    * Image item: left=0 to 6, width=88 to 100, height ≤ 50.
      |      Image is a top banner that takes the full WIDTH of the canvas,
      |      NEVER half-width with text on the other half.
      |    * Every text item must sit BELOW the image (top > image.height).
      |      No text item may overlap horizontally with the image.
      |    * Text items also use the full text-column width (left≈6, width≈88).
      |    * If you find yourself writing `left: 50, width: 40` for a text
      |      item on Mobile, you're laying out side-by-side — STOP and stack.
      |    * SPECIAL CASE — Japanese tategaki: vertical-rl writing mode is
      |      tempting for headlines, but on Mobile it MUST still sit BELOW
      |      the image, NOT in a left/right column beside the image. Use
      |      writingMode="horizontal-tb" for Mobile Japanese headlines
      |      regardless of language. Save vertical-rl for landscape/PC
      |      where there's horizontal room to dedicate a column.
      |- Fade in with opacity=0 base + animationTo.opacity=1 for a subtle entrance;
      |  keep motion purposeful.
      |
      |Hard rule for text items:
      |- ALWAYS use `field` (headline / sub / body / caption) — never a
      |  literal `text` string. The renderer reads the live value at
      |  delivery; copying the string risks drift and truncation. Do not
      |  emit any literal `text` item — no "Read More", no eyebrow labels.
      |
      |Return ONLY a raw JSON object with keys "pc" and "mobile", each
      |an array of layout items. No markdown fences, no explanation.
      |
      |Worked example output shape (abbreviated):
      |{
      |  "pc": [
      |    { "type": "image", "src": "<page.img>", "left": 55, "top": 10, "width": 40, "height": 80 },
      |    { "type": "text",  "field": "headline", "left": 6, "top": 18, "width": 45, "fontSize": 10, "color": "#ffffff", "fontFamily": "Georgia", "fontWeight": "bold" },
      |    { "type": "text",  "field": "body",     "left": 6, "top": 56, "width": 45, "fontSize": 3,  "color": "#9ca3af" }
      |  ],
      |  "mobile": [
      |    { "type": "image", "src": "<page.img>", "left": 6, "top": 6, "width": 88, "height": 38 },
      |    { "type": "text",  "field": "headline", "left": 6, "top": 50, "width": 88, "fontSize": 7,   "color": "#ffffff", "fontFamily": "Georgia", "textAlign": "center", "fontWeight": "bold" },
      |    { "type": "text",  "field": "body",     "left": 6, "top": 74, "width": 88, "fontSize": 2.8, "color": "#9ca3af", "textAlign": "center" }
      |  ]
      |}
      |""".stripMargin

  private def callGemini(fullPrompt: String): Future[Vector[BannerPage]] = {
    val requestBody = JsObject(
      "contents" -> JsArray(JsObject(
        "parts" -> JsArray(JsObject("text" -> JsString(fullPrompt)))
      )),
      "generationConfig" -> JsObject(
        "temperature" -> JsNumber(0.7),
        "maxOutputTokens" -> JsNumber(8000)
      )
    ).compactPrint

    val apiUrl = s"https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$geminiApiKey"
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = apiUrl,
      entity = HttpEntity(ContentTypes.`application/json`, requestBody)
    )

    logGeminiRequest("callGemini", model, fullPrompt)
    callGeminiWithRetry(request, "callGemini", model).map { case (status, body) =>
      if (status.isFailure()) {
        throw new RuntimeException(
          s"Gemini callGemini failed: HTTP ${status.intValue()} after retries: ${body.take(500)}")
      }
      parseGeminiResponse(body)
    }
  }

  /**
   * Like callGemini, but returns the parsed JSON payload (array or object)
   * from Gemini's text response — caller decides how to type it.
   */
  private def callGeminiRaw(
      fullPrompt: String,
      overrideModel: String,
      // Default 0.5 keeps deterministic-ish behavior for boot-time
      // generation (rewriteSections etc.). The user-initiated
      // generateLayout overrides to 0.95 so back-to-back regenerates
      // actually diverge — same page content + same prompt at temp
      // 0.5 produces near-identical layouts.
      temperature: Double = 0.5
  ): Future[JsValue] = {
    val requestBody = JsObject(
      "contents" -> JsArray(JsObject(
        "parts" -> JsArray(JsObject("text" -> JsString(fullPrompt)))
      )),
      "generationConfig" -> JsObject(
        "temperature" -> JsNumber(temperature),
        "maxOutputTokens" -> JsNumber(8000)
      )
    ).compactPrint

    val apiUrl =
      s"https://generativelanguage.googleapis.com/v1beta/models/$overrideModel:generateContent?key=$geminiApiKey"
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = apiUrl,
      entity = HttpEntity(ContentTypes.`application/json`, requestBody)
    )

    logGeminiRequest("callGeminiRaw", overrideModel, fullPrompt)
    callGeminiWithRetry(request, "callGeminiRaw", overrideModel).map { case (status, body) =>
      if (status.isFailure()) {
        throw new RuntimeException(
          s"Gemini callGeminiRaw failed: HTTP ${status.intValue()} after retries: ${body.take(500)}")
      }
      val json = body.parseJson.asJsObject
      val text = json.fields("candidates")
        .asInstanceOf[JsArray].elements.head.asJsObject
        .fields("content").asJsObject
        .fields("parts")
        .asInstanceOf[JsArray].elements.head.asJsObject
        .fields("text").convertTo[String]
      val cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim
      cleaned.parseJson
    }
  }

  private def parseGeminiResponse(body: String): Vector[BannerPage] = {
    try {
      val json = body.parseJson.asJsObject
      val text = json.fields("candidates")
        .asInstanceOf[JsArray].elements.head.asJsObject
        .fields("content").asJsObject
        .fields("parts")
        .asInstanceOf[JsArray].elements.head.asJsObject
        .fields("text").convertTo[String]

      val cleaned = text
        .replaceAll("```json\\s*", "")
        .replaceAll("```\\s*", "")
        .trim

      cleaned.parseJson.convertTo[Vector[BannerPage]]
    } catch {
      case e: Exception =>
        log.error("Failed to parse Gemini response: {}", e.getMessage)
        throw new RuntimeException(s"Rewrite failed: ${e.getMessage}", e)
    }
  }
}
