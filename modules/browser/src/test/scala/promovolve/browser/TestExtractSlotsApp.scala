package promovolve.browser

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Smoke test for the per-slot position-signal extraction in
  * `extractSlots` (crawler.js). Loads a fixture HTML with slots in
  * varied positions/regions and dumps what we capture.
  *
  * Run: sbt "crawler/Test / runMain promovolve.browser.TestExtractSlotsApp"
  */
object TestExtractSlotsApp {

  private val fixtureHtml: String =
    """<!doctype html>
      |<html>
      |<head>
      |  <meta charset="utf-8">
      |  <title>fixture</title>
      |  <style>
      |    body { margin: 0; font: 14px/1.5 sans-serif; }
      |    header, footer, main, aside, .footer-soup { padding: 16px; }
      |    .layout { display: grid; grid-template-columns: 1fr 240px; gap: 16px; max-width: 1200px; margin: 0 auto; }
      |    .ad { background: #eef; outline: 1px dashed #88a; }
      |    .filler { height: 600px; background: #f8f8f8; padding: 16px; }
      |    .footer-soup { background: #222; color: #ddd; }
      |  </style>
      |</head>
      |<body>
      |  <header>
      |    <h1>Site Name</h1>
      |    <div class="ad" data-promovolve-slot="hdr-leaderboard" data-w="728" data-h="90"
      |         style="width:728px;height:90px"></div>
      |  </header>
      |
      |  <div class="layout">
      |    <main>
      |      <article>
      |        <h2>Article headline</h2>
      |        <p>Lorem ipsum dolor sit amet, consectetur adipiscing elit. """.stripMargin +
      ("Sed do eiusmod tempor incididunt ut labore. " * 8) + """</p>
      |        <div class="ad" data-promovolve-slot="in-article" data-w="300" data-h="250"
      |             style="width:300px;height:250px"></div>
      |        <p>""".stripMargin + ("More body text to lift density. " * 20) + """</p>
      |      </article>
      |    </main>
      |    <aside>
      |      <div class="ad" data-promovolve-slot="sidebar-mpu" data-w="300" data-h="250"
      |           style="width:300px;height:250px"></div>
      |    </aside>
      |  </div>
      |
      |  <div class="filler">below-the-fold spacer</div>
      |
      |  <!-- div-soup footer: no semantic tag, no ARIA, just position -->
      |  <div class="footer-soup">
      |    <div class="ad" data-promovolve-slot="footer-divsoup" data-w="728" data-h="90"
      |         style="width:728px;height:90px;background:#eef"></div>
      |  </div>
      |</body>
      |</html>
      |""".stripMargin

  def main(args: Array[String]): Unit = {
    val crawlerJs = new String(
      getClass.getResourceAsStream("/crawler.js").readAllBytes(),
      StandardCharsets.UTF_8,
    )

    val tmpHtml: Path = Files.createTempFile("extract-slots-fixture", ".html")
    Files.writeString(tmpHtml, fixtureHtml)
    tmpHtml.toFile.deleteOnExit()

    val playwright = Playwright.create()
    try {
      val browser = playwright.chromium().launch(
        new BrowserType.LaunchOptions().setHeadless(true)
      )
      val context = browser.newContext(
        new Browser.NewContextOptions().setViewportSize(1280, 800)
      )
      val page = context.newPage()
      page.navigate("file://" + tmpHtml.toAbsolutePath.toString)
      page.addScriptTag(new Page.AddScriptTagOptions().setContent(crawlerJs))

      val result = page
        .evaluate("extractSlots")
        .asInstanceOf[java.util.List[java.util.Map[String, Any]]]
        .asScala

      println(s"--- extracted ${result.size} slot(s) ---")
      result.foreach { slotObj =>
        val m = slotObj.asScala
        val slotId = m.getOrElse("slotId", "?")
        val w = m.getOrElse("width", "?")
        val h = m.getOrElse("height", "?")
        val yTop = m.getOrElse("yTop", "?")
        val docH = m.getOrElse("docHeight", "?")
        val above = m.getOrElse("aboveFold", "?")
        val vis = m.get("initialViewability").map(_.toString.toDouble).getOrElse(Double.NaN)
        val region = m.getOrElse("region", "?")
        val density = m.get("textDensity").map(_.toString.toDouble).getOrElse(Double.NaN)
        println(f"  $slotId%-22s ${w}x${h}%-9s y=$yTop%-5s/$docH%-5s fold=$above%-5s vis=${vis}%.2f region=$region%-10s density=${density}%.2f")
      }

      browser.close()
    } catch {
      case e: Throwable =>
        println(s"FAILED: ${e.getClass.getSimpleName}: ${e.getMessage}")
        e.printStackTrace()
    } finally {
      playwright.close()
      sys.exit(0)
    }
  }
}
