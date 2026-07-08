package promovolve.api

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.`User-Agent`
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.util.Timeout
import promovolve.{CampaignId, CategoryId}
import promovolve.advertiser.CampaignEntity
import promovolve.taxonomy.TieredCategory
import promovolve.creative.BannerPage
import promovolve.publisher.{Creative, CreativeRepo, ImageAsset, ImageAssetRepo}
import promovolve.publisher.assets.{ImageStorage, WebpEncoder}
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.security.MessageDigest
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/** Actor that processes rich creatives after initial save.
  *
  * Handles the full pipeline:
  * 1. Download external images and store via ImageStorage
  * 2. Render collapsed banner screenshot via Playwright (LPAnalyzer)
  * 3. Store banner image + update creative record
  * 4. Run category verification via Gemini
  *
  * Runs on its own dispatcher to avoid blocking the serve path.
  */
object CreativeProcessor {

  sealed trait Command

  /** Process a newly created rich creative.
    *
    * When `skipVerify=true` (draft save), the pipeline still imports
    * images, renders the banner PNG, and stores it in R2 (so drafts
    * get a real thumbnail in the list), but skips the Gemini category
    * verification and leaves status as Draft.
    */
  case class Process(
      creativeId: String,
      advertiserId: String,
      campaignId: String,
      name: String,
      landingUrl: String,
      pages: Vector[PageData],
      originalPagesJson: String,
      originalHash: String,
      width: Int = 300,
      height: Int = 250,
      skipVerify: Boolean = false
  ) extends Command

  /** A page in the rich creative (mirrors ExtractedBannerPage fields). */
  case class PageData(
      tag: String, headline: String, sub: String, body: String,
      accent: String, bg: String, imgEmoji: String, caption: String,
      img: Option[String],
      layout: Option[spray.json.JsValue] = None,
      banners: Option[spray.json.JsValue] = None,
      designAspect: Option[String] = None,
      videoBg: Option[spray.json.JsValue] = None,
      textureBg: Option[spray.json.JsValue] = None
  )

  /** Internal: image downloads completed. */
  private case class ImagesDownloaded(
      creativeId: String, advertiserId: String, campaignId: String,
      name: String, landingUrl: String,
      updatedPages: Vector[PageData], updatedPagesJson: String,
      originalHash: String, width: Int, height: Int,
      skipVerify: Boolean
  ) extends Command

  /** Internal: banner rendered and stored. `imageBytes` is whatever
    * format we ended up storing — WebP when the re-encode succeeds,
    * PNG when it didn't. `mime` reports which so downstream (Gemini
    * verify) sends the right Content-Type. */
  private case class BannerRendered(
      creativeId: String, advertiserId: String, campaignId: String,
      name: String, landingUrl: String, updatedPagesJson: String,
      bannerHash: String, bannerS3Key: String,
      imageBytes: Array[Byte], mime: String,
      width: Int, height: Int,
      skipVerify: Boolean
  ) extends Command

  /** Internal: banner render skipped or failed. */
  private case class BannerSkipped(
      creativeId: String, advertiserId: String, campaignId: String,
      name: String, landingUrl: String, updatedPagesJson: String,
      originalHash: String, width: Int, height: Int,
      skipVerify: Boolean
  ) extends Command

  /** Internal: verification completed. */
  private case class VerificationDone(creativeId: String) extends Command

  /** Internal: a step failed. */
  private case class StepFailed(creativeId: String, step: String, error: String) extends Command

  /** Internal: scan for creatives that need rendering on startup. */
  private case object ScanPending extends Command

  /** Internal: pending creatives found. */
  private case class PendingFound(creatives: Vector[promovolve.publisher.Creative]) extends Command

  def apply(
      creativeRepo: CreativeRepo,
      imageAssetRepo: ImageAssetRepo,
      imageStorage: ImageStorage,
      cdnBaseUrl: String,
      lpAnalyzer: Option[promovolve.browser.LPAnalyzer],
      categoryVerificationClient: Option[promovolve.publisher.assessment.CategoryVerificationClient],
      sharding: ClusterSharding,
      fontProvisioner: Option[GoogleFontProvisioner] = None
  )(using system: ActorSystem[?]): Behavior[Command] = Behaviors.setup { ctx =>
    given ExecutionContext = ctx.executionContext
    given Timeout = Timeout(5.seconds)

    import org.apache.pekko.actor.typed.scaladsl.adapter.*
    val httpExt = Http(system.toClassic)

    // Self-host allow-listed Google fonts referenced by the (final,
    // non-draft) creative. Fire-and-forget — provisioning never blocks
    // publish and failures fall back to the system font in the CSS stack.
    def provisionFonts(pagesJson: String): Unit =
      fontProvisioner.foreach { p =>
        // CJK faces need a per-text `text=` subset; latin ignores it.
        val subset = subsetTextFromPagesJson(pagesJson)
        fontsFromPagesJson(pagesJson).foreach { case (fam, w) => p.ensure(fam, w, Some(subset)); () }
      }

    // On startup, scan for creatives that need banner rendering
    ctx.self ! ScanPending

    def downloadAndStoreImage(url: String): Future[String] = {
      // Accept: image/webp first so CDNs running format negotiation
      // (Cloudinary f_auto, Imgix auto=format, Cloudflare Polish, …)
      // hand us pre-compressed WebP instead of larger JPEG defaults.
      // When the CDN honours it, the byte shrink compounds — R2
      // stores smaller objects and every impression ships less.
      val request = HttpRequest(
        uri = url,
        headers = List(
          `User-Agent`("Mozilla/5.0 (compatible; Promovolve/1.0)"),
          org.apache.pekko.http.scaladsl.model.headers.Accept(
            org.apache.pekko.http.scaladsl.model.MediaRange(
              org.apache.pekko.http.scaladsl.model.MediaTypes.`image/webp`,
            ),
            org.apache.pekko.http.scaladsl.model.MediaRange(
              org.apache.pekko.http.scaladsl.model.MediaTypes.`image/jpeg`,
            ),
            org.apache.pekko.http.scaladsl.model.MediaRange(
              org.apache.pekko.http.scaladsl.model.MediaTypes.`image/png`,
            ),
            org.apache.pekko.http.scaladsl.model.MediaRanges.`image/*`,
          ),
        )
      )
      httpExt.singleRequest(request).flatMap { response =>
        if (response.status.isSuccess) {
          val rawMime = response.entity.contentType.mediaType.toString
          Unmarshal(response.entity).to[Array[Byte]].flatMap { rawBytes =>
            // Compress + dimension-clamp via the same pipeline used by
            // storeIfNew, so LP-to-Creative scraped images land on R2
            // at the same weight budget as direct uploads. See
            // ImageCompression for the format policy.
            val (bytes, mime, w, h) = ImageCompression.compress(rawBytes, rawMime)
            val hash = MessageDigest.getInstance("SHA-256")
              .digest(bytes).map("%02x".format(_)).mkString
            imageStorage.exists(hash).flatMap { exists =>
              if (!exists) {
                imageStorage.store(hash, bytes, mime).flatMap { s3Key =>
                  imageAssetRepo.put(ImageAsset(hash, s3Key, mime, w, h, Instant.now()))
                    .map(_ => s"$cdnBaseUrl/$s3Key")
                }
              } else {
                // Image already in storage — look up the s3Key, or reconstruct from MIME
                imageAssetRepo.get(hash).flatMap {
                  case Some(a) => Future.successful(s"$cdnBaseUrl/${a.s3Key}")
                  case None =>
                    // DB row missing (e.g. after --fresh) but R2 has the file — re-insert
                    val ext = mime match {
                      case "image/jpeg"    => "jpg"
                      case "image/png"     => "png"
                      case "image/gif"     => "gif"
                      case "image/webp"    => "webp"
                      case "image/svg+xml" => "svg"
                      case _               => "bin"
                    }
                    val s3Key = s"assets/$hash.$ext"
                    imageAssetRepo.put(ImageAsset(hash, s3Key, mime, w, h, Instant.now()))
                      .map(_ => s"$cdnBaseUrl/$s3Key")
                }
              }
            }
          }
        } else {
          response.entity.discardBytes()
          Future.failed(new RuntimeException(s"HTTP ${response.status} for $url"))
        }
      }
    }

    Behaviors.receiveMessage {
      // Step 1: Download external images
      case Process(creativeId, advertiserId, campaignId, name, landingUrl, pages, originalPagesJson, originalHash, width, height, skipVerify) =>
        ctx.log.info("CreativeProcessor: starting for creative {} ({}x{}) skipVerify={}", creativeId, width, height, skipVerify)

        val imgTimeout = 15.seconds
        // Best-effort, TOLERANT: download each image to R2 and rewrite its src,
        // but on failure keep the ORIGINAL url and carry the reason as data —
        // bot-protected third-party CDNs (usnews tarpit, food.fnr 403) refuse
        // server-side fetches, yet the banner render's real browser can still
        // load them. One un-fetchable image must never fail the whole creative,
        // and retrying a 403/tarpit is futile. Future body stays pure (no ctx,
        // no recover); `transform` folds both outcomes into a value.
        val downloadF = Future.traverse(pages) { page =>
          page.img match {
            case Some(imgUrl) if imgUrl.startsWith("http") && !imgUrl.startsWith(cdnBaseUrl) =>
              val dlF = downloadAndStoreImage(imgUrl).map(cdnUrl => page.copy(img = Some(cdnUrl)))
              val timeoutF = org.apache.pekko.pattern.after(imgTimeout, system.classicSystem.scheduler)(
                Future.failed(new RuntimeException(s"timeout after $imgTimeout: $imgUrl")))
              Future.firstCompletedOf(Seq(dlF, timeoutF)).transform {
                case Success(updated) => Success((updated, None))
                case Failure(e)       => Success((page, Some(s"$imgUrl — ${e.getMessage}")))
              }
            case _ => Future.successful((page, None))
          }
        }

        // pipeToSelf delivers the outcome on the actor thread, where ctx is
        // safe. Per-image failures are tolerated above, so this only fails on
        // an unexpected whole-future error.
        ctx.pipeToSelf(downloadF) {
          case Success(results) =>
            results.flatMap(_._2).foreach(msg =>
              ctx.log.warn("CreativeProcessor: {} kept un-downloadable image (render browser will fetch it): {}", creativeId, msg))
            val updatedPages = results.map(_._1)
            val json = updatedPages.map(p =>
              BannerPage(p.tag, p.headline, p.sub, p.body, p.accent, p.bg,
                p.imgEmoji, p.caption, p.img,
                p.layout, p.banners, p.designAspect, p.videoBg, p.textureBg)
            ).toJson.compactPrint
            ImagesDownloaded(creativeId, advertiserId, campaignId, name, landingUrl, updatedPages, json, originalHash, width, height, skipVerify)
          case Failure(e) =>
            StepFailed(creativeId, "image-download", e.getMessage)
        }
        Behaviors.same

      // Step 2: Render banner screenshot
      case ImagesDownloaded(creativeId, advertiserId, campaignId, name, landingUrl, _, updatedPagesJson, originalHash, width, height, skipVerify) =>
        ctx.log.info("CreativeProcessor: images done for {}, rendering banner...", creativeId)

        lpAnalyzer match {
          case Some(analyzer) =>
            // ctx.log is unsafe to touch from a Future callback (the
            // flatMap runs on the executor, not the actor's mailbox
            // thread). Use a static SLF4J logger inside the Future
            // and reserve ctx.log for code inside this match's actor-
            // thread arm (i.e., before/after the Future is set up).
            val asyncLog = org.slf4j.LoggerFactory.getLogger("promovolve.api.CreativeProcessor.async")
            val renderF = analyzer.renderBanner(updatedPagesJson, width, height).flatMap { pngBytes =>
              // Re-encode PNG → WebP. Playwright only outputs PNG/JPEG
              // but WebP saves ~25–35% at visually-identical quality.
              // Encode is fail-soft (returns None) so a broken cwebp
              // doesn't break the creative pipeline — we fall through
              // to the original PNG.
              val (bytes, mime) = WebpEncoder.encode(pngBytes) match {
                case Some(webp) =>
                  asyncLog.info(
                    "webp encode for {} {}x{} png={}B webp={}B ({}% saved)",
                    creativeId, width: java.lang.Integer, height: java.lang.Integer,
                    pngBytes.length: java.lang.Integer, webp.length: java.lang.Integer,
                    ((1.0 - webp.length.toDouble / pngBytes.length) * 100).toInt: java.lang.Integer,
                  )
                  (webp, "image/webp")
                case None =>
                  asyncLog.warn("webp encode failed for {}, storing PNG", creativeId)
                  (pngBytes, "image/png")
              }
              val imgHash = MessageDigest.getInstance("SHA-256")
                .digest(bytes).map("%02x".format(_)).mkString
              for {
                key <- imageStorage.store(imgHash, bytes, mime)
                _   <- imageAssetRepo.put(ImageAsset(imgHash, key, mime, width, height, Instant.now()))
              } yield (imgHash, key, bytes, mime)
            }

            ctx.pipeToSelf(renderF) {
              case Success((hash, key, bytes, mime)) =>
                BannerRendered(creativeId, advertiserId, campaignId, name, landingUrl, updatedPagesJson, hash, key, bytes, mime, width, height, skipVerify)
              case Failure(e) =>
                ctx.log.warn("Banner render failed for {}: {}", creativeId, e.getMessage)
                BannerSkipped(creativeId, advertiserId, campaignId, name, landingUrl, updatedPagesJson, originalHash, width, height, skipVerify)
            }

          case None =>
            ctx.log.info("CreativeProcessor: LPAnalyzer not available, skipping banner for {}", creativeId)
            ctx.self ! BannerSkipped(creativeId, advertiserId, campaignId, name, landingUrl, updatedPagesJson, originalHash, width, height, skipVerify)
        }
        Behaviors.same

      // Step 3a: Banner rendered — update creative + run verification
      case BannerRendered(creativeId, advertiserId, campaignId, name, landingUrl, updatedPagesJson, bannerHash, bannerS3Key, bannerImageBytes, bannerMime, width, height, skipVerify) =>
        ctx.log.info("CreativeProcessor: banner rendered for {}, s3Key={} mime={} skipVerify={}", creativeId, bannerS3Key, bannerMime, skipVerify)

        // Fetch existing to preserve lp_text_snapshot + Draft status
        // + bannerConfigJson — CreativeProcessor rewrites the row
        // post-render so any field not carried explicitly gets nulled.
        val updateF = creativeRepo.get(creativeId).flatMap { existing =>
          creativeRepo.put(Creative(
            creativeId       = creativeId,
            imageHash        = bannerHash,
            advertiserId     = advertiserId,
            campaignId       = campaignId,
            name             = name,
            landingUrl       = landingUrl,
            landingDomain    = scala.util.Try(java.net.URI.create(landingUrl).getHost).getOrElse("unknown"),
            createdAt        = existing.map(_.createdAt).getOrElse(Instant.now()),
            s3Key            = bannerS3Key,
            mime             = "application/json+expandable",
            width            = width,
            height           = height,
            pagesJson        = Some(updatedPagesJson),
            bannerConfigJson = existing.flatMap(_.bannerConfigJson),
            lpTextSnapshot   = existing.flatMap(_.lpTextSnapshot),
            status           = existing.map(_.status).getOrElse(promovolve.publisher.CreativeStatus.Active)
          )).map(_ => existing.flatMap(_.lpTextSnapshot))
        }

        if (skipVerify) {
          // Draft: thumbnail only. No Gemini call, no status promotion.
        } else {
          // Build the text fed to Gemini's verifyText. Preferred source
          // is the Designer's authored banner page text (what the
          // advertiser actually wrote — headline, sub, body, caption,
          // tag, ctaLabel across all pages), so words they add via the
          // Designer's audience-signal-chip prompts actually reach the
          // classifier. Falls back to the raw LP snapshot when the
          // creative was hand-built without going through the LP-to-
          // creative flow (or the pages JSON parse fails). Falls back
          // further to the rendered banner image bytes when neither
          // text source is available.
          // Self-host any allow-listed Google fonts the creative uses so
          // the expanded view renders the exact brand face from our CDN
          // (never Google at the visitor's runtime). Fire-and-forget,
          // dedup'd + idempotent inside the provisioner.
          provisionFonts(updatedPagesJson)
          val designerText = textFromPagesJson(updatedPagesJson)
          // Fire-and-forget verification — prefer stored LP text over image bytes
          updateF.foreach { lpText =>
            categoryVerificationClient.foreach { client =>
              val campaignRef = sharding.entityRefFor(CampaignEntity.TypeKey, s"$advertiserId|$campaignId")
              campaignRef.ask[CampaignEntity.CampaignInfo](CampaignEntity.GetCampaign(_))(using Timeout(3.seconds))
                .foreach { info =>
                  val declaredCat = info.adProductCategory.map(_.value)
                  // Source priority: authored Designer text → LP scrape
                  // → image. The first non-empty one wins. Logging the
                  // chosen source helps when diagnosing why Gemini
                  // returned no categories.
                  val (verifyF, source) =
                    if (designerText.nonEmpty) (client.verifyText(designerText, declaredCat), "designer-text")
                    else lpText match {
                      case Some(text) if text.nonEmpty => (client.verifyText(text, declaredCat), "lp-snapshot")
                      case _                            => (client.verify(bannerImageBytes, bannerMime, declaredCat), "image")
                    }
                  system.log.info(
                    "CreativeProcessor: {} verifying via {} ({} chars)",
                    creativeId, source,
                    (if (source == "designer-text") designerText.length
                     else if (source == "lp-snapshot") lpText.map(_.length).getOrElse(0)
                     else bannerImageBytes.length): java.lang.Integer,
                  )
                  verifyF.foreach { result =>
                    creativeRepo.updateVerification(
                      creativeId, result.matchConfidence, result.reason,
                      result.adultContent, result.violence, result.hateSpeech,
                      result.safetyScore, result.suggestedContentCategories
                    )
                    system.log.info("CreativeProcessor: {} verified: {}% match", creativeId, (result.matchConfidence * 100).toInt)
                    // Auto-derive campaign targeting from the LLM's suggested
                    // content categories. Before the IAB 3.0 migration, target
                    // categories were auto-derived from adProductCategory via
                    // a static IAB Content↔AdProduct bridge. That bridge is
                    // gone; the LLM's per-creative suggestion is a richer,
                    // dynamic replacement. RefineCategoriesFromCreative unions
                    // (doesn't replace) so any explicit advertiser-declared
                    // targets survive.
                    //
                    // keepMostSpecific drops any returned id that is an
                    // ancestor of another in the set — without this, an LLM
                    // returning {497 Horse Racing, 483 Sports} would target
                    // both, and the auctioneer's ancestor expansion would
                    // pull in every Sports descendant (Baseball, Soccer, …)
                    // even though the advertiser sells horse-racing tickets.
                    val rawIds = result.suggestedContentCategories.toSet
                    val specificIds = TieredCategory.keepMostSpecific(rawIds)
                    if (rawIds != specificIds) {
                      system.log.info(
                        "CreativeProcessor: {} pruned ancestor categories: {} → {}",
                        creativeId,
                        rawIds.mkString(","),
                        specificIds.mkString(",")
                      )
                    }
                    val refined: Set[CategoryId] = specificIds.map(CategoryId(_))
                    if (refined.nonEmpty) {
                      campaignRef.ask[CampaignEntity.CategoriesRefined](
                        CampaignEntity.RefineCategoriesFromCreative(refined, _)
                      )(using Timeout(3.seconds))
                        .recover { case ex =>
                          system.log.warn("CreativeProcessor: refine categories failed for {}: {}", creativeId, ex.getMessage)
                          CampaignEntity.CategoriesRefined(CampaignId(campaignId))
                        }
                    } else {
                      // Silent-failure flag: Gemini's verify pass
                      // returned no usable content categories for this
                      // LP. The campaign won't get auto-targeting from
                      // this creative; if the advertiser also didn't
                      // declare targetCategories on create, the
                      // campaign sits untargeted forever. The advertiser
                      // dashboard surfaces this via `Campaign.untargeted`
                      // (derived in EndpointRoutes.getCampaignLogic), but
                      // the warn here at least makes it greppable.
                      system.log.warn(
                        "CreativeProcessor: {} categorisation returned NO usable categories (rawIds={}, specificIds={}). Campaign {} will only bid if `bidOnUnmatchedContext=true` or `targetCategories` was manually set.",
                        creativeId,
                        rawIds.mkString(","),
                        specificIds.mkString(","),
                        campaignId,
                      )
                    }
                  }
                }
            }
          }
        }
        Behaviors.same

      // Step 3b: Banner skipped — still update creative with stored images
      case BannerSkipped(creativeId, advertiserId, campaignId, name, landingUrl, updatedPagesJson, originalHash, width, height, _) =>
        ctx.log.info("CreativeProcessor: updating creative {} (no banner)", creativeId)
        creativeRepo.get(creativeId).foreach { existing =>
          creativeRepo.put(Creative(
            creativeId       = creativeId,
            imageHash        = originalHash,
            advertiserId     = advertiserId,
            campaignId       = campaignId,
            name             = name,
            landingUrl       = landingUrl,
            landingDomain    = scala.util.Try(java.net.URI.create(landingUrl).getHost).getOrElse("unknown"),
            createdAt        = existing.map(_.createdAt).getOrElse(Instant.now()),
            s3Key            = "",
            mime             = "application/json+expandable",
            width            = width,
            height           = height,
            pagesJson        = Some(updatedPagesJson),
            bannerConfigJson = existing.flatMap(_.bannerConfigJson),
            lpTextSnapshot   = existing.flatMap(_.lpTextSnapshot),
            status           = existing.map(_.status).getOrElse(promovolve.publisher.CreativeStatus.Active)
          ))
        }
        Behaviors.same

      case ScanPending =>
        ctx.log.info("CreativeProcessor: scanning for creatives pending banner render...")
        ctx.pipeToSelf(creativeRepo.getPendingRender()) {
          case Success(creatives) => PendingFound(creatives)
          case Failure(e) =>
            ctx.log.warn("CreativeProcessor: scan failed: {}", e.getMessage)
            PendingFound(Vector.empty)
        }
        Behaviors.same

      case PendingFound(creatives) =>
        if (creatives.nonEmpty) {
          ctx.log.info("CreativeProcessor: found {} creatives pending render", creatives.size)
          creatives.foreach { c =>
            c.pagesJson.foreach { json =>
              import promovolve.creative.BannerPage
              val pages = scala.util.Try(json.parseJson.convertTo[Vector[BannerPage]]).getOrElse(Vector.empty)
              if (pages.nonEmpty) {
                ctx.self ! Process(
                  creativeId = c.creativeId,
                  advertiserId = c.advertiserId,
                  campaignId = c.campaignId,
                  name = c.name,
                  landingUrl = c.landingUrl,
                  pages = pages.map(p => PageData(
                    p.tag, p.headline, p.sub, p.body, p.accent, p.bg,
                    p.imgEmoji, p.caption, p.img,
                    p.layout, p.banners, p.designAspect, p.videoBg, p.textureBg
                  )),
                  originalPagesJson = json,
                  originalHash = c.imageHash,
                  width = c.width,
                  height = c.height
                )
              }
            }
          }
        } else {
          ctx.log.info("CreativeProcessor: no creatives pending render")
        }
        Behaviors.same

      case StepFailed(creativeId, step, error) =>
        ctx.log.error("CreativeProcessor: {} failed at {}: {}", creativeId, step, error)
        Behaviors.same

      case VerificationDone(creativeId) =>
        ctx.log.info("CreativeProcessor: {} fully processed", creativeId)
        Behaviors.same
    }
  }

  /** Extract the user-authored text from a serialised pages JSON array
    * for feeding into the Gemini category-verify call. Joins every
    * page's headline + sub + body + caption + tag + ctaLabel into a
    * single space-separated string. Returns "" if the JSON doesn't
    * parse — caller should fall back to lpTextSnapshot in that case.
    *
    * This is the bridge between the Designer's audience-signal chip
    * and the server's category classifier: whatever words the
    * advertiser adds in the Designer reach Gemini through this path.
    */
  private[api] def textFromPagesJson(pagesJson: String): String = {
    import spray.json.*
    val textFields = Vector("headline", "sub", "body", "caption", "tag", "ctaLabel")
    scala.util.Try {
      pagesJson.parseJson match {
        case arr: JsArray =>
          arr.elements.flatMap {
            case obj: JsObject =>
              textFields.flatMap { k =>
                obj.fields.get(k) match {
                  case Some(JsString(s)) if s.trim.nonEmpty => Some(s.trim)
                  case _                                     => None
                }
              }
            case _ => Vector.empty
          }.mkString(" ").trim
        case _ => ""
      }
    }.getOrElse("")
  }

  /** Concatenated content text the creative renders — headline/sub/body/tag/
    * caption across all pages, PLUS every text item's baked `text` override in
    * page.layout and every banner bucket. A size-specific text edit renders
    * characters the page fields don't contain; without collecting them the
    * CJK subset misses those glyphs and the browser falls back PER-GLYPH —
    * mixed typefaces mid-sentence. Fed to the CJK font provisioner as the
    * `text=` subset and keyed by GoogleFontCatalog.subsetKey. MUST mirror the
    * banner's collectSubsetText (banner/font-catalog.ts) — same page fields
    * AND item texts (order is irrelevant: subsetKey canonicalizes to the
    * sorted unique code-point set) — or the server-stored CJK woff2 and the
    * banner's derived URL won't agree (the banner then 404s and falls back to
    * the system font). Best-effort. */
  private[api] def subsetTextFromPagesJson(pagesJson: String): String = {
    import spray.json.*
    val fields = Vector("headline", "sub", "body", "tag", "caption")
    def itemTexts(v: JsValue): Vector[String] = v match {
      case JsArray(items) =>
        items.toVector.flatMap { item =>
          val f = item.asJsObject.fields
          (f.get("type"), f.get("text")) match {
            case (Some(JsString("text")), Some(JsString(s))) => Some(s)
            case _                                           => None
          }
        }
      case _ => Vector.empty
    }
    scala.util.Try {
      pagesJson.parseJson match {
        case JsArray(pages) =>
          pages.flatMap { page =>
            val f = page.asJsObject.fields
            val pageFields  = fields.flatMap(k => f.get(k).collect { case JsString(s) => s })
            val layoutTexts = f.get("layout").toVector.flatMap(itemTexts)
            val bannerTexts = f.get("banners") match {
              case Some(b: JsObject) => b.fields.values.toVector.flatMap(itemTexts)
              case _                 => Vector.empty
            }
            pageFields ++ layoutTexts ++ bannerTexts
          }.mkString
        case _ => ""
      }
    }.getOrElse("")
  }

  /** Distinct font-family stacks referenced by text items across page.layout
    * AND every per-size banner bucket. The banner self-hosts a font used by
    * ANY bucket (banner/font-catalog.ts collectExpandedFonts scans them all),
    * so the provisioner MUST too — otherwise a family/weight that appears only
    * in a collapsed IAB size (e.g. a bold tag/CTA) is never fetched and the
    * banner 404s for it. Fed to the provisioner at publish so allow-listed
    * Google fonts get self-hosted. Best-effort: a parse failure → empty set. */
  /** Distinct (font-family stack, CSS font-weight) pairs used by text items.
    * The weight lets the provisioner fetch the exact face (e.g. Montserrat
    * Thin = 100) instead of the family's default 400. */
  private[api] def fontsFromPagesJson(pagesJson: String): Set[(String, Option[Int])] = {
    import spray.json.*
    def weightOf(v: Option[JsValue]): Option[Int] = v.flatMap {
      case JsNumber(n) => Some(n.toInt)
      case JsString(s) => s.trim.toLowerCase match {
        case "bold"   => Some(700)
        case "normal" => Some(400)
        case w        => w.toIntOption
      }
      case _ => None
    }.filter(w => w >= 100 && w <= 900 && w % 100 == 0)
    scala.util.Try {
      pagesJson.parseJson match {
        case JsArray(pages) =>
          pages.flatMap { page =>
            val obj = page.asJsObject
            val groups = Vector(obj.fields.get("layout")) ++ (obj.fields.get("banners") match {
              case Some(b: JsObject) => b.fields.values.map(Some(_)).toVector
              case _                 => Vector.empty
            })
            groups.flatten.flatMap {
              case JsArray(items) =>
                items.flatMap { item =>
                  val f = item.asJsObject.fields
                  (f.get("type"), f.get("fontFamily")) match {
                    case (Some(JsString("text")), Some(JsString(ff))) if ff.trim.nonEmpty =>
                      Some((ff, weightOf(f.get("fontWeight"))))
                    case _ => None
                  }
                }
              case _ => Vector.empty
            }
          }.toSet
        case _ => Set.empty[(String, Option[Int])]
      }
    }.getOrElse(Set.empty[(String, Option[Int])])
  }
}
