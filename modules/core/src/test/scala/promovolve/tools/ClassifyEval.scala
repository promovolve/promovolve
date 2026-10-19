package promovolve.tools

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import promovolve.taxonomy.{ IABTaxonomy, Places, TieredCategory }
import spray.json.*

import java.nio.file.{ Files, Path, Paths }
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext }
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Classifier eval harness — scripts/classify-eval/run.sh drives this.
 *
 * Runs the REAL classifier (IABTaxonomy.analyze: same prompt, same
 * provider, same Places.resolveNamed post-processing the serve path
 * uses) over scripts/classify-eval/pages.tsv, using page text captured
 * by extract.mjs exactly as the ad tag captures it, and scores each
 * answer against the expectation written in the TSV.
 *
 * Test scope on purpose: it calls a paid external model and reads live
 * pages, so it is a tool you run before a prompt change or a
 * classifierVersion bump — never part of `sbt test`.
 *
 *   sbt "core/Test/runMain promovolve.tools.ClassifyEval <dir> [--no-hints] [--label L] [--baseline F] [--strict]"
 */
object ClassifyEval {

  final case class Expect(url: String, categories: Vector[String], places: Vector[String], note: String) {
    // "-" in the TSV = must be empty; Vector.empty = not checked
    def placesMustBeEmpty: Boolean = places == Vector("-")
    def categoriesMustBeEmpty: Boolean = categories == Vector("-")
  }

  final case class PageText(url: String, text: String, section: String, place: String)

  /** One cell's verdict, worst first so a sort surfaces trouble. */
  enum Verdict(val rank: Int, val label: String) {
    case Wrong extends Verdict(0, "WRONG") // answered, none acceptable
    case Invented extends Verdict(1, "INVENTED") // expected nothing, answered something
    case Missing extends Verdict(2, "missing") // expected something, answered nothing
    case Broad extends Verdict(3, "broad") // an ancestor of the expected
    case Hit extends Verdict(4, "hit")
    case Ok extends Verdict(5, "ok") // expected nothing, got nothing
    case Skip extends Verdict(6, "—") // not checked
  }

  final case class Result(
      url: String,
      categories: Vector[String],
      places: Vector[String],
      catVerdict: Verdict,
      placeVerdict: Verdict,
      hintsUsed: Boolean,
      error: Option[String]
  )

  def main(args: Array[String]): Unit = {
    val dir = Paths.get(args.headOption.getOrElse("scripts/classify-eval"))
    val flags = args.drop(1).toList
    val noHints = flags.contains("--no-hints")
    val strict = flags.contains("--strict")
    def flagValue(name: String): Option[String] =
      flags.sliding(2).collectFirst { case List(`name`, v) => v }
    val label = flagValue("--label").getOrElse(gitShortSha().getOrElse("run"))
    val baseline = flagValue("--baseline").map(Paths.get(_))

    val expects = readExpectations(dir.resolve("pages.tsv"))
    val texts = readPageTexts(dir.resolve("pages"))
    val provider = IABTaxonomy.Provider.fromEnv()
    println(s"classify-eval: ${expects.size} pages, provider=${provider.name}, hints=${
        if (noHints) "OFF" else "as the tag sends them"
      }, label=$label")

    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "classify-eval")
    given ec: ExecutionContext = system.executionContext
    val classifier = new IABTaxonomy(provider)

    val results =
      try
        expects.map { e =>
          texts.get(e.url) match {
            case None =>
              Result(e.url, Vector.empty, Vector.empty, Verdict.Skip, Verdict.Skip, hintsUsed = false,
                error = Some("no extracted text — run extract.mjs"))
            case Some(t) =>
              val hint = Option.when(!noHints && t.section.nonEmpty)(t.section)
              val placeHint = Option.when(!noHints && t.place.nonEmpty)(t.place)
              val attempt = Try(Await.result(classifier.analyze(e.url, t.text, Set.empty, hint, placeHint), 60.seconds))
              val r = attempt.fold(
                ex =>
                  Result(e.url, Vector.empty, Vector.empty, Verdict.Skip, Verdict.Skip,
                    hint.isDefined || placeHint.isDefined,
                    Some(ex.getMessage)),
                a => {
                  val cats = a.categories.map(_.id).toVector
                  val places = a.places.toVector
                  Result(e.url, cats, places, scoreCategories(e, cats), scorePlaces(e, places),
                    hint.isDefined || placeHint.isDefined, None)
                }
              )
              println(s"  ${shortUrl(e.url).padTo(34, ' ')} cats=[${r.categories.map(catName).mkString(", ")}] " +
                s"${r.catVerdict.label.padTo(8, ' ')} places=[${r.places.map(placeName).mkString(", ")}] ${r.placeVerdict.label}" +
                r.error.fold("")(m => s"  ERROR $m"))
              Thread.sleep(800) // be a polite client of the provider's rate limit
              r
          }
        }
      finally system.terminate()

    println()
    printSummary(results)
    val outDir = dir.resolve("out")
    Files.createDirectories(outDir)
    val outFile = outDir.resolve(s"$label.json")
    Files.writeString(outFile, toJson(results, label, provider.name, noHints).prettyPrint)
    println(s"results → $outFile")
    baseline.foreach(b => printDiff(readResults(b), results, b))

    val bad = results.count(r => r.catVerdict.rank <= 2 || r.placeVerdict.rank <= 2)
    if (strict && bad > 0) { println(s"STRICT: $bad page(s) wrong/invented/missing"); sys.exit(1) }
  }

  // ── scoring ──────────────────────────────────────────────────────────

  private[tools] def scoreCategories(e: Expect, actual: Vector[String]): Verdict =
    if (e.categories.isEmpty) Verdict.Skip
    else if (e.categoriesMustBeEmpty) { if (actual.isEmpty) Verdict.Ok else Verdict.Invented }
    else if (actual.isEmpty) Verdict.Missing
    else {
      val accepted = e.categories.toSet
      def ancestorsOf(id: String): List[String] = {
        @annotation.tailrec
        def loop(c: Option[TieredCategory], acc: List[String]): List[String] = c match {
          case Some(node) => loop(node.parent, node.id :: acc)
          case None       => acc
        }
        loop(TieredCategory.get(id).flatMap(_.parent), Nil)
      }
      // a child of an accepted id is at least as good as the id itself
      val hit = actual.exists(a => accepted.contains(a) || ancestorsOf(a).exists(accepted.contains))
      // the model stopped at a parent of what we wanted
      val broad = actual.exists(a => accepted.exists(x => ancestorsOf(x).contains(a)))
      // a SIBLING of what we wanted contradicts it — Europe Travel on a
      // Japan page is wrong even beside Asia Travel. Unrelated extras
      // (Food & Drink on a travel page) are not judged.
      def related(a: String, x: String) = a == x || ancestorsOf(a).contains(x) || ancestorsOf(x).contains(a)
      def parentOf(id: String) = TieredCategory.get(id).flatMap(_.parent).map(_.id)
      val contradicts = actual.exists(a =>
        parentOf(a).isDefined && !accepted.exists(related(a, _)) && accepted.exists(x => parentOf(x) == parentOf(a)))
      if (contradicts) Verdict.Wrong else if (hit) Verdict.Hit else if (broad) Verdict.Broad else Verdict.Wrong
    }

  private[tools] def scorePlaces(e: Expect, actual: Vector[String]): Verdict =
    if (e.places.isEmpty) Verdict.Skip
    else if (e.placesMustBeEmpty) { if (actual.isEmpty) Verdict.Ok else Verdict.Invented }
    else if (actual.isEmpty) Verdict.Missing
    else {
      val accepted = e.places.toSet
      def ancestorsOf(code: String): Set[String] = Places.ancestors(code).map(_.code).toSet
      val hit = actual.exists(a => accepted.contains(a) || ancestorsOf(a).exists(accepted.contains))
      val broad = actual.exists(a => accepted.exists(x => ancestorsOf(x).contains(a)))
      if (hit) Verdict.Hit else if (broad) Verdict.Broad else Verdict.Wrong
    }

  // ── reporting ────────────────────────────────────────────────────────

  private def printSummary(results: Vector[Result]): Unit = {
    def tally(f: Result => Verdict): String =
      Verdict.values.toList.sortBy(_.rank).flatMap { v =>
        val n = results.count(r => f(r) == v)
        Option.when(n > 0)(s"${v.label}=$n")
      }.mkString("  ")
    println(s"categories: ${tally(_.catVerdict)}")
    println(s"places:     ${tally(_.placeVerdict)}")
    val errors = results.count(_.error.isDefined)
    if (errors > 0) println(s"errors:     $errors (see ERROR lines above)")
  }

  private def printDiff(before: Map[String, Result], after: Vector[Result], from: Path): Unit = {
    println(s"\nvs baseline ${from.getFileName}:")
    var changes = 0
    after.foreach { r =>
      before.get(r.url).foreach { b =>
        def cell(name: String, was: Verdict, now: Verdict, wasV: Vector[String], nowV: Vector[String],
            show: String => String) =
          if (was != now || wasV != nowV) {
            changes += 1
            val arrow = if (now.rank > was.rank) "▲" else if (now.rank < was.rank) "▼" else "·"
            println(s"  $arrow ${shortUrl(r.url).padTo(34, ' ')} $name ${was.label} → ${now.label}  " +
              s"[${wasV.map(show).mkString(", ")}] → [${nowV.map(show).mkString(", ")}]")
          }
        cell("cats  ", b.catVerdict, r.catVerdict, b.categories, r.categories, catName)
        cell("places", b.placeVerdict, r.placeVerdict, b.places, r.places, placeName)
      }
    }
    if (changes == 0) println("  no changes")
  }

  private def shortUrl(u: String): String = u.replaceFirst("^https?://", "")
  private def catName(id: String): String = TieredCategory.get(id).map(c => s"${c.name}($id)").getOrElse(id)
  private def placeName(code: String): String = s"${Places.displayName("", code)}($code)"

  // ── io ───────────────────────────────────────────────────────────────

  private def readExpectations(tsv: Path): Vector[Expect] =
    Files.readAllLines(tsv).asScala.toVector
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .map { l =>
        val cols = l.split("\t", -1).map(_.trim)
        def alts(i: Int): Vector[String] =
          if (cols.length > i && cols(i).nonEmpty) cols(i).split("\\|").map(_.trim).filter(_.nonEmpty).toVector
          else Vector.empty
        Expect(cols(0), alts(1), alts(2), if (cols.length > 3) cols(3) else "")
      }

  private def readPageTexts(dir: Path): Map[String, PageText] =
    if (!Files.isDirectory(dir)) Map.empty
    else
      Files.list(dir).iterator().asScala.filter(_.toString.endsWith(".json")).flatMap { p =>
        Try {
          val f = Files.readString(p).parseJson.asJsObject.fields
          def s(k: String) = f.get(k).collect { case JsString(v) => v }.getOrElse("")
          PageText(s("url"), s("text"), s("section"), s("place"))
        }.toOption
      }.map(t => t.url -> t).toMap

  private def toJson(results: Vector[Result], label: String, provider: String, noHints: Boolean): JsValue =
    JsObject(
      "label" -> JsString(label),
      "provider" -> JsString(provider),
      "hints" -> JsBoolean(!noHints),
      "pages" -> JsArray(results.map { r =>
        JsObject(
          "url" -> JsString(r.url),
          "categories" -> JsArray(r.categories.map(JsString(_))),
          "places" -> JsArray(r.places.map(JsString(_))),
          "catVerdict" -> JsString(r.catVerdict.toString),
          "placeVerdict" -> JsString(r.placeVerdict.toString),
          "error" -> r.error.fold[JsValue](JsNull)(JsString(_))
        )
      })
    )

  private def readResults(p: Path): Map[String, Result] =
    Files.readString(p).parseJson.asJsObject.fields("pages").asInstanceOf[JsArray].elements.map { v =>
      val f = v.asJsObject.fields
      def arr(k: String) = f(k).asInstanceOf[JsArray].elements.collect { case JsString(s) => s }
      def verdict(k: String) = Verdict.valueOf(f(k).asInstanceOf[JsString].value)
      val url = f("url").asInstanceOf[JsString].value
      url ->
      Result(url, arr("categories"), arr("places"), verdict("catVerdict"), verdict("placeVerdict"), hintsUsed = true,
        None)
    }.toMap

  private def gitShortSha(): Option[String] =
    Try(new String(Runtime.getRuntime.exec(Array("git", "rev-parse", "--short",
      "HEAD")).getInputStream.readAllBytes()).trim)
      .toOption.filter(_.nonEmpty)
}
