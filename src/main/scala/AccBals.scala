package com.github.magkairatov

import akka.stream.Materializer
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSource}
import akka.stream.scaladsl.Sink
import org.slf4j.Logger

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Try}

object AccBals {
  case class Acc(acc: String, cur: String, bal: Double, capfl: Int, odt: String, plan: String, systp: String, t: String, j:Int)
}

class AccBals(tax: String)(implicit cs: CassandraSession, context: ExecutionContext, mat: Materializer, log: Logger) extends RestApiFlows_Supervision {
  def get() =
    Try {
      CassandraSource("select cc from core_banking.cli_search where tax=? limit 1;", tax)
        .map(_.getString("cc"))
        .flatMapMerge(10, cc => {
          CassandraSource(s"select se from core_banking.cli_info where cc=? limit 1;", cc)
            .filter(!_.isNull("se"))
            .map(_.getInt("se"))
            .map(se => {
              val f=CassandraSource(s"select acc,cc,capfl,cdt,cur,odt,plan,s,systp,t from core_banking.acc_state where cc = ?;", cc)
                .filter(_.isNull("cdt"))
                .filter(rec => !rec.isNull("s") && rec.getInt("s").equals(1))
                .filter(rec => rec.getString("cc").equals(cc))
                .flatMapMerge(10, rec => {
                  val acc = rec.getString("acc")
                  CassandraSource(s"select bal from raw_trn.acc_bal where acc=?;", acc)
                    .map { r =>
                      AccBals.Acc(
                        acc,
                        rec.getString("cur"),
                        r.getDouble("bal"),
                        rec.getInt("capfl"),
                        dp.fmtLd(rec.getLocalDate("odt")),
                        rec.getString("plan"),
                        rec.getString("systp"),
                        rec.getString("t"),
                        if (se==9) 0 else 1
                      )
                    }
                }).runWith(Sink.seq)
              Await.result(f,5.seconds)
            })
        }).runWith(Sink.headOption)
    }.recoverWith {
      case exception =>
        Entry.log.info(s"Error getting account stats: ${exception.getMessage}")
        Failure(exception)
    }
}
