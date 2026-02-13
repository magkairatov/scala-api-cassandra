package com.github.magkairatov

import akka.NotUsed
import akka.actor.typed.{ActorRef, ActorSystem, Props, SpawnProtocol}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._


object WsFlow {
  def newSessionFlow(system: ActorSystem[_],
                     wsMgr: ActorRef[WsMgr.Evt],
                     wsSessionParent: ActorRef[SpawnProtocol.Command],
                     remoteIp: String
                    ): Flow[Message, Message, Future[NotUsed]] = {
    implicit val ec = system.executionContext
    import akka.actor.typed.scaladsl.AskPattern._
    implicit val timeout: Timeout = 3.seconds
    implicit val scheduler = system.scheduler
    val futureUserActor: Future[ActorRef[WsSession.WsSessionMessage]] =
      wsSessionParent.ask(SpawnProtocol.Spawn(WsSession(wsMgr, remoteIp), "ws-session", Props.empty, _))

    Flow.futureFlow(futureUserActor.map { sessionActor =>
      val incomingMessages: Sink[Message, NotUsed] =
        Flow[Message]
          .map {
            case TextMessage.Strict(text) => WsSession.IncomingMessage(text)
          }
          .to(
            ActorSink.actorRef[WsSession.WsSessionMessage](
              sessionActor,
              WsSession.Disconnected,
              _ => WsSession.Disconnected
            )
          )

      val outgoingMessages: Source[Message, NotUsed] =
        ActorSource
          .actorRef[WsSession.OutgoingMessage](
            PartialFunction.empty,
            PartialFunction.empty,
            128,
            OverflowStrategy.fail
          )
          .mapMaterializedValue { outActor =>
            sessionActor ! WsSession.Connected(outActor)
            NotUsed
          }
          .map(
            (outMsg: WsSession.OutgoingMessage) => TextMessage(outMsg.text)
          )

      Flow.fromSinkAndSourceCoupled(incomingMessages, outgoingMessages)
    })

  }
}
