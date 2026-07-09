package promovolve.common

import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import promovolve.publisher.PublisherEntity

import java.security.SecureRandom
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.*

trait PublisherSecretsRepo {
  def secretFor(pub: String): Future[Option[Array[Byte]]]
}

/**
 * PublisherSecretsRepo backed by PublisherEntity (persistent).
 * Reads/generates secrets from the publisher's durable state.
 * Auto-generates secrets for publishers that don't have one yet.
 */
class EntityBackedPublisherSecretsRepo(sharding: ClusterSharding, autoGenerate: Boolean = true)(using
    system: org.apache.pekko.actor.typed.ActorSystem[?]
) extends PublisherSecretsRepo {

  import org.apache.pekko.util.Timeout
  private given Timeout = Timeout(5.seconds)
  private given ExecutionContext = system.executionContext
  private val random = new SecureRandom()

  def secretFor(pub: String): Future[Option[Array[Byte]]] = {
    val ref = sharding.entityRefFor(PublisherEntity.TypeKey, pub)
    ref.ask[PublisherEntity.HmacSecret](PublisherEntity.GetHmacSecret(_)).flatMap { result =>
      result.secret match {
        case Some(secret)         => Future.successful(Some(secret))
        case None if autoGenerate =>
          val generated = new Array[Byte](32)
          random.nextBytes(generated)
          ref.ask[PublisherEntity.HmacSecretUpdated](PublisherEntity.SetHmacSecret(generated, _))
            .map(_ => Some(generated))
        case None => Future.successful(None)
      }
    }.recover {
      case _: Exception => None
    }
  }
}
