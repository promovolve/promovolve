package promovolve.fraud

import spray.json.*

import scala.io.Source
import scala.util.Using

/**
 * User-agent hygiene (docs/design/FRAUD_PREVENTION.md, Layer 0).
 *
 * Catches self-declared automation: crawlers, HTTP libraries, and
 * headless browsers that say so. Deliberately conservative — a real
 * reader's in-app webview must never match, because a false positive
 * here silently poisons an honest publisher's stats. Adversaries who
 * fake a clean UA graduate to the ASN check and the economics layer;
 * this tier only needs to catch the ones that don't bother lying.
 *
 * Two sources, either one marks: the maintained crawler list
 * (resources/fraud/crawler-user-agents.json, vendored verbatim from
 * github.com/monperrus/crawler-user-agents, MIT — refresh by replacing the
 * file; its own example UAs are the regression test) and the hand-written
 * terms below, which keep the generic words and HTTP libraries the list
 * doesn't carry.
 */
object BotUaMatcher {

  /**
   * Upstream entries that also match real readers, so they are not used:
   * an Android build id (labelled 80legs, but every phone on that build
   * sends it), Viber's in-app browser (a reader, like LINE — its link
   * fetcher sends a bare "Viber", which the degenerate-UA rule catches)
   * and the VS Code editor.
   */
  private[fraud] val Excluded = Set("AP3A\\.240617\\.008", "Viber", "Code\\/1\\.")

  private[fraud] val MaintainedPatterns: Vector[String] = {
    val json =
      Using.resource(Source.fromResource("fraud/crawler-user-agents.json", getClass.getClassLoader))(_.mkString)
    json.parseJson
      .asInstanceOf[JsArray]
      .elements
      .flatMap(_.asJsObject.fields.get("pattern"))
      .collect { case JsString(p) if !Excluded(p) => p }
  }

  // Most entries are plain strings. Checking those with `contains` and
  // only the ~150 real regexes as one alternation takes a 240-char UA from
  // ~1ms to ~0.1ms. Matching stays case-sensitive, as the list's authors
  // wrote it.
  private val EscapedPunct = """\\([^A-Za-z0-9])""".r
  private val (literals, regexes) =
    MaintainedPatterns.partition(p => !EscapedPunct.replaceAllIn(p, "").exists("[](){}.*+?|^$\\".contains(_)))
  private val MaintainedLiterals =
    literals.map(p => EscapedPunct.replaceAllIn(p, m => scala.util.matching.Regex.quoteReplacement(m.group(1))))
  private val MaintainedRegex = regexes.mkString("|").r

  private val Pattern =
    ("(?i)(bot\\b|crawler|spider|scrape|headless|phantomjs|slimerjs|selenium|puppeteer|playwright" +
    "|curl/|wget/|python-requests|python-urllib|aiohttp|httpx/|go-http-client|okhttp|apache-httpclient" +
    "|java/|libwww|lighthouse|pagespeed|pingdom|uptimerobot|statuscake|site24x7|newrelicpinger" +
    "|facebookexternalhit|slackbot|twitterbot|discordbot|telegrambot|whatsapp|skypeuripreview" +
    "|googlebot|bingbot|yandex|baiduspider|duckduckbot|applebot|semrush|ahrefs|mj12bot|dotbot|petalbot)").r

  /**
   * True when the UA self-identifies as automation. Absent or
   * degenerate UAs count too: every real browser sends one, and the
   * cost of marking a UA-less request is one unbilled impression.
   */
  def isBot(userAgent: Option[String]): Boolean =
    userAgent match {
      case None                           => true
      case Some(ua) if ua.trim.length < 8 => true
      case Some(ua)                       => Pattern.findFirstIn(ua).isDefined || MaintainedLiterals.exists(ua.contains) ||
        MaintainedRegex.findFirstIn(ua).isDefined
    }
}
