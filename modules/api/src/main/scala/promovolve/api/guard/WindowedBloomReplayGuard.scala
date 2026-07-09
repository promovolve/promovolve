package promovolve.api.guard

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRef, Behavior }
import org.apache.pekko.cluster.ddata.typed.scaladsl.{ DistributedData, Replicator }
import org.apache.pekko.cluster.ddata.{ LWWRegister, LWWRegisterKey, SelfUniqueAddress }
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ EntityContext, EntityTypeKey }
import promovolve.CborSerializable

import java.util.zip.CRC32

/** Snapshot envelope stored in DData. */
final case class SnapshotEnvelope(
    version: Int,
    mBits: Int,
    k: Int,
    rotatedAtNanos: Long,
    currentBucket: Long,
    prevBucket: Long,
    @JsonDeserialize(contentAs = classOf[java.lang.Long]) current: Array[Long],
    @JsonDeserialize(contentAs = classOf[java.lang.Long]) previous: Array[Long],
    crc32: Long
) extends CborSerializable

object WindowedBloomReplayGuard {

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("replay-guard")

  def initBehavior(
      entityCtx: EntityContext[Command],
      config: GuardConfiguration
  ): Behavior[Command] = {
    val part = parsePart(entityCtx.entityId)
    apply(part, config)
  }

  private def parsePart(entityId: String): Int = {
    val parts = entityId.split("\\|")
    require(
      parts.length == 2 && parts(0) == "replay",
      s"Invalid entityId '$entityId', expected replay|<part>"
    )
    parts(1).toInt
  }

  def apply(partitionId: Int, config: GuardConfiguration): Behavior[Command] = {
    require(config.isValid, "Invalid guard configuration")

    val key = snapKey(partitionId)
    val bucketStrategy = new BucketRotationStrategy(config.bucketMs)
    val timingAnalyzer = new EventTimingAnalyzer(bucketStrategy, config.extendedSkew)

    Behaviors.setup { ctx =>
      val node: SelfUniqueAddress = DistributedData(ctx.system).selfUniqueAddress

      DistributedData.withReplicatorMessageAdapter[Command, LWWRegister[SnapshotEnvelope]] { repl =>
        repl.askGet(
          ask => Replicator.Get(key, Replicator.ReadLocal, ask),
          InternalGetResponse.apply
        )

        Behaviors.withTimers { timers =>
          timers.startSingleTimer("boot-deadline", BootDeadline, config.bootMaxWait)
          timers.startTimerAtFixedRate("publish", Publish, config.publishEvery)

          Behaviors.withStash(Int.MaxValue) { buffer =>
            def loading: Behavior[Command] =
              Behaviors.receiveMessage {
                case InternalGetResponse(success @ Replicator.GetSuccess(`key`)) =>
                  val envelope = success.get(key).value
                  val maybeState = ReplayGuardState.fromSnapshot(envelope, config, bucketStrategy)
                  val initialState = maybeState.getOrElse(
                    ReplayGuardState.initial(config, bucketStrategy).withForceInitialPublish
                  )
                  timers.cancel("boot-deadline")
                  buffer.unstashAll(active(initialState))

                case InternalGetResponse(_) =>
                  Behaviors.same

                case BootDeadline =>
                  val initialState = ReplayGuardState.initial(config, bucketStrategy).withForceInitialPublish
                  buffer.unstashAll(active(initialState))

                case other =>
                  buffer.stash(other)
                  Behaviors.same
              }

            def active(state: ReplayGuardState): Behavior[Command] =
              Behaviors.receiveMessage {
                case ValidateReplayAt(replyTo, nonce, eventTimeMs) =>
                  // Check for bucket rotation
                  val rotatedState = bucketStrategy.checkRotation(state.currentBucket) match {
                    case Some(rotation) => state.applyRotation(rotation, config)
                    case None           => state
                  }

                  // Process validation
                  val (newState, allowed) = rotatedState.processValidation(nonce, eventTimeMs, timingAnalyzer)
                  replyTo ! allowed
                  active(newState)

                case Publish =>
                  if (state.shouldPublish(config)) {
                    val curV = state.bloomCurrent.snapshotWords
                    val prevV = state.bloomPrevious.snapshotWords
                    val crc = computeCrc32(curV, prevV, state.bloomCurrent.mBits, state.bloomCurrent.k,
                      state.currentBucket, state.previousBucket)
                    val snap = SnapshotEnvelope(
                      version = 1,
                      mBits = state.bloomCurrent.mBits,
                      k = state.bloomCurrent.k,
                      rotatedAtNanos = state.rotatedAtNs,
                      currentBucket = state.currentBucket,
                      prevBucket = state.previousBucket,
                      current = curV,
                      previous = prevV,
                      crc32 = crc
                    )
                    repl.askUpdate(
                      ask =>
                        Replicator.Update(key, LWWRegister(node, snap), Replicator.WriteLocal, ask)(reg =>
                          reg.withValue(node, snap)
                        ),
                      InternalUpdateResponse.apply
                    )
                    active(state.startPublish)
                  } else {
                    Behaviors.same
                  }

                case InternalUpdateResponse(_) =>
                  active(state.publishCompleted)

                case InternalGetResponse(_) | BootDeadline =>
                  Behaviors.same
              }

            loading
          }
        }
      }
    }
  }

  private def snapKey(part: Int): LWWRegisterKey[SnapshotEnvelope] =
    LWWRegisterKey[SnapshotEnvelope](s"replay|$part")

  private def computeCrc32(cur: Array[Long], prev: Array[Long], mBits: Int, k: Int, curB: Long, prevB: Long): Long = {
    val crc = new CRC32()
    putInt(crc, mBits); putInt(crc, k); putLong(crc, curB); putLong(crc, prevB)
    cur.foreach(putLong(crc, _))
    prev.foreach(putLong(crc, _))
    crc.getValue
  }

  private def putInt(crc: CRC32, v: Int): Unit = {
    crc.update((v >>> 24) & 0xFF); crc.update((v >>> 16) & 0xFF)
    crc.update((v >>> 8) & 0xFF); crc.update(v & 0xFF)
  }

  private def putLong(crc: CRC32, v: Long): Unit = {
    crc.update(((v >>> 56) & 0xFF).toInt); crc.update(((v >>> 48) & 0xFF).toInt)
    crc.update(((v >>> 40) & 0xFF).toInt); crc.update(((v >>> 32) & 0xFF).toInt)
    crc.update(((v >>> 24) & 0xFF).toInt); crc.update(((v >>> 16) & 0xFF).toInt)
    crc.update(((v >>> 8) & 0xFF).toInt); crc.update((v & 0xFF).toInt)
  }

  sealed trait Command

  final case class ValidateReplayAt(
      replyTo: ActorRef[Boolean],
      nonce: Long,
      eventTimeMs: Long = System.currentTimeMillis()
  ) extends Command with CborSerializable

  private final case class InternalGetResponse(
      rsp: Replicator.GetResponse[LWWRegister[SnapshotEnvelope]]
  ) extends Command

  private final case class InternalUpdateResponse(
      rsp: Replicator.UpdateResponse[LWWRegister[SnapshotEnvelope]]
  ) extends Command

  private case object Publish extends Command
  private case object BootDeadline extends Command
}
