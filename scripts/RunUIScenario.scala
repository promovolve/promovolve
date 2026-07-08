//> using scala 3.3.3
//> using dep com.softwaremill.sttp.client3::core:3.11.0

import sttp.client3._
import scala.util.Random

/**
 * RunUIScenario - Test script for campaigns created via the dashboard UI.
 *
 * Unlike RunScenario which creates its own test data, this script uses
 * existing campaigns that were set up through the UI.
 *
 * Usage:
 *   scala-cli scripts/RunUIScenario.scala -- --category IAB20
 *   scala-cli scripts/RunUIScenario.scala -- --category travel --requests 200
 *
 * The script will:
 *   1. Trigger page classification (auction) for the specified category
 *   2. Approve pending creatives (all campaigns targeting that category)
 *   3. Run traffic simulation
 *   4. Report impressions, clicks, CTR
 */
object RunUIScenario {

  case class Config(
      baseUrl: String = "http://localhost:8080",
      category: String = "",
      siteId: String = "",
      slotId: String = "slot-header",
      slotWidth: Int = 300,
      slotHeight: Int = 250,
      // Traffic config
      maxImpressions: Int = 100,
      reportEvery: Int = 20,
      clickRate: Double = 0.05,
      delayMs: Int = 50,
      continuous: Boolean = false
  )

  case class Counters(
      requests: Int = 0,
      selected: Int = 0,
      noContent: Int = 0,
      httpErrors: Int = 0,
      impressions: Int = 0,
      clicks: Int = 0,
      startTimeMs: Long = System.currentTimeMillis()
  ) {
    def successRate: Double = if (requests > 0) selected.toDouble / requests * 100 else 0
    def ctr: Double = if (impressions > 0) clicks.toDouble / impressions * 100 else 0
    def elapsedSec: Double = (System.currentTimeMillis() - startTimeMs) / 1000.0
  }

  case class ServeResponse(
      selected: Boolean,
      creativeId: String = "",
      campaignId: String = "",
      advertiserId: String = "",
      cpm: Double = 0,
      category: String = "",
      impUrl: String = "",
      clickUrl: String = ""
  )

  private val backend = HttpClientSyncBackend()
  private val rng = new Random()

  def main(args: Array[String]): Unit = {
    val config = parseArgs(args)

    if (config.category.isEmpty) {
      println("Error: --category is required")
      printHelp()
      sys.exit(1)
    }

    val runId = s"${System.currentTimeMillis() % 100000}"
    val finalConfig = config.copy(
      siteId = if (config.siteId.isEmpty) s"ui-test-$runId" else config.siteId
    )

    run(finalConfig, runId)
  }

  def run(config: Config, runId: String): Unit = {
    val pageUrl = s"https://publisher.com/ui-test-$runId"

    println(s"""
      |==========================================
      | RunUIScenario - Testing UI Campaigns
      |==========================================
      | Base URL:  ${config.baseUrl}
      | Category:  ${config.category}
      | Site ID:   ${config.siteId}
      | Slot:      ${config.slotId} (${config.slotWidth}x${config.slotHeight})
      | Page URL:  $pageUrl
      |==========================================
      |""".stripMargin)

    // Step 1: Trigger auction
    println("[1/3] Triggering auction (page classification)...")
    val classifyOk = triggerAuction(config, pageUrl)
    if (classifyOk) {
      println(s"       Auction triggered for category: ${config.category}")
      println("       (All active campaigns targeting this category will bid)")
    } else {
      println("       Warning: Classify request may have failed")
    }

    Thread.sleep(1000)

    // Step 2: Approve pending creatives
    println("\n[2/3] Approving pending creatives...")
    val (approved, failed) = approveAll(config, pageUrl)
    println(s"       Approved: $approved, Failed: $failed")

    // Check serve index
    Thread.sleep(500)
    val candidates = checkServeIndex(config, pageUrl)
    println(s"       ServeIndex: $candidates candidates ready")

    if (candidates == 0) {
      println("\n       No candidates to serve. Possible issues:")
      println("       - No campaigns targeting category '${config.category}'")
      println("       - Campaigns not active (activate in UI)")
      println("       - No creatives assigned to campaigns")
      println(s"       - Creative dimensions don't match slot (${config.slotWidth}x${config.slotHeight})")
      println("\n       Exiting")
      sys.exit(1)
    }

    // Step 3: Run traffic
    println("\n[3/3] Running traffic simulation...")
    println(s"""
      | Impressions: ${if (config.continuous) "continuous" else config.maxImpressions}
      | Delay:       ${config.delayMs}ms
      | Click rate:  ${(config.clickRate * 100).toInt}%
      |==========================================
      |""".stripMargin)

    runTraffic(config, pageUrl)
  }

  def triggerAuction(config: Config, pageUrl: String): Boolean = {
    val publisherId = "publisher-1"
    val response = basicRequest
      .post(uri"${config.baseUrl}/v1/publishers/$publisherId/sites/${config.siteId}/classify")
      .header("Content-Type", "application/json")
      .body(s"""{
        "url": "$pageUrl",
        "categories": {"${config.category}": 0.9},
        "slots": [{"slotId": "${config.slotId}", "width": ${config.slotWidth}, "height": ${config.slotHeight}}]
      }""")
      .send(backend)
    response.code.isSuccess
  }

  def approveAll(config: Config, pageUrl: String): (Int, Int) = {
    val publisherId = "publisher-1"
    val response = basicRequest
      .post(uri"${config.baseUrl}/v1/publishers/$publisherId/sites/${config.siteId}/approval/bulk")
      .header("Content-Type", "application/json")
      .body(s"""{
        "url": "$pageUrl",
        "slotId": "${config.slotId}"
      }""")
      .send(backend)

    if (response.code.isSuccess) {
      val body = response.body.getOrElse("")
      (extractInt(body, "approved"), extractInt(body, "failed"))
    } else {
      (0, 0)
    }
  }

  def checkServeIndex(config: Config, pageUrl: String): Int = {
    val publisherId = "publisher-1"
    val response = basicRequest
      .get(uri"${config.baseUrl}/v1/publishers/$publisherId/sites/${config.siteId}/serve-index?url=$pageUrl&slotId=${config.slotId}")
      .send(backend)

    if (response.code.isSuccess) {
      extractInt(response.body.getOrElse(""), "candidateCount")
    } else {
      0
    }
  }

  def runTraffic(config: Config, pageUrl: String): Unit = {
    var counters = Counters()
    // Track per creative: (impressions, clicks, campaignId, advertiserId)
    val perCreative = scala.collection.mutable.Map[String, (Int, Int, String, String)]()
    var lastReportedImps = 0

    var running = true
    while (running) {
      val response = requestServe(config, pageUrl)
      counters = counters.copy(requests = counters.requests + 1)

      response match {
        case None =>
          counters = counters.copy(httpErrors = counters.httpErrors + 1)

        case Some(resp) if !resp.selected =>
          counters = counters.copy(noContent = counters.noContent + 1)

        case Some(resp) =>
          counters = counters.copy(selected = counters.selected + 1)

          val impOk = trackImpression(resp)
          if (impOk) {
            counters = counters.copy(impressions = counters.impressions + 1)
            val (prevImps, prevClicks, _, _) = perCreative.getOrElse(resp.creativeId, (0, 0, resp.campaignId, resp.advertiserId))

            if (rng.nextDouble() < config.clickRate) {
              val clickOk = trackClick(resp)
              if (clickOk) {
                counters = counters.copy(clicks = counters.clicks + 1)
                perCreative(resp.creativeId) = (prevImps + 1, prevClicks + 1, resp.campaignId, resp.advertiserId)
              } else {
                perCreative(resp.creativeId) = (prevImps + 1, prevClicks, resp.campaignId, resp.advertiserId)
              }
            } else {
              perCreative(resp.creativeId) = (prevImps + 1, prevClicks, resp.campaignId, resp.advertiserId)
            }
          }
      }

      // Report based on impressions
      if (counters.impressions > 0 && counters.impressions % config.reportEvery == 0 &&
          counters.impressions != lastReportedImps) {
        printReport(counters, perCreative.toMap)
        lastReportedImps = counters.impressions
      }

      // Stop condition based on impressions
      if (!config.continuous && counters.impressions >= config.maxImpressions) {
        running = false
      }

      if (config.delayMs > 0) Thread.sleep(config.delayMs)
    }

    println()
    printReport(counters, perCreative.toMap)
  }

  def requestServe(config: Config, pageUrl: String): Option[ServeResponse] = {
    try {
      val resp = basicRequest
        .get(uri"${config.baseUrl}/v1/serve?pub=${config.siteId}&url=$pageUrl&slot=${config.slotId}")
        .send(backend)

      if (resp.code.code == 204) {
        return Some(ServeResponse(selected = false))
      }

      if (!resp.code.isSuccess) return None

      val body = resp.body.getOrElse("")
      val creativeId = extractField(body, "creativeId")
      val impUrl = extractField(body, "impUrl")
      val clickUrl = extractField(body, "clickUrl")

      val campaignId = extractUrlParam(impUrl, "camp")
      val advertiserId = extractUrlParam(impUrl, "adv")
      val cpm = extractUrlParam(impUrl, "cpm").toDoubleOption.getOrElse(0.0)
      val category = extractUrlParam(impUrl, "cat")

      Some(ServeResponse(
        selected = true,
        creativeId = creativeId,
        campaignId = campaignId,
        advertiserId = advertiserId,
        cpm = cpm,
        category = category,
        impUrl = impUrl,
        clickUrl = clickUrl
      ))
    } catch {
      case _: Exception => None
    }
  }

  def trackImpression(resp: ServeResponse): Boolean = {
    if (resp.impUrl.isEmpty) return false
    try {
      val r = basicRequest.get(uri"${resp.impUrl}").send(backend)
      r.code.code == 204 || r.code.isSuccess
    } catch {
      case _: Exception => false
    }
  }

  def trackClick(resp: ServeResponse): Boolean = {
    try {
      val r = basicRequest
        .get(uri"${resp.clickUrl}")
        .followRedirects(false)
        .send(backend)
      r.code.code == 302 || r.code.code == 204 || r.code.isSuccess
    } catch {
      case _: Exception => false
    }
  }

  def printReport(c: Counters, perCreative: Map[String, (Int, Int, String, String)]): Unit = {
    println(s"\n  --- Report @ ${c.requests} requests (${f"${c.elapsedSec}%.1f"}s) ---")
    println(f"    Selected:    ${c.selected}%5d (${c.successRate}%.1f%%)")
    println(f"    No Content:  ${c.noContent}%5d")
    println(f"    Errors:      ${c.httpErrors}%5d")
    println(f"    Impressions: ${c.impressions}%5d")
    println(f"    Clicks:      ${c.clicks}%5d (CTR: ${c.ctr}%.1f%%)")

    if (perCreative.nonEmpty) {
      println("\n    Per-Creative:")
      perCreative.toList.sortBy(-_._2._1).foreach { case (cid, (imps, clicks, campId, advId)) =>
        val ctr = if (imps > 0) clicks.toDouble / imps * 100 else 0
        val shortAdv = advId.takeRight(8)
        val shortCamp = campId.take(8)
        println(f"      $shortAdv:$shortCamp ${cid.take(12)}%-12s  $imps%5d imps  $clicks%4d clicks  CTR=${ctr}%.1f%%")
      }
    }
    println()
  }

  // ==================== Parsing ====================

  def parseArgs(args: Array[String]): Config = {
    var config = Config()
    var i = 0

    while (i < args.length) {
      args(i) match {
        case "--base-url" if i + 1 < args.length =>
          config = config.copy(baseUrl = args(i + 1))
          i += 2
        case "--category" | "--cat" | "-c" if i + 1 < args.length =>
          config = config.copy(category = args(i + 1))
          i += 2
        case "--site" | "-s" if i + 1 < args.length =>
          config = config.copy(siteId = args(i + 1))
          i += 2
        case "--slot" if i + 1 < args.length =>
          config = config.copy(slotId = args(i + 1))
          i += 2
        case "--slot-size" if i + 1 < args.length =>
          val parts = args(i + 1).split("x")
          if (parts.length == 2) {
            config = config.copy(slotWidth = parts(0).toInt, slotHeight = parts(1).toInt)
          }
          i += 2
        case "--impressions" | "-n" if i + 1 < args.length =>
          config = config.copy(maxImpressions = args(i + 1).toInt)
          i += 2
        case "--delay" if i + 1 < args.length =>
          config = config.copy(delayMs = args(i + 1).toInt)
          i += 2
        case "--click-rate" if i + 1 < args.length =>
          config = config.copy(clickRate = args(i + 1).toDouble)
          i += 2
        case "--report-every" if i + 1 < args.length =>
          config = config.copy(reportEvery = args(i + 1).toInt)
          i += 2
        case "--continuous" =>
          config = config.copy(continuous = true)
          i += 1
        case "--help" | "-h" =>
          printHelp()
          sys.exit(0)
        case _ =>
          i += 1
      }
    }
    config
  }

  def printHelp(): Unit = {
    println("""
      |RunUIScenario - Test campaigns created via the dashboard UI
      |
      |Usage:
      |  scala-cli scripts/RunUIScenario.scala -- --category <category>
      |
      |Required:
      |  --category, -c <cat>  Content category for auction (e.g., IAB20, travel)
      |
      |Optional:
      |  --site, -s <id>       Site ID (default: auto-generated)
      |  --slot <id>           Slot ID (default: slot-header)
      |  --slot-size WxH       Slot dimensions (default: 300x250)
      |  --requests, -n <n>    Number of requests (default: 100)
      |  --delay <ms>          Delay between requests (default: 50)
      |  --click-rate <r>      Click probability 0.0-1.0 (default: 0.05)
      |  --report-every <n>    Report stats every N requests (default: 20)
      |  --continuous          Run indefinitely
      |  --base-url <url>      API URL (default: http://localhost:8080)
      |
      |Examples:
      |  # Test campaigns targeting IAB20 (Travel) category
      |  scala-cli scripts/RunUIScenario.scala -- --category IAB20
      |
      |  # Run 200 requests with 10% click rate
      |  scala-cli scripts/RunUIScenario.scala -- -c travel -n 200 --click-rate 0.10
      |
      |  # Continuous mode with custom site
      |  scala-cli scripts/RunUIScenario.scala -- -c IAB20 --site my-site --continuous
      |""".stripMargin)
  }

  def extractField(json: String, field: String): String = {
    val pattern = s""""$field"\\s*:\\s*"([^"]+)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1)).getOrElse("")
  }

  def extractInt(json: String, field: String): Int = {
    val pattern = s""""$field"\\s*:\\s*([0-9]+)""".r
    pattern.findFirstMatchIn(json).map(_.group(1).toInt).getOrElse(0)
  }

  def extractUrlParam(url: String, param: String): String = {
    val pattern = s"[?&]$param=([^&]*)".r
    pattern.findFirstMatchIn(url).map(_.group(1)).getOrElse("")
  }
}
