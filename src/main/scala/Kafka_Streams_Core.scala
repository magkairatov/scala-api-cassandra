package com.github.magkairatov

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.kafka.scaladsl.Producer
import akka.kafka.{ConsumerSettings, ProducerSettings}
import io.confluent.kafka.serializers.{AbstractKafkaSchemaSerDeConfig, KafkaAvroDeserializer, KafkaAvroSerializer}
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import com.typesafe.config.Config
import kz.altyn.bi.msv005.Entry.RootNode.RootMsg

import scala.jdk.CollectionConverters._

class Kafka_Streams_Core(implicit config: Config, system: ActorSystem[RootMsg]) {

  private lazy val (kafkaAvroSerializer,kafkaAvroDeserializer) = {
    val kafkaAvroSerDeConfig = Map[String, Any](
      AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG -> config.getString("schema-registry.url"),
      AbstractKafkaSchemaSerDeConfig.USE_LATEST_VERSION -> true.toString,
    )
    val avroSer = new KafkaAvroSerializer
    avroSer.configure(kafkaAvroSerDeConfig.asJava, false)
    val avroDe = new KafkaAvroDeserializer
    avroDe.configure(kafkaAvroSerDeConfig.asJava, false)
    (avroSer,avroDe)
  }

  private val kafkaSettingsAvroSerWithProducer = {
    val producerSettings = ProducerSettings(config.getConfig("akka.kafka.producer"), new StringSerializer, kafkaAvroSerializer)
    val kafkaProducer = producerSettings.createKafkaProducer()
    producerSettings.withProducer(kafkaProducer)
  }

  private lazy val kafkaConsumerAvroDeSettings = ConsumerSettings(system.toClassic, new StringDeserializer, kafkaAvroDeserializer)
    .withGroupId(config.getString("kafka.groupid"))

  private lazy val producer = Producer.plainSink(Kafka_Streams_Core.KafkaSettingsAvroSerWithProducer)

  private lazy val flexiProducer = Producer.flexiFlow(kafkaSettingsAvroSerWithProducer.asInstanceOf[ProducerSettings[String,com.sksamuel.avro4s.Record]])

}

object Kafka_Streams_Core {
  private var instance: Kafka_Streams_Core = null
  def KafkaSettingsAvroSerWithProducer(implicit config: Config, system: ActorSystem[RootMsg]) = {
    if (instance == null) {
      instance = new Kafka_Streams_Core()
    }
    instance.kafkaSettingsAvroSerWithProducer
  }
  def KafkaConsumerAvroDeSettings(implicit config: Config, system: ActorSystem[RootMsg]) = {
    if (instance == null) {
      instance = new Kafka_Streams_Core()
    }
    instance.kafkaConsumerAvroDeSettings
  }
  def Producer(implicit config: Config, system: ActorSystem[RootMsg]) = {
    if (instance == null) {
      instance = new Kafka_Streams_Core()
    }
    instance.producer
  }
  def FlowProducer(implicit config: Config, system: ActorSystem[RootMsg]) = {
    if (instance == null) {
      instance = new Kafka_Streams_Core()
    }
    instance.flexiProducer
  }
}
