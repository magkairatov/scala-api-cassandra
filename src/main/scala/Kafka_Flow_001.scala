package com.github.magkairatov

import akka.actor.typed.ActorSystem
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.stream.ActorAttributes
import akka.stream.scaladsl.Flow
import com.sksamuel.avro4s.RecordFormat
import com.typesafe.config.Config
import kz.altyn.bi.msv005.Entry.RootNode.RootMsg
import org.apache.avro.generic.GenericRecord
import org.slf4j.Logger

import scala.concurrent.duration._
import scala.util.Try

class Kafka_Flow_001(implicit log: Logger,
                     system: ActorSystem[RootMsg],
                     config: Config
                    ) extends Kafka_Flow_000 {

  case class ccPeriod(SD: Option[String], SDTS: Option[Long], ED: Option[String], EDTS: Option[Long])
  private val avroSchema = RecordFormat[ccPeriod]

  def parse(msg: CommittableMessage[String,AnyRef]) = Try {
      val record = avroSchema.from(msg.record.value.asInstanceOf[GenericRecord])
      Period(msg.record.key, record.SD.get, record.ED.get)
    }.toOption

  private def flow =
    Flow[CommittableMessage[String,AnyRef]]
      .map(parse)
      .map {
        case Some(p) => p
      }
      .groupedWithin(256, 1.second)
      .map(Periods.publish)

  override def run() = {
    source("dwh_periods")
      .via(flow)
      .withAttributes(ActorAttributes.supervisionStrategy(globalSupervisorException))
      .run()
  }
}
