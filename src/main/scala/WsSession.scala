package com.github.magkairatov

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.Materializer
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats, _}
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object WsSession {
  trait WsSessionMessage extends JsonSerializable
  case class Connected(outgoing: ActorRef[OutgoingMessage]) extends WsSessionMessage
  case object Disconnected extends WsSessionMessage
  case class IncomingMessage(text: String) extends WsSessionMessage
  case class OutgoingMessage(text: String) extends WsSessionMessage
  case class Response(t: String, data: AnyRef)

  implicit val formats: Formats = DefaultFormats
  implicit val log = LoggerFactory.getLogger("app")

  def apply(wsMgr: ActorRef[WsMgr.Evt], remoteIp: String): Behavior[WsSessionMessage] = {
    waitingForConnection(wsMgr, remoteIp)
  }

  private def waitingForConnection(wsMgr: ActorRef[WsMgr.Evt], remoteIp: String): Behavior[WsSessionMessage] =
    Behaviors.setup { implicit context =>
      log.info(s"Accepted new websocket connection. Actor: ${context.self}, remote ip: ${remoteIp}")
      Behaviors.receiveMessagePartial {
        case Connected(outgoing) =>
          wsMgr ! WsMgr.Join(context.messageAdapter {
            case WsMgr.WsMessage(text) => OutgoingMessage(text)
          })
          connected(wsMgr, outgoing)
      }
    }

  private def connected(wsMgr: ActorRef[WsMgr.Evt], outgoing: ActorRef[OutgoingMessage])(implicit context: ActorContext[WsSessionMessage]): Behavior[WsSessionMessage] =
    Behaviors.receiveMessagePartial {
      case IncomingMessage(text) =>
        inc_message(text, outgoing)
        //Чтоб разослать информацию всем сессиям можно через менеджера
        //wsMgr ! WsMgr.WsMessage(text)
        Behaviors.same
      case msg: Period =>
        val json = write(Response(Periods.key,Array(msg)))
        outgoing ! OutgoingMessage(json)
        Behaviors.same
      case msg: OutgoingMessage =>
        outgoing ! msg
        Behaviors.same
      case Disconnected =>
        unsubscribeall
        log.info(s"Websocket disconnected. Actor has stopped ${context.self}")
        Behaviors.stopped
    }

  val subscriptions = new ListBuffer[String]()

  private def inc_message(text: String, outgoing: ActorRef[OutgoingMessage])(implicit context: ActorContext[WsSessionMessage]) = {
    Try(parse(text)).toOption match {
      case Some(j) =>
        j \ "t" match {
          case JString(t) =>

            t match {
              case "subscribe" => subscribe(j, outgoing)
              case "unsubscribe" => unsubscribe(j)
              case _ =>
            }
          case _ =>
        }
    }
  }

  private def respond(channel: String, outgoing: ActorRef[OutgoingMessage])(implicit ec: ExecutionContext, mat: Materializer, fut: Future[Option[AnyRef]]) = {
    fut.onComplete {
      case Success(res) =>
        if (res.isDefined) {
          Future {
            Thread.sleep(100)
            val json = write(Response(channel, res.get))
            outgoing ! OutgoingMessage(json)
          }
        }
      case Failure(exception) =>
        log.error(s"ERROR: ${this} ${exception.getCause}")
    }
  }

  private def respond2(channel: String, outgoing: ActorRef[OutgoingMessage])(implicit ec: ExecutionContext, mat: Materializer, fut: Future[Option[Iterable[AnyRef]]]) = {
    fut.onComplete {
      case Success(res) =>
        if (res.isDefined) {
          Future {
            Thread.sleep(100)
            for (v <- res.get) {
              val json = write(Response(channel, v))
              outgoing ! OutgoingMessage(json)
            }
          }
        }
      case Failure(exception) =>
        log.error(s"ERROR: ${this} ${exception.getCause}")
    }
  }

  //{"t":"subscribe","channel":"loans.periods"}
  private def subscribe(json: JValue, outgoing: ActorRef[OutgoingMessage])(implicit context: ActorContext[WsSessionMessage]) = {
    implicit val ec = context.system.executionContext
    implicit val mat = Materializer(context.system)

    json \ "channel" match {
      case JString(ch) =>
        ch match {
          case Periods.key =>
            subscriptions += ch
            Periods.topic ! Topic.Subscribe(context.self)
            implicit val fut = Future {Periods.get}
            respond(ch, outgoing)

          case _ =>
        }
        log.info(s"Websocket ${context.self} subscribed for '${ch}'")
      case _ =>
    }
  }

  //{"t":"unsubscribe","channel":"loans.periods"}
  private def unsubscribe(json: JValue)(implicit context: ActorContext[WsSessionMessage]) = {
    json \ "channel" match {
      case JString(ch) =>
        ch match {
          case Periods.key =>
            subscriptions -= ch
            Periods.topic ! Topic.Unsubscribe(context.self)
          case _ =>
        }
        log.info(s"Websocket ${context.self} unsubscribed from '${ch}''")
      case _ =>
    }
  }

  private def unsubscribeall(implicit context: ActorContext[WsSessionMessage]) = {
    subscriptions.foreach {
      case Periods.key =>
        Periods.topic ! Topic.Unsubscribe(context.self)
      case _ =>
    }
    subscriptions.clear()
  }

  final case class CardAccTrn(
                               id: Option[String],
                               cc: Option[String],
                               acc: Option[String],
                               card: Option[String],
                               tdt: Option[Long],
                               pdt: Option[Int],
                               tam: Option[Double],
                               tcur: Option[String],
                               rrn: Option[String],
                               tdtl: Option[String],
                               tcount: Option[String],
                               tcity: Option[String],
                               term: Option[String],
                               tt: Option[String],
                               mmc: Option[String],
                               mmcd: Option[String],
                               mmcdru: Option[String],
                               stamp: Option[Long]
                             ) extends WsSessionMessage

  final case class AccBal (acc: String, bal: Double, cur: String, stamp: Long, source: Option[String])
    extends WsSessionMessage

}

