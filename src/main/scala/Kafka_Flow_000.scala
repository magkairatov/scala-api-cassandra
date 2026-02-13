package com.github.magkairatov

import akka.actor.typed.ActorSystem
import com.typesafe.config.Config
import kz.altyn.bi.msv005.Entry.RootNode.RootMsg
import org.json4s.{DefaultFormats, Formats}

abstract class Kafka_Flow_000(implicit system: ActorSystem[RootMsg], config: Config) extends Kafka_Flow {
  implicit val formats: Formats = DefaultFormats
  implicit val ec = system.executionContext
  implicit val des = Kafka_Streams_Core.KafkaConsumerAvroDeSettings
  val producer = Kafka_Streams_Core.Producer
}
