package com.github.magkairatov

import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.RestartSettings
import akka.stream.scaladsl.RestartSource

import scala.concurrent.duration._

trait Kafka_Flow extends Kafka_Source_Flow_Supervision {
  def run(): Unit

  val backoffSettings = RestartSettings(
    minBackoff = 1.minute,
    maxBackoff = 15.minutes,
    randomFactor = 0.2
  ).withMaxRestarts(20, 1.hour)

  def source(topic: String)(implicit kafkaConsumerAvroDeSettings: ConsumerSettings[String,AnyRef]) =
    RestartSource.withBackoff(backoffSettings) { () =>
      Consumer.committableSource(kafkaConsumerAvroDeSettings, Subscriptions.topics(topic))
    }
}
