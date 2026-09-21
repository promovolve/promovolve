package promovolve.dbit

import java.nio.file.{ Files, Path, Paths }

import org.scalatest.concurrent.TimeLimits
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Minutes, Span }
import org.scalatest.wordspec.AnyWordSpec

/**
 * docker/init-db.sql is what creates the tracking journal on a fresh
 * deployment — hypertable, retention policy and compression policy included.
 * It is applied by hand or by the dev compose file, so nothing else notices
 * when an edit leaves it invalid until the next empty database.
 */
class InitDbSqlSpec extends AnyWordSpec with Matchers with TimeLimits {

  /** Walk up to the repo root: sbt's working directory is the subproject. */
  private def repoFile(rel: String): Path = {
    def find(dir: Path): Path = {
      if (dir == null) fail(s"$rel not found above ${Paths.get("").toAbsolutePath}")
      val candidate = dir.resolve(rel)
      if (Files.isRegularFile(candidate)) candidate else find(dir.getParent)
    }
    find(Paths.get("").toAbsolutePath)
  }

  "docker/init-db.sql" should {

    "apply cleanly to an empty TimescaleDB database" in failAfter(Span(5, Minutes)) {
      val sql = Files.readString(repoFile("docker/init-db.sql"))

      PostgresTestContainer.withFreshDatabase("initdb_smoke") { url =>
        val conn = java.sql.DriverManager.getConnection(
          url,
          PostgresTestContainer.username,
          PostgresTestContainer.password
        )
        try {
          val st = conn.createStatement()
          try {
            // One execute for the whole file: the statements depend on each
            // other, and splitting on ';' would break the $$-quoted bodies.
            st.execute(sql)

            // The extension, hypertable and policies are the parts plain
            // Postgres would have silently refused.
            val hyper = st.executeQuery(
              "SELECT count(*) FROM timescaledb_information.hypertables " +
                "WHERE hypertable_name = 'tracking_events'"
            )
            hyper.next() shouldBe true
            withClue("tracking_events should be a hypertable: ") {
              hyper.getInt(1) shouldBe 1
            }

            val jobs = st.executeQuery(
              "SELECT count(*) FROM timescaledb_information.jobs " +
                "WHERE hypertable_name = 'tracking_events'"
            )
            jobs.next() shouldBe true
            withClue("retention + compression policies should be registered: ") {
              jobs.getInt(1) should be >= 2
            }

            // Re-applying is how an operator re-runs it against a live
            // database; every statement is guarded, so it must stay idempotent.
            st.execute(sql)
          } finally st.close()
        } finally conn.close()
      }
    }
  }
}
