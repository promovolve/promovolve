package promovolve.common

import java.time.{ Instant, LocalDate, ZoneId, ZoneOffset }
import scala.util.Try

/**
 * Advertiser-account timezone helpers.
 *
 * Timezones are carried as plain IANA strings ("" = UTC) in persisted entity
 * state and cross-node messages — never as ZoneId — so Jackson recovery of
 * pre-timezone snapshots and mixed-version clusters stay safe. Parse at the
 * point of use via these total helpers: an empty or unknown id silently
 * resolves to UTC (the legacy behavior), never throws.
 *
 * Budget windows and pacing day-windows use the advertiser zone; traffic-shape
 * learning, settlement, and metering deliberately stay UTC.
 */
object Timezones {

  /** Total parse: "" or invalid → UTC. */
  def zoneOf(tz: String): ZoneId =
    if (tz.isEmpty) ZoneOffset.UTC
    else Try(ZoneId.of(tz)).getOrElse(ZoneOffset.UTC)

  /** True for a non-empty, known IANA zone id. */
  def isValid(tz: String): Boolean =
    tz.nonEmpty && Try(ZoneId.of(tz)).isSuccess

  /** Calendar day number of `instant` in the advertiser's zone. */
  def localEpochDay(instant: Instant, tz: String): Long =
    LocalDate.ofInstant(instant, zoneOf(tz)).toEpochDay

  /** The zone-midnight strictly after `instant` — the budget-window end. */
  def nextMidnightAfter(instant: Instant, tz: String): Instant = {
    val zone = zoneOf(tz)
    LocalDate.ofInstant(instant, zone).plusDays(1).atStartOfDay(zone).toInstant
  }
}
