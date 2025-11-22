package outbox

import javax.inject._
import org.apache.pekko.actor.ActorSystem
import play.api.Configuration
import play.api.Logging
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import services.KafkaPublisher

/**
 * Periodically reads unpublished outbox events and publishes them to Kafka.
 * After publishing, marks the events as SENT to avoid reprocessing.
 */
@Singleton
class OutboxPoller @Inject()(
                              actorSystem: ActorSystem,
                              repo: repos.OutboxRepo,
                              kafka: KafkaPublisher,
                              config: Configuration
                            )(implicit ec: ExecutionContext)
  extends Logging {
  /**
   * Poll interval (seconds)
   * Max rows to fetch per polling cycle
   * Kafka topics for routing events
    */
  private val interval = config.getOptional[Long]("outbox.poll.interval.seconds").getOrElse(5L)
  private val limit = config.getOptional[Int]("outbox.poll.limit").getOrElse(50)
  private val checkinTopic = config.get[String]("kafka.topic.checkin")
  private val checkoutTopic = config.get[String]("kafka.topic.checkout")

  logger.info(s"[OutboxPoller] Starting poller every $interval seconds")

  /**
   * Schedule background polling using Akka (Pekko) scheduler.
   * Starts after 3 seconds and runs periodically.
   */
  actorSystem.scheduler.scheduleWithFixedDelay(
    initialDelay = 3.seconds,
    delay = interval.seconds
  )(() => runPoll())(ec)

  /**
   * Reads unpublished events from DB, publishes each one to Kafka,
   * and updates the outbox table accordingly.
   *
   * Errors are logged but do not stop the scheduler.
   */
  private def runPoll(): Unit = {
    repo.fetchUnpublished(limit)
      .map { rows =>
        rows.foreach { case (id, aggId, eventType, payload) =>
          val topic = if (eventType == "visitor.checkin") checkinTopic else checkoutTopic

          kafka.publish(topic, aggId.toString, payload).onComplete {
            case Success(_) =>
              repo.markPublished(id)
              logger.info(s"[Outbox] Published id=$id to topic=$topic")

            case Failure(ex) =>
              logger.error(s"[Outbox] Failed id=$id due to: ${ex.getMessage}")
          }
        }
      }
      .recover { case ex =>
        logger.error(s"[Outbox] Poll failed: ${ex.getMessage}")
      }
  }
}
