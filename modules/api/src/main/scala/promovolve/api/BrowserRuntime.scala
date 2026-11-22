package promovolve.api

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import promovolve.browser.{ BannerWorker, BrowserSessionPool, LPAnalyzer, LPWorker }
import promovolve.publisher.assets.ImageStorage

object BrowserRuntime {
  def start(system: ActorSystem[?], sharding: ClusterSharding): Unit = {
    given ActorSystem[?] = system
    val storage =
      ImageStorage.fromEnv().getOrElse(throw new IllegalStateException("Browser workers require image storage"))
    start(system, sharding, storage)
  }

  private[api] def start(system: ActorSystem[?], sharding: ClusterSharding, storage: ImageStorage): Unit = {
    given ActorSystem[?] = system
    given scala.concurrent.ExecutionContext = system.executionContext
    require(!sys.env.get("CHROMIUM_NO_SANDBOX").contains("true"), "Browser workers require Chromium sandboxing")
    val config = system.settings.config
    val pool = BrowserSessionPool.init(system)
    val analyzer = new LPAnalyzer(config.getString("promovolve.banner-script-url"), pool)
    val settings = LPWorker.settingsFromConfig(config)
    LPWorker.initSharding(system, sharding,
      LPAnalysisRunner(analyzer, storage, None, config.getString("promovolve.cdn-base-url")), settings)
    LPWorker.initKeepAlive(system, sharding, settings)
    val render = BannerRenderer.local(analyzer, storage)
    BannerWorker.init(system, sharding,
      request =>
        render(request.pagesJson, request.width, request.height).map(image =>
          BannerWorker.Result(image.hash, image.key, image.mime, image.brokenImages, None)))
  }
}
