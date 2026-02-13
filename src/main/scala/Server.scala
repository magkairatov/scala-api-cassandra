package com.github.magkairatov

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorSystem, Behavior, SpawnProtocol}
import akka.cluster.ClusterEvent
import akka.cluster.ClusterEvent._
import akka.cluster.typed._
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.stream.Materializer
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import akka.util.ByteString
import com.typesafe.config.{Config, ConfigFactory}
import io.netty.util.HashedWheelTimer
import kz.altyn.bi.msv005.Entry.RootNode.Start_Root
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.{Failure, Success}
import oracle.jdbc.pool.OracleDataSource

object Entry extends App with Routes {
  implicit val config: Config = ConfigFactory.load()
  val clusterName = config.getString("application.cluster-name")

  val localhost: InetAddress = InetAddress.getLocalHost
  lazy val nodeId = Option(localhost.getHostName) filterNot (_.isEmpty) getOrElse localhost.getHostAddress
  lazy val cores = Runtime.getRuntime.availableProcessors

  implicit val log = LoggerFactory.getLogger("app")

  log.info(s"Starting '${config.getString("application.name")}', version ${config.getString("application.version")}, node ${nodeId}, ip ${localhost.getHostAddress}, cpu cores ${cores}")

  var hostsList = List("localhost")
  if (System.getProperty("http.nonProxyHosts") != null && System.getProperty("http.nonProxyHosts").nonEmpty) {
    hostsList = hostsList ++ System.getProperty("http.nonProxyHosts").split('|')
  }
  if (config.getString("proxy.non_proxy_hosts") != null && config.getString("proxy.non_proxy_hosts").nonEmpty) {
    hostsList = hostsList ++ config.getString("proxy.non_proxy_hosts").split(',')
  }
  System.setProperty("http.nonProxyHosts",hostsList.distinct.mkString("|"))

  val roles = config.getString("application.node.roles").split(',').map(_.trim)
  for (r <- roles) log.info(s"${nodeId} node roles: ${r}")

  //val classicSystem = akka.actor.ActorSystem("system")
  //val system = classicSystem.toTyped

  implicit val system = ActorSystem(RootNode(), clusterName, config)
  implicit val mat = Materializer(system)
  implicit val cs = CassandraSessionRegistry.get(system).sessionFor(CassandraSessionSettings())
  implicit val timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS)

  if (config.getString("akka.discovery.method") == "kubernetes-api") {
    log.info(s"${nodeId}: starting in Kubernetes cluster mode")
    AkkaManagement(system).start()
    ClusterBootstrap(system).start()
  } else {
    log.info(s"${nodeId}: starting in local docker containers")
    log.info(s"${nodeId}: starting in local docker containers")
  }

  lazy val periods: PeriodsN = new PeriodsN(15.minutes)
  lazy val rand = new scala.util.Random
  case class KSQLContactPoint(ip: String, port: Int, host: String)
  lazy val ksqldbContactPoints =
    for (x <- config.getString("ksqldb.contact-points").split(',').map(_.split(':')).toSeq)
      yield KSQLContactPoint(InetAddress.getByName(x(0)).getHostAddress, Integer.parseInt(x(1)), x(0)+':'+x(1))

  def ksqldbContactPoint = ksqldbContactPoints(rand.nextInt(ksqldbContactPoints.size))

  val ods = new OracleDataSource()
  ods.setUser(config.getString("colvir-oracledb.user"))
  ods.setURL(config.getString("colvir-oracledb.tns"))
  ods.setPassword(config.getString("colvir-oracledb.pass"))
  val tns = config.getString("colvir-oracledb.tns")
  log.info(s"Colvir connection: ${tns}")

  val address = config.getString("http.ip")

  log.info(s"Node '$nodeId' is up and running")

  system ! Start_Root

  Await.result(system.whenTerminated, Duration.Inf)

  object RootNode {
    sealed trait RootMsg extends JsonSerializable

    final case object Start_Root extends RootMsg

    def apply(): Behavior[RootMsg] = {
      Behaviors.setup { ctx =>
        Behaviors.receiveMessage {
          case Start_Root =>
            start(ctx)
            Behaviors.same
          case _ => Behaviors.unhandled
        }
      }
    }

    def start(context: ActorContext[RootMsg]) {
      context.spawn(ClusterListener(), "cluster-listener")

      context.spawn(Periods(), "stream-periods")
      context.spawn(CbsTransactions(), "stream-cbstrns")
      context.spawn(Ps24Transactions(), "stream-ps24trns")
      context.spawn(EvPaydoc(), "stream-evpaydoc")
      context.spawn(BpLoansFailed(), "stream-bploansfailed")
      context.spawn(AccBalances(), "stream-accbals")
      context.spawn(AccEndOfDayBalances(), "stream-acceodbals")
      context.spawn(Reestr(), "stream-paydocregistry")
      context.spawn(CountryDetection(), "stream-countrydetection")
      context.spawn(CountryTransactionns(), "stream-countrytransactionns")


      if (roles.contains("stream_subscriber")) {
        log.info(s"Node '$nodeId' act as stream_subscriber")
        context.spawn(Stream_Subscriber(), "stream-subscriber")
      }

      if (roles.contains("websocket_api")) {
        log.info(s"Node '$nodeId' acts as websocket api")
        val wsMgr = context.spawn(WsMgr(), "ws-mgr")
        val wsSessionParent = context.spawn(SpawnProtocol(), "ws-sessions")

        val route = extractClientIP { ip =>
          path("ws") {
            get {
              val remoteIp = ip.toOption.map(_.getHostAddress).getOrElse("unknown")
              handleWebSocketMessages(WsFlow.newSessionFlow(system, wsMgr, wsSessionParent, remoteIp))
            }
          }
        }

        val pingCounter = new AtomicInteger()

        import context.executionContext

        Http().newServerAt("0.0.0.0", config.getInt("http.ws_port"))
          .adaptSettings(_.mapWebsocketSettings(_.withPeriodicKeepAliveData(() => ByteString(s"ws-keep-alive-ping-${pingCounter.incrementAndGet()}"))))
          .bind(route).onComplete {
          case Success(binding) =>
            log.info(s"Node '$nodeId' websocket api is available at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/ws")
          case Failure(ex) =>
            ex.printStackTrace()
            log.info("HTTP web-socket server failed to start, terminating ...")
            context.system.terminate()
        }
      }

      if (roles.contains("rest_api")) {
        import context.executionContext
        lazy val routes = healthRoute ~ bpLoans
        Http().newServerAt("0.0.0.0", config.getInt("http.port")).bind(routes).onComplete {
          case Success(binding) =>
            log.info(s"Node '$nodeId' REST API is available at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/api/[...]")
          case Failure(ex) =>
            ex.printStackTrace()
            log.info("HTTP web-api server failed to start, terminating ...")
            context.system.terminate()
        }
      }

      Behaviors.same
    }
  }

  private object ClusterListener {
    val cluster_log = LoggerFactory.getLogger("cluster")

    def apply(): Behavior[ClusterEvent.ClusterDomainEvent] = {

      Behaviors.setup { ctx =>

        val cluster = Cluster(ctx.system)

        cluster_log.debug("starting up cluster listener...")
        cluster.subscriptions ! Subscribe(ctx.self, classOf[ClusterEvent.ClusterDomainEvent])

        Behaviors.receiveMessagePartial {
          case MemberUp(member) =>
            cluster_log.debug("Member is Up: {}", member.address)
            Behaviors.same
          case UnreachableMember(member) =>
            cluster_log.debug("Member detected as unreachable: {}", member)
            Behaviors.same
          case MemberRemoved(member, previousStatus) =>
            cluster_log.debug("Member is Removed: {} after {}",
              member.address, previousStatus)
            Behaviors.same
          case LeaderChanged(member) =>
            cluster_log.info("Leader changed: " + member)
            Behaviors.same
          case any: MemberEvent =>
            cluster_log.info("Member Event: " + any.toString)
            Behaviors.same
        }
      }
    }
  }
}
