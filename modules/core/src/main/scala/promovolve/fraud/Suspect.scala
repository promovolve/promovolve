package promovolve.fraud

/**
 * Suspect-reason vocabulary (docs/design/FRAUD_PREVENTION.md).
 *
 * The long name is what lands in `tracking_events.suspect_reason` and
 * what every money/learning consumer filters on (`IS NULL` = clean).
 * The single-char code is what rides inside the signed tracking token
 * from serve time to the beacons — tokens live in URLs, so the wire
 * form stays one byte.
 *
 * Plain strings, not a sealed trait: these cross serialization
 * boundaries and sealed-trait case objects corrupt Jackson CBOR
 * recovery (see the Jackson sealed-trait rule).
 */
object Suspect {

  val DatacenterAsn = "datacenter_asn"
  val BotUa = "bot_ua"
  val RateCap = "rate_cap"
  val Chain = "chain" // Layer 1: event without its predecessor in the token chain
  val Timing = "timing" // Layer 1: sub-human render→event delta

  private val toCodeMap: Map[String, String] = Map(
    DatacenterAsn -> "d",
    BotUa -> "b",
    RateCap -> "r",
    Chain -> "c",
    Timing -> "t"
  )
  private val fromCodeMap: Map[String, String] = toCodeMap.map(_.swap)

  /** Compact wire code for the tracking token; unknown reasons pass through. */
  def toCode(reason: String): String = toCodeMap.getOrElse(reason, reason)

  /** Inverse of [[toCode]]; unknown codes pass through (forward compat). */
  def fromCode(code: String): String = fromCodeMap.getOrElse(code, code)
}
