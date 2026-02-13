package com.github.magkairatov

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.Behaviors

object WsMgr {
  sealed trait Evt extends JsonSerializable
  case class Join(user: ActorRef[WsMessage]) extends Evt
  case class WsMessage(message: String) extends Evt

  def apply(): Behavior[Evt] = run(Set.empty)

  private def run(sessions: Set[ActorRef[WsMessage]]): Behavior[Evt] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage[Evt] {
        case Join(session) =>
          // отлавливаем события завершения сессии
          context.watch(session)
          run(sessions + session)
        case msg: WsMessage =>
          sessions.foreach(_ ! msg)
          Behaviors.same
      }.receiveSignal {
        case (__, Terminated(session)) =>
          run(sessions.filterNot(_ == session))
      }
    }
}