package promovolve.browser

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * One-off manual check: hit an LP with the LPAnalyzer and dump what
 * we got. Invoke via: sbt "crawler/Test / runMain promovolve.browser.TestLPAnalyzerApp [url]"
 *
 * Spins up a minimal ActorSystem with a 1-session BrowserSessionPool
 * since LPAnalyzer now routes its Playwright work through the pool.
 */
object TestLPAnalyzerApp {
  def main(args: Array[String]): Unit = {
    val url = args.headOption.getOrElse("https://www.asics.com/jp/ja-jp/")
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "lp-analyzer-test")
    val pool = BrowserSessionPool.init(system)
    val analyzer = new LPAnalyzer(
      bannerScriptUrl = "https://example.com/banner.js",
      browserPool = pool
    )
    try {
      println(s"--- Analyzing $url ---")
      val (result, captured) = Await.result(analyzer.analyze(url, "auto"), 60.seconds)
      println(s"URL:            ${result.url}")
      println(s"Sections:       ${result.sections.size}")
      println(s"Captured bytes: ${captured.size} images")
      println(s"Dominant bg:    ${result.dominantColor.getOrElse("(none)")}")
      println(s"Text colour:    ${result.textColor.getOrElse("(none)")}")
      result.sections.zipWithIndex.foreach { case (s, i) =>
        val headingPreview = if (s.heading.nonEmpty) s.heading.take(80) else "(no heading)"
        val textPreview = s.text.take(120).replace("\n", " ")
        println(s"  [$i] $headingPreview")
        println(s"      text: $textPreview")
        println(
          s"      imgs: ${s.images.size} (${s.images.take(3).map(i => s"${i.width}x${i.height}").mkString(", ")})")
      }
    } catch {
      case e: Throwable =>
        println(s"FAILED: ${e.getClass.getSimpleName}: ${e.getMessage}")
        e.printStackTrace()
    } finally {
      analyzer.close()
      system.terminate()
      sys.exit(0)
    }
  }
}
