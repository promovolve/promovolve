//> using scala 3.3.3
//> using dep com.softwaremill.sttp.client3::core:3.11.0
//> using dep org.apache.commons:commons-math3:3.6.1

import sttp.client3._
import scala.util.Random
import scala.io.Source
import java.io.File
import org.apache.commons.math3.distribution.BetaDistribution

/**
 * RunScenario - Unified test script for promovolve platform.
 *
 * Supports multiple test modes:
 *   - pacing (default): Budget pacing test with traffic shapes
 *   - auction: Basic Thompson Sampling feedback loop test
 *   - category-race: Multi-category Thompson Sampling race
 *   - creative-race: Multi-creative Thompson Sampling race
 *
 * Usage:
 *   scala-cli scripts/RunScenario.scala -- --scenario scenarios/continuous.json
 *   scala-cli scripts/RunScenario.scala -- --mode category-race --categories "sports:0.08,tech:0.05,gaming:0.02"
 *   scala-cli scripts/RunScenario.scala -- --mode creative-race --creatives "alpha:0.10,beta:0.05,gamma:0.02"
 *
 * The script will:
 *   1. Create advertisers, campaigns, creatives based on mode
 *   2. Trigger auctions and approve creatives
 *   3. Configure pacing (for pacing mode)
 *   4. Run traffic simulation with mode-specific behavior
 *   5. Report results including Thompson Sampling stats
 */
object RunScenario {

  // ---------- Race Mode Configs ----------

  /** Category configuration for category-race mode */
  case class CategoryConfig(name: String, trueCtr: Double)

  /** Creative configuration for creative-race mode */
  case class CreativeRaceConfig(name: String, trueCtr: Double)

  /** Creative info tracked during race */
  case class CreativeRaceInfo(
      advertiserId: String,
      campaignId: String,
      creativeId: String,
      name: String,
      trueCtr: Double
  )

  // Churn event: pause or resume an advertiser's campaigns at a specific simulated day
  case class ChurnEvent(day: Int, action: String, advertiser: Int)

  case class Config(
      baseUrl: String = "http://localhost:8080",
      siteId: String = "",
      // Setup config
      mode: String = "pacing",  // pacing, auction, category-race, creative-race
      advertisers: Int = 1,
      campaignsPerAdvertiser: Int = 1,
      creativesPerCampaign: Int = 3,
      category: String = "perf-test",
      budget: Double = 100.0,  // Budget per campaign
      advertiserBudget: Option[Double] = None,  // Override advertiser budget (default: sum of campaign budgets)
      cpm: Double = 5.0,
      cpms: Option[Array[Double]] = None, // Per-advertiser CPMs (cycles if fewer than advertisers)
      budgets: Option[Array[Double]] = None, // Per-advertiser campaign budgets (cycles if fewer than advertisers)
      slotId: String = "slot-header",
      dayDurationSeconds: Int = 86400,
      weekdayShapeVolumes: Option[Array[Double]] = None,  // 24 hourly values for weekday traffic shape
      weekendShapeVolumes: Option[Array[Double]] = None,  // 24 hourly values for weekend traffic shape
      // IAB Targeting config (adProductCategory is required by API)
      adProductCategory: String = "1529",                 // Required: IAB Ad Product ID (default: Travel)
      additionalCategories: List[String] = List.empty,    // Optional: Extra content categories for incomplete mappings
      runOfNetwork: Boolean = false,                      // Optional: Target all inventory regardless of category
      // Traffic config
      continuous: Boolean = false,
      maxRequests: Int = 1000,
      reportEvery: Int = 50,
      clickRate: Double = 0.05,
      delayMs: Int = 50,
      variableDelay: Boolean = false,
      maxDelayMs: Int = 500,
      // Quiet period config (for testing staleness detection)
      quietPeriodProbability: Double = 0.0,  // Probability of starting a quiet period after each request
      quietPeriodMinMs: Int = 35000,         // Minimum quiet period duration (above 30s staleness threshold)
      quietPeriodMaxMs: Int = 60000,         // Maximum quiet period duration
      categoryBias: Map[String, Double] = Map("gaming" -> 2.0, "tech" -> 1.0, "sports" -> 0.5),
      // Race mode configs
      categories: List[CategoryConfig] = List(
        CategoryConfig("sports", 0.08),
        CategoryConfig("tech", 0.05),
        CategoryConfig("gaming", 0.02)
      ),
      creatives: List[CreativeRaceConfig] = List(
        CreativeRaceConfig("alpha", 0.10),
        CreativeRaceConfig("beta", 0.05),
        CreativeRaceConfig("gamma", 0.02)
      ),
      thompsonSelection: Boolean = false,  // Use Thompson Sampling for selection in race modes
      randomClickRate: Option[Double] = None,  // Override per-item CTR with flat rate
      refreshIntervalSeconds: Int = 1800,  // Re-classify pages every 30 min to refresh ServeIndex TTL (default 2h)
      stopAfterDays: Int = 0,  // Stop after N simulated days (0 = don't stop based on days)
      churnEvents: List[ChurnEvent] = Nil  // Campaign pause/resume events at specific days
  ) {
    // Helper: returns traffic shape for the given simulated day (0-indexed)
    // Days 5,6 are weekend (matching server's simulated day-of-week progression)
    def trafficShapeForDay(day: Int): Option[Array[Double]] = {
      val dayOfWeek = day % 7
      val isWeekend = dayOfWeek >= 5
      if (isWeekend) weekendShapeVolumes.orElse(weekdayShapeVolumes)
      else weekdayShapeVolumes.orElse(weekendShapeVolumes)
    }
    // Default: weekday shape (for display/logging)
    def trafficShape: Option[Array[Double]] = weekdayShapeVolumes.orElse(weekendShapeVolumes)
  }

  case class Counters(
      requests: Int = 0,
      selected: Int = 0,
      pacingSkipped: Int = 0,
      httpErrors: Int = 0,
      impressions: Int = 0,
      clicks: Int = 0,
      spendDollars: Double = 0.0,
      startTimeMs: Long = System.currentTimeMillis()
  ) {
    def successRate: Double = if (requests > 0) selected.toDouble / requests * 100 else 0
    def ctr: Double = if (impressions > 0) clicks.toDouble / impressions * 100 else 0
    def elapsedSec: Double = (System.currentTimeMillis() - startTimeMs) / 1000.0
    def impressionsPerSec: Double = if (elapsedSec > 0) impressions / elapsedSec else 0
    def requestsPerSec: Double = if (elapsedSec > 0) requests / elapsedSec else 0
  }

  case class ServeResponse(
      selected: Boolean,
      creativeId: String = "",
      campaignId: String = "",
      advertiserId: String = "",
      cpm: Double = 0,
      category: String = "",
      requestId: String = "",
      impUrl: String = "",
      clickUrl: String = ""
  )

  case class SiteStats(
      selected: Long,
      pacingSkipped: Long,
      budgetExhausted: Long,
      noCandidates: Long,
      totalSpend: Double,
      elapsedHours: Double,
      expectedSpendFraction: Double,
      pacingNote: String,
      trafficShapeSummary: Option[String] = None
  )

  /** Campaign info for day start reset */
  case class CampaignInfo(advertiserId: String, campaignId: String)

  /** Setup result including page URLs and campaigns created */
  case class SetupResult(pageUrls: List[String], campaigns: List[CampaignInfo], categoryWithRunId: String)

  private val backend = HttpClientSyncBackend()
  private val rng = new Random()

  def main(args: Array[String]): Unit = {
    val config = parseArgs(args)
    // Include process ID and nanoseconds for guaranteed uniqueness across concurrent runs
    val pid = ProcessHandle.current().pid()
    val runId = s"${System.currentTimeMillis() % 100000}-$pid-${System.nanoTime() % 10000}"
    val finalConfig = config.copy(
      siteId = if (config.siteId.isEmpty) s"site-$runId" else config.siteId
    )

    // Dispatch based on mode
    finalConfig.mode match {
      case "pacing" | "auction" =>
        runPacingMode(finalConfig, runId)
      case "category-race" =>
        runCategoryRaceMode(finalConfig, runId)
      case "creative-race" =>
        runCreativeRaceMode(finalConfig, runId)
      case "restart-recovery" =>
        runRestartRecoveryMode(finalConfig, runId)
      case _ =>
        println(s"Unknown mode: ${finalConfig.mode}")
        sys.exit(1)
    }
  }

  // ==================== Pacing/Auction Mode ====================

  def runPacingMode(config: Config, runId: String): Unit = {
    // Phase 1: Setup
    val setupResult = runSetup(config, runId)

    if (setupResult.pageUrls.isEmpty) {
      println("Setup failed - no pages created")
      System.exit(1)
    }

    // Phase 2: Reset campaign day starts to NOW
    println("\n[6/6] Resetting campaign day starts...")
    val resetSuccess = resetCampaignDayStarts(config, setupResult.campaigns)
    if (resetSuccess) {
      println(s"       Campaign day starts reset to current time (${setupResult.campaigns.size} campaigns)")
    } else {
      println("       Warning: Failed to reset campaign day starts (continuing anyway)")
    }

    // Small delay to ensure reset propagates
    Thread.sleep(200)

    // Phase 3: Run traffic (time starts NOW)
    println("\n" + "=" * 50)
    println(" Starting Traffic Simulation")
    println("=" * 50)

    runTraffic(config, setupResult.pageUrls, setupResult.campaigns, setupResult.categoryWithRunId)
  }

  // ==================== Setup Phase ====================

  def runSetup(config: Config, runId: String): SetupResult = {
    val dayDurationDisplay = formatDuration(config.dayDurationSeconds)
    val totalCampaigns = config.advertisers * config.campaignsPerAdvertiser
    val totalCreatives = totalCampaigns * config.creativesPerCampaign
    val categoryWithRunId = s"${config.category}-$runId"

    val totalCampaignBudget = config.budget * totalCampaigns
    val effectiveAdvBudget = config.advertiserBudget.getOrElse(config.budget * config.campaignsPerAdvertiser)
    val advBudgetNote = config.advertiserBudget match {
      case Some(b) if b < totalCampaignBudget => s" ⚠️ shortage possible (campaigns want $$$totalCampaignBudget)"
      case Some(_) => " (override)"
      case None => ""
    }

    println(s"""
      |==========================================
      | RunScenario - Setup Phase
      |==========================================
      | Base URL:    ${config.baseUrl}
      | Site ID:     ${config.siteId}
      | Slot ID:     ${config.slotId}
      | Category:    ${config.category}
      | Advertisers: ${config.advertisers}
      | Campaigns:   ${config.campaignsPerAdvertiser} per advertiser ($totalCampaigns total)
      | Creatives:   ${config.creativesPerCampaign} per campaign ($totalCreatives total)
      | Budget:      ${config.budgets.map(arr => arr.map(b => f"$$$b%.2f").mkString(", ")).getOrElse(f"$$${config.budget}%.2f each")} ($$${totalCampaignBudget} total)
      | Adv Budget:  $$${effectiveAdvBudget} per advertiser$advBudgetNote
      | CPM:         ${config.cpms.map(arr => arr.map(c => f"$$$c%.2f").mkString(", ")).getOrElse(f"$$${config.cpm}%.2f")}
      | Day length:  $dayDurationDisplay (${config.dayDurationSeconds}s)
      |==========================================
      |""".stripMargin)

    val pageUrls = scala.collection.mutable.ListBuffer[String]()
    val campaigns = scala.collection.mutable.ListBuffer[CampaignInfo]()

    // 1. Create advertisers, campaigns, creatives
    println("[1/5] Creating advertisers and campaigns...")
    for (advNum <- 1 to config.advertisers) {
      val advId = s"adv-$advNum-$runId"

      // Per-advertiser budget: from budgets array, or advertiserBudget override, or default
      val campBudget = config.budgets.map(arr => arr((advNum - 1) % arr.length)).getOrElse(config.budget)
      val advBudget = config.advertiserBudget.getOrElse(campBudget * config.campaignsPerAdvertiser)
      basicRequest
        .put(uri"${config.baseUrl}/v1/advertisers/$advId/budget")
        .header("Content-Type", "application/json")
        .body(s"""{"dailyBudget": "$advBudget"}""")
        .send(backend)

      for (campNum <- 1 to config.campaignsPerAdvertiser) {
        val advCpm = config.cpms.map(arr => arr((advNum - 1) % arr.length))
        val campId = createCampaign(config, advId, categoryWithRunId, s"adv$advNum-camp$campNum", advCpm, Some(campBudget))

        // Track this campaign for later day start reset
        campaigns += CampaignInfo(advId, campId)

        for (creativeNum <- 1 to config.creativesPerCampaign) {
          createCreative(config, advId, campId, s"adv$advNum-camp$campNum-cr$creativeNum")
        }

        activateCampaign(config, advId, campId)
      }
      println(s"       Created: $advId (${config.campaignsPerAdvertiser} campaigns × ${config.creativesPerCampaign} creatives)")
    }

    Thread.sleep(500)

    // 1b. Force-verify site domain (bypass HTTP check for testing)
    println("\n[1b/5] Force-verifying site domain...")
    val publisherId = "publisher-1"
    val siteId = config.siteId
    val verifyResp = basicRequest
      .post(uri"${config.baseUrl}/v1/publishers/$publisherId/sites/$siteId/force-verify?host=publisher.com")
      .header("Content-Type", "application/json")
      .send(backend)
    println(s"       Force-verify: ${verifyResp.body.getOrElse("failed")}")

    Thread.sleep(500)

    // 2. Trigger auction
    println("\n[2/5] Triggering auctions...")
    val pageUrl = s"https://publisher.com/perf-test-$runId"
    pageUrls += pageUrl
    // Use real IAB category (without runId) so it maps to adProductCategory
    triggerAuction(config, config.category, pageUrl)
    println(s"       Auction triggered: $pageUrl (category: ${config.category})")

    Thread.sleep(1000)

    // 3. Approve all pending creatives
    println("\n[3/5] Approving all pending creatives...")
    val (approved, failed) = approveAll(config, pageUrl)
    println(s"       $pageUrl -> approved: $approved, failed: $failed")

    Thread.sleep(2000)  // Increased delay to allow DData propagation

    // 4. Verify serve index
    println("\n[4/5] Verifying ServeIndex...")
    val totalCandidates = checkServeIndex(config, pageUrl)
    println(s"       $pageUrl -> $totalCandidates candidates")

    // 5. Configure pacing
    println("\n[5/5] Configuring pacing...")
    val pacingSuccess = updatePacingConfig(config)
    if (pacingSuccess) {
      println(s"       Pacing config updated: dayDurationSeconds=${config.dayDurationSeconds}")
    } else {
      println(s"       Warning: Failed to update pacing config")
    }

    SetupResult(pageUrls.toList, campaigns.toList, categoryWithRunId)
  }

  def createCampaign(config: Config, advId: String, category: String, name: String, cpmOverride: Option[Double] = None, budgetOverride: Option[Double] = None): String = {
    val now = java.time.Instant.now().toString
    val effectiveCpm = cpmOverride.getOrElse(config.cpm)
    val effectiveBudget = budgetOverride.getOrElse(config.budget)

    val response = basicRequest
      .post(uri"${config.baseUrl}/v1/advertisers/$advId/campaigns")
      .header("Content-Type", "application/json")
      .body(s"""{
        "name": "Campaign $name",
        "budget": {"daily": "$effectiveBudget"},
        "schedule": {"startAt": "$now"},
        "adProductCategory": "${config.adProductCategory}",
        "bidding": {"strategy": "fixed", "maxCpm": "$effectiveCpm"},
        "landingUrl": "https://example.com/landing"
      }""")
      .send(backend)

    val body = response.body.getOrElse("")
    val campaignId = extractField(body, "id")
    if (campaignId.isEmpty) {
      System.err.println(s"ERROR: Failed to create campaign for $advId. Response: $body")
      throw new RuntimeException(s"Campaign creation failed for $advId: $body")
    }
    campaignId
  }

  def createCreative(config: Config, advId: String, campaignId: String, name: String): String = {
    // Synthetic fluid creative for simulation: minimal pages payload,
    // landing URL placeholder, skipVerify so the CreativeProcessor
    // (Playwright + Gemini) isn't invoked.
    val response = basicRequest
      .post(uri"${config.baseUrl}/v1/advertisers/$advId/campaigns/$campaignId/creatives")
      .header("Content-Type", "application/json")
      .body(s"""{
        "name": "$name",
        "landingUrl": "https://example.com/landing",
        "skipVerify": true,
        "pages": [{
          "tag": "FEATURE",
          "headline": "$name",
          "sub": "",
          "body": "",
          "accent": "#1a5276",
          "bg": "#ffffff",
          "imgEmoji": "",
          "caption": "",
          "banners": {}
        }]
      }""")
      .send(backend)
    val body = response.body.getOrElse("")
    val creativeId = extractField(body, "id")
    if (creativeId.isEmpty) {
      System.err.println(s"ERROR: Failed to create creative for $advId/$campaignId. Response: $body")
      throw new RuntimeException(s"Creative creation failed for $advId/$campaignId: $body")
    }
    creativeId
  }

  def activateCampaign(config: Config, advId: String, campaignId: String): Unit = {
    basicRequest
      .put(uri"${config.baseUrl}/v1/advertisers/$advId/campaigns/$campaignId/status")
      .header("Content-Type", "application/json")
      .body(s"""{"status": "active"}""")
      .send(backend)
  }

  def triggerAuction(config: Config, category: String, pageUrl: String): Unit = {
    val publisherId = "publisher-1"
    // Build categories map: primary category + any additionalCategories from config
    val allCategories = (category :: config.additionalCategories).distinct
    val categoriesJson = allCategories.map(c => s""""$c": 0.9""").mkString(", ")
    basicRequest
      .post(uri"${config.baseUrl}/v1/publishers/$publisherId/sites/${config.siteId}/classify")
      .header("Content-Type", "application/json")
      .body(s"""{
        "url": "$pageUrl",
        "categories": {$categoriesJson},
        "slots": [{"slotId": "${config.slotId}", "width": 300, "height": 250}]
      }""")
      .send(backend)
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
    // Note: sttp's uri interpolation handles URL encoding automatically
    val response = basicRequest
      .get(uri"${config.baseUrl}/v1/publishers/$publisherId/sites/${config.siteId}/serve-index?url=$pageUrl&slotId=${config.slotId}")
      .send(backend)

    if (response.code.isSuccess) {
      extractInt(response.body.getOrElse(""), "candidateCount")
    } else {
      0
    }
  }

  def updatePacingConfig(config: Config): Boolean = {
    val publisherId = "publisher-1"
    val response = basicRequest
      .put(uri"${config.baseUrl}/v1/publishers/$publisherId/sites/${config.siteId}/pacing")
      .header("Content-Type", "application/json")
      .body(s"""{"dayDurationSeconds": ${config.dayDurationSeconds}}""")
      .send(backend)
    response.code.isSuccess
  }

  /** Reset campaign day starts to current time.
    * This synchronizes the server's notion of "day start" with our traffic start time.
    */
  def resetCampaignDayStarts(config: Config, campaigns: List[CampaignInfo]): Boolean = {
    if (campaigns.isEmpty) {
      println("       No campaigns to reset")
      return true
    }

    val publisherId = "publisher-1"
    val campaignsJson = campaigns.map { c =>
      s"""{"advertiserId":"${c.advertiserId}","campaignId":"${c.campaignId}"}"""
    }.mkString("[", ",", "]")

    val response = basicRequest
      .post(uri"${config.baseUrl}/v1/publishers/$publisherId/sites/${config.siteId}/pacing/reset")
      .header("Content-Type", "application/json")
      .body(s"""{"campaigns":$campaignsJson}""")
      .send(backend)
    response.code.isSuccess
  }

  // ==================== Traffic Phase ====================

  def runTraffic(config: Config, pageUrls: List[String], campaigns: List[CampaignInfo], categoryWithRunId: String): Unit = {
    val delayMode = if (config.variableDelay) {
      s"variable (0-${config.maxDelayMs}ms, changes every min)"
    } else if (config.trafficShape.isDefined) {
      val maxVol = config.trafficShape.get.max
      val minVol = config.trafficShape.get.min
      val peakRate = if (config.delayMs > 0) 1000 / config.delayMs else 0
      val lowRate = if (config.delayMs > 0) (1000.0 / (config.delayMs * maxVol / minVol)).toInt else 0
      s"traffic-shaped (${lowRate}-${peakRate} req/sec)"
    } else if (config.delayMs > 0) {
      s"fixed ${config.delayMs}ms (~${1000 / config.delayMs} req/sec)"
    } else {
      "none (max speed)"
    }

    val trafficInfo = config.trafficShape match {
      case Some(shape) =>
        val peakHour = shape.zipWithIndex.maxBy(_._1)._2
        s" (peak at hour $peakHour)"
      case None => ""
    }

    val quietPeriodInfo = if (config.quietPeriodProbability > 0) {
      f"\n | Quiet:    ${config.quietPeriodProbability * 100}%.1f%% chance, ${config.quietPeriodMinMs / 1000}-${config.quietPeriodMaxMs / 1000}s duration"
    } else ""

    println(s"""
      | Site:     ${config.siteId}
      | Slot:     ${config.slotId}
      | URLs:     ${pageUrls.size}
      | Mode:     ${if (config.continuous) "continuous" else s"${config.maxRequests} requests"}
      | Delay:    $delayMode$trafficInfo$quietPeriodInfo
      | Click:    ${(config.clickRate * 100).toInt}%
      | Day:      ${config.dayDurationSeconds}s (${config.dayDurationSeconds / 60} min)
      |==========================================
      |""".stripMargin)

    var counters = Counters()
    // creativeId -> (imps, clicks, category, advertiserId, campaignId)
    val perCreative = scala.collection.mutable.Map[String, (Int, Int, String, String, String)]()
    val perUrl = scala.collection.mutable.Map[String, Int]().withDefaultValue(0)


    var lastReportImps = 0
    var lastReportTime = System.currentTimeMillis()
    var lastStats: Option[SiteStats] = None
    var lastSpend: Double = 0.0

    var currentDelayMs = config.delayMs
    var lastDelayChangeMinute = 0L
    val startMinute = System.currentTimeMillis() / 60000

    var intervalStartReqs = 0
    var intervalStartSelected = 0
    var lastRefreshTimeMs = System.currentTimeMillis()
    val trafficStartTimeMs = System.currentTimeMillis()
    var completedDays = 0
    var lastDayCheck = 0

    var running = true
    while (running) {
      // Check for simulated day rollover
      if (config.dayDurationSeconds > 0 && config.dayDurationSeconds < 86400) {
        val elapsedSeconds = (System.currentTimeMillis() - trafficStartTimeMs) / 1000
        val currentDay = (elapsedSeconds / config.dayDurationSeconds).toInt
        if (currentDay > lastDayCheck) {
          completedDays = currentDay
          lastDayCheck = currentDay
          val dayType = if (completedDays % 7 >= 5) "weekend" else "weekday"
          println(s"\n  ⏰ DAY ROLLOVER: Completed simulated day $completedDays → day ${completedDays + 1} ($dayType) (at ${elapsedSeconds}s elapsed)")
          // Reset campaign budgets on the server for the new simulated day
          resetCampaignDayStarts(config, campaigns)
          println(s"  ✓ Budget reset for ${campaigns.size} campaigns")
          // Process churn events for this day
          config.churnEvents.filter(_.day == completedDays).foreach { event =>
            val advCampaigns = campaigns.filter(_.advertiserId.contains(s"adv-${event.advertiser}-"))
            if (advCampaigns.nonEmpty) {
              val status = if (event.action == "pause") "paused" else "active"
              advCampaigns.foreach { ci =>
                basicRequest
                  .put(uri"${config.baseUrl}/v1/advertisers/${ci.advertiserId}/campaigns/${ci.campaignId}/status")
                  .header("Content-Type", "application/json")
                  .body(s"""{"status": "$status"}""")
                  .send(backend)
              }
              println(s"  ↕ CHURN: Advertiser ${event.advertiser} ${event.action}d (${advCampaigns.size} campaigns)")
            }
          }
          if (config.stopAfterDays > 0 && completedDays >= config.stopAfterDays) {
            println(s"  🛑 STOPPING: Reached $completedDays simulated days (stopAfterDays=${config.stopAfterDays})")
            running = false
          }
        }
      }
      if (!running) {
        // Break out of the loop - will print final report below
      } else {
      // Periodic refresh: re-classify pages to refresh ServeIndex TTL (prevents entries from expiring)
      val nowMs = System.currentTimeMillis()
      if (config.continuous && config.refreshIntervalSeconds > 0 &&
          (nowMs - lastRefreshTimeMs) >= config.refreshIntervalSeconds * 1000L) {
        println(s"\n  🔄 REFRESH: Re-classifying ${pageUrls.size} pages to refresh ServeIndex TTL...")
        pageUrls.foreach { pageUrl =>
          // Use real IAB category (without runId) so it maps to adProductCategory
          triggerAuction(config, config.category, pageUrl)
          Thread.sleep(100) // Small delay between classify calls
        }
        lastRefreshTimeMs = nowMs
        println(s"  ✓ Refresh complete - ServeIndex entries renewed\n")
      }

      // Variable delay: change every minute (if enabled)
      if (config.variableDelay) {
        val currentMinute = System.currentTimeMillis() / 60000
        if (currentMinute > lastDelayChangeMinute) {
          lastDelayChangeMinute = currentMinute
          currentDelayMs = rng.nextInt(config.maxDelayMs + 1)
          val minuteNum = currentMinute - startMinute
          val approxRate = if (currentDelayMs > 0) f"~${1000.0 / currentDelayMs}%.0f req/sec" else "max speed"
          println(s"\n  [Minute $minuteNum] Delay: ${currentDelayMs}ms ($approxRate)")
        }
      }

      val url = pageUrls(rng.nextInt(pageUrls.size))

      val response = requestServe(config, url)
      counters = counters.copy(requests = counters.requests + 1)

      response match {
        case None =>
          counters = counters.copy(httpErrors = counters.httpErrors + 1)

        case Some(resp) if !resp.selected =>
          counters = counters.copy(pacingSkipped = counters.pacingSkipped + 1)

        case Some(resp) =>
          counters = counters.copy(selected = counters.selected + 1)

          val impOk = trackImpression(config, url, resp)
          if (impOk) {
            val spendForImp = resp.cpm / 1000.0
            counters = counters.copy(
              impressions = counters.impressions + 1,
              spendDollars = counters.spendDollars + spendForImp
            )
            perUrl(url) = perUrl(url) + 1

            val (prevImps, prevClicks, _, _, _) = perCreative.getOrElse(resp.creativeId, (0, 0, resp.category, resp.advertiserId, resp.campaignId))

            val shortCat = resp.category.split("-").headOption.getOrElse("")
            val bias = config.categoryBias.getOrElse(shortCat, 1.0)
            if (rng.nextDouble() < config.clickRate * bias) {
              val clickOk = trackClick(config, url, resp)
              if (clickOk) {
                counters = counters.copy(clicks = counters.clicks + 1)
                perCreative(resp.creativeId) = (prevImps + 1, prevClicks + 1, resp.category, resp.advertiserId, resp.campaignId)
              } else {
                perCreative(resp.creativeId) = (prevImps + 1, prevClicks, resp.category, resp.advertiserId, resp.campaignId)
              }
            } else {
              perCreative(resp.creativeId) = (prevImps + 1, prevClicks, resp.category, resp.advertiserId, resp.campaignId)
            }
          }
      }

      // Reporting
      if (counters.requests % config.reportEvery == 0) {
        val now = System.currentTimeMillis()
        val intervalSec = (now - lastReportTime) / 1000.0
        val intervalImps = counters.impressions - lastReportImps
        val intervalRate = if (intervalSec > 0) intervalImps / intervalSec else 0.0

        val intervalReqs = counters.requests - intervalStartReqs
        val intervalSelected = counters.selected - intervalStartSelected

        if (config.continuous) {
          val currentStats = getSiteStats(config)
          val intervalSpend = currentStats.map(_.totalSpend).getOrElse(0.0) - lastSpend
          val intervalSpendRate = if (intervalSec > 0) intervalSpend / intervalSec else 0.0

          printReport(config, counters, perUrl.toMap, perCreative.toMap, intervalRate, lastStats, intervalSpendRate, campaigns)
          lastStats = currentStats
          lastSpend = currentStats.map(_.totalSpend).getOrElse(lastSpend)
        } else {
          val rate = if (intervalReqs > 0) intervalSelected.toDouble / intervalReqs * 100 else 100.0
          printProgress(counters, rate)
        }

        lastReportImps = counters.impressions
        lastReportTime = now
        intervalStartReqs = counters.requests
        intervalStartSelected = counters.selected
      }

      // Stop condition
      if (!config.continuous && counters.requests >= config.maxRequests) {
        running = false
      }

      // Apply delay (use day-aware traffic shape for weekday/weekend variation)
      val delayToApply = if (config.trafficShapeForDay(completedDays).isDefined && !config.variableDelay) {
        calculateShapedDelay(config, counters.elapsedSec, completedDays)
      } else {
        currentDelayMs
      }
      if (delayToApply > 0) Thread.sleep(delayToApply)

      // Random quiet period (for testing staleness detection)
      if (config.quietPeriodProbability > 0 && rng.nextDouble() < config.quietPeriodProbability) {
        val quietDuration = config.quietPeriodMinMs + rng.nextInt(config.quietPeriodMaxMs - config.quietPeriodMinMs + 1)
        val quietSec = quietDuration / 1000.0
        println(f"\n  ⏸️  QUIET PERIOD: Pausing traffic for ${quietSec}%.1fs (testing staleness detection)...")
        Thread.sleep(quietDuration)
        println(f"  ▶️  QUIET PERIOD ENDED: Resuming traffic after ${quietSec}%.1fs gap\n")
      }
      } // end else (not stopped by day rollover)
    } // end while

    println()
    printReport(config, counters, perUrl.toMap, perCreative.toMap, campaigns = campaigns)
  }

  def requestServe(config: Config, url: String): Option[ServeResponse] = {
    try {
      // POST /v1/serve/batch — only serve endpoint since the per-slot
      // GET /v1/serve was retired. Send a single-slot batch and unwrap
      // seatbid[0]. Body shape mirrors BatchServeReq in the API.
      val body =
        s"""{"pub":"${config.siteId}","url":"$url","imp":[{"id":"${config.slotId}","w":300,"h":250}]}"""
      val resp = basicRequest
        .post(uri"${config.baseUrl}/v1/serve/batch")
        .header("User-Agent", "RunScenario/1.0")
        .header("Content-Type", "application/json")
        .body(body)
        .send(backend)

      // 204 No Content = batch content too old; treat as no-ad just like
      // the per-slot path used to.
      if (resp.code.code == 204) return Some(ServeResponse(selected = false))
      if (!resp.code.isSuccess) return None

      val respBody = resp.body.getOrElse("")
      // Empty winner ("winner":null) means the slot didn't fill — record
      // as a non-selection so pacing/grace skip counts surface correctly.
      if (respBody.contains("\"winner\":null") || !respBody.contains("\"creativeId\""))
        return Some(ServeResponse(selected = false))

      // Single-slot batch: regex extraction picks the (only) winner's
      // fields directly from the response body. impUrl is the signed
      // tracking URL; we parse the campaign/advertiser/cpm/category/rid
      // back out of its query params, mirroring the per-slot path.
      val creativeId = extractField(respBody, "creativeId")
      val impUrl     = extractField(respBody, "impUrl")
      val clickUrl   = extractField(respBody, "clickUrl")

      val campaignId   = extractUrlParam(impUrl, "camp")
      val advertiserId = extractUrlParam(impUrl, "adv")
      val cpm          = extractUrlParam(impUrl, "cpm").toDoubleOption.getOrElse(0.0)
      val category     = extractUrlParam(impUrl, "cat")
      val requestId    = extractUrlParam(impUrl, "rid")

      Some(ServeResponse(
        selected = true,
        creativeId = creativeId,
        campaignId = campaignId,
        advertiserId = advertiserId,
        cpm = cpm,
        category = category,
        requestId = requestId,
        impUrl = impUrl,
        clickUrl = clickUrl
      ))
    } catch {
      case _: Exception => None
    }
  }

  /** Extract a query parameter value from a URL */
  def extractUrlParam(url: String, param: String): String = {
    val pattern = s"[?&]$param=([^&]*)".r
    pattern.findFirstMatchIn(url).map(_.group(1)).getOrElse("")
  }

  // Debug counters for tracking failures
  private val trackingStatusCounts = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
  private var trackingExceptionCount = 0
  private var emptyImpUrlCount = 0

  def trackImpression(config: Config, url: String, resp: ServeResponse): Boolean = {
    if (resp.impUrl.isEmpty) {
      emptyImpUrlCount += 1
      return false
    }
    try {
      // Use the signed impUrl from serve response - this goes through production tracking
      // which writes to TrackingEventJournal for dashboard projection
      val r = basicRequest
        .get(uri"${resp.impUrl}")
        .header("User-Agent", "RunScenario/1.0")
        .send(backend)

      trackingStatusCounts(r.code.code) += 1

      // 204 No Content is success for tracking endpoints
      r.code.code == 204 || r.code.isSuccess
    } catch {
      case _: Exception =>
        trackingExceptionCount += 1
        false
    }
  }

  def printTrackingDebug(): Unit = {
    if (trackingStatusCounts.nonEmpty || trackingExceptionCount > 0 || emptyImpUrlCount > 0) {
      println("    Tracking debug:")
      trackingStatusCounts.toList.sortBy(_._1).foreach { case (code, count) =>
        val status = code match {
          case 200 | 204 => "OK"
          case 403 => "Forbidden (HMAC fail)"
          case 409 => "Conflict (replay)"
          case _ => ""
        }
        println(f"      HTTP $code: $count%6d $status")
      }
      if (emptyImpUrlCount > 0) println(f"      Empty impUrl: $emptyImpUrlCount%6d")
      if (trackingExceptionCount > 0) println(f"      Exceptions: $trackingExceptionCount%6d")
      println()
    }
  }

  def trackClick(config: Config, url: String, resp: ServeResponse): Boolean = {
    try {
      // Use the signed clickUrl from serve response - this goes through production tracking
      // which writes to TrackingEventJournal for dashboard projection
      val r = basicRequest
        .get(uri"${resp.clickUrl}")
        .header("User-Agent", "RunScenario/1.0")
        .followRedirects(false)  // Don't follow redirect to destination
        .send(backend)
      // Click tracking returns a redirect (302) to the destination URL, or 204/200
      r.code.code == 302 || r.code.code == 204 || r.code.isSuccess
    } catch {
      case _: Exception => false
    }
  }

  def calculateShapedDelay(config: Config, elapsedSeconds: Double, currentDay: Int = 0): Int = {
    config.trafficShapeForDay(currentDay) match {
      case Some(shape) if shape.length == 24 =>
        // For real days (86400s), use UTC wall clock hour to align with server
        // For simulated short days, use elapsed time scaled to 24 buckets
        val bucket = if (config.dayDurationSeconds == 86400) {
          java.time.LocalTime.now(java.time.ZoneOffset.UTC).getHour
        } else {
          val bucketDuration = config.dayDurationSeconds.toDouble / 24
          ((elapsedSeconds / bucketDuration) % 24).toInt.max(0).min(23)
        }
        val volume = shape(bucket)
        val maxVolume = shape.max

        if (volume > 0) {
          (config.delayMs * maxVolume / volume).toInt.max(1)
        } else {
          config.delayMs * 10
        }

      case _ =>
        config.delayMs
    }
  }

  def printProgress(c: Counters, recentSelectRate: Double): Unit = {
    val rate = if (recentSelectRate >= 0) recentSelectRate else c.successRate
    val barLen = 20
    val filled = (rate / 100.0 * barLen).toInt.max(0).min(barLen)
    val bar = "█" * filled + "░" * (barLen - filled)
    val status = if (rate >= 90) "✓" else if (rate >= 50) "◐" else if (rate > 0) "◔" else "✗"

    print(f"\r  $bar $status ${rate}%5.1f%% | Req: ${c.requests}%d | Sel: ${c.selected} | Skip: ${c.pacingSkipped}    ")
  }

  def printReport(
      config: Config,
      c: Counters,
      perUrl: Map[String, Int],
      perCreative: Map[String, (Int, Int, String, String, String)],
      intervalRate: Double = 0.0,
      prevStats: Option[SiteStats] = None,
      intervalSpendRate: Double = 0.0,
      campaigns: List[CampaignInfo] = List.empty
  ): Unit = {
    val totalCampaigns = config.advertisers * config.campaignsPerAdvertiser

    println()
    println(s"  ─── Report @ ${c.requests} requests (${f"${c.elapsedSec}%.1f"}s elapsed) ───")
    println()
    println(f"    Requests:      ${c.requests}%6d  (${c.requestsPerSec}%.0f/sec)")
    println(f"    Selected:      ${c.selected}%6d (${c.successRate}%.1f%%)")
    println(f"    Pacing skip:   ${c.pacingSkipped}%6d")
    println(f"    HTTP Errors:   ${c.httpErrors}%6d")
    println()
    println(f"    Impressions: ${c.impressions}%6d  (avg: ${c.impressionsPerSec}%.1f/sec, this interval: $intervalRate%.1f/sec)")
    println(f"    Clicks:      ${c.clicks}%6d (CTR: ${c.ctr}%.1f%%)")
    println()

    if (c.selected != c.impressions) {
      println(f"    ⚠ Selected (${c.selected}) != Impressions (${c.impressions}) - tracking failures")
      printTrackingDebug()
    }

    if (perUrl.nonEmpty) {
      println("    Per-URL (top 3):")
      perUrl.toList.sortBy(-_._2).take(3).foreach { case (url, imps) =>
        val short = url.split("/").lastOption.getOrElse(url)
        println(f"      $short%-30s $imps%5d imps")
      }
      println()
    }

    if (campaigns.nonEmpty) {
      println("    Campaigns (for curl testing):")
      val statuses = getCampaignStatuses(config, campaigns)
      statuses.foreach { cs =>
        println(f"      ${cs.info.advertiserId}  ${cs.info.campaignId}  budget=$$${cs.budget}%.2f  cpm=$$${cs.cpm}%.2f")
      }
      println()

      // RL bid optimization stats
      val rlStatsList = campaigns.flatMap { camp =>
        getCampaignRLStats(config, camp).map(rl => (camp, rl))
      }
      if (rlStatsList.nonEmpty) {
        println("    RL Bid Optimization:")
        rlStatsList.foreach { case (camp, rl) =>
          val shortCampId = camp.campaignId.take(11)
          val ctrPct = rl.dayCtr * 100
          println(f"      $shortCampId  mult=${rl.bidMultiplier}%.3f  ε=${rl.epsilon}%.3f  train=${rl.trainingSteps}%d  obs=${rl.dayObservations}%d")
          println(f"        imps=${rl.dayImpressions}%d  clicks=${rl.dayClicks}%d  CTR=${ctrPct}%.2f%%  CPC=$$${rl.dayCostPerClick}%.4f  reward=${rl.dayReward}%.2f  spend=$$${rl.daySpend}%.4f")
        }
        println()
      }
    }

    if (perCreative.nonEmpty) {
      println("    Per-Creative (top 5):")
      perCreative.toList.sortBy(-_._2._1).take(5).foreach { case (cid, (imps, clicks, cat, advId, campId)) =>
        val shortCat = cat.split("-").headOption.getOrElse(cat)
        val shortAdvId = advId.takeRight(11)
        val shortCampId = campId.take(11)
        val ctr = if (imps > 0) clicks.toDouble / imps * 100 else 0
        println(s"      $shortAdvId:$shortCampId[$shortCat] ${cid.take(12)}  $imps imps  $clicks clicks  CTR=${f"$ctr%.1f"}%")
      }
      println()
    }

    getSiteStats(config) match {
      case Some(stats) =>
        // Fetch actual budget from server (reflects mid-delivery changes)
        // Fallback uses config values if fetch fails or returns 0
        val configBasedBudget = config.budget * config.advertisers * config.campaignsPerAdvertiser
        val fetchedBudget = if (campaigns.nonEmpty) getTotalBudget(config, campaigns) else 0.0
        val totalBudget = if (fetchedBudget > 0) fetchedBudget else configBasedBudget
        val expectedSpend = totalBudget * stats.expectedSpendFraction
        val actualSpend = stats.totalSpend
        val spendRatio = if (expectedSpend > 0) actualSpend / expectedSpend else 0.0

        val prevSpendRatio = prevStats.map { prev =>
          val prevExpected = totalBudget * prev.expectedSpendFraction
          if (prevExpected > 0) prev.totalSpend / prevExpected else 0.0
        }

        val targetSpendRate = totalBudget / config.dayDurationSeconds.toDouble

        println("    Server-side stats:")
        println(f"      Selected:     ${stats.selected}%6d")
        println(f"      Pacing skip:  ${stats.pacingSkipped}%6d")
        println(f"      Budget out:   ${stats.budgetExhausted}%6d")
        println(f"      No cands:     ${stats.noCandidates}%6d")
        println()

        println("    Pacing status:")
        val trendArrow = prevSpendRatio match {
          case Some(prev) if spendRatio < prev - 0.01 => "↓"
          case Some(prev) if spendRatio > prev + 0.01 => "↑"
          case _ => "→"
        }
        val trendDesc = prevSpendRatio match {
          case Some(prev) if spendRatio < prev - 0.01 => "converging"
          case Some(prev) if spendRatio > prev + 0.01 => "diverging"
          case _ => "stable"
        }
        println(f"      Spend ratio:   ${spendRatio}%.2fx $trendArrow ($trendDesc)")
        println(f"      Spend rate:    $$$intervalSpendRate%.4f/sec (target: $$$targetSpendRate%.4f/sec)")
        val rateStatus = if (intervalSpendRate <= targetSpendRate * 1.1) "ON PACE" else "OVER"
        println(f"      Rate status:   $rateStatus")

        println()
        println(f"    Cumulative (${stats.elapsedHours}%.1fh elapsed):")
        println(f"      Spent: $$$actualSpend%.2f / Expected: $$$expectedSpend%.2f")

        val totalRequests = stats.selected + stats.pacingSkipped
        if (totalRequests > 0) {
          val skipRate = stats.pacingSkipped.toDouble / totalRequests * 100
          println(f"      Throttle rate: $skipRate%.1f%%")
        }
        println()

        stats.trafficShapeSummary.foreach { summary =>
          println("    Traffic Shape Learning:")
          println(s"      $summary")
          println()
        }

      case None =>
        println("    (Server stats unavailable)")
        println()
    }
  }

  def getSiteStats(config: Config): Option[SiteStats] = {
    try {
      val publisherId = "publisher-1"
      val resp = basicRequest.get(uri"${config.baseUrl}/v1/publishers/$publisherId/sites/${config.siteId}/stats").send(backend)
      if (resp.code.isSuccess) {
        val body = resp.body.getOrElse("")
        Some(SiteStats(
          selected = extractLong(body, "selected"),
          pacingSkipped = extractLong(body, "pacingSkipped"),
          budgetExhausted = extractLong(body, "budgetExhausted"),
          noCandidates = extractLong(body, "noCandidates"),
          totalSpend = extractDouble(body, "totalSpend"),
          elapsedHours = extractDouble(body, "elapsedHours"),
          expectedSpendFraction = extractDouble(body, "expectedSpendFraction"),
          pacingNote = extractField(body, "pacingNote"),
          trafficShapeSummary = extractOptionalField(body, "trafficShapeSummary")
        ))
      } else None
    } catch {
      case _: Exception => None
    }
  }

  /** Fetch current daily budget for a campaign from the server */
  def getCampaignBudget(config: Config, camp: CampaignInfo): Option[Double] = {
    try {
      val resp = basicRequest
        .get(uri"${config.baseUrl}/v1/advertisers/${camp.advertiserId}/campaigns/${camp.campaignId}/budget")
        .send(backend)
      if (resp.code.isSuccess) {
        val body = resp.body.getOrElse("")
        Some(extractDouble(body, "dailyBudget"))
      } else None
    } catch {
      case _: Exception => None
    }
  }

  /** Fetch total daily budget across all campaigns from the server */
  def getTotalBudget(config: Config, campaigns: List[CampaignInfo]): Double = {
    campaigns.flatMap(getCampaignBudget(config, _)).sum
  }

  /** Fetch current CPM for a campaign from the server */
  def getCampaignCpm(config: Config, camp: CampaignInfo): Option[Double] = {
    try {
      val resp = basicRequest
        .get(uri"${config.baseUrl}/v1/advertisers/${camp.advertiserId}/campaigns/${camp.campaignId}")
        .send(backend)
      if (resp.code.isSuccess) {
        val body = resp.body.getOrElse("")
        Some(extractDouble(body, "maxCpm"))
      } else None
    } catch {
      case _: Exception => None
    }
  }

  /** Campaign info with current budget and CPM */
  case class CampaignStatus(info: CampaignInfo, budget: Double, cpm: Double)

  /** Fetch budget and CPM for all campaigns */
  def getCampaignStatuses(config: Config, campaigns: List[CampaignInfo]): List[CampaignStatus] = {
    campaigns.flatMap { camp =>
      for {
        budget <- getCampaignBudget(config, camp)
        cpm <- getCampaignCpm(config, camp)
      } yield CampaignStatus(camp, budget, cpm)
    }
  }

  /** RL stats for a campaign */
  case class CampaignRLStats(
      bidMultiplier: Double, epsilon: Double, trainingSteps: Long, totalSteps: Long,
      dayImpressions: Long, dayClicks: Long, daySpend: Double,
      dayObservations: Int, dayReward: Double, dayCtr: Double, dayCostPerClick: Double
  )

  /** Fetch RL bid optimization stats for a campaign */
  def getCampaignRLStats(config: Config, camp: CampaignInfo): Option[CampaignRLStats] = {
    try {
      val resp = basicRequest
        .get(uri"${config.baseUrl}/v1/advertisers/${camp.advertiserId}/campaigns/${camp.campaignId}/rl")
        .send(backend)
      if (resp.code.isSuccess) {
        val body = resp.body.getOrElse("")
        Some(CampaignRLStats(
          bidMultiplier   = extractDouble(body, "bidMultiplier"),
          epsilon         = extractDouble(body, "epsilon"),
          trainingSteps   = extractLong(body, "trainingSteps"),
          totalSteps      = extractLong(body, "totalSteps"),
          dayImpressions  = extractLong(body, "dayImpressions"),
          dayClicks       = extractLong(body, "dayClicks"),
          daySpend        = extractDouble(body, "daySpend"),
          dayObservations = extractLong(body, "dayObservations").toInt,
          dayReward       = extractDouble(body, "dayReward"),
          dayCtr          = extractDouble(body, "dayCtr"),
          dayCostPerClick = extractDouble(body, "dayCostPerClick")
        ))
      } else None
    } catch {
      case _: Exception => None
    }
  }

  // ==================== Category Race Mode ====================

  def runCategoryRaceMode(config: Config, runId: String): Unit = {
    val clickModeInfo = config.randomClickRate match {
      case Some(rate) => s"Click Mode:  RANDOM (flat ${(rate * 100).toInt}% for all)"
      case None       => "Click Mode:  Per-category true CTR"
    }
    val selectionModeInfo = if (config.thompsonSelection)
      "Selection:   THOMPSON (highest sampled score wins)"
    else
      "Selection:   UNIFORM (random category each time)"

    println(s"""
      |==========================================
      | Category Race Mode
      |==========================================
      | Base URL:    ${config.baseUrl}
      | Site ID:     ${config.siteId}
      | Impressions: ${config.maxRequests}
      | $clickModeInfo
      | $selectionModeInfo
      |------------------------------------------
      | Categories (with true CTRs):
      |${config.categories.map(c => s"|   ${c.name}: ${(c.trueCtr * 100).toInt}%").mkString("\n")}
      |==========================================
      |""".stripMargin)

    val advertiserId = s"adv-catrace-$runId"
    val categoryStats = scala.collection.mutable.Map[String, (Int, Int)]()
    config.categories.foreach(c => categoryStats(c.name) = (0, 0))

    // Setup
    println("\n[1/6] Setting advertiser budget...")
    setBudgetDirect(config, advertiserId, 500.0)

    println("\n[2/6] Creating campaign targeting all categories...")
    val fullCategories = config.categories.map(c => s"${c.name}-$runId")
    val campaignId = createCampaignDirect(config, advertiserId, fullCategories, runId)
    println(s"       Campaign ID: $campaignId")

    println("\n[3/6] Creating creative...")
    val creativeId = createCreativeDirect(config, advertiserId, campaignId, "Race Banner")
    println(s"       Creative ID: $creativeId")

    println("\n[4/6] Activating campaign...")
    activateCampaignDirect(config, advertiserId, campaignId)

    println("\n[5/6] Registering with CategoryBidders...")
    config.categories.foreach { cat =>
      val fullCategory = s"${cat.name}-$runId"
      registerCategoryDirect(config, campaignId, advertiserId, fullCategory)
    }

    // Trigger auctions and approve for each category
    config.categories.foreach { cat =>
      val fullCategory = s"${cat.name}-$runId"
      val pageUrl = s"https://publisher.com/${cat.name}-article-$runId"
      triggerAuctionDirect(config, fullCategory, pageUrl)
      Thread.sleep(300)
      approveCreativeDirect(config, creativeId, pageUrl)
    }
    Thread.sleep(500)

    println("\n[6/6] Running category race simulation...")
    var serveFailures = 0
    var totalImpressions = 0

    val iterator: Iterator[Int] = if (config.continuous) Iterator.from(1) else (1 to config.maxRequests).iterator

    iterator.foreach { i =>
      val cat = if (config.thompsonSelection) {
        selectCategoryByThompson(config, runId)
      } else {
        config.categories(rng.nextInt(config.categories.size))
      }
      val pageUrl = s"https://publisher.com/${cat.name}-article-$runId"
      val fullCategory = s"${cat.name}-$runId"

      serveDirect(config, pageUrl) match {
        case Some(resp) =>
          trackImpression(config, pageUrl, resp)
          val (imps, clicks) = categoryStats.getOrElse(cat.name, (0, 0))
          val clickRate = config.randomClickRate.getOrElse(cat.trueCtr)
          if (rng.nextDouble() < clickRate) {
            trackClick(config, pageUrl, resp)
            categoryStats(cat.name) = (imps + 1, clicks + 1)
          } else {
            categoryStats(cat.name) = (imps + 1, clicks)
          }
        case None =>
          serveFailures += 1
      }
      totalImpressions += 1

      if (config.continuous && totalImpressions % config.reportEvery == 0) {
        printCategoryRaceReport(config, categoryStats.toMap, totalImpressions, serveFailures, runId)
      } else if (!config.continuous && (i % 50 == 0 || i == config.maxRequests)) {
        print(s"\r       Progress: $i/${config.maxRequests} | Failures: $serveFailures    ")
      }
      Thread.sleep(30)
    }

    if (!config.continuous) {
      println()
      printCategoryRaceReport(config, categoryStats.toMap, totalImpressions, serveFailures, runId)
    }
  }

  def printCategoryRaceReport(config: Config, stats: Map[String, (Int, Int)], total: Int, failures: Int, runId: String): Unit = {
    println(s"\n  ─── Report @ $total impressions (failures: $failures) ───\n")
    println("  Local Stats:")
    stats.toList.sortBy { case (_, (i, c)) => -c.toDouble / math.max(i, 1) }.foreach {
      case (cat, (imps, clicks)) =>
        val ctr = if (imps > 0) clicks.toDouble / imps * 100 else 0.0
        val trueCtr = config.categories.find(_.name == cat).map(_.trueCtr * 100).getOrElse(0.0)
        println(f"       $cat%-10s: $imps%5d imps, $clicks%4d clicks, CTR=$ctr%5.1f%% (true: $trueCtr%.0f%%)")
    }
    println()
    println("  TaxonomyRanker Stats:")
    config.categories.foreach { catConfig =>
      getTaxonomyStatsDirect(config, s"${catConfig.name}-$runId") match {
        case Some((wins, clicks, meanCtr)) =>
          println(f"       ${catConfig.name}%-10s: wins=$wins%6.1f, clicks=$clicks%5.1f, meanCTR=${meanCtr * 100}%5.2f%%")
        case None =>
          println(f"       ${catConfig.name}%-10s: (no stats)")
      }
    }
    println()
  }

  def selectCategoryByThompson(config: Config, runId: String): CategoryConfig = {
    val sampledScores = config.categories.map { catConfig =>
      val fullCategory = s"${catConfig.name}-$runId"
      val (clicks, wins) = getTaxonomyStatsDirect(config, fullCategory).map(s => (s._2, s._1)).getOrElse((0.0, 0.0))
      val score = sampleBeta(clicks, wins)
      (catConfig, score)
    }
    sampledScores.maxBy(_._2)._1
  }

  // ==================== Creative Race Mode ====================

  def runCreativeRaceMode(config: Config, runId: String): Unit = {
    val category = s"shared-$runId"

    val clickModeInfo = config.randomClickRate match {
      case Some(rate) => s"Click Mode:  RANDOM (flat ${(rate * 100).toInt}% for all)"
      case None       => "Click Mode:  Per-creative true CTR"
    }
    val selectionModeInfo = if (config.thompsonSelection)
      "Selection:   THOMPSON (AdServer scores)"
    else
      "Selection:   UNIFORM (random creative)"

    println(s"""
      |==========================================
      | Creative Race Mode (AdServer MAB)
      |==========================================
      | Base URL:    ${config.baseUrl}
      | Site ID:     ${config.siteId}
      | Impressions: ${config.maxRequests}
      | Category:    $category (shared by all)
      | $clickModeInfo
      | $selectionModeInfo
      |------------------------------------------
      | Creatives (with true CTRs):
      |${config.creatives.map(c => s"|   ${c.name}: ${(c.trueCtr * 100).toInt}%").mkString("\n")}
      |==========================================
      |""".stripMargin)

    // Setup each creative with its own advertiser
    println("\n[SETUP] Creating advertisers, campaigns, and creatives...")
    val creativeInfos = config.creatives.map { cc =>
      val advId = s"adv-${cc.name}-$runId"
      setBudgetDirect(config, advId, 500.0)
      val campId = createCampaignDirect(config, advId, List(category), runId)
      val cid = createCreativeDirect(config, advId, campId, s"${cc.name} Banner")
      activateCampaignDirect(config, advId, campId)
      registerCategoryDirect(config, campId, advId, category)
      println(s"       Created: ${cc.name} -> adv=$advId, cid=$cid")
      CreativeRaceInfo(advId, campId, cid, cc.name, cc.trueCtr)
    }

    println("\n[SETUP] Triggering auction...")
    val pageUrl = s"https://publisher.com/shared-article-$runId"
    triggerAuctionDirect(config, category, pageUrl)
    Thread.sleep(500)

    println("\n[SETUP] Approving creatives...")
    creativeInfos.foreach { info =>
      approveCreativeDirect(config, info.creativeId, pageUrl)
    }
    Thread.sleep(500)

    val creativeStats = scala.collection.mutable.Map[String, (Int, Int)]()
    creativeInfos.foreach(info => creativeStats(info.name) = (0, 0))

    println("\n[SIM] Running creative race simulation...")
    var serveFailures = 0
    var totalImpressions = 0

    val iterator: Iterator[Int] = if (config.continuous) Iterator.from(1) else (1 to config.maxRequests).iterator

    iterator.foreach { i =>
      serveDirect(config, pageUrl) match {
        case Some(resp) =>
          val servedInfo = creativeInfos.find(_.creativeId == resp.creativeId).getOrElse(creativeInfos.head)
          trackImpression(config, pageUrl, resp)
          val (imps, clicks) = creativeStats.getOrElse(servedInfo.name, (0, 0))
          val clickRate = config.randomClickRate.getOrElse(servedInfo.trueCtr)
          if (rng.nextDouble() < clickRate) {
            trackClick(config, pageUrl, resp)
            creativeStats(servedInfo.name) = (imps + 1, clicks + 1)
          } else {
            creativeStats(servedInfo.name) = (imps + 1, clicks)
          }
        case None =>
          serveFailures += 1
      }
      totalImpressions += 1

      if (config.continuous && totalImpressions % config.reportEvery == 0) {
        printCreativeRaceReport(config, creativeInfos, creativeStats.toMap, totalImpressions, serveFailures)
      } else if (!config.continuous && (i % 50 == 0 || i == config.maxRequests)) {
        print(s"\r       Progress: $i/${config.maxRequests} | Failures: $serveFailures    ")
      }
      Thread.sleep(30)
    }

    if (!config.continuous) {
      println()
      printCreativeRaceReport(config, creativeInfos, creativeStats.toMap, totalImpressions, serveFailures)
    }
  }

  def printCreativeRaceReport(config: Config, infos: List[CreativeRaceInfo], stats: Map[String, (Int, Int)], total: Int, failures: Int): Unit = {
    println(s"\n  ─── Report @ $total impressions (failures: $failures) ───\n")
    println("  Local Stats:")
    stats.toList.sortBy { case (_, (i, c)) => -c.toDouble / math.max(i, 1) }.foreach {
      case (name, (imps, clicks)) =>
        val ctr = if (imps > 0) clicks.toDouble / imps * 100 else 0.0
        val trueCtr = config.creatives.find(_.name == name).map(_.trueCtr * 100).getOrElse(0.0)
        println(f"       $name%-10s: $imps%5d imps, $clicks%4d clicks, CTR=$ctr%5.1f%% (true: $trueCtr%.0f%%)")
    }
    println()
    println("  AdServer Stats:")
    getAdServerStatsDirect(config) match {
      case Some(serverStats) =>
        serverStats.foreach { case (cid, imps, clicks, ctr) =>
          val name = infos.find(_.creativeId == cid).map(_.name).getOrElse(cid.take(8))
          println(f"       $name%-10s: imps=$imps%5d, clicks=$clicks%4d, CTR=${ctr * 100}%5.2f%%")
        }
      case None =>
        println("       (no stats available)")
    }
    println()
  }

  // ==================== Direct API Helpers (for race modes) ====================

  def setBudgetDirect(config: Config, advertiserId: String, budget: Double): Unit = {
    basicRequest
      .put(uri"${config.baseUrl}/v1/advertisers/$advertiserId/budget")
      .header("Content-Type", "application/json")
      .body(s"""{"dailyBudget": "$budget"}""")
      .send(backend)
  }

  def createCampaignDirect(config: Config, advertiserId: String, categories: List[String], runId: String): String = {
    val now = java.time.Instant.now().toString
    val adProductCategory = categories.headOption.getOrElse("654") // IAB Travel content category

    val response = basicRequest
      .post(uri"${config.baseUrl}/v1/advertisers/$advertiserId/campaigns")
      .header("Content-Type", "application/json")
      .body(s"""{
        "name": "Race Campaign $runId",
        "budget": {"daily": "200.0"},
        "schedule": {"startAt": "$now"},
        "adProductCategory": "$adProductCategory",
        "bidding": {"strategy": "fixed", "maxCpm": "5.0"},
        "landingUrl": "https://example.com/landing"
      }""")
      .send(backend)
    extractField(response.body.getOrElse(""), "id")
  }

  def createCreativeDirect(config: Config, advertiserId: String, campaignId: String, name: String): String = {
    val response = basicRequest
      .post(uri"${config.baseUrl}/v1/advertisers/$advertiserId/campaigns/$campaignId/creatives")
      .header("Content-Type", "application/json")
      .body(s"""{
        "name": "$name",
        "landingUrl": "https://example.com/landing",
        "skipVerify": true,
        "pages": [{
          "tag": "FEATURE",
          "headline": "$name",
          "sub": "",
          "body": "",
          "accent": "#1a5276",
          "bg": "#ffffff",
          "imgEmoji": "",
          "caption": "",
          "banners": {}
        }]
      }""")
      .send(backend)
    extractField(response.body.getOrElse(""), "id")
  }

  def activateCampaignDirect(config: Config, advertiserId: String, campaignId: String): Unit = {
    basicRequest
      .put(uri"${config.baseUrl}/v1/advertisers/$advertiserId/campaigns/$campaignId/status")
      .header("Content-Type", "application/json")
      .body(s"""{"status": "active"}""")
      .send(backend)
  }

  def registerCategoryDirect(config: Config, campaignId: String, advertiserId: String, category: String): Unit = {
    basicRequest
      .post(uri"${config.baseUrl}/v1/auction/categories/$category/campaigns")
      .header("Content-Type", "application/json")
      .body(s"""{"campaigns": {"$campaignId": "$advertiserId"}}""")
      .send(backend)
  }

  def triggerAuctionDirect(config: Config, category: String, pageUrl: String): Unit = {
    val publisherId = "publisher-1"
    basicRequest
      .post(uri"${config.baseUrl}/v1/publishers/$publisherId/sites/${config.siteId}/classify")
      .header("Content-Type", "application/json")
      .body(s"""{
        "url": "$pageUrl",
        "categories": {"$category": 0.9},
        "slots": [{"slotId": "${config.slotId}", "width": 300, "height": 250}]
      }""")
      .send(backend)
  }

  def approveCreativeDirect(config: Config, creativeId: String, pageUrl: String): Unit = {
    val publisherId = "publisher-1"
    val response = basicRequest
      .post(uri"${config.baseUrl}/v1/publishers/$publisherId/sites/${config.siteId}/approval/approve")
      .header("Content-Type", "application/json")
      .body(s"""{
        "url": "$pageUrl",
        "slot": "${config.slotId}",
        "creativeId": "$creativeId"
      }""")
      .send(backend)
    if (response.code.isSuccess) println(s"       Approved: $creativeId")
  }

  /** Serve using production endpoint - returns full ServeResponse for tracking */
  def serveDirect(config: Config, pageUrl: String): Option[ServeResponse] = {
    requestServe(config, pageUrl).filter(_.selected)
  }

  def getTaxonomyStatsDirect(config: Config, category: String): Option[(Double, Double, Double)] = {
    val encCategory = java.net.URLEncoder.encode(category, "UTF-8")
    val response = basicRequest
      .get(uri"${config.baseUrl}/v1/taxonomy/categories/$encCategory/sites/${config.siteId}/stats")
      .send(backend)
    if (response.code.isSuccess) {
      val body = response.body.getOrElse("")
      Some((extractDouble(body, "wins"), extractDouble(body, "clicks"), extractDouble(body, "meanCtr")))
    } else None
  }

  def getAdServerStatsDirect(config: Config): Option[List[(String, Long, Long, Double)]] = {
    val publisherId = "publisher-1"
    val response = basicRequest
      .get(uri"${config.baseUrl}/v1/publishers/$publisherId/sites/${config.siteId}/adserver-stats")
      .send(backend)
    if (response.code.isSuccess) {
      val body = response.body.getOrElse("")
      val creativesPattern = """"creatives"\s*:\s*\[(.*?)\]""".r
      creativesPattern.findFirstMatchIn(body).map { m =>
        val itemPattern = """\{[^}]+\}""".r
        itemPattern.findAllIn(m.group(1)).map { item =>
          (extractField(item, "creativeId"), extractLong(item, "impressions"), extractLong(item, "clicks"), extractDouble(item, "ctr"))
        }.toList
      }
    } else None
  }

  /** Sample from Beta distribution for Thompson Sampling */
  def sampleBeta(clicks: Double, wins: Double): Double = {
    val alpha = math.max(0.01, clicks + 1.0)
    val beta = math.max(0.01, math.max(0.0, wins - clicks) + 1.0)
    new BetaDistribution(alpha, beta).sample()
  }

  // ==================== Restart Recovery Mode ====================

  /** Known IAB Ad Product → Content Category mappings for auction trigger.
    * Ad product "1529" (Travel and Tourism) maps to content "653" (Travel).
    */
  private val adProductToContentCategory: Map[String, String] = Map(
    "1529" -> "653",   // Travel and Tourism -> Travel
    "1524" -> "483",   // Sports -> Sports
    "1361" -> "420",   // Gaming -> Video Gaming
    "1551" -> "31",    // Vehicles -> Automotive
    "1010" -> "1",     // Arts & Entertainment -> Arts & Entertainment
    "1082" -> "357"    // Home & Garden -> Home & Garden
  )

  /** Resolve a content category ID from an ad product category ID.
    * Falls back to the ad product ID itself if no mapping found (works when category == content ID).
    */
  def resolveContentCategory(adProductCategory: String): String =
    adProductToContentCategory.getOrElse(adProductCategory, adProductCategory)

  /** Tracks per-creative stats from AdServer for comparison */
  case class AdServerCreativeStats(creativeId: String, impressions: Long, clicks: Long, ctr: Double)

  def runRestartRecoveryMode(config: Config, runId: String): Unit = {
    // Resolve content category from adProductCategory using IAB mapping
    // Ad product "1529" (Travel) maps to content "653" (Travel)
    val contentCategory = resolveContentCategory(config.adProductCategory)

    println(s"""
      |==========================================
      | Restart Recovery Mode
      |==========================================
      | Base URL:       ${config.baseUrl}
      | Site ID:        ${config.siteId}
      | Ad Product:     ${config.adProductCategory}
      | Content Cat:    $contentCategory (for auction)
      | Phase 1:        ${config.maxRequests} impressions (build stats)
      | Phase 3:        100 impressions (verify TS continues)
      |------------------------------------------
      | Creatives (with true CTRs):
      |${config.creatives.map(c => s"|   ${c.name}: ${(c.trueCtr * 100).toInt}%").mkString("\n")}
      |==========================================
      |""".stripMargin)

    // ── Setup phase (uses pacing-mode's proven approach) ──
    println("\n[1/5] Creating advertisers, campaigns, and creatives...")
    val creativeInfos = config.creatives.map { cc =>
      val advId = s"adv-${cc.name}-$runId"

      // Set advertiser budget (like pacing mode)
      basicRequest
        .put(uri"${config.baseUrl}/v1/advertisers/$advId/budget")
        .header("Content-Type", "application/json")
        .body(s"""{"dailyBudget": "500.0"}""")
        .send(backend)

      // Create campaign with valid IAB adProductCategory (like pacing mode)
      val campId = createCampaign(config, advId, contentCategory, s"${cc.name}-camp")

      // Create one creative per campaign
      val cid = createCreative(config, advId, campId, s"${cc.name}-cr")

      // Activate campaign
      activateCampaign(config, advId, campId)

      println(s"       Created: ${cc.name} -> adv=$advId, camp=$campId, cid=$cid")
      CreativeRaceInfo(advId, campId, cid, cc.name, cc.trueCtr)
    }

    Thread.sleep(500)

    // Trigger auction using real IAB content category
    println("\n[2/5] Triggering auction...")
    val pageUrl = s"https://publisher.com/recovery-$runId"
    triggerAuction(config, contentCategory, pageUrl)
    println(s"       Auction triggered: $pageUrl (content category: $contentCategory)")

    Thread.sleep(1000)

    // Bulk approve (like pacing mode)
    println("\n[3/5] Approving all pending creatives...")
    val (approved, failed) = approveAll(config, pageUrl)
    println(s"       Approved: $approved, Failed: $failed")
    if (approved == 0) {
      println("       WARNING: No creatives approved. Retrying after delay...")
      Thread.sleep(2000)
      val (approved2, failed2) = approveAll(config, pageUrl)
      println(s"       Retry: Approved: $approved2, Failed: $failed2")
    }

    Thread.sleep(2000) // Allow DData propagation

    // Verify serve index (like pacing mode)
    println("\n[4/5] Verifying ServeIndex...")
    val totalCandidates = checkServeIndex(config, pageUrl)
    println(s"       $totalCandidates candidates in ServeIndex")
    if (totalCandidates == 0) {
      println("       ERROR: No candidates in ServeIndex - setup failed")
      sys.exit(1)
    }

    // Configure pacing
    println("\n[5/5] Configuring pacing and resetting day starts...")
    updatePacingConfig(config)
    val campaigns = creativeInfos.map(info => CampaignInfo(info.advertiserId, info.campaignId))
    resetCampaignDayStarts(config, campaigns)
    Thread.sleep(200)

    // ── Phase 1: Build stats ──
    println(s"\n[PHASE 1] Building stats with ${config.maxRequests} impressions...")
    val phase1Stats = scala.collection.mutable.Map[String, (Int, Int)]()
    creativeInfos.foreach(info => phase1Stats(info.name) = (0, 0))
    var serveSelected = 0
    var serveSkipped = 0
    var serveErrors = 0

    (1 to config.maxRequests).foreach { i =>
      requestServe(config, pageUrl) match {
        case Some(resp) if resp.selected =>
          serveSelected += 1
          val servedInfo = creativeInfos.find(_.creativeId == resp.creativeId).getOrElse(creativeInfos.head)
          trackImpression(config, pageUrl, resp)
          val (imps, clicks) = phase1Stats.getOrElse(servedInfo.name, (0, 0))
          val clickRate = config.randomClickRate.getOrElse(servedInfo.trueCtr)
          if (rng.nextDouble() < clickRate) {
            trackClick(config, pageUrl, resp)
            phase1Stats(servedInfo.name) = (imps + 1, clicks + 1)
          } else {
            phase1Stats(servedInfo.name) = (imps + 1, clicks)
          }
        case Some(_) =>
          serveSkipped += 1
        case None =>
          serveErrors += 1
      }
      if (i % config.reportEvery == 0) {
        print(s"\r       Progress: $i/${config.maxRequests} | Selected: $serveSelected | Skipped: $serveSkipped | Errors: $serveErrors    ")
      }
      if (config.delayMs > 0) Thread.sleep(config.delayMs)
    }
    println()

    // Fetch pre-restart stats from AdServer
    println("\n[PHASE 1] Fetching pre-restart AdServer stats...")
    Thread.sleep(500) // Allow in-flight tracking to settle
    val preRestartStats = fetchAdServerStats(config, creativeInfos)
    if (preRestartStats.isEmpty) {
      println("       ERROR: Could not fetch pre-restart stats")
      sys.exit(1)
    }
    println("       Pre-restart stats:")
    preRestartStats.foreach { s =>
      val name = creativeInfos.find(_.creativeId == s.creativeId).map(_.name).getOrElse(s.creativeId.take(8))
      println(f"         $name%-10s: imps=${s.impressions}%5d, clicks=${s.clicks}%4d, CTR=${s.ctr * 100}%5.2f%%")
    }

    // ── Phase 2: Passivate + verify recovery ──
    println("\n[PHASE 2] Passivating AdServer entity...")
    val passivateOk = passivateAdServer(config)
    if (!passivateOk) {
      println("       WARNING: Passivate request may have failed (continuing anyway)")
    }
    println("       Waiting 2s for entity to fully stop...")
    Thread.sleep(2000)

    println("\n[PHASE 2] Fetching post-restart AdServer stats (triggers entity restart)...")
    // Retry loop: entity needs time to restart and load stats from DB
    var postRestartStats = List.empty[AdServerCreativeStats]
    var attempts = 0
    val maxAttempts = 10
    while (postRestartStats.isEmpty && attempts < maxAttempts) {
      attempts += 1
      postRestartStats = fetchAdServerStats(config, creativeInfos)
      if (postRestartStats.isEmpty) {
        print(s"\r       Waiting for entity restart... (attempt $attempts/$maxAttempts)    ")
        Thread.sleep(2000)
      }
    }
    println()
    if (postRestartStats.isEmpty) {
      println("       ERROR: Could not fetch post-restart stats after all retries")
      sys.exit(1)
    }
    println("       Post-restart stats:")
    postRestartStats.foreach { s =>
      val name = creativeInfos.find(_.creativeId == s.creativeId).map(_.name).getOrElse(s.creativeId.take(8))
      println(f"         $name%-10s: imps=${s.impressions}%5d, clicks=${s.clicks}%4d, CTR=${s.ctr * 100}%5.2f%%")
    }

    // Compare pre/post stats
    println("\n[PHASE 2] Comparing pre-restart vs post-restart stats...")
    val tolerance = 0.10 // 10% tolerance for in-flight events
    var recoveryPass = true
    preRestartStats.foreach { pre =>
      val post = postRestartStats.find(_.creativeId == pre.creativeId)
      post match {
        case Some(p) =>
          val impDiff = math.abs(pre.impressions - p.impressions)
          val impTolerance = math.max(1, (pre.impressions * tolerance).toLong)
          val clickDiff = math.abs(pre.clicks - p.clicks)
          val clickTolerance = math.max(1, (pre.clicks * tolerance).toLong)
          val impOk = impDiff <= impTolerance
          val clickOk = clickDiff <= clickTolerance
          val name = creativeInfos.find(_.creativeId == pre.creativeId).map(_.name).getOrElse(pre.creativeId.take(8))
          val impVerdict = if (impOk) "OK" else "FAIL"
          val clickVerdict = if (clickOk) "OK" else "FAIL"
          println(f"         $name%-10s: imps ${pre.impressions}%d -> ${p.impressions}%d (diff=$impDiff%d, tol=$impTolerance%d) [$impVerdict]  clicks ${pre.clicks}%d -> ${p.clicks}%d (diff=$clickDiff%d, tol=$clickTolerance%d) [$clickVerdict]")
          if (!impOk || !clickOk) recoveryPass = false
        case None =>
          val name = creativeInfos.find(_.creativeId == pre.creativeId).map(_.name).getOrElse(pre.creativeId.take(8))
          println(f"         $name%-10s: MISSING in post-restart stats [FAIL]")
          recoveryPass = false
      }
    }

    val recoveryVerdict = if (recoveryPass) "PASS" else "FAIL"
    println(s"\n       Recovery verdict: $recoveryVerdict")

    // ── Phase 3: Verify TS continues ──
    val phase3Impressions = 100
    println(s"\n[PHASE 3] Running $phase3Impressions more impressions to verify TS continues...")
    val phase3Stats = scala.collection.mutable.Map[String, (Int, Int)]()
    creativeInfos.foreach(info => phase3Stats(info.name) = (0, 0))
    var phase3Selected = 0
    var phase3Skipped = 0

    (1 to phase3Impressions).foreach { i =>
      requestServe(config, pageUrl) match {
        case Some(resp) if resp.selected =>
          phase3Selected += 1
          val servedInfo = creativeInfos.find(_.creativeId == resp.creativeId).getOrElse(creativeInfos.head)
          trackImpression(config, pageUrl, resp)
          val (imps, clicks) = phase3Stats.getOrElse(servedInfo.name, (0, 0))
          val clickRate = config.randomClickRate.getOrElse(servedInfo.trueCtr)
          if (rng.nextDouble() < clickRate) {
            trackClick(config, pageUrl, resp)
            phase3Stats(servedInfo.name) = (imps + 1, clicks + 1)
          } else {
            phase3Stats(servedInfo.name) = (imps + 1, clicks)
          }
        case _ =>
          phase3Skipped += 1
      }
      if (config.delayMs > 0) Thread.sleep(config.delayMs)
    }
    println(s"       Phase 3: $phase3Selected selected, $phase3Skipped skipped")

    // Fetch final stats
    println("\n[PHASE 3] Fetching final AdServer stats...")
    Thread.sleep(500)
    val finalStats = fetchAdServerStats(config, creativeInfos)

    // ── Report ──
    println(s"""
      |
      |==========================================
      | Restart Recovery Report
      |==========================================
      |""".stripMargin)

    // Comparison table
    println("  Creative Stats Comparison:")
    println(f"  ${"Creative"}%-10s | ${"Pre-Restart"}%-20s | ${"Post-Restart"}%-20s | ${"Final"}%-20s")
    println("  " + "-" * 80)
    creativeInfos.foreach { info =>
      val pre = preRestartStats.find(_.creativeId == info.creativeId)
      val post = postRestartStats.find(_.creativeId == info.creativeId)
      val fin = finalStats.find(_.creativeId == info.creativeId)
      val preStr = pre.map(s => f"${s.impressions}%d imp, ${s.clicks}%d clk").getOrElse("N/A")
      val postStr = post.map(s => f"${s.impressions}%d imp, ${s.clicks}%d clk").getOrElse("N/A")
      val finStr = fin.map(s => f"${s.impressions}%d imp, ${s.clicks}%d clk").getOrElse("N/A")
      println(f"  ${info.name}%-10s | $preStr%-20s | $postStr%-20s | $finStr%-20s")
    }
    println()

    // TS learning check: highest true-CTR creative should have most impressions in final stats
    val bestCreative = creativeInfos.maxBy(_.trueCtr)
    val finalBestImps = finalStats.find(_.creativeId == bestCreative.creativeId).map(_.impressions).getOrElse(0L)
    val finalTotalImps = finalStats.map(_.impressions).sum
    val bestFraction = if (finalTotalImps > 0) finalBestImps.toDouble / finalTotalImps else 0.0
    val tsLearned = bestFraction > (1.0 / creativeInfos.size) // Better than uniform
    val tsVerdict = if (tsLearned) "PASS" else "WEAK"
    println(f"  TS Learning: ${bestCreative.name} has ${bestFraction * 100}%.1f%% of impressions ($tsVerdict - ${if (tsLearned) "better than uniform" else "not clearly better than uniform"})")
    println(s"  Recovery:    $recoveryVerdict")
    println()

    val overallPass = recoveryPass
    if (overallPass) {
      println("  OVERALL: PASS - Stats recovered correctly after restart")
    } else {
      println("  OVERALL: FAIL - Stats did not recover correctly")
    }
    println()
  }

  /** Call POST passivate endpoint to force AdServer entity to stop */
  def passivateAdServer(config: Config): Boolean = {
    try {
      val publisherId = "publisher-1"
      val resp = basicRequest
        .post(uri"${config.baseUrl}/v1/publishers/$publisherId/sites/${config.siteId}/passivate")
        .send(backend)
      resp.code.isSuccess || resp.code.code == 204
    } catch {
      case _: Exception => false
    }
  }

  /** Fetch per-creative stats from AdServer endpoint.
    * Returns only stats for creatives we know about (from infos list).
    */
  def fetchAdServerStats(config: Config, infos: List[CreativeRaceInfo]): List[AdServerCreativeStats] = {
    val knownIds = infos.map(_.creativeId).toSet
    getAdServerStatsDirect(config) match {
      case Some(stats) =>
        stats
          .map { case (cid, imps, clicks, ctr) => AdServerCreativeStats(cid, imps, clicks, ctr) }
          .filter(s => knownIds.contains(s.creativeId))
      case None => List.empty
    }
  }

  // ==================== Parsing ====================

  def parseArgs(args: Array[String]): Config = {
    var scenarioFile: Option[String] = None
    var baseUrl: Option[String] = None
    var siteId: Option[String] = None
    var mode: Option[String] = None
    var categories: Option[List[CategoryConfig]] = None
    var creatives: Option[List[CreativeRaceConfig]] = None
    var maxRequests: Option[Int] = None
    var reportEvery: Option[Int] = None
    var continuous = false
    var thompsonSelection = false
    var randomClickRate: Option[Double] = None
    var advertiserBudget: Option[Double] = None
    var quietPeriodProbability: Option[Double] = None
    var quietPeriodMinMs: Option[Int] = None
    var quietPeriodMaxMs: Option[Int] = None
    var i = 0

    while (i < args.length) {
      args(i) match {
        case "--scenario" if i + 1 < args.length =>
          scenarioFile = Some(args(i + 1))
          i += 2
        case "--base-url" if i + 1 < args.length =>
          baseUrl = Some(args(i + 1))
          i += 2
        case "--site" if i + 1 < args.length =>
          siteId = Some(args(i + 1))
          i += 2
        case "--mode" if i + 1 < args.length =>
          mode = Some(args(i + 1))
          i += 2
        case "--categories" if i + 1 < args.length =>
          // Parse "sports:0.08,tech:0.05,gaming:0.02"
          categories = Some(args(i + 1).split(",").map { s =>
            val parts = s.split(":")
            CategoryConfig(parts(0).trim, parts(1).trim.toDouble)
          }.toList)
          i += 2
        case "--creatives" if i + 1 < args.length =>
          // Parse "alpha:0.10,beta:0.05,gamma:0.02"
          creatives = Some(args(i + 1).split(",").map { s =>
            val parts = s.split(":")
            CreativeRaceConfig(parts(0).trim, parts(1).trim.toDouble)
          }.toList)
          i += 2
        case "--impressions" | "--max-requests" if i + 1 < args.length =>
          maxRequests = Some(args(i + 1).toInt)
          i += 2
        case "--report-every" if i + 1 < args.length =>
          reportEvery = Some(args(i + 1).toInt)
          i += 2
        case "--continuous" =>
          continuous = true
          i += 1
        case "--thompson-selection" =>
          thompsonSelection = true
          i += 1
        case "--random-clicks" if i + 1 < args.length =>
          randomClickRate = Some(args(i + 1).toDouble)
          i += 2
        case "--advertiser-budget" if i + 1 < args.length =>
          advertiserBudget = Some(args(i + 1).toDouble)
          i += 2
        case "--quiet-probability" if i + 1 < args.length =>
          quietPeriodProbability = Some(args(i + 1).toDouble)
          i += 2
        case "--quiet-min-ms" if i + 1 < args.length =>
          quietPeriodMinMs = Some(args(i + 1).toInt)
          i += 2
        case "--quiet-max-ms" if i + 1 < args.length =>
          quietPeriodMaxMs = Some(args(i + 1).toInt)
          i += 2
        case "--help" =>
          printHelp()
          sys.exit(0)
        case _ =>
          i += 1
      }
    }

    // Build config: either from scenario file or from command line
    var config = scenarioFile match {
      case Some(path) => loadScenario(path)
      case None => Config()
    }

    // Apply command-line overrides
    baseUrl.foreach(url => config = config.copy(baseUrl = url))
    siteId.foreach(id => config = config.copy(siteId = id))
    mode.foreach(m => config = config.copy(mode = m))
    categories.foreach(cats => config = config.copy(categories = cats))
    creatives.foreach(crs => config = config.copy(creatives = crs))
    maxRequests.foreach(n => config = config.copy(maxRequests = n))
    reportEvery.foreach(n => config = config.copy(reportEvery = n))
    if (continuous) config = config.copy(continuous = true)
    if (thompsonSelection) config = config.copy(thompsonSelection = true)
    randomClickRate.foreach(r => config = config.copy(randomClickRate = Some(r)))
    advertiserBudget.foreach(b => config = config.copy(advertiserBudget = Some(b)))
    quietPeriodProbability.foreach(p => config = config.copy(quietPeriodProbability = p))
    quietPeriodMinMs.foreach(ms => config = config.copy(quietPeriodMinMs = ms))
    quietPeriodMaxMs.foreach(ms => config = config.copy(quietPeriodMaxMs = ms))

    // Validate mode
    val validModes = Set("pacing", "auction", "category-race", "creative-race", "restart-recovery")
    if (!validModes.contains(config.mode)) {
      println(s"Error: Invalid mode '${config.mode}'. Valid modes: ${validModes.mkString(", ")}")
      sys.exit(1)
    }

    config
  }

  def printHelp(): Unit = {
    println("""
      |RunScenario - Unified test script for promovolve platform
      |
      |Usage:
      |  scala-cli scripts/RunScenario.scala -- [options]
      |
      |Options:
      |  --scenario <file>     Load config from JSON scenario file
      |  --mode <mode>         Test mode: pacing, auction, category-race, creative-race, restart-recovery
      |  --base-url <url>      API base URL (default: http://localhost:8080)
      |  --site <id>           Site ID override
      |  --impressions <n>     Number of impressions/requests
      |  --report-every <n>    Report stats every N impressions
      |  --continuous          Run indefinitely
      |  --thompson-selection  Use Thompson Sampling for selection in race modes
      |  --random-clicks <r>   Override CTR with flat rate (e.g., 0.05)
      |  --advertiser-budget <$> Override advertiser budget (default: sum of campaign budgets)
      |                         Set lower than campaign total to test budget exhaustion
      |  --quiet-probability <p> Probability (0.0-1.0) of triggering a quiet period
      |  --quiet-min-ms <ms>   Minimum quiet period duration (default: 35000)
      |  --quiet-max-ms <ms>   Maximum quiet period duration (default: 60000)
      |  --categories <spec>   Categories for category-race (e.g., "sports:0.08,tech:0.05")
      |  --creatives <spec>    Creatives for creative-race (e.g., "alpha:0.10,beta:0.05")
      |
      |IAB Taxonomy Targeting (in scenario JSON):
      |  "adProductCategory": "1529"    IAB Ad Product ID (e.g., 1529=Travel)
      |  "additionalCategories": ["653"] Extra content categories for incomplete mappings
      |  "runOfNetwork": true           Target all inventory regardless of category
      |
      |Examples:
      |  # Pacing test with scenario file
      |  scala-cli scripts/RunScenario.scala -- --scenario scenarios/continuous.json
      |
      |  # Category race test
      |  scala-cli scripts/RunScenario.scala -- --mode category-race --impressions 200
      |
      |  # Creative race with Thompson Sampling selection
      |  scala-cli scripts/RunScenario.scala -- --mode creative-race --thompson-selection --continuous
      |
      |IAB Ad Product IDs (common):
      |  1551 = Vehicles             1529 = Travel
      |  1524 = Sports               1361 = Gaming
      |  1010 = Arts & Entertainment 1082 = Home & Garden
      |""".stripMargin)
  }

  def loadScenario(path: String): Config = {
    val file = new File(path)
    val actualFile = if (file.exists()) file else {
      val altFile = new File(s"scripts/$path")
      if (!altFile.exists()) {
        println(s"Error: Scenario file not found: $path")
        sys.exit(1)
      }
      altFile
    }

    val source = Source.fromFile(actualFile)
    try {
      val json = source.mkString.replaceAll("\\s+", " ")
      println(s"Loading scenario: ${extractString(json, "name")} - ${extractString(json, "description")}")

      val budget = extractDouble(json, "budget")
      val advertiserBudget = extractDouble(json, "advertiserBudget") match {
        case 0.0 => None  // Not specified
        case b => Some(b)
      }
      val cpm = extractDouble(json, "cpm")
      val cpms = parseDoubleArray(json, "cpms") match {
        case arr if arr.nonEmpty => Some(arr)
        case _ => None
      }
      val budgets = parseDoubleArray(json, "budgets") match {
        case arr if arr.nonEmpty => Some(arr)
        case _ => None
      }
      val dayDurationSeconds = extractInt(json, "dayDurationSeconds")
      if (dayDurationSeconds > 86400) {
        println(s"Error: dayDurationSeconds ($dayDurationSeconds) cannot exceed 86400 (24 hours)")
        sys.exit(1)
      }
      val weekdayShapeVolumes = parseDoubleArray(json, "weekdayShapeVolumes")
      val weekendShapeVolumes = parseDoubleArray(json, "weekendShapeVolumes")
      val advertisers = extractInt(json, "advertisers").max(1)
      val campaignsPerAdvertiser = extractInt(json, "campaignsPerAdvertiser").max(1)
      val creativesPerCampaign = extractInt(json, "creativesPerCampaign").max(1)
      val category = extractString(json, "category") match {
        case "" => "perf-test"
        case c => c
      }
      val continuous = json.contains("\"continuous\": true") || json.contains("\"continuous\":true")
      val delayMs = extractInt(json, "delayMs").max(1)
      val reportEvery = extractInt(json, "reportEvery").max(10)
      val clickRate = extractDouble(json, "clickRate") match {
        case 0.0 => 0.05
        case r => r
      }
      val variableDelay = json.contains("\"variableDelay\": true") || json.contains("\"variableDelay\":true")
      val quietPeriodProbability = extractDouble(json, "quietPeriodProbability")
      val quietPeriodMinMs = extractInt(json, "quietPeriodMinMs") match {
        case 0 => 35000  // Default: 35 seconds (above 30s staleness threshold)
        case n => n
      }
      val quietPeriodMaxMs = extractInt(json, "quietPeriodMaxMs") match {
        case 0 => 60000  // Default: 60 seconds
        case n => n
      }
      val refreshIntervalSeconds = extractInt(json, "refreshIntervalSeconds") match {
        case 0 => 1800  // Default: 30 minutes
        case n => n
      }

      // IAB Taxonomy targeting (adProductCategory is required with default)
      val adProductCategory = extractString(json, "adProductCategory") match {
        case "" => "1529"  // Default: Travel
        case s => s
      }
      val additionalCategories = parseStringArray(json, "additionalCategories")
      val runOfNetwork = json.contains("\"runOfNetwork\": true") || json.contains("\"runOfNetwork\":true")
      val stopAfterDays = extractInt(json, "stopAfterDays")
      val churnEvents = parseChurnEvents(json)

      Config(
        budget = budget,
        advertiserBudget = advertiserBudget,
        cpm = cpm,
        cpms = cpms,
        budgets = budgets,
        dayDurationSeconds = dayDurationSeconds,
        weekdayShapeVolumes = if (weekdayShapeVolumes.isEmpty) None else Some(weekdayShapeVolumes),
        weekendShapeVolumes = if (weekendShapeVolumes.isEmpty) None else Some(weekendShapeVolumes),
        advertisers = advertisers,
        campaignsPerAdvertiser = campaignsPerAdvertiser,
        creativesPerCampaign = creativesPerCampaign,
        category = category,
        adProductCategory = adProductCategory,
        additionalCategories = additionalCategories,
        runOfNetwork = runOfNetwork,
        continuous = continuous,
        delayMs = delayMs,
        reportEvery = reportEvery,
        clickRate = clickRate,
        variableDelay = variableDelay,
        quietPeriodProbability = quietPeriodProbability,
        quietPeriodMinMs = quietPeriodMinMs,
        quietPeriodMaxMs = quietPeriodMaxMs,
        refreshIntervalSeconds = refreshIntervalSeconds,
        stopAfterDays = stopAfterDays,
        churnEvents = churnEvents
      )
    } finally {
      source.close()
    }
  }

  def formatDuration(seconds: Int): String = {
    if (seconds >= 3600) f"${seconds / 3600.0}%.1fh"
    else if (seconds >= 60) s"${seconds / 60}min"
    else s"${seconds}s"
  }

  def extractField(json: String, field: String): String = {
    val pattern = s""""$field"\\s*:\\s*"([^"]+)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1)).getOrElse("")
  }

  def extractString(json: String, field: String): String = extractField(json, field)

  def extractDouble(json: String, field: String): Double = {
    // Try unquoted number first: "field": 123.45
    val unquotedPattern = s""""$field"\\s*:\\s*([0-9.]+)""".r
    unquotedPattern.findFirstMatchIn(json).map(_.group(1).toDouble).getOrElse {
      // Try quoted string number: "field": "123.45"
      val quotedPattern = s""""$field"\\s*:\\s*"([0-9.]+)"""".r
      quotedPattern.findFirstMatchIn(json).map(_.group(1).toDouble).getOrElse(0.0)
    }
  }

  def extractInt(json: String, field: String): Int = {
    val pattern = s""""$field"\\s*:\\s*([0-9]+)""".r
    pattern.findFirstMatchIn(json).map(_.group(1).toInt).getOrElse(0)
  }

  def extractLong(json: String, field: String): Long = {
    val pattern = s""""$field"\\s*:\\s*([0-9]+)""".r
    pattern.findFirstMatchIn(json).map(_.group(1).toLong).getOrElse(0L)
  }

  def extractOptionalField(json: String, field: String): Option[String] = {
    val pattern = s""""$field"\\s*:\\s*(?:"([^"]*)"|null)""".r
    pattern.findFirstMatchIn(json).flatMap(m => Option(m.group(1))).map(_.replace("\\n", "\n"))
  }

  def parseDoubleArray(json: String, field: String): Array[Double] = {
    val pattern = s""""$field"\\s*:\\s*\\[([^\\]]+)\\]""".r
    pattern.findFirstMatchIn(json) match {
      case Some(m) => m.group(1).split(",").map(_.trim.toDouble)
      case None => Array.empty
    }
  }

  def parseStringArray(json: String, field: String): List[String] = {
    val pattern = s""""$field"\\s*:\\s*\\[([^\\]]+)\\]""".r
    pattern.findFirstMatchIn(json) match {
      case Some(m) =>
        m.group(1).split(",").map(_.trim.replaceAll("\"", "")).filter(_.nonEmpty).toList
      case None => List.empty
    }
  }

  /** Parse churn events: [{"day": 3, "action": "pause", "advertiser": 2}, ...] */
  def parseChurnEvents(json: String): List[ChurnEvent] = {
    val pattern = """"churn"\s*:\s*\[([^\]]+)\]""".r
    pattern.findFirstMatchIn(json) match {
      case Some(m) =>
        val objPattern = """\{[^}]+\}""".r
        objPattern.findAllIn(m.group(1)).flatMap { obj =>
          val day = extractInt(obj, "day")
          val action = extractString(obj, "action")
          val advertiser = extractInt(obj, "advertiser")
          if (day > 0 && action.nonEmpty && advertiser > 0) Some(ChurnEvent(day, action, advertiser))
          else None
        }.toList
      case None => Nil
    }
  }
}