package promovolve.publisher

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import slick.jdbc.PostgresProfile.api.*

/** Durable record of which campaigns hold demand in which category, so a
  * `CategoryBidderEntity` can SEED its in-memory demand on its own startup
  * instead of waiting for the `CampaignDirectory` singleton to re-push it after
  * a restart (the post-restart ad-dark-window).
  *
  * Written by `CampaignEntity` as its categories/status change (it owns its own
  * rows; PK is (category, campaign) so there's no cross-campaign contention).
  * Read by `CategoryBidderEntity` on startup, keyed by category. Filler demand
  * is stored under the filler category id, same as the live fan-out. */
trait CategoryDemandRepo {
  /** Replace this campaign's full category set: delete its existing rows, then
    * insert one row per current category. Empty `categoryIds` = remove all. */
  def upsertCampaign(categoryIds: Set[String], campaignId: String, advertiserId: String): Future[Unit]

  /** Remove all of a campaign's rows (paused / deleted / inactive). */
  def removeCampaign(campaignId: String): Future[Unit]

  /** (campaignId, advertiserId) for every campaign with demand in this category. */
  def listByCategory(categoryId: String): Future[Vector[(String, String)]]
}

/** PostgreSQL-backed implementation using Slick (raw SQL — the table is trivial). */
final class SlickCategoryDemandRepo(db: slick.jdbc.JdbcBackend#Database)(using ec: ExecutionContext)
    extends CategoryDemandRepo {

  def ensureSchema(): Unit = {
    val create = sqlu"""
      CREATE TABLE IF NOT EXISTS category_demand (
        category_id   VARCHAR(255) NOT NULL,
        campaign_id   VARCHAR(255) NOT NULL,
        advertiser_id VARCHAR(255) NOT NULL,
        updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
        PRIMARY KEY (category_id, campaign_id)
      )
    """
    val idx = sqlu"CREATE INDEX IF NOT EXISTS category_demand_category_idx ON category_demand (category_id)"
    Await.result(db.run(create >> idx), 10.seconds)
  }

  override def upsertCampaign(categoryIds: Set[String], campaignId: String, advertiserId: String): Future[Unit] = {
    val del = sqlu"DELETE FROM category_demand WHERE campaign_id = $campaignId"
    if (categoryIds.isEmpty) db.run(del).map(_ => ())
    else {
      val inserts = categoryIds.toVector.map { cat =>
        sqlu"""INSERT INTO category_demand (category_id, campaign_id, advertiser_id)
               VALUES ($cat, $campaignId, $advertiserId)
               ON CONFLICT (category_id, campaign_id)
               DO UPDATE SET advertiser_id = EXCLUDED.advertiser_id, updated_at = now()"""
      }
      db.run(DBIO.sequence(del +: inserts).transactionally).map(_ => ())
    }
  }

  override def removeCampaign(campaignId: String): Future[Unit] =
    db.run(sqlu"DELETE FROM category_demand WHERE campaign_id = $campaignId").map(_ => ())

  override def listByCategory(categoryId: String): Future[Vector[(String, String)]] =
    db.run(
      sql"SELECT campaign_id, advertiser_id FROM category_demand WHERE category_id = $categoryId"
        .as[(String, String)]
    ).map(_.toVector)
}
