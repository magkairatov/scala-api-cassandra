package com.github.magkairatov

import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import org.slf4j.LoggerFactory

object Stream_Subscriber {
  val log = LoggerFactory.getLogger("Stream_Subscriber")
  val Key: ServiceKey[Request] = ServiceKey("Stream_Subscriber")

  sealed trait Request extends JsonSerializable
  final case class SubscribeToAccTrunover(iban: String, replyTo: ActorRef[Response]) extends Request {
    require(iban.nonEmpty)
  }

  sealed trait Response extends JsonSerializable
  final case class JobResult(meanWordLength: Double) extends Response

  import kz.altyn.bi.msv005.Entry.nodeId
  log.info(s"${nodeId}: Stream subscriber started")

  def apply(): Behavior[Request] = {

    Behaviors.setup { ctx =>
      ctx.system.receptionist ! Receptionist.Register(Key, ctx.self)

      Behaviors.receiveMessage {
        case SubscribeToAccTrunover(iban, replyTo) =>
          log.info("Delegating request")
          //ctx.spawnAnonymous(StatsAggregator(words, workers, replyTo))
          Behaviors.same
      }
    }
  }
}
