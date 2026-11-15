package promovolve.tools

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import promovolve.creative.{ CreativeArc, LPExtractor }
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.nio.file.{ Files, Paths }
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext }

/**
 * Fold-out copy eval: runs the REAL synthesis (same prompt and model as the
 * creative editor) with the one-page "poster" brief over landing pages
 * captured by scripts/classify-eval/extract.mjs, and prints the ad it writes.
 * Flags what the prompt promises: exactly one page, field lengths in range,
 * and every number in the copy found verbatim in the page.
 *
 * Test scope on purpose: paid model — run by hand before changing the poster
 * brief, never in `sbt test`.
 *
 *   GEMINI_API_KEY=… sbt "core/Test/runMain promovolve.tools.PosterCopyEval scripts/classify-eval/pages/<a>.json …"
 */
object PosterCopyEval {
  def main(args: Array[String]): Unit = {
    val apiKey = sys.env.getOrElse("GEMINI_API_KEY", sys.error("set GEMINI_API_KEY"))
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "poster-copy-eval")
    given ec: ExecutionContext = system.executionContext
    val extractor = new LPExtractor(apiKey)
    try
      args.foreach { file =>
        val f = Files.readString(Paths.get(file)).parseJson.asJsObject.fields
        val text = f("text").convertTo[String]
        val pages = Await.result(extractor.synthesizeSections(Vector("" -> text), CreativeArc.poster.id), 120.seconds)
        println(s"\n=== ${f("url").convertTo[String]}  (${pages.size} page)")
        pages.foreach { p =>
          val copy = s"${p.headline} ${p.sub} ${p.body}"
          val ungrounded = "\\d[\\d,.]*".r.findAllIn(copy).filterNot(text.contains).toVector
          def len(name: String, s: String, max: Int) = s"$name ${s.length}/$max${if (s.length > max) " OVER" else ""}"
          println(s"tag:      ${p.tag}")
          println(s"headline: ${p.headline}")
          println(s"sub:      ${p.sub}")
          println(s"body:     ${p.body}")
          println(s"${len("headline", p.headline, 30)} · ${len("sub", p.sub, 60)} · ${len("body", p.body, 220)}" +
            (if (ungrounded.isEmpty) " · numbers grounded" else s" · UNGROUNDED ${ungrounded.mkString(",")}"))
        }
      }
    finally system.terminate()
  }
}
