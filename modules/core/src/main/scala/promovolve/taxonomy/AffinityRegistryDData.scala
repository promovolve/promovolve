package promovolve.taxonomy

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.ddata.typed.scaladsl.{DistributedData, Replicator}
import org.apache.pekko.cluster.ddata.{ORMap, ORMapKey, ORSet, SelfUniqueAddress}

/** Lightweight DData registry tracking which (contentCategory, adProductCategory) pairs exist.
  *
  * Used for discovery at auction time: "For content category X, which ad product categories
  * have ever had impressions?" The AffinityExpander then queries ContentProductAffinityEntity
  * for scores on those pairs.
  *
  * Uses ORMap[String, ORSet[String]] replicated via gossip. Writes are local (fast),
  * reads are local (instant). Eventually consistent across the cluster.
  */
object AffinityRegistryDData {

  private val DataKey = ORMapKey[String, ORSet[String]]("affinity-registry")

  sealed trait Cmd
  final case class Register(contentCategory: String, adProductCategory: String) extends Cmd
  final case class Lookup(contentCategory: String, replyTo: ActorRef[AffinityPairs]) extends Cmd
  final case class AffinityPairs(contentCategory: String, adProductCategories: Set[String])

  // Internal messages for DData responses
  private sealed trait Internal extends Cmd
  private case class GetResult(
      contentCategory: String,
      replyTo: ActorRef[AffinityPairs],
      rsp: Replicator.GetResponse[ORMap[String, ORSet[String]]]
  ) extends Internal
  private case class UpdateResult(
      rsp: Replicator.UpdateResponse[ORMap[String, ORSet[String]]]
  ) extends Internal

  def apply(): Behavior[Cmd] = Behaviors.setup { ctx =>
    given node: SelfUniqueAddress = DistributedData(ctx.system).selfUniqueAddress

    DistributedData.withReplicatorMessageAdapter[Cmd, ORMap[String, ORSet[String]]] { adapter =>

      Behaviors.receiveMessage {
        case Register(contentCategory, adProductCategory) =>
          adapter.askUpdate(
            askRef => Replicator.Update(
              DataKey,
              ORMap.empty[String, ORSet[String]],
              Replicator.WriteLocal,
              askRef
            ) { existing =>
              existing.updated(node, contentCategory, ORSet.empty[String]) { set =>
                set :+ adProductCategory
              }
            },
            rsp => UpdateResult(rsp)
          )
          Behaviors.same

        case Lookup(contentCategory, replyTo) =>
          adapter.askGet(
            askRef => Replicator.Get(DataKey, Replicator.ReadLocal, askRef),
            rsp => GetResult(contentCategory, replyTo, rsp)
          )
          Behaviors.same

        case GetResult(contentCategory, replyTo, rsp) =>
          rsp match {
            case g @ Replicator.GetSuccess(`DataKey`) =>
              val orMap = g.get(DataKey)
              val adProducts = orMap.get(contentCategory) match {
                case Some(orSet) => orSet.elements
                case None        => Set.empty[String]
              }
              replyTo ! AffinityPairs(contentCategory, adProducts)
            case _: Replicator.NotFound[?] =>
              replyTo ! AffinityPairs(contentCategory, Set.empty)
            case other =>
              ctx.log.warn("AffinityRegistry lookup failed: {}", other)
              replyTo ! AffinityPairs(contentCategory, Set.empty)
          }
          Behaviors.same

        case UpdateResult(rsp) =>
          rsp match {
            case _: Replicator.UpdateSuccess[?] => // ok
            case other => ctx.log.warn("AffinityRegistry update failed: {}", other)
          }
          Behaviors.same
      }
    }
  }
}
