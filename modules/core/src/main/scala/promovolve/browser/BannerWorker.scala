package promovolve.browser

import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior }
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.ClusterShardingSettings
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity, EntityTypeKey }

import scala.concurrent.Future
import scala.util.{ Failure, Success }

object BannerWorker {
  sealed trait Command extends promovolve.CborSerializable
  final case class Render(pagesJson: String, width: Int, height: Int, replyTo: ActorRef[Result]) extends Command
  // Screenshots can exceed Artery's frame limit; only storage metadata crosses the cluster.
  final case class Result(hash: String, key: String, mime: String, brokenImages: Int, error: Option[String])
      extends promovolve.CborSerializable
  private final case class Completed(replyTo: ActorRef[Result], result: Result) extends Command

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("browser-banner-worker")
  type Run = Render => Future[Result]

  def apply(run: Run): Behavior[Command] = Behaviors.setup { ctx =>
    var running = 0
    Behaviors.receiveMessage {
      case request: Render =>
        if (running >= 2)
          request.replyTo ! Result("", "", "", 0, Some("Browser renderer is busy; retry later"))
        else {
          running += 1
          ctx.pipeToSelf(Future.unit.flatMap(_ => run(request))(using ctx.executionContext)) {
            case Success(result) => Completed(request.replyTo, result)
            case Failure(error)  => Completed(request.replyTo, Result("", "", "", 0, Some(error.getMessage)))
          }
        }
        Behaviors.same
      case Completed(replyTo, result) =>
        running -= 1
        replyTo ! result
        Behaviors.same
    }
  }

  def init(system: ActorSystem[?], sharding: ClusterSharding, run: Run): Unit =
    sharding.init(
      Entity(TypeKey)(_ => apply(run))
        .withSettings(ClusterShardingSettings(system).withRole("browser").withRememberEntities(false))
    )
}
