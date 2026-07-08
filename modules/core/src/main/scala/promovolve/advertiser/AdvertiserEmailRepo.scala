package promovolve.advertiser

import slick.jdbc.PostgresProfile.api.*

import java.time.Instant
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** Repository for email → advertiser mapping */
trait AdvertiserEmailRepo {
  /** Find advertiser ID by email */
  def findByEmail(email: String): Future[Option[String]]

  /** Associate email with advertiser */
  def associate(email: String, advertiserId: String): Future[Unit]

  /** Remove email association */
  def dissociate(email: String): Future[Unit]

  /** Get all emails for an advertiser */
  def getEmails(advertiserId: String): Future[Set[String]]
}

/** PostgreSQL-backed implementation using Slick */
class SlickAdvertiserEmailRepo(db: slick.jdbc.JdbcBackend#Database)(using ec: ExecutionContext)
    extends AdvertiserEmailRepo {

  import promovolve.SlickMappers.given

  private val emails = TableQuery[AdvertiserEmailTable]

  /** Create table if not exists */
  def ensureSchema(): Unit = {
    val createTableSql = sql"""
      CREATE TABLE IF NOT EXISTS advertiser_email (
        email VARCHAR(320) PRIMARY KEY,
        advertiser_id VARCHAR(32) NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT NOW()
      )
    """.asUpdate

    val createIndexSql = sql"""
      CREATE INDEX IF NOT EXISTS idx_advertiser_email_advertiser ON advertiser_email (advertiser_id)
    """.asUpdate

    Await.result(db.run(createTableSql >> createIndexSql), 10.seconds)
  }

  override def findByEmail(email: String): Future[Option[String]] = {
    val normalizedEmail = email.toLowerCase.trim
    val query = emails.filter(_.email === normalizedEmail).map(_.advertiserId).result.headOption
    db.run(query)
  }

  override def associate(email: String, advertiserId: String): Future[Unit] = {
    val normalizedEmail = email.toLowerCase.trim
    val row = AdvertiserEmailRow(normalizedEmail, advertiserId, Instant.now())
    val upsert = emails.insertOrUpdate(row)
    db.run(upsert).map(_ => ())
  }

  override def dissociate(email: String): Future[Unit] = {
    val normalizedEmail = email.toLowerCase.trim
    val delete = emails.filter(_.email === normalizedEmail).delete
    db.run(delete).map(_ => ())
  }

  override def getEmails(advertiserId: String): Future[Set[String]] = {
    val query = emails.filter(_.advertiserId === advertiserId).map(_.email).result
    db.run(query).map(_.toSet)
  }

  // Row type
  private case class AdvertiserEmailRow(
      email: String,
      advertiserId: String,
      createdAt: Instant
  )

  // Table definition
  private class AdvertiserEmailTable(tag: Tag) extends Table[AdvertiserEmailRow](tag, "advertiser_email") {
    def email        = column[String]("email", O.PrimaryKey)
    def advertiserId = column[String]("advertiser_id")
    def createdAt    = column[Instant]("created_at")

    // Index for looking up all emails for an advertiser
    def advertiserIdx = index("idx_advertiser_email_advertiser", advertiserId)

    def * = (email, advertiserId, createdAt).mapTo[AdvertiserEmailRow]
  }
}
