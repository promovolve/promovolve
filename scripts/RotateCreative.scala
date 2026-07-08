//> using scala 3.3.1
//> using dep com.softwaremill.sttp.client4::core:4.0.14
//> using dep com.softwaremill.sttp.client4::spray-json:4.0.14
//> using dep io.spray::spray-json:1.3.6

import sttp.client4.*
import sttp.client4.sprayJson.*
import sttp.client4.ResponseException
import spray.json.*
import spray.json.DefaultJsonProtocol.*

/** Rotate Creative Script
  *
  * This script:
  * 1. Gets creative stats to find the creative with most impressions
  * 2. Discovers serve-index keys for the site to find URL/slot
  * 3. Unassigns that creative from its campaign
  * 4. Creates a new creative under the same campaign
  * 5. Approves all pending creatives for the slot
  *
  * Usage:
  *   scala-cli RotateCreative.scala -- --site-id <siteId>
  *
  * Example:
  *   scala-cli RotateCreative.scala -- --site-id site-67738-84704-4500
  */
object RotateCreative {

  val baseUrl = "http://localhost:8080"

  // JSON protocols
  case class CreativeStatsItem(creativeId: String, impressions: Int, clicks: Int, ctr: Double)
  case class AdServerStatsRes(siteId: String, creatives: Vector[CreativeStatsItem])
  case class CandidateRes(creativeId: String, campaignId: String, advertiserId: String, cpm: Double, category: String)
  case class ServeIndexRes(url: String, slotId: String, found: Boolean, candidateCount: Int, candidates: Vector[CandidateRes])
  case class ServeIndexKeyItem(url: String, slotId: String)
  case class ServeIndexKeysRes(siteId: String, keys: Vector[ServeIndexKeyItem])
  case class TestCreateCreativeRes(id: String, campaignId: String, status: String)
  case class ApproveAllRes(url: String, slotId: String, approved: Int, failed: Int)
  case class ErrorResponse(error: String, message: String)

  given JsonFormat[CreativeStatsItem] = jsonFormat4(CreativeStatsItem.apply)
  given JsonFormat[AdServerStatsRes]  = jsonFormat2(AdServerStatsRes.apply)
  given JsonFormat[CandidateRes]      = jsonFormat5(CandidateRes.apply)
  given JsonFormat[ServeIndexRes]     = jsonFormat5(ServeIndexRes.apply)
  given JsonFormat[ServeIndexKeyItem] = jsonFormat2(ServeIndexKeyItem.apply)
  given JsonFormat[ServeIndexKeysRes] = jsonFormat2(ServeIndexKeysRes.apply)
  given JsonFormat[TestCreateCreativeRes] = jsonFormat3(TestCreateCreativeRes.apply)
  given JsonFormat[ApproveAllRes]     = jsonFormat4(ApproveAllRes.apply)
  given JsonFormat[ErrorResponse]     = jsonFormat2(ErrorResponse.apply)

  def main(args: Array[String]): Unit = {
    val config = parseArgs(args)
    val backend = DefaultSyncBackend()

    try {
      println(s"\n=== Rotate Creative ===")
      println(s"Site: ${config.siteId}")

      // Step 1: Get creative stats
      println("\n[1/6] Getting creative stats...")
      val publisherId = "publisher-1"
      val statsResp = basicRequest
        .get(uri"$baseUrl/v1/publishers/$publisherId/sites/${config.siteId}/adserver-stats")
        .response(asJson[AdServerStatsRes])
        .send(backend)

      val stats = statsResp.body match {
        case Right(s) => s
        case Left(err) =>
          println(s"ERROR: Failed to get stats: $err")
          sys.exit(1)
      }

      if (stats.creatives.isEmpty) {
        println("ERROR: No creatives found in stats")
        sys.exit(1)
      }

      // Find creative with most impressions
      val topCreative = stats.creatives.maxBy(_.impressions)
      println(s"   Found ${stats.creatives.size} creatives")
      println(s"   Top creative: ${topCreative.creativeId} (${topCreative.impressions} imps)")

      // Step 2: Discover serve-index keys for the site
      println("\n[2/6] Discovering serve-index keys...")
      val keysResp = basicRequest
        .get(uri"$baseUrl/v1/publishers/$publisherId/sites/${config.siteId}/serve-index/keys")
        .response(asJson[ServeIndexKeysRes])
        .send(backend)

      val keysResult = keysResp.body match {
        case Right(r) => r
        case Left(err) =>
          println(s"ERROR: Failed to get serve-index keys: $err")
          sys.exit(1)
      }

      if (keysResult.keys.isEmpty) {
        println("ERROR: No serve-index entries found for site")
        sys.exit(1)
      }

      println(s"   Found ${keysResult.keys.size} serve-index entries")
      keysResult.keys.foreach { k =>
        println(s"     - url=${k.url} slot=${k.slotId}")
      }

      // Step 3: Find the serve-index entry containing our top creative
      println("\n[3/6] Finding creative location...")
      var targetUrl = ""
      var targetSlotId = ""
      var targetCandidate: Option[CandidateRes] = None

      for (key <- keysResult.keys if targetCandidate.isEmpty) {
        // Note: sttp's uri interpolation handles URL encoding automatically
        val indexResp = basicRequest
          .get(uri"$baseUrl/v1/publishers/$publisherId/sites/${config.siteId}/serve-index?url=${key.url}&slotId=${key.slotId}")
          .response(asJson[ServeIndexRes])
          .send(backend)

        indexResp.body match {
          case Right(idx) if idx.found =>
            val candidateOpt = idx.candidates.find(_.creativeId == topCreative.creativeId)
            if (candidateOpt.isDefined) {
              targetUrl = key.url
              targetSlotId = key.slotId
              targetCandidate = candidateOpt
              println(s"   Found creative in: url=${key.url} slot=${key.slotId}")
            }
          case _ => // continue
        }
      }

      // If not found, use first entry and first candidate
      val (url, slotId, candidate) = targetCandidate match {
        case Some(c) => (targetUrl, targetSlotId, c)
        case None =>
          println(s"   WARNING: Top creative not found in serve-index, using first entry")
          val firstKey = keysResult.keys.head
          // Note: sttp's uri interpolation handles URL encoding automatically
          val indexResp = basicRequest
            .get(uri"$baseUrl/v1/publishers/$publisherId/sites/${config.siteId}/serve-index?url=${firstKey.url}&slotId=${firstKey.slotId}")
            .response(asJson[ServeIndexRes])
            .send(backend)

          val idx = indexResp.body match {
            case Right(s) if s.found && s.candidates.nonEmpty => s
            case _ =>
              println("ERROR: No candidates in first serve-index entry")
              sys.exit(1)
          }
          (firstKey.url, firstKey.slotId, idx.candidates.head)
      }

      val campaignId = candidate.campaignId
      val advertiserId = candidate.advertiserId

      println(s"   URL: $url")
      println(s"   Slot: $slotId")
      println(s"   Campaign: $campaignId")
      println(s"   Advertiser: $advertiserId")
      println(s"   Creative to remove: ${candidate.creativeId}")

      // Step 4: Unassign the creative
      println("\n[4/6] Unassigning creative...")
      val unassignResp = basicRequest
        .delete(uri"$baseUrl/v1/advertisers/$advertiserId/campaigns/$campaignId/creatives")
        .body(s"""{"creativeIds":["${candidate.creativeId}"]}""")
        .contentType("application/json")
        .response(asString)
        .send(backend)

      if (unassignResp.code.isSuccess) {
        println(s"   Unassigned: ${candidate.creativeId}")
      } else {
        println(s"   WARNING: Unassign returned ${unassignResp.code}: ${unassignResp.body}")
      }

      // Step 5: Create new creative
      println("\n[5/6] Creating new creative...")
      val timestamp = System.currentTimeMillis()
      val createResp = basicRequest
        .post(uri"$baseUrl/v1/advertisers/$advertiserId/campaigns/$campaignId/creatives")
        .body(s"""{
          "name": "rotated-$timestamp",
          "landingUrl": "https://example.com/landing",
          "skipVerify": true,
          "pages": [{
            "tag": "FEATURE",
            "headline": "rotated-$timestamp",
            "sub": "",
            "body": "",
            "accent": "#1a5276",
            "bg": "#ffffff",
            "imgEmoji": "",
            "caption": "",
            "banners": {}
          }]
        }""")
        .contentType("application/json")
        .response(asJson[TestCreateCreativeRes])
        .send(backend)

      val newCreative = createResp.body match {
        case Right(c) => c
        case Left(err) =>
          println(s"ERROR: Failed to create creative: $err")
          sys.exit(1)
      }
      println(s"   Created: ${newCreative.id}")

      // Wait for re-auction to complete and new creative to be queued as pending
      println("   Waiting for re-auction (5s)...")
      Thread.sleep(5000)

      // Step 6: Approve all pending
      println("\n[6/6] Approving all pending creatives...")
      val approveResp = basicRequest
        .post(uri"$baseUrl/v1/publishers/$publisherId/sites/${config.siteId}/approval/bulk")
        .body(s"""{"url":"$url","slotId":"$slotId"}""")
        .contentType("application/json")
        .response(asJson[ApproveAllRes])
        .send(backend)

      approveResp.body match {
        case Right(r) =>
          println(s"   Approved: ${r.approved}, Failed: ${r.failed}")
        case Left(err) =>
          println(s"   WARNING: Approve failed: $err")
      }

      // Verify
      println("\n[Done] Verifying serve-index...")
      Thread.sleep(2000)
      // Note: sttp's uri interpolation handles URL encoding automatically
      val verifyResp = basicRequest
        .get(uri"$baseUrl/v1/publishers/$publisherId/sites/${config.siteId}/serve-index?url=$url&slotId=$slotId")
        .response(asJson[ServeIndexRes])
        .send(backend)

      verifyResp.body match {
        case Right(idx) =>
          println(s"   Candidates in serve-index: ${idx.candidateCount}")
          idx.candidates.foreach { c =>
            val marker = if (c.creativeId == newCreative.id) " [NEW]"
                        else if (c.creativeId == candidate.creativeId) " [REMOVED - BUG!]"
                        else ""
            println(s"     - ${c.creativeId} (cpm=${c.cpm})$marker")
          }
          if (idx.candidates.exists(_.creativeId == candidate.creativeId)) {
            println("\n   WARNING: Old creative still in serve-index!")
          }
          if (idx.candidates.exists(_.creativeId == newCreative.id)) {
            println("\n   SUCCESS: New creative is now serving!")
          } else {
            println("\n   NOTE: New creative not yet in serve-index (may need approval)")
          }
        case Left(err) =>
          println(s"   Failed to verify: $err")
      }

      println("\n=== Complete ===\n")

    } finally {
      backend.close()
    }
  }

  case class Config(
    siteId: String = ""
  )

  def parseArgs(args: Array[String]): Config = {
    var config = Config()
    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--site-id" =>
          config = config.copy(siteId = args(i + 1))
          i += 2
        case other =>
          println(s"Unknown option: $other")
          printUsage()
          sys.exit(1)
      }
    }

    if (config.siteId.isEmpty) {
      println("ERROR: --site-id is required")
      printUsage()
      sys.exit(1)
    }
    config
  }

  def printUsage(): Unit = {
    println("""
Usage: scala-cli RotateCreative.scala -- [options]

Options:
  --site-id <id>        Site ID (required)

Example:
  scala-cli RotateCreative.scala -- --site-id site-67738-84704-4500
""")
  }
}
