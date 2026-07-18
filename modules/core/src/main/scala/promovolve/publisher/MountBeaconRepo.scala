package promovolve.publisher

import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
 * Append-only store for mount heartbeats — the anonymous per-pageview
 * integration-health beacon the publisher ad tag fires
 * (script → slot → serve → render, see banner-bootstrap/src/heartbeat.ts).
 *
 * Deliberately NOT a tracking event: it carries no URL, no reader
 * identity, no creative — just the site id, the furthest lifecycle
 * stage reached, and counts. The publisher dashboard aggregates it
 * into the per-site "Integration health" panel; nothing bills off it.
 */
trait MountBeaconRepo {

  /** Record one heartbeat. Fire-and-forget from the route. */
  def record(
      pub: String,
      stage: String,
      ok: Boolean,
      reason: Option[String],
      slots: Int,
      served: Int,
      mounted: Int
  ): Future[Unit]
}

/** PostgreSQL-backed implementation using Slick */
class SlickMountBeaconRepo(db: slick.jdbc.JdbcBackend#Database)(using ec: ExecutionContext)
    extends MountBeaconRepo {

  /** Create table if not exists */
  def ensureSchema(): Unit = {
    val createTableSql = sql"""
      CREATE TABLE IF NOT EXISTS mount_beacons (
        pub VARCHAR(128) NOT NULL,
        stage VARCHAR(8) NOT NULL,
        ok BOOLEAN NOT NULL,
        reason VARCHAR(120),
        slots INT NOT NULL,
        served INT NOT NULL,
        mounted INT NOT NULL,
        ts TIMESTAMP NOT NULL DEFAULT NOW()
      )
    """.asUpdate

    val createIndexSql = sql"""
      CREATE INDEX IF NOT EXISTS idx_mount_beacons_pub_ts ON mount_beacons (pub, ts)
    """.asUpdate

    Await.result(db.run(createTableSql >> createIndexSql), 10.seconds)
  }

  override def record(
      pub: String,
      stage: String,
      ok: Boolean,
      reason: Option[String],
      slots: Int,
      served: Int,
      mounted: Int
  ): Future[Unit] = {
    val insert = sqlu"""
      INSERT INTO mount_beacons (pub, stage, ok, reason, slots, served, mounted)
      VALUES ($pub, $stage, $ok, $reason, $slots, $served, $mounted)
    """
    db.run(insert).map(_ => ())
  }
}
