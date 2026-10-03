package promovolve.dbit

import java.sql.Connection
import java.time.Duration

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * One TimescaleDB container for the whole `dbIt/test` run, started on first
 * use and stopped when the forked JVM exits.
 *
 * TimescaleDB rather than plain Postgres because docker/init-db.sql creates
 * the extension, a hypertable, a retention policy and a compression policy —
 * none of which exist on stock Postgres. The tag is the one the cluster runs
 * (k8s/db-statefulset.yaml), so the Postgres major matches production too.
 *
 * Nothing here skips on a missing Docker daemon: a container that will not
 * start fails the suite, which is the whole point of the job.
 */
object PostgresTestContainer {

  private val image = "timescale/timescaledb:2.17.2-pg15"

  /**
   * PostgreSQLContainer is F-bounded on itself; instantiating it directly
   * infers SELF = Nothing and every `withX` call stops type-checking, so name
   * the self type once here.
   */
  private final class TimescaleContainer(name: DockerImageName)
      extends PostgreSQLContainer[TimescaleContainer](name)

  /**
   * Escape hatch for a machine whose Docker socket Testcontainers cannot see
   * (Docker Desktop on macOS serves an API docker-java rejects), and for
   * re-runs against a server that is already warm. Must point at a TimescaleDB
   * server, since init-db.sql needs the extension:
   *
   * {{{
   * DB_IT_DATABASE_URL='jdbc:postgresql://localhost:5433/postgres' sbt dbIt/test
   * }}}
   *
   * Unset — the normal path, and what CI uses — a container is started.
   */
  private val externalUrl = sys.env.get("DB_IT_DATABASE_URL").filter(_.nonEmpty)

  lazy val container: PostgreSQLContainer[?] = {
    val c = new TimescaleContainer(
      DockerImageName.parse(image).asCompatibleSubstituteFor("postgres")
    )
      .withDatabaseName("promovolve_test")
      .withUsername("promovolve")
      .withPassword("promovolve")
      // initdb runs, the server stops, then starts again — waiting for a
      // single "ready" line races that restart and hands back a refused
      // connection.
      .withStartupTimeout(Duration.ofMinutes(3))
    c.setWaitStrategy(
      Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
        .withStartupTimeout(Duration.ofMinutes(3))
    )
    c.start()
    sys.addShutdownHook(c.stop())
    c
  }

  def jdbcUrl: String = externalUrl.getOrElse(container.getJdbcUrl)
  def username: String =
    sys.env.getOrElse("DB_IT_DATABASE_USER", externalUrl.fold(container.getUsername)(_ => "promovolve"))
  def password: String =
    sys.env.getOrElse("DB_IT_DATABASE_PASSWORD", externalUrl.fold(container.getPassword)(_ => "promovolve"))

  /** A fresh admin connection on the default database. Caller closes it. */
  def adminConnection(): Connection =
    java.sql.DriverManager.getConnection(jdbcUrl, username, password)

  /**
   * Runs `body` against a brand-new database on the shared server. A separate
   * DATABASE, not a schema, because the TimescaleDB extension and its
   * background jobs are per-database — init-db.sql has to land somewhere it
   * can create them cleanly.
   */
  def withFreshDatabase[A](name: String)(body: String => A): A = {
    val admin = adminConnection()
    try {
      val st = admin.createStatement()
      try {
        st.execute(s"""DROP DATABASE IF EXISTS "$name"""")
        st.execute(s"""CREATE DATABASE "$name"""")
      } finally st.close()
    } finally admin.close()

    val url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", s"/$name$$1")
    try body(url)
    finally {
      val admin2 = adminConnection()
      try {
        val st = admin2.createStatement()
        try st.execute(s"""DROP DATABASE IF EXISTS "$name" WITH (FORCE)""")
        finally st.close()
      } finally admin2.close()
    }
  }
}
