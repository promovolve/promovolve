package promovolve

/** Marker trait for Jackson CBOR serialization in cluster messages.
  *
  * All messages that need to be serialized across the cluster should extend this trait.
  * This enables Jackson CBOR serialization as configured in application.conf:
  *
  * {{{
  * pekko.actor.serialization-bindings {
  *   "promovolve.CborSerializable" = jackson-cbor
  * }
  * }}}
  */
trait CborSerializable