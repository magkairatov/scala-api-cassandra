package com.github.magkairatov

import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSource}
import akka.stream.scaladsl.Sink
import akka.stream.{ActorAttributes, Materializer}
import com.datastax.oss.driver.api.core.cql.Row
import org.slf4j.Logger

import java.time.LocalDate
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
 * Транзакции Colvir
 */

class CbsAccTrns(implicit cs: CassandraSession, log: Logger, mat: Materializer)
    extends RestApiFlows_Supervision {

  def fetch(acc: String, sd: LocalDate, ed: LocalDate)(implicit context: ExecutionContext) =
    CassandraSource(s"select ts,acc1capfl,acc2,agrcdt,agrdord,agrimpdt,agrrecvdt,am1,cur1,dval,id,jfl1,jfl2,jrnid,kbe,kbk,knp,kod,ksocode,nat1,nm1,nm2,no,oacc,obic,obnm,onm,op1,orf,origin,otax,prp,rate,rf,rmvd,sp2,src,stamp,tax1,tax2,trnid,fpnm,fptax,fbnm,fbtax,pcountry,bcountry,fkbe from raw_trn.rtcbstrnd where acc1=? and dval>=? and dval<=?",
      acc: java.lang.String, sd, ed)
      .filter{r => r.isNull("rmvd") || (!r.isNull("rmvd") && r.getInt("rmvd").equals(0)) }
      .filter{r => !r.isNull("origin") && !r.getString("origin").equals("revaluation") }
      .filter{r => !r.isNull("dval")}
      .filter{r =>
        val dval = r.getLocalDate("dval")
        dval.compareTo(sd) >= 0 && dval.compareTo(ed) <= 0
      }
      .map(parse)
      .withAttributes(ActorAttributes.supervisionStrategy(supervisorException))
      .runWith(Sink.seq)
      .map { r =>
        if (r.size > 0) Some {
          val rs = r.sortBy(_.ts)
          val fut = CassandraSource("select orf,id,acc,am,bcountry,bd,cat,fkbe,knp,name,ordcd,paytp,prd,tax from raw_trn.cbs_reestr where orf in (" + (for (x <- rs) yield "'" + x.rf.get + "'").mkString(",") + ");")
            .map(parser)
            .withAttributes(ActorAttributes.supervisionStrategy(supervisorException))
            .runWith(Sink.seq)
          val s = Await.result(fut, 5.seconds)
          s.groupBy(_._1).foreach { x => rs.find { y => y.rf.isDefined && y.rf.get.equals(x._1) }.map { trn => trn.reg = Some(x._2.map(_._2)) } }
          rs
        } else None
      }

  def parse(rec: Row) = {
    val obic = Try(rec.getString("obic")).toOption
    val sp2 = Try(rec.getString("sp2")).toOption
    val origin = Try(rec.getString("origin")).toOption
    val oacc = Try(rec.getString("oacc")).toOption
    val prp = Try(rec.getString("prp")).toOption
    var otax = Try(if (!rec.getString("otax").equals("000000000000")) rec.getString("otax") else throw new Exception()).toOption
    otax = if (!otax.isDefined) Try(if (!rec.getString("tax2").equals("000000000000")) rec.getString("tax2") else throw new Exception()).toOption else otax
    val acc = {
      if (obic.isDefined) {
        obic.get match {
          case "ATYNKZKA" =>
            if (sp2.isDefined && Array("1", "4", "6").contains(sp2.get.substring(0, 1))) None
            else origin.get match {
              case "exch" => oacc
              case _ =>
                if ((prp.isDefined && prp.get.toLowerCase.contains("комисс")) ||
                  (sp2.isDefined && Array("2859", "2869", "2858").contains(sp2.get))) None
                else oacc
            }
          case _ => oacc
        }
      } else if (oacc.isDefined) oacc else Try(rec.getString("acc2")).toOption
    }
    CbsTrnN(
      rec.getInstant("ts").toEpochMilli,
      rec.getDouble("am1"),
      rec.getString("cur1"),
      dp.fmtLd(rec.getLocalDate("dval")),
      rec.getLong("id"),
      if (!rec.isNull("no")) Try(rec.getString("no")).toOption else None,
      if (!rec.isNull("kbe")) Try(rec.getString("kbe")).toOption else None,
      if (!rec.isNull("kbk")) Try(rec.getString("kbk")).toOption else None,
      if (!rec.isNull("knp")) Try(rec.getString("knp")).toOption else None,
      if (!rec.isNull("kod")) Try(rec.getString("kod")).toOption else None,
      if (!rec.isNull("ksocode")) Try(rec.getString("ksocode")).toOption else None,
      if (!rec.isNull("nat1")) Try(rec.getDouble("nat1")).toOption else None,
      acc,
      if (!rec.isNull("onm")) Try(rec.getString("onm")).toOption else None,
      obic,
      otax,
      prp,
      if (!rec.isNull("rate")) Try(rec.getDouble("rate")).toOption else None,
      if (!rec.isNull("orf")) Try(rec.getString("orf")).toOption else None,
      origin,
      if (!rec.isNull("op1")) Try(rec.getString("op1")).toOption else None,
      if (!rec.isNull("fpnm")) Try(rec.getString("fpnm")).toOption else None,
      if (!rec.isNull("fptax")) Try(rec.getString("fptax")).toOption else None,
      if (!rec.isNull("fbnm")) Try(rec.getString("fbnm")).toOption else None,
      if (!rec.isNull("fbtax")) Try(rec.getString("fbtax")).toOption else None,
      if (!rec.isNull("pcountry")) Try(rec.getString("pcountry")).toOption else None,
      if (!rec.isNull("bcountry")) Try(rec.getString("bcountry")).toOption else None,
      if (!rec.isNull("fkbe")) Try(rec.getString("fkbe")).toOption else None,
      None
    )
  }

  private def parser(row: Row) = {
    (
      row.getString("orf"),
      FReestrN(
        row.getLong("id"),
        Try(row.getString("acc")).toOption,
        row.getDouble("am"),
        if (!row.isNull("bcountry")) Try(row.getString("bcountry")).toOption else None,
        if (!row.isNull("bd")) Try(row.getString("bd")).toOption else None,
        if (!row.isNull("cat")) Try(row.getString("cat")).toOption else None,
        if (!row.isNull("fkbe")) Try(row.getString("fkbe")).toOption else None,
        Try(row.getString("knp")).toOption,
        Try(row.getString("name")).toOption,
        Try(row.getString("ordcd")).toOption,
        row.getByte("paytp").toInt,
        if (!row.isNull("prd")) Try(row.getString("prd")).toOption else None,
        Try(row.getString("tax")).toOption
      )
    )
  }

}
