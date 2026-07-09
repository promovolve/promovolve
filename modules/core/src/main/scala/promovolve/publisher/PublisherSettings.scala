package promovolve.publisher

import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import promovolve.SiteId

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.*

/**
 * Publisher-specific configuration for content monetization.
 * Each publisher can customize their monetization strategy.
 */
trait PublisherSettings {

  /**
   * Get the content recency window for a publisher.
   * Only content classified within this window will be monetized.
   *
   * Examples:
   * - News sites: 24-48 hours (breaking news, high turnover)
   * - Magazine sites: 3-7 days (feature content, slower turnover)
   * - Analysis sites: 1-2 weeks (evergreen analysis)
   *
   * Valid range: 24 hours to 7 days
   */
  def contentRecencyWindow(publisherId: SiteId): Future[FiniteDuration]

  /** Default: 48 hours (news/media standard) */
  def contentRecencyWindowMs(publisherId: SiteId): Future[Long] =
    contentRecencyWindow(publisherId).map(_.toMillis)(ExecutionContext.parasitic)
}

/**
 * PublisherSettings backed by PublisherEntity (persistent).
 * Reads settings from the publisher's durable state.
 */
class EntityBackedPublisherSettings(sharding: ClusterSharding)(
    using system: org.apache.pekko.actor.typed.ActorSystem[?])
    extends PublisherSettings {

  import org.apache.pekko.util.Timeout
  private given Timeout = Timeout(5.seconds)
  private given ExecutionContext = system.executionContext

  def contentRecencyWindow(publisherId: SiteId): Future[FiniteDuration] = {
    val ref = sharding.entityRefFor(PublisherEntity.TypeKey, publisherId.value)
    ref.ask[PublisherEntity.ContentRecencyWindow](PublisherEntity.GetContentRecencyWindow(_))
      .map(_.windowMs.millis)
      .recover {
        case _: Exception => 48.hours // Default on error
      }
  }
}
