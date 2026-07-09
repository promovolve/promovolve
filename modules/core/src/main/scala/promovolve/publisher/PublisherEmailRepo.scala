package promovolve.publisher

import slick.jdbc.PostgresProfile.api.*

import java.time.Instant
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.*

/** Repository for email → publisher mapping */
trait PublisherEmailRepo {

  /** Find publisher ID by email */
  def findByEmail(email: String): Future[Option[String]]

  /** Associate email with publisher */
  def associate(email: String, publisherId: String): Future[Unit]

  /** Remove email association */
  def dissociate(email: String): Future[Unit]

  /** Get all emails for a publisher */
  def getEmails(publisherId: String): Future[Set[String]]
}

/** PostgreSQL-backed implementation using Slick */
class SlickPublisherEmailRepo(db: slick.jdbc.JdbcBackend#Database)(using ec: ExecutionContext)
    extends PublisherEmailRepo {

  import promovolve.SlickMappers.given

  private val emails = TableQuery[PublisherEmailTable]

  /** Create table if not exists */
  def ensureSchema(): Unit = {
    val createTableSql = sql"""
      CREATE TABLE IF NOT EXISTS publisher_email (
        email VARCHAR(320) PRIMARY KEY,
        publisher_id VARCHAR(32) NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT NOW()
      )
    """.asUpdate

    val createIndexSql = sql"""
      CREATE INDEX IF NOT EXISTS idx_publisher_email_publisher ON publisher_email (publisher_id)
    """.asUpdate

    Await.result(db.run(createTableSql >> createIndexSql), 10.seconds)
  }

  override def findByEmail(email: String): Future[Option[String]] = {
    val normalizedEmail = email.toLowerCase.trim
    val query = emails.filter(_.email === normalizedEmail).map(_.publisherId).result.headOption
    db.run(query)
  }

  override def associate(email: String, publisherId: String): Future[Unit] = {
    val normalizedEmail = email.toLowerCase.trim
    val row = PublisherEmailRow(normalizedEmail, publisherId, Instant.now())
    val upsert = emails.insertOrUpdate(row)
    db.run(upsert).map(_ => ())
  }

  override def dissociate(email: String): Future[Unit] = {
    val normalizedEmail = email.toLowerCase.trim
    val delete = emails.filter(_.email === normalizedEmail).delete
    db.run(delete).map(_ => ())
  }

  override def getEmails(publisherId: String): Future[Set[String]] = {
    val query = emails.filter(_.publisherId === publisherId).map(_.email).result
    db.run(query).map(_.toSet)
  }

  // Row type
  private case class PublisherEmailRow(
      email: String,
      publisherId: String,
      createdAt: Instant
  )

  // Table definition
  private class PublisherEmailTable(tag: Tag) extends Table[PublisherEmailRow](tag, "publisher_email") {
    def email = column[String]("email", O.PrimaryKey)
    def publisherId = column[String]("publisher_id")
    def createdAt = column[Instant]("created_at")

    // Index for looking up all emails for a publisher
    def publisherIdx = index("idx_publisher_email_publisher", publisherId)

    def * = (email, publisherId, createdAt).mapTo[PublisherEmailRow]
  }
}
