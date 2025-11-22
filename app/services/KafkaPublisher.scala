package services

import javax.inject._
import play.api.Configuration
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, RecordMetadata}
import java.util.Properties
import scala.concurrent.{Future, Promise, ExecutionContext}
import scala.util.control.NonFatal

/**
 * KafkaPublisher is responsible for producing messages to Kafka topics.
 *
 * It initializes a KafkaProducer using application configuration and exposes
 * a `publish` method to send messages asynchronously with proper error handling.
 *
 * This component is used by the Outbox Poller to publish events reliably.
 */
@Singleton
class KafkaPublisher @Inject()(config: Configuration)(implicit ec: ExecutionContext) {

  /** Kafka producer properties loaded from application.conf */
  private val props = new Properties()
  props.put("bootstrap.servers", config.get[String]("kafka.bootstrap.servers"))
  props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
  props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
  props.put("acks", "all")
  props.put("enable.idempotence", "true")
  props.put("retries", "3")

  private val producer = new KafkaProducer[String, String](props)

  /**
   * Publishes a message to a Kafka topic.
   *
   * @param topic Kafka topic to publish to.
   * @param key   Message key (helps Kafka partitioning).
   * @param value Message payload.
   * @return Future that completes with RecordMetadata on success,
   *         or fails if Kafka publishing encounters an error.
   */
  def publish(topic: String, key: String, value: String): Future[RecordMetadata] = {
    val p = Promise[RecordMetadata]()

    try {
      val record = new ProducerRecord[String, String](topic, key, value)
      producer.send(record, (metadata: RecordMetadata, ex: Exception) => {
        if (ex != null) p.failure(ex)
        else p.success(metadata)
      })
    } catch {
      case NonFatal(e) => p.failure(e)
    }

    p.future
  }

  /**
   * Closes the Kafka producer and releases underlying resources.
   * Should be called only during application shutdown.
   */
  def close(): Unit = producer.close()
}
