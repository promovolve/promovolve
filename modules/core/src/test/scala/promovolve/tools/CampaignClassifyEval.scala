package promovolve.tools

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import promovolve.publisher.assessment.CategoryVerificationClient
import promovolve.taxonomy.TieredCategory
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.nio.file.{ Files, Path, Paths }
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext }
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Campaign-side eval (#422) — scripts/classify-eval/run-campaigns.sh drives
 * this. The page eval's sibling: runs the REAL CategoryVerificationClient
 * (same prompt and model as campaign creation) over campaigns.tsv, using
 * landing-page text captured by extract.mjs, and scores the content
 * categories it picks with the page eval's own rule (ClassifyEval.scoreCategories),
 * minus its sibling-contradiction check.
 * Also prints how many categories each answer had: on this side breadth is
 * the cost, and the score does not judge unrelated extras.
 *
 * Test scope on purpose: paid model, live pages — run by hand before a
 * campaign-side prompt or model change, never in `sbt test`.
 *
 *   GEMINI_CAMPAIGN_MODEL=… sbt "core/Test/runMain promovolve.tools.CampaignClassifyEval <dir> [--label L] [--baseline F]"
 */
object CampaignClassifyEval {
  import ClassifyEval.{ Expect, Verdict }

  final case class Case(url: String, adProduct: Option[String], expect: Expect)
  final case class Row(url: String, categories: Vector[String], verdict: Verdict, error: Option[String])

  // Below this the browser got a stub (chanel 244, fourseasons 205 chars of
  // "Access Denied"), not the landing page: nothing there to score (#429).
  private val MinText = 400

  def main(args: Array[String]): Unit = {
    val dir = Paths.get(args.headOption.getOrElse("scripts/classify-eval"))
    val flags = args.drop(1).toList
    def flagValue(name: String): Option[String] = flags.sliding(2).collectFirst { case List(`name`, v) => v }
    val model = CategoryVerificationClient.modelFromEnv()
    val label = flagValue("--label").getOrElse(model)
    val apiKey = sys.env.getOrElse("GEMINI_API_KEY", sys.error("set GEMINI_API_KEY"))

    val cases = readCases(dir.resolve("campaigns.tsv"))
    val texts = readTexts(dir.resolve("pages"))
    println(s"campaign-eval: ${cases.size} landing pages, model=$model, label=$label")

    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "campaign-eval")
    given ec: ExecutionContext = system.executionContext
    val client = new CategoryVerificationClient(apiKey, model = model)

    val rows =
      try
        cases.map { c =>
          texts.get(c.url) match {
            case None                                => Row(c.url, Vector.empty, Verdict.Skip, Some("no extracted text — run extract.mjs"))
            case Some(text) if text.length < MinText =>
              Row(c.url, Vector.empty, Verdict.Skip,
                Some(s"only ${text.length} chars — blocked or access-denied page?"))
            case Some(text) =>
              Try(Await.result(client.verifyText(text, c.adProduct), 90.seconds)).fold(
                ex => Row(c.url, Vector.empty, Verdict.Skip, Some(ex.getMessage)),
                r => {
                  val cats = r.suggestedContentCategories.toVector
                  Row(c.url, cats, ClassifyEval.scoreCategories(c.expect, cats, siblingsContradict = false), None)
                }
              )
          }
        }
      finally system.terminate()

    rows.sortBy(_.verdict.rank).foreach { r =>
      val got = r.error.getOrElse(r.categories.map(catName).mkString(", "))
      println(f"${r.verdict.label}%-9s ${r.categories.size}%2d  ${r.url.replaceFirst("^https?://", "")}  →  $got")
    }
    val tally = rows.groupBy(_.verdict).toVector.sortBy(_._1.rank).map((v, rs) => s"${v.label} ${rs.size}")
    println(s"\n${tally.mkString(" · ")}  ·  categories per answer: ${
        if (rows.isEmpty) "—" else f"${rows.map(_.categories.size).sum.toDouble / rows.size}%.1f"
      }")

    flagValue("--baseline").map(Paths.get(_)).filter(Files.exists(_)).foreach { b =>
      val before = readRows(b)
      println(s"\nvs ${b.getFileName}:")
      rows.foreach { r =>
        before.get(r.url).filter(_.verdict != r.verdict).foreach { was =>
          println(s"  ${was.verdict.label} → ${r.verdict.label}  ${r.url}")
        }
      }
    }
    val out = dir.resolve("out").resolve(s"campaigns-$label.json")
    Files.createDirectories(out.getParent)
    Files.writeString(out, toJson(rows, label, model).prettyPrint)
    println(s"\nwrote $out")
  }

  private def catName(id: String): String = TieredCategory.get(id).map(c => s"${c.name}($id)").getOrElse(id)

  // url <TAB> ad-product category id (empty = none, as production sends today) <TAB> accepted (|-separated) <TAB> note
  private def readCases(tsv: Path): Vector[Case] =
    Files.readAllLines(tsv).asScala.toVector.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).map { l =>
      val cols = l.split("\t", -1).map(_.trim)
      val accepted =
        if (cols.length > 2) cols(2).split("\\|").map(_.trim).filter(_.nonEmpty).toVector else Vector.empty
      Case(cols(0), Option.when(cols.length > 1 && cols(1).nonEmpty)(cols(1)),
        Expect(cols(0), accepted, Vector.empty, if (cols.length > 3) cols(3) else ""))
    }

  private def readTexts(dir: Path): Map[String, String] =
    if (!Files.isDirectory(dir)) Map.empty
    else
      Files.list(dir).iterator().asScala.filter(_.toString.endsWith(".json")).flatMap { p =>
        Try {
          val f = Files.readString(p).parseJson.asJsObject.fields
          (f("url").convertTo[String], f("text").convertTo[String])
        }.toOption
      }.toMap

  private def toJson(rows: Vector[Row], label: String, model: String): JsValue =
    JsObject(
      "label" -> JsString(label),
      "model" -> JsString(model),
      "pages" -> JsArray(rows.map { r =>
        JsObject(
          "url" -> JsString(r.url),
          "categories" -> JsArray(r.categories.map(JsString(_))),
          "verdict" -> JsString(r.verdict.toString),
          "error" -> r.error.fold[JsValue](JsNull)(JsString(_))
        )
      })
    )

  private def readRows(p: Path): Map[String, Row] =
    Files.readString(p).parseJson.asJsObject.fields("pages").asInstanceOf[JsArray].elements.map { v =>
      val f = v.asJsObject.fields
      val url = f("url").convertTo[String]
      url -> Row(url, f("categories").convertTo[Vector[String]], Verdict.valueOf(f("verdict").convertTo[String]), None)
    }.toMap
}
