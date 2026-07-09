package promovolve.auction

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRef, Behavior }
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, EntityRef, EntityTypeKey }
import promovolve.*
import promovolve.auction.CategoryBidderEntity
import promovolve.common.Aggregator

import scala.collection.immutable.Queue
import scala.concurrent.duration.*

/**
 * CampaignDistributor - Sharded entity for broadcasting campaign updates.
 *
 * == Purpose ==
 * Distributes the broadcast workload from CampaignDirectory across the cluster.
 * Each worker handles a partition of categories based on hash(categoryId) % numWorkers.
 *
 * == Why Sharded? ==
 * CampaignDirectory is a singleton that was becoming a bottleneck when broadcasting
 * to many CategoryBidderEntity virtual shards. By offloading the actual message
 * fan-out to N sharded workers distributed across the cluster:
 * - Singleton only does lightweight routing
 * - Broadcast load is distributed across nodes
 * - Each worker can rate-limit its own concurrent broadcasts
 *
 * == Work Queue ==
 * Each worker maintains a bounded queue of pending publish requests.
 * When at max concurrent broadcasts, new requests are queued.
 * This provides natural backpressure without dropping work.
 */
object CampaignDistributor {

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("campaign-distributor")

  /** Number of publisher shards (tunable based on cluster size and load) */
  val NumWorkers: Int = 8

  /** Maximum concurrent Aggregator broadcasts per worker */
  val MaxConcurrentBroadcasts: Int = 5

  /** Maximum queued work items per worker before applying backpressure */
  val MaxQueueSize: Int = 100

  /** Compute which worker handles a given category */
  def workerIdFor(categoryId: CategoryId): Int =
    math.abs(categoryId.value.hashCode % NumWorkers)

  // ----------- Protocol -----------

  sealed trait Command extends promovolve.CborSerializable

  /** Request to publish campaign list to a category's virtual shards */
  final case class Publish(
      categoryId: CategoryId,
      campaigns: Map[CampaignId, AdvertiserId],
      replyTo: ActorRef[PublishAck]
  ) extends Command

  /** Acknowledgment that publish was queued (not necessarily completed) */
  final case class PublishAck(categoryId: CategoryId) extends promovolve.CborSerializable

  /** Internal: broadcast to virtual shards completed */
  private final case class BroadcastCompleted(categoryId: CategoryId) extends Command

  /** Internal: broadcast to virtual shards timed out */
  private final case class BroadcastTimeout(categoryId: CategoryId) extends Command

  // ----------- State -----------

  private final case class State(
      workerId: Int,
      pending: Queue[Publish] = Queue.empty,
      inFlight: Set[CategoryId] = Set.empty
  ) {
    def canStartBroadcast: Boolean = inFlight.size < MaxConcurrentBroadcasts
    def hasQueuedWork: Boolean = pending.nonEmpty
    def queueSize: Int = pending.size
  }

  /** Compute entity ID for a worker */
  def entityIdFor(workerId: Int): String = workerId.toString

  /** Compute entity ID for a category (routes to appropriate worker) */
  def entityIdForCategory(categoryId: CategoryId): String =
    workerIdFor(categoryId).toString

  // ----------- Behavior -----------

  def apply(entityId: String, sharding: ClusterSharding): Behavior[Command] =
    Behaviors.setup { ctx =>
      val workerId = entityId.toInt
      ctx.log.info("CampaignDistributor[{}] started", workerId)

      def behavior(state: State): Behavior[Command] =
        Behaviors.receiveMessage {
          case req @ Publish(categoryId, campaigns, replyTo) =>
            // Immediately ack that we received the request
            replyTo ! PublishAck(categoryId)

            if (state.inFlight.contains(categoryId)) {
              // Already broadcasting for this category, queue to coalesce
              ctx.log.debug(
                "CampaignDistributor[{}] coalescing publish for {} (already in-flight)",
                workerId,
                categoryId
              )
              val newPending = if (state.queueSize < MaxQueueSize) {
                // Replace any existing queued request for same category
                state.pending.filterNot(_.categoryId == categoryId).enqueue(req)
              } else {
                ctx.log.warn(
                  "CampaignDistributor[{}] queue full, dropping publish for {}",
                  workerId,
                  categoryId
                )
                state.pending
              }
              behavior(state.copy(pending = newPending))

            } else if (state.canStartBroadcast) {
              // Start broadcast immediately
              startBroadcast(categoryId, campaigns, sharding, ctx)
              behavior(state.copy(inFlight = state.inFlight + categoryId))

            } else {
              // Queue for later
              ctx.log.debug(
                "CampaignDistributor[{}] queueing publish for {} (at max concurrency)",
                workerId,
                categoryId
              )
              val newPending = if (state.queueSize < MaxQueueSize) {
                state.pending.enqueue(req)
              } else {
                ctx.log.warn(
                  "CampaignDistributor[{}] queue full, dropping publish for {}",
                  workerId,
                  categoryId
                )
                state.pending
              }
              behavior(state.copy(pending = newPending))
            }

          case BroadcastCompleted(categoryId) =>
            ctx.log.debug("CampaignDistributor[{}] broadcast completed for {}", workerId, categoryId)
            val newInFlight = state.inFlight - categoryId
            processQueue(state.copy(inFlight = newInFlight), sharding, ctx)

          case BroadcastTimeout(categoryId) =>
            ctx.log.warn("CampaignDistributor[{}] broadcast timed out for {}", workerId, categoryId)
            val newInFlight = state.inFlight - categoryId
            processQueue(state.copy(inFlight = newInFlight), sharding, ctx)
        }

      def processQueue(
          state: State,
          sharding: ClusterSharding,
          ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command]
      ): Behavior[Command] =
        if (state.canStartBroadcast && state.hasQueuedWork) {
          val (req, remaining) = state.pending.dequeue
          startBroadcast(req.categoryId, req.campaigns, sharding, ctx)
          behavior(state.copy(pending = remaining, inFlight = state.inFlight + req.categoryId))
        } else {
          behavior(state)
        }

      def startBroadcast(
          categoryId: CategoryId,
          campaigns: Map[CampaignId, AdvertiserId],
          sharding: ClusterSharding,
          ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command]
      ): Unit = {
        // Expand to all virtual shards for this category
        val shardIds = CategoryBidderEntity.allEntityIdsFor(categoryId.value)

        ctx.log.debug(
          "CampaignDistributor[{}] broadcasting to {} virtual shards for category {}",
          workerId,
          shardIds.size,
          categoryId
        )

        // Pre-compute entity refs (must be done on this actor's thread)
        val shardRefs: Seq[EntityRef[CategoryBidderEntity.Command]] =
          shardIds.map(id => sharding.entityRefFor(CategoryBidderEntity.TypeKey, id))

        // Create response adapter for aggregator results
        val completedAdapter = ctx.messageAdapter[AggregatorResult](_ => BroadcastCompleted(categoryId))

        ctx.spawnAnonymous(
          Aggregator[CategoryBidderEntity.ActiveCampaignsAck, AggregatorResult](
            sendRequests = { replyAdapter =>
              shardRefs.foreach { ref =>
                ref ! CategoryBidderEntity.ActiveCampaigns(campaigns, replyAdapter)
              }
            },
            expectedReplies = shardIds.size,
            replyTo = completedAdapter,
            aggregateReplies = replies => AggregatorResult(replies.size),
            timeout = 5.seconds
          )
        )
      }

      behavior(State(workerId))
    }

  /** Internal result wrapper for aggregator */
  private final case class AggregatorResult(ackedCount: Int)
}
