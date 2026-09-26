package promovolve.dbit

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.compiletime.uninitialized
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.TimeLimits
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Minutes, Span }
import org.scalatest.wordspec.AnyWordSpec
import slick.jdbc.PostgresProfile.api.*

import promovolve.publisher.SlickTrafficShapeSnapshotRepo
import promovolve.publisher.delivery.TrafficShapeSnapshot

/**
 * The real SQL round trip for the traffic-shape snapshot (GH #23).
 * TrafficShapePersistenceSpec covers the pure row ↔ snapshot mapping; what it
 * cannot see is whether the Slick table definition matches what Postgres
 * actually accepts, or whether `ensureSchema` upgrades a table written before
 * the weekday/weekend split existed.
 */
class TrafficShapeRepoDbSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with TimeLimits {

  private val limit = Span(3, Minutes)

  private val weekday = Array.tabulate(24)(h => if (h >= 9 && h <= 17) 2.0 else 0.5)
  private val weekend = Array.tabulate(24)(h => if (h >= 11 && h <= 22) 1.8 else 0.4)

  // Postgres timestamps keep microseconds, the JVM clock offers nanos —
  // truncate so the comparison tests persistence, not clock precision.
  private val updatedAt = Instant.parse("2026-07-13T04:02:00Z").truncatedTo(ChronoUnit.MICROS)

  private val snapshot = TrafficShapeSnapshot(
    bucketCount = 24,
    alpha = 0.1,
    volumes = weekday,
    emaBucketRequests = 42.5,
    updatedAt = updatedAt,
    weekdayVolumes = Some(weekday),
    weekendVolumes = Some(weekend)
  )

  private var db: Database = uninitialized

  override def beforeAll(): Unit =
    db = Database.forURL(
      url = PostgresTestContainer.jdbcUrl,
      user = PostgresTestContainer.username,
      password = PostgresTestContainer.password,
      driver = "org.postgresql.Driver"
    )

  override def afterAll(): Unit = if (db != null) db.close()

  /** Each test owns its schema, so column-level changes cannot leak sideways. */
  private def withSchema[A](name: String)(body: SlickTrafficShapeSnapshotRepo => A): A = {
    Await.result(db.run(sqlu"""DROP SCHEMA IF EXISTS #$name CASCADE"""), 30.seconds)
    Await.result(db.run(sqlu"""CREATE SCHEMA #$name"""), 30.seconds)
    val base = PostgresTestContainer.jdbcUrl
    val sep = if (base.contains("?")) "&" else "?"
    val scoped = Database.forURL(
      url = s"$base${sep}currentSchema=$name",
      user = PostgresTestContainer.username,
      password = PostgresTestContainer.password,
      driver = "org.postgresql.Driver"
    )
    try body(new SlickTrafficShapeSnapshotRepo(scoped))
    finally {
      scoped.close()
      Await.result(db.run(sqlu"""DROP SCHEMA IF EXISTS #$name CASCADE"""), 30.seconds)
    }
  }

  "SlickTrafficShapeSnapshotRepo against PostgreSQL" should {

    "create its schema, then upsert, read back and delete" in failAfter(limit) {
      withSchema("shape_roundtrip") { repo =>
        repo.ensureSchema()

        Await.result(repo.upsert("site-a", snapshot), 30.seconds)

        val loaded = Await.result(repo.get("site-a"), 30.seconds)
        loaded.isDefined shouldBe true
        val got = loaded.get
        got.bucketCount shouldBe 24
        got.alpha shouldBe 0.1
        got.emaBucketRequests shouldBe 42.5
        got.updatedAt shouldBe updatedAt
        got.volumes.toSeq shouldBe weekday.toSeq
        // The 2026-07-13 regression: both arrays have to come back, or
        // weekend learning restarts from nothing every deploy.
        got.weekdayVolumes.map(_.toSeq) shouldBe Some(weekday.toSeq)
        got.weekendVolumes.map(_.toSeq) shouldBe Some(weekend.toSeq)

        // Upsert is the write path used on every save tick — a second write
        // for the same site must update, not fail on the primary key.
        val moved = snapshot.copy(emaBucketRequests = 99.0)
        Await.result(repo.upsert("site-a", moved), 30.seconds)
        Await.result(repo.get("site-a"), 30.seconds).map(_.emaBucketRequests) shouldBe Some(99.0)

        Await.result(repo.delete("site-a"), 30.seconds)
        Await.result(repo.get("site-a"), 30.seconds) shouldBe None
      }
    }

    "upgrade a table written before the weekday/weekend split" in failAfter(limit) {
      withSchema("shape_legacy") { repo =>
        // The pre-upgrade table: no weekday_volumes, no weekend_volumes.
        Await.result(
          db.run(sqlu"""
            CREATE TABLE shape_legacy.traffic_shape_snapshot (
              site_id             VARCHAR NOT NULL PRIMARY KEY,
              bucket_count        INTEGER NOT NULL,
              alpha               DOUBLE PRECISION NOT NULL,
              volumes             VARCHAR NOT NULL,
              ema_bucket_requests DOUBLE PRECISION NOT NULL,
              updated_at          TIMESTAMP NOT NULL
            )"""),
          30.seconds
        )
        Await.result(
          db.run(sqlu"""
            INSERT INTO shape_legacy.traffic_shape_snapshot
              (site_id, bucket_count, alpha, volumes, ema_bucket_requests, updated_at)
            VALUES ('site-legacy', 24, 0.1, '[1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0]',
                    7.5, '2026-07-01 00:00:00')"""),
          30.seconds
        )

        // createIfNotExists leaves the existing table alone, so the ALTERs in
        // ensureSchema are what has to add the columns.
        repo.ensureSchema()

        val legacy = Await.result(repo.get("site-legacy"), 30.seconds)
        legacy.isDefined shouldBe true
        legacy.get.volumes.toSeq shouldBe Seq.fill(24)(1.0)
        // Null columns on the old row read as None rather than blowing up —
        // the tracker then relearns the split instead of starting empty.
        legacy.get.weekdayVolumes shouldBe None
        legacy.get.weekendVolumes shouldBe None

        // And the upgraded table now accepts a full snapshot.
        Await.result(repo.upsert("site-legacy", snapshot), 30.seconds)
        Await.result(repo.get("site-legacy"), 30.seconds)
          .flatMap(_.weekendVolumes.map(_.toSeq)) shouldBe Some(weekend.toSeq)
      }
    }
  }
}
