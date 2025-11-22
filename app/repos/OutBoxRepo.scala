package repos

import javax.inject._
import slick.jdbc.JdbcProfile
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import models.OutboxEvent
import tables.OutboxEventTable
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for managing outbox events used in the Outbox Pattern.
 *
 * This repo is responsible for:
 *  - Writing new outbox records inside a DB transaction (DBIO),
 *  - Fetching unpublished events so they can be pushed to Kafka,
 *  - Marking events as published after successful Kafka delivery.
 */
@Singleton
class OutboxRepo @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)
                          (implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._

  private val outbox = TableQuery[OutboxEventTable]

  /**
   * Inserts a new Outbox event.
   *
   * NOTE: Returns DBIO so this can be executed as part of a larger
   * database transaction (e.g., inside visitor check-in).
   */
  def insert(event: OutboxEvent): DBIO[Long] =
    (outbox returning outbox.map(_.id)) += event

  /**
   * Fetches unpublished events from the database.
   *
   * Returns a Future because this method is executed OUTSIDE a DB transaction,
   * typically used by OutboxPoller which runs in background.
   */
  def fetchUnpublished(limit: Int): Future[Seq[(Long, Long, String, String)]] =
    db.run(outbox.filter(_.published === false)
      .sortBy(_.id).take(limit)
      .map(r => (r.id, r.aggregateId, r.eventType, r.payload))
      .result)

  /**
   * Marks an outbox event as published.
   *
   * Also returns a Future because it's run by the Outbox Poller in the background,
   * not inside the visitor check-in transaction.
   */
  def markPublished(id: Long): Future[Int] =
    db.run(outbox.filter(_.id === id).map(_.published).update(true))
}
