package tables

import models.OutboxEvent
import slick.jdbc.MySQLProfile.api._

class OutboxEventTable(tag: Tag) extends Table[OutboxEvent](tag, "outbox_events") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def aggregateId = column[Long]("aggregate_id")
  def eventType = column[String]("event_type")
  def payload = column[String]("payload")
  def published = column[Boolean]("published")

  def * = (id.?, aggregateId, eventType, payload, published) <> (OutboxEvent.tupled, OutboxEvent.unapply)
}
