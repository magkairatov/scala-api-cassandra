package com.github.magkairatov

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.Materializer
import akka.stream.alpakka.cassandra.scaladsl.CassandraSource
import akka.stream.scaladsl.Sink
import com.datastax.oss.driver.api.core.cql.Row
import org.slf4j.Logger

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class AccBalances(expireinsecs: Long)(implicit mat: Materializer, log: Logger) extends RestApiFlows_Supervision with Expirable {

  private def parse_result(row: Row) = {
    AccBalance(
      row.getString("acc"),
      row.getDouble("bal"),
      row.getByte("capfl").toShort,
      Try(row.getString("cli")).toOption,
      row.getString("cur"),
      Try(row.getDouble("nat")).toOption,
      Try(row.getString("plan")).toOption,
      Try(row.getDouble("rate")).toOption,
      Try(row.getString("sp")).toOption,
      Try(dp.fmtI(row.getInstant("stamp"))).toOption,
      Try(row.getString("tax")).toOption
    )
  }

  //KZ149491100100000247
  private val cqlaccbal = s"select acc,bal,capfl,cli,cur,nat,plan,rate,sp,stamp,tax from raw_trn.acc_bal where acc=?"

  private def fetch = (acc: String) => {
    import Entry.cs
    val fut = CassandraSource(cqlaccbal, acc).runWith(Sink.collection)
    parseAccBalResult(fut)
  }

  private def parseAccBalResult(fut: Future[Iterable[Row]]) = {
    Try(Await.result(fut, 5.seconds)) match {
      case Success(res) =>
        if (!res.isEmpty) Some(res.map { r => parse_result(r) }.toList)
        else None
      case Failure(ex) =>
        log.error(s"ERROR in ${this}: ${ex.getCause}")
        ex.printStackTrace
        None
    }
  }

}

object AccBalances {
  val key1 = "acc.bal"

  private var instance: AccBalances = null
  private def makeifnotexist(implicit mat: Materializer, log: Logger) = {
    this.synchronized {
      if (instance == null) {
        instance = new AccBalances(10)
      }
    }
    instance
  }

  def bal(acc: String)(implicit mat: Materializer, log: Logger) = {
    makeifnotexist.fetch(acc)
  }

  /*
  var topic1: ActorRef[Topic.Command[RmAtWd]] = null
  var topic2: ActorRef[Topic.Command[CreditRiskManagerProcLck]] = null
  var topic3: ActorRef[Topic.Command[CreditRiskManagerVerdikt]] = null
  var kafkaStream1: Kafka_Flow_005 = null;
  var kafkaStream2: Kafka_Flow_003 = null;
  var kafkaStream3: Kafka_Flow_004 = null;
  */

  def apply(): Behavior[Any] = {
    Behaviors.setup { context =>
      /*
      topic1 = context.spawn(Topic[RmAtWd](key1), key1)
      topic2 = context.spawn(Topic[CreditRiskManagerProcLck](key5), key5)
      topic3 = context.spawn(Topic[CreditRiskManagerVerdikt](key6), key6)
      */

      import kz.altyn.bi.msv005.Entry.roles
      if (roles.contains("stream_subscriber")) {

        /*
        kafkaStream1 = new Kafka_Flow_005()
        kafkaStream1.run

        kafkaStream2 = new Kafka_Flow_003()
        kafkaStream2.run

        kafkaStream3 = new Kafka_Flow_004()
        kafkaStream3.run
        */
      }

      Behaviors.empty
    }
  }

  /*
  def records(recs: Seq[CreditRiskManagerStatRecord]) = {
    if (instance != null) instance.expire

    for (r <- recs
      .groupBy{ r => (r.nm, r.staff, r.wd)}
      .map { r=> (r._1, r._2.map { x=> StatRecord(x.c,x.v) }) }
      .toList.map{ r=> RmAtWd(r._1._1, r._1._2, r._1._3, r._2) }) {
      topic1 ! Topic.Publish(r)
    }
  }

  def proclck(rec: CreditRiskManagerProcLck) = {
    if (instance != null) instance.expire
    topic2 ! Topic.Publish(rec)
  }

  def procrjctreason(rec: CreditRiskManagerVerdikt) = {
    if (instance != null) instance.expire
    topic3 ! Topic.Publish(rec)
  }
  */

}

case class AccBalance(
  acc: String,
  bal: Double,
  capfl: Short,
  cli: Option[String],
  cur: String,
  nat: Option[Double],
  plan: Option[String],
  rate: Option[Double],
  sp: Option[String],
  stamp: Option[String],
  tax: Option[String]
)
