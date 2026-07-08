package promovolve.api.projection

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.ShardedDaemonProcessSettings
import org.apache.pekko.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import org.apache.pekko.persistence.query.Offset
import org.apache.pekko.persistence.query.{Sequence => PekkoSequence}
import org.apache.pekko.projection.{ProjectionBehavior, ProjectionId}
import org.apache.pekko.projection.scaladsl.SourceProvider
import org.apache.pekko.projection.slick.SlickProjection
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import scala.concurrent.{ExecutionContext, Future}

/** Dashboard projection setup using ShardedDaemonProcess for distribution. */
object DashboardProjection {

  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  /** Initialize the dashboard projection across the cluster.
    *
    * Uses ShardedDaemonProcess to distribute projection processing
    * across cluster nodes for scalability and fault tolerance.
    */
  def init(
      system: ActorSystem[?],
      dbConfig: DatabaseConfig[PostgresProfile]
  ): Unit = {
    given ActorSystem[?] = system
    given ExecutionContext = system.executionContext

    log.info("Initializing DashboardProjection with ShardedDaemonProcess")

    // Single projection instance (can be partitioned later if needed)
    val numberOfPartitions = 1

    ShardedDaemonProcess(system).init(
      name = "DashboardProjection",
      numberOfInstances = numberOfPartitions,
      behaviorFactory = { partition =>
        val projectionId = ProjectionId("dashboard", s"partition-$partition")
        val sourceProvider = createSourceProvider(dbConfig, partition, numberOfPartitions)
        val projection = SlickProjection.exactlyOnce(
          projectionId = projectionId,
          sourceProvider = sourceProvider,
          databaseConfig = dbConfig,
          handler = () => new DashboardProjectionHandler
        )
        ProjectionBehavior(projection)
      },
      settings = ShardedDaemonProcessSettings(system),
      stopMessage = Some(ProjectionBehavior.Stop)
    )
  }

  /** Creates a source provider that reads tracking events from the journal table.
    *
    * Uses a custom SourceProvider that queries the tracking_events table
    * using sequence numbers as offsets.
    */
  private def createSourceProvider(
      dbConfig: DatabaseConfig[PostgresProfile],
      partition: Int,
      numberOfPartitions: Int
  )(using ec: ExecutionContext): SourceProvider[Offset, TrackingEvent] = {
    new TrackingEventSourceProvider(dbConfig, partition, numberOfPartitions)
  }
}

/** Custom SourceProvider that reads from tracking_events table.
  *
  * Uses sequence_nr as offset for exactly-once processing.
  */
class TrackingEventSourceProvider(
    dbConfig: DatabaseConfig[PostgresProfile],
    partition: Int,
    numberOfPartitions: Int
)(using ec: ExecutionContext)
    extends SourceProvider[Offset, TrackingEvent] {

  import dbConfig.profile.api.*
  import TrackingEventJournal.*

  private val log = org.slf4j.LoggerFactory.getLogger(getClass)
  private val pollInterval = java.time.Duration.ofMillis(500)
  private val batchSize = 100

  override def source(offset: () => Future[Option[Offset]]): Future[Source[TrackingEvent, NotUsed]] = {
    offset().map { maybeOffset =>
      val startOffset = maybeOffset match {
        case Some(seq: PekkoSequence) => seq.value
        case _                        => 0L
      }

      log.info("Starting TrackingEvent source from offset {} for partition {}/{}",
        startOffset, partition, numberOfPartitions)

      // Polling source that reads events in batches
      Source
        .unfoldAsync[Long, Seq[TrackingEvent]](startOffset) { fromSeq =>
          // Single partition - read all events in order
          val query = trackingEvents
            .filter(_.sequenceNr > fromSeq)
            .sortBy(_.sequenceNr)
            .take(batchSize)
            .result

          dbConfig.db.run(query).map { events =>
            if (events.nonEmpty) {
              val nextOffset = events.last.sequenceNr
              Some((nextOffset, events))
            } else {
              // No events, wait and try again
              Thread.sleep(pollInterval.toMillis)
              Some((fromSeq, Seq.empty))
            }
          }
        }
        .mapConcat(identity)
        .mapMaterializedValue(_ => NotUsed)
    }
  }

  override def extractOffset(event: TrackingEvent): Offset =
    PekkoSequence(event.sequenceNr)

  override def extractCreationTime(event: TrackingEvent): Long =
    event.eventTime.toEpochMilli
}