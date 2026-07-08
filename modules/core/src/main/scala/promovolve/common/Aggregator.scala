package promovolve.common

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object Aggregator {

  def apply[Reply: ClassTag, Aggregate](
      sendRequests: ActorRef[Reply] => Unit,
      expectedReplies: Int,
      replyTo: ActorRef[Aggregate],
      aggregateReplies: immutable.IndexedSeq[Reply] => Aggregate,
      timeout: FiniteDuration
  ): Behavior[Command] =
    Behaviors.setup { context =>
      if (expectedReplies == 0) {
        replyTo ! aggregateReplies(Vector.empty)
        Behaviors.stopped
      } else {
        context.setReceiveTimeout(timeout, ReceiveTimeout)
        val replyAdapter = context.messageAdapter[Reply](WrappedReply(_))

        def collecting(replies: Vector[Reply]): Behavior[Command] =
          Behaviors.receiveMessage {
            case WrappedReply(reply) =>
              val newReplies = replies :+ reply.asInstanceOf[Reply]
              if (newReplies.size == expectedReplies) {
                replyTo ! aggregateReplies(newReplies)
                Behaviors.stopped
              } else collecting(newReplies)

            case ReceiveTimeout =>
              replyTo ! aggregateReplies(replies)
              Behaviors.stopped
          }

        Try(sendRequests(replyAdapter)) match {
          case Success(_) =>
            collecting(Vector.empty)
          case Failure(e) =>
            context.log.error("Aggregator sendRequests failed", e)
            replyTo ! aggregateReplies(Vector.empty)
            Behaviors.stopped
        }
      }
    }

  sealed trait Command

  private final case class WrappedReply[R](reply: R) extends Command

  private case object ReceiveTimeout extends Command
}
