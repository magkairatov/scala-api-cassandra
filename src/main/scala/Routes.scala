package com.github.magkairatov

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.{ByteString, Timeout}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import kz.altyn.bi.msv005.TaxTransactionStatement.TaxTransactionStmt
import kz.altyn.bi.msv005.WsSession.WsSessionMessage
import net.liftweb.json.Extraction.decompose
import net.liftweb.json.Serialization.write
import net.liftweb.json.{DefaultFormats, Formats, ShortTypeHints}

import java.time.{Instant, LocalDate}
import java.util.Date
import scala.concurrent.duration._
import scala.util.{Failure, Success}

case class msg(t: String, data: Any)

trait Routes extends RestApiFlows_Supervision {
  import Entry.{cs, log, system}
  import system.executionContext

  implicit val formats: Formats = new DefaultFormats {
    outer =>
    override val typeHintFieldName = "type"
    override val typeHints = ShortTypeHints(List(classOf[String], classOf[Date]))
  }

  implicit lazy val timeout = Timeout(5.seconds)

  // Initialize services for the remaining files
  lazy val cbstrns: CbsAccTrns = new CbsAccTrns()

  lazy val healthRoute: Route = pathPrefix("health") {
    concat(
      pathEnd {
        concat(
          get {
            complete(StatusCodes.OK)
          }
        )
      }
    )
  }

  val mapper = new ObjectMapper()
    .registerModule(DefaultScalaModule)
    .registerModule(new JavaTimeModule())

  def respond(t: String, resp: AnyRef, ms: Long): HttpResponse = {
    val h = scala.collection.immutable.Seq(
      RawHeader("Cache-Control", "no-cache"),
      RawHeader("X-Request-Duration-Ms", ms.toString),
      RawHeader("X-Node-Id", Entry.nodeId),
    )
    resp match {
      case Some(data) =>
        HttpResponse(
          StatusCodes.OK,
          headers = h,
          entity = HttpEntity(ContentTypes.`application/json`, write(decompose(msg(t, data))))
        )
      case None =>
        HttpResponse(StatusCodes.NoContent)
    }
  }

  lazy val apiRoutes: Route = {
    get {
      import Entry.{log, mat}
      val start = System.currentTimeMillis()

      concat(
        path("api" / AccBalances.key1 / Segment) { (acc: String) =>
          get {
            complete(
              respond(
                AccBalances.key1,
                AccBalances.bal(acc),
                System.currentTimeMillis() - start
              )
            )
          }
        },

        path("api" / "accbals" / Segment) { (taxId: String) =>
          get {
            val accBals = new AccBals(taxId)
            accBals.get() match {
              case Success(result) =>
                onComplete(result) {
                  case Success(accounts) =>
                    complete(
                      respond(
                        "accbals",
                        if (accounts.isEmpty) None else Some(accounts),
                        System.currentTimeMillis() - start
                      )
                    )
                  case Failure(ex) =>
                    log.error(s"Error fetching account balances for tax $taxId", ex)
                    complete(StatusCodes.InternalServerError, s"Error: ${ex.getMessage}")
                }
              case Failure(ex) =>
                log.error(s"Error initializing AccBals for tax $taxId", ex)
                complete(StatusCodes.InternalServerError, s"Error: ${ex.getMessage}")
            }
          }
        },

        path("api" / TopicKeys.CBSTrn / Segment / Segment / Segment) {
          (acc: String, sd: String, ed: String) =>
            get {
              val fut = cbstrns.fetch(acc, dp.ld(sd), dp.ld(ed))
              onSuccess(fut) { l =>
                val rep = if (l.isEmpty) None else Some(l)
                complete(respond(TopicKeys.CBSTrn, rep, System.currentTimeMillis() - start))
              }
            }
        },

        path("api" / "tax-transaction-statement" / Segment / Segment / Segment) {
          (taxId: String, startDate: String, endDate: String) =>
            get {
              val taxStmt = new TaxTransactionStatement(taxId, startDate, endDate)
              onComplete(taxStmt.get()) {
                case Success(Some(result: TaxTransactionStmt)) =>
                  complete(
                    respond(
                      "tax-transaction-statement",
                      Some(result),
                      System.currentTimeMillis() - start
                    )
                  )
                case Success(None) =>
                  complete(HttpResponse(StatusCodes.NoContent))
                case Failure(ex) =>
                  log.error(s"Error fetching tax transaction statement for $taxId", ex)
                  complete(StatusCodes.InternalServerError, s"Error: ${ex.getMessage}")
              }
            }
        }
      )
    }
  }

  lazy val routes: Route = healthRoute ~ apiRoutes
}

case class CbsTrnN(
  ts: Long,                          
  am: Double,                       
  cur: String,                     
  dval: String,                     
  id: Long,                      
  no: Option[String],              
  kbe: Option[String],            
  kbk: Option[String],              
  knp: Option[String],              
  kod: Option[String],            
  ksocode: Option[String],       
  nat: Option[Double],       
  acc: Option[String],             
  nm: Option[String],        
  bic: Option[String],              
  tax: Option[String],              
  prp: Option[String],             
  rate: Option[Double],          
  rf: Option[String],              
  origin: Option[String],         
  op: Option[String],            
  fpnm: Option[String],          
  fptax: Option[String],          
  fbnm: Option[String],            
  fbtax: Option[String],        
  pcountry: Option[String],         
  bcountry: Option[String],       
  fkbe: Option[String],          
  var reg: Option[Iterable[FReestrN]] 
) extends WsSessionMessage

case class FReestrN(
  id: Long,
  acc: Option[String],
  am: Double,
  bcountry: Option[String],
  bd: Option[String],
  cat: Option[String],
  fkbe: Option[String],
  knp: Option[String],
  name: Option[String],
  ordcd: Option[String],
  paytp: Int,
  prd: Option[String],
  tax: Option[String]
)

case class AccState(
  acc: String,
  capfl: Int,
  cc: String,
  cdt: Option[LocalDate],
  cur: String,
  odt: LocalDate,
  plan: String,
  s: Int,
  stampdt: Instant,
  status: String,
  systp: String,
  t: String,
  tax: String
)

case class Bal(
  at: String,      // Date/time
  v: Double,       // Value
  cur: String      // Currency
)

case class CbsAccStmt(
  acc: String,
  cur: String,
  rate: Option[Double],
  edrate: Option[Double],
  cc: String,
  fullnm: Option[String],
  latnm: Option[String],
  tax: Option[String],
  addr: Option[String],
  capfl: Int,
  cdt: Option[String],
  odt: String,
  plan: String,
  status: String,
  t: String,
  bal: Option[Bal],
  s: Option[Bal],
  e: Option[Bal],
  trns: Option[IterableOnce[CbsTrnN]]
)

case class AccsStmt(
  rqsd: String,                          
  rqed: String,                        
  accs: Option[Iterable[CbsAccStmt]]   
) extends WsSessionMessage