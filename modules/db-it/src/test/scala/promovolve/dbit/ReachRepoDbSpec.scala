package promovolve.dbit

import java.time.LocalDate
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

import promovolve.publisher.{ ReachRepo, SlickReachRepo }

/**
 * Reach storage against real PostgreSQL (GH #238): the single-statement
 * count-unless-seen-freshness, and that the stored counts answer unique
 * browsers for an arbitrary day range.
 */
class ReachRepoDbSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with TimeLimits {

  private val limit = Span(3, Minutes)
  private var db: Database = uninitialized

  override def beforeAll(): Unit =
    db = Database.forURL(
      url = PostgresTestContainer.jdbcUrl,
      user = PostgresTestContainer.username,
      password = PostgresTestContainer.password,
      driver = "org.postgresql.Driver"
    )

  override def afterAll(): Unit = if (db != null) db.close()

  private def withSchema[A](name: String)(body: (SlickReachRepo, Database) => A): A = {
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
    try {
      val repo = new SlickReachRepo(scoped)
      repo.ensureSchema()
      body(repo, scoped)
    } finally {
      scoped.close()
      Await.result(db.run(sqlu"""DROP SCHEMA IF EXISTS #$name CASCADE"""), 30.seconds)
    }
  }

  private val day1 = LocalDate.parse("2026-10-01")
  private def fresh(n: Int): String = f"$n%032x"
  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 30.seconds)

  /** The report's range formula: a report on day d counts toward a..b when
    * it is "never" or its previous visit was before a. */
  private def reach(scoped: Database, a: LocalDate, b: LocalDate): Long = {
    val (sa, sb) = (java.sql.Date.valueOf(a), java.sql.Date.valueOf(b))
    await(scoped.run(sql"""
      SELECT COALESCE(SUM(reports), 0) FROM campaign_reach_daily
      WHERE campaign_id = 'c1' AND day_bucket BETWEEN $sa AND $sb
        AND (days_since = 0 OR days_since > day_bucket - $sa)
    """.as[Long].head))
  }

  "SlickReachRepo against PostgreSQL" should {

    "count a report once, and refuse a repeated freshness value" in failAfter(limit) {
      withSchema("reach_dedupe") { (repo, scoped) =>
        await(repo.record("c1", "site-a", day1, ReachRepo.Never, fresh(1))) shouldBe true
        // Same storage replayed (cloned profile): same freshness, not counted.
        await(repo.record("c1", "site-a", day1, ReachRepo.Never, fresh(1))) shouldBe false
        await(repo.record("c1", "site-a", day1, ReachRepo.Never, fresh(2))) shouldBe true
        await(scoped.run(sql"SELECT reports FROM campaign_reach_daily".as[Long].head)) shouldBe 2L
      }
    }

    "answer unique browsers for any range of days" in failAfter(limit) {
      withSchema("reach_ranges") { (repo, scoped) =>
        // Browser A sees the ad on days 1, 2 and 5: reports never, 1, 3.
        await(repo.record("c1", "site-a", day1, ReachRepo.Never, fresh(10)))
        await(repo.record("c1", "site-a", day1.plusDays(1), 1, fresh(11)))
        await(repo.record("c1", "site-a", day1.plusDays(4), 3, fresh(12)))
        // Browser B sees it only on day 4, for the first time.
        await(repo.record("c1", "site-a", day1.plusDays(3), ReachRepo.Never, fresh(20)))

        reach(scoped, day1, day1.plusDays(6)) shouldBe 2L            // days 1–7: A and B
        reach(scoped, day1.plusDays(2), day1.plusDays(4)) shouldBe 2L // days 3–5: A (day 5) and B
        reach(scoped, day1.plusDays(1), day1.plusDays(1)) shouldBe 1L // day 2: A
        reach(scoped, day1.plusDays(2), day1.plusDays(2)) shouldBe 0L // day 3: nobody
        // New reach (first ever) sums over any range.
        await(scoped.run(sql"SELECT SUM(reports) FROM campaign_reach_daily WHERE days_since = 0".as[Long].head)) shouldBe 2L
      }
    }
  }
}
