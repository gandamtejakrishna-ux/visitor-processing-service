package models

case class OutboxEvent(
                        id: Option[Long],
                        aggregateId: Long,
                        eventType: String,
                        payload: String,
                        published: Boolean
                      )
