package promovolve.dbit

import java.time.Instant
import scala.compiletime.uninitialized
import scala.concurrent.Await
import scala.concurrent.duration.*

import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.TimeLimits
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Minutes, Span }
import org.scalatest.wordspec.AnyWordSpec
import slick.jdbc.PostgresProfile.api.*

import promovolve.publisher.SiteTopQueries

/**
 * The publisher Overview's top pages and top ads against real PostgreSQL:
 * the window, the event rules shared with revenue-today, URL folding, the
 * ranking and the join to deleted creatives.
 */
class SiteTopQueriesDbSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with TimeLimits {

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

  private def await[A](a: DBIO[A], on: Database): A = Await.result(on.run(a), 30.seconds)

  private def withSchema[A](name: String)(body: Database => A): A = {
    await(sqlu"""DROP SCHEMA IF EXISTS #$name CASCADE""", db)
    await(sqlu"""CREATE SCHEMA #$name""", db)
    val base = PostgresTestContainer.jdbcUrl
    val sep = if (base.contains("?")) "&" else "?"
    val scoped = Database.forURL(
      url = s"$base${sep}currentSchema=$name",
      user = PostgresTestContainer.username,
      password = PostgresTestContainer.password,
      driver = "org.postgresql.Driver"
    )
    try {
      // The columns the queries read, as docker/init-db.sql declares them.
      await(sqlu"""
        CREATE TABLE tracking_events (
          event_type VARCHAR(20) NOT NULL, event_time TIMESTAMPTZ NOT NULL,
          site_id VARCHAR(100) NOT NULL, creative_id VARCHAR(100) NOT NULL,
          suspect_reason VARCHAR(20), cpm DECIMAL(10, 4),
          dogeared BOOLEAN NOT NULL DEFAULT FALSE, url TEXT)""", scoped)
      await(sqlu"""
        CREATE TABLE creative (creative_id VARCHAR(32) PRIMARY KEY, name VARCHAR(256),
          landing_domain VARCHAR(256), s3_key VARCHAR(512) NOT NULL)""", scoped)
      body(scoped)
    } finally {
      scoped.close()
      await(sqlu"""DROP SCHEMA IF EXISTS #$name CASCADE""", db)
    }
  }

  private val since = Instant.parse("2026-10-24T15:00:00Z")
  private val inWindow = "2026-10-30T03:00:00Z"

  private def ev(
      scoped: Database,
      kind: String,
      creative: String,
      url: Option[String],
      cpm: Double = 0,
      at: String = inWindow,
      site: String = "s1",
      dogeared: Boolean = false,
      suspect: Option[String] = None
  ): Unit =
    await(sqlu"""INSERT INTO tracking_events VALUES
      ($kind, $at::timestamptz, $site, $creative, $suspect, $cpm, $dogeared, $url)""", scoped)

  "SiteTopQueries.pages" should {

    "fold query and fragment, count only clean billed views, and rank by revenue" in failAfter(limit) {
      withSchema("top_pages") { s =>
        // /a: two billed views across query variants, one click.
        ev(s, "impression", "c1", Some("https://x.jp/a?utm=1"), cpm = 2000)
        ev(s, "impression", "c1", Some("https://x.jp/a#top"), cpm = 2000)
        ev(s, "click", "c1", Some("https://x.jp/a"))
        // Neither counted nor paid: a dogeared re-view, a suspect view,
        // another site's view, and one before the window.
        ev(s, "impression", "c1", Some("https://x.jp/a"), cpm = 9000, dogeared = true)
        ev(s, "impression", "c1", Some("https://x.jp/a"), cpm = 9000, suspect = Some("bot_ua"))
        ev(s, "impression", "c1", Some("https://x.jp/a"), cpm = 9000, site = "s2")
        ev(s, "impression", "c1", Some("https://x.jp/a"), cpm = 9000, at = "2026-10-24T14:59:59Z")
        // /b: more views but less revenue; ranks below /a.
        (1 to 3).foreach(_ => ev(s, "impression", "c1", Some("https://x.jp/b"), cpm = 1000))
        // No URL recorded.
        ev(s, "impression", "c1", None, cpm = 500)
        // Four more pages to push past the limit of 5.
        (1 to 4).foreach(i => ev(s, "impression", "c1", Some(s"https://x.jp/z$i"), cpm = 100))

        val rows = await(SiteTopQueries.pages("s1", since), s)
        rows.map(_.page).take(3) shouldBe Vector("https://x.jp/a", "https://x.jp/b", "")
        rows should have size 5
        rows.head.impressions shouldBe 2L
        rows.head.clicks shouldBe 1L
        rows.head.grossRevenue shouldBe 4.0 +- 1e-9 // 2 × $2000 CPM / 1000
        rows(1).impressions shouldBe 3L
        rows(1).grossRevenue shouldBe 3.0 +- 1e-9
      }
    }
  }

  "SiteTopQueries.creatives" should {

    "rank creatives and keep a deleted one with blank details" in failAfter(limit) {
      withSchema("top_creatives") { s =>
        await(sqlu"""INSERT INTO creative VALUES ('c1', 'Autumn', 'ryokan.jp', 'assets/a.png')""", s)
        ev(s, "impression", "c1", Some("https://x.jp/a"), cpm = 1000)
        ev(s, "click", "c1", Some("https://x.jp/a"))
        (1 to 2).foreach(_ => ev(s, "impression", "gone", Some("https://x.jp/a"), cpm = 2000))
        ev(s, "impression", "c1", Some("https://x.jp/a"), cpm = 9000, dogeared = true)

        val rows = await(SiteTopQueries.creatives("s1", since), s)
        rows.map(_.creativeId) shouldBe Vector("gone", "c1")
        rows.head.name shouldBe ""
        rows.head.s3Key shouldBe ""
        rows.head.grossRevenue shouldBe 4.0 +- 1e-9
        rows(1).name shouldBe "Autumn"
        rows(1).landingDomain shouldBe "ryokan.jp"
        rows(1).s3Key shouldBe "assets/a.png"
        rows(1).impressions shouldBe 1L
        rows(1).clicks shouldBe 1L
      }
    }
  }
}
