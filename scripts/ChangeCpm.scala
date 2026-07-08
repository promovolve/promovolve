//> using scala 3.3.1
//> using dep com.softwaremill.sttp.client4::core:4.0.14
//> using dep com.softwaremill.sttp.client4::spray-json:4.0.14
//> using dep io.spray::spray-json:1.3.6

import sttp.client4.*
import sttp.client4.sprayJson.*
import spray.json.*
import spray.json.DefaultJsonProtocol.*

/** Change CPM Script
  *
  * This script:
  * 1. Gets serve-index to find current CPM and campaign info
  * 2. Updates the campaign's maxCpm
  * 3. Waits for re-auction to propagate the change
  * 4. Verifies the new CPM in serve-index
  *
  * Usage:
  *   scala-cli ChangeCpm.scala -- --site-id <siteId> --url <url> --slot-id <slotId> --cpm <newCpm>
  *
  * Or with explicit campaign:
  *   scala-cli ChangeCpm.scala -- --advertiser-id <advId> --campaign-id <campId> --cpm <newCpm>
  *
  * Example:
  *   scala-cli ChangeCpm.scala -- \
  *     --site-id site-67738-84704-4500 \
  *     --url "https://publisher.com/perf-test-67738-84704-4500" \
  *     --slot-id slot-header \
  *     --cpm 8.50
  */
object ChangeCpm {

  val baseUrl = "http://localhost:8080"

  // JSON protocols
  case class CandidateRes(creativeId: String, campaignId: String, advertiserId: String, cpm: Double, category: String)
  case class ServeIndexRes(url: String, slotId: String, found: Boolean, candidateCount: Int, candidates: Vector[CandidateRes])
  case class CampaignUpdateRes(id: String, status: String, maxCpm: Double)
  case class ErrorResponse(error: String, message: String)

  given JsonFormat[CandidateRes]      = jsonFormat5(CandidateRes.apply)
  given JsonFormat[ServeIndexRes]     = jsonFormat5(ServeIndexRes.apply)
  given JsonFormat[CampaignUpdateRes] = jsonFormat3(CampaignUpdateRes.apply)
  given JsonFormat[ErrorResponse]     = jsonFormat2(ErrorResponse.apply)

  def main(args: Array[String]): Unit = {
    val config = parseArgs(args)
    val backend = DefaultSyncBackend()

    try {
      println(s"\n=== Change CPM ===")
      println(s"New CPM: ${config.newCpm}")

      val (advertiserId, campaignId, currentCpm) = if (config.advertiserId.isDefined && config.campaignId.isDefined) {
        // Direct mode - use provided IDs
        println(s"Advertiser: ${config.advertiserId.get}")
        println(s"Campaign: ${config.campaignId.get}")
        (config.advertiserId.get, config.campaignId.get, None)
      } else {
        // Discovery mode - find from serve-index
        println(s"Site: ${config.siteId}")
        println(s"URL: ${config.url}")
        println(s"Slot: ${config.slotId}")

        println("\n[1/3] Getting serve-index to find campaign...")
        val publisherId = "publisher-1"
        // Note: sttp's uri interpolation handles URL encoding automatically
        val indexResp = basicRequest
          .get(uri"$baseUrl/v1/publishers/$publisherId/sites/${config.siteId}/serve-index?url=${config.url}&slotId=${config.slotId}")
          .response(asJson[ServeIndexRes])
          .send(backend)

        val serveIndex = indexResp.body match {
          case Right(s) => s
          case Left(err) =>
            println(s"ERROR: Failed to get serve-index: $err")
            sys.exit(1)
        }

        if (!serveIndex.found || serveIndex.candidates.isEmpty) {
          println("ERROR: No candidates in serve-index")
          sys.exit(1)
        }

        val candidate = serveIndex.candidates.head
        val campId = candidate.campaignId
        val advId = candidate.advertiserId
        val currCpm = candidate.cpm

        println(s"   Found campaign: $campId")
        println(s"   Advertiser: $advId")
        println(s"   Current CPM: $currCpm")
        println(s"   Candidates: ${serveIndex.candidateCount}")
        serveIndex.candidates.foreach { c =>
          println(s"     - ${c.creativeId} (cpm=${c.cpm})")
        }

        (advId, campId, Some(currCpm))
      }

      // Step 2: Update campaign CPM
      println(s"\n[2/3] Updating campaign CPM to ${config.newCpm}...")
      val updateResp = basicRequest
        .patch(uri"$baseUrl/v1/advertisers/$advertiserId/campaigns/$campaignId")
        .body(s"""{"maxCpm":${config.newCpm}}""")
        .contentType("application/json")
        .response(asString)
        .send(backend)

      if (updateResp.code.isSuccess) {
        println(s"   Campaign updated successfully")
      } else {
        println(s"   WARNING: Update returned ${updateResp.code}: ${updateResp.body}")
      }

      // Wait for re-auction
      println("   Waiting for re-auction (3s)...")
      Thread.sleep(3000)

      // Step 3: Verify
      if (config.siteId.nonEmpty && config.url.nonEmpty) {
        println("\n[3/3] Verifying new CPM in serve-index...")
        val verifyPublisherId = "publisher-1"
        // Note: sttp's uri interpolation handles URL encoding automatically
        val verifyResp = basicRequest
          .get(uri"$baseUrl/v1/publishers/$verifyPublisherId/sites/${config.siteId}/serve-index?url=${config.url}&slotId=${config.slotId}")
          .response(asJson[ServeIndexRes])
          .send(backend)

        verifyResp.body match {
          case Right(idx) =>
            println(s"   Candidates in serve-index: ${idx.candidateCount}")
            var allUpdated = true
            idx.candidates.foreach { c =>
              val status = if (math.abs(c.cpm - config.newCpm) < 0.01) "OK" else {
                allUpdated = false
                "PENDING"
              }
              println(s"     - ${c.creativeId} cpm=${c.cpm} [$status]")
            }
            if (allUpdated) {
              println(s"\n   SUCCESS: All candidates updated to CPM ${config.newCpm}")
            } else {
              println(s"\n   NOTE: Some candidates still have old CPM (may need more time)")
            }
          case Left(err) =>
            println(s"   Failed to verify: $err")
        }
      } else {
        println("\n[3/3] Skipping verification (no site/url provided)")
      }

      println("\n=== Complete ===\n")

    } finally {
      backend.close()
    }
  }

  case class Config(
    siteId: String = "",
    url: String = "",
    slotId: String = "slot-header",
    advertiserId: Option[String] = None,
    campaignId: Option[String] = None,
    newCpm: Double = 0.0
  )

  def parseArgs(args: Array[String]): Config = {
    var config = Config()
    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--site-id" =>
          config = config.copy(siteId = args(i + 1))
          i += 2
        case "--url" =>
          config = config.copy(url = args(i + 1))
          i += 2
        case "--slot-id" =>
          config = config.copy(slotId = args(i + 1))
          i += 2
        case "--advertiser-id" =>
          config = config.copy(advertiserId = Some(args(i + 1)))
          i += 2
        case "--campaign-id" =>
          config = config.copy(campaignId = Some(args(i + 1)))
          i += 2
        case "--cpm" =>
          config = config.copy(newCpm = args(i + 1).toDouble)
          i += 2
        case other =>
          println(s"Unknown option: $other")
          printUsage()
          sys.exit(1)
      }
    }

    if (config.newCpm <= 0) {
      println("ERROR: --cpm is required and must be positive")
      printUsage()
      sys.exit(1)
    }

    // Either need site-id+url OR advertiser-id+campaign-id
    val hasDiscovery = config.siteId.nonEmpty && config.url.nonEmpty
    val hasDirect = config.advertiserId.isDefined && config.campaignId.isDefined

    if (!hasDiscovery && !hasDirect) {
      println("ERROR: Need either (--site-id and --url) or (--advertiser-id and --campaign-id)")
      printUsage()
      sys.exit(1)
    }

    config
  }

  def printUsage(): Unit = {
    println("""
Usage: scala-cli ChangeCpm.scala -- [options]

Options (discovery mode):
  --site-id <id>        Site ID
  --url <url>           Page URL
  --slot-id <id>        Slot ID (default: slot-header)
  --cpm <value>         New CPM value (required)

Options (direct mode):
  --advertiser-id <id>  Advertiser ID
  --campaign-id <id>    Campaign ID
  --cpm <value>         New CPM value (required)

Examples:
  # Discovery mode - finds campaign from serve-index
  scala-cli ChangeCpm.scala -- \
    --site-id site-67738-84704-4500 \
    --url "https://publisher.com/perf-test-67738-84704-4500" \
    --cpm 8.50

  # Direct mode - specify campaign directly
  scala-cli ChangeCpm.scala -- \
    --advertiser-id adv-1-67738-84704-4500 \
    --campaign-id 01KFAN0KKCFR81MJCWHTH1WVX3 \
    --cpm 8.50
""")
  }
}