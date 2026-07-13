package promovolve.api

import org.apache.pekko.actor.typed.{ ActorRef, Behavior }
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import promovolve.{ BudgetEvent, CreativeAutoApproved, PendingCreativesQueued }

/**
 * Adapter actor that converts BudgetEvents to PendingEventHub commands.
 *
 * Subscribed to the BudgetEvent topic, filters for PendingCreativesQueued events,
 * and forwards them to the PendingEventHub.
 */
object PendingEventHubAdapter {

  def apply(hub: ActorRef[PendingEventHub.Command]): Behavior[BudgetEvent] =
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case event: PendingCreativesQueued =>
          ctx.log.debug(
            "Forwarding PendingCreativesQueued to hub: site={} count={}",
            event.siteId.value,
            event.count
          )
          hub ! PendingEventHub.PublishPendingUpdate(
            siteId = event.siteId.value,
            url = event.url.value,
            slotId = event.slotId.value,
            count = event.count,
            topCreativeId = event.topCreativeId.value
          )
          Behaviors.same

        case event: CreativeAutoApproved =>
          ctx.log.debug(
            "Forwarding CreativeAutoApproved to hub: site={} creative={}",
            event.siteId.value,
            event.creativeId.value
          )
          hub ! PendingEventHub.PublishAutoApproved(
            siteId = event.siteId.value,
            url = event.url.value,
            slotId = event.slotId.value,
            creativeId = event.creativeId.value,
            campaignId = event.campaignId.value
          )
          Behaviors.same

        case _ =>
          // Ignore other budget events
          Behaviors.same
      }
    }
}
