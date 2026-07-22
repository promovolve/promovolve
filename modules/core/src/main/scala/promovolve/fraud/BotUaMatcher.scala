package promovolve.fraud

/**
 * User-agent hygiene (docs/design/FRAUD_PREVENTION.md, Layer 0).
 *
 * Catches self-declared automation: crawlers, HTTP libraries, and
 * headless browsers that say so. Deliberately conservative — a real
 * reader's in-app webview must never match, because a false positive
 * here silently poisons an honest publisher's stats. Adversaries who
 * fake a clean UA graduate to the ASN check and the economics layer;
 * this tier only needs to catch the ones that don't bother lying.
 */
object BotUaMatcher {

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
      case Some(ua)                       => Pattern.findFirstIn(ua).isDefined
    }
}
