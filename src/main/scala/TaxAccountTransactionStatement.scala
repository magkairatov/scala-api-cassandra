package com.github.magkairatov

import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSource}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorAttributes, Materializer}
import org.slf4j.Logger

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Service for fetching all transactions for all accounts of a given tax ID
 */
object TaxTransactionStatement {
  
  case class TransactionInfo(
    id: Long,
    acc: String,
    tam: Option[Double],
    tcur: Option[String],
    pdt: String,
    sicd: Option[String],
    rrn: Option[String],
    card: Option[String],
    tcount: Option[String],
    tcity: Option[String]
  )
  
  case class BankStatementInfo(
    acquire: Option[String],
    cpid: Option[String],
    cpna: Option[String],
    cprn: Option[String],
    terminal: Option[String]
  )
  
  case class MergedTransactionInfo(
    id: Long,
    tam: Option[Double],
    tcur: Option[String],
    pdt: String,
    sicd: Option[String],
    rrn: Option[String],
    card: Option[String],
    tcount: Option[String],
    tcity: Option[String],
    acquire: Option[String],
    cpid: Option[String],
    cpna: Option[String],
    cprn: Option[String],
    terminal: Option[String]
  )
  
  case class BalanceInfo(
    date: String,
    bal: Double,
    cur: String
  )
  
  case class HistoricalBalanceInfo(
    acc: String,
    doper: String,
    bal: Double,
    cur: String
  )
  
  case class CurrentBalanceInfo(
    bal: Double,
    cur: String
  )
  
  case class AccountInfo(
    acc: String,
    cc: String,
    capfl: Option[String],
    cdt: Option[String],
    cur: Option[String],
    odt: Option[String],
    plan: Option[String],
    s: Option[String],
    systp: Option[String],
    t: Option[String],
    startBalance: Option[BalanceInfo],
    endBalance: Option[BalanceInfo],
    transactions: List[MergedTransactionInfo]
  )
  
  case class TaxTransactionStmt(
    tax: String,
    startDate: String,
    endDate: String,
    accounts: List[AccountInfo]
  )
}

class TaxTransactionStatement(
  tax: String,
  startDate: String,
  endDate: String
)(implicit
  cs: CassandraSession,
  context: ExecutionContext,
  mat: Materializer,
  log: Logger
) extends RestApiFlows_Supervision {

  import TaxTransactionStatement._

  private val sd = LocalDate.parse(startDate)
  private val ed = LocalDate.parse(endDate)

  if (sd.compareTo(ed) > 0) {
    throw new IllegalArgumentException("Start date cannot be after end date")
  }

  def get(): Future[Option[TaxTransactionStmt]] = {
    val result = for {
      accounts <- fetchAccountsForTax()
      accountsWithTransactionsAndBalances <- fetchTransactionsAndBalancesForAllAccounts(accounts)
    } yield {
      TaxTransactionStmt(
        tax = tax,
        startDate = startDate,
        endDate = endDate,
        accounts = accountsWithTransactionsAndBalances
      )
    }

    result.map(Some(_)).recover {
      case ex =>
        log.error(s"Error fetching tax transaction statement for tax $tax", ex)
        None
    }
  }

  private def fetchAccountsForTax(): Future[List[AccountInfo]] = {
    val ccFuture = CassandraSource("SELECT cc FROM core_banking.cli_search WHERE tax=? LIMIT 1", tax)
      .map(_.getString("cc"))
      .runWith(Sink.headOption)

    ccFuture.flatMap {
      case Some(cc) =>
        val query = "SELECT acc, cc, capfl, cdt, cur, odt, plan, s, systp, t FROM core_banking.acc_state WHERE cc = ?"
        
        CassandraSource(query, cc)
          .map { row =>
            AccountInfo(
              acc = row.getString("acc"),
              cc = row.getString("cc"),
              capfl = Try(row.getString("capfl")).toOption,
              cdt = Try(dp.fmtLd(row.getLocalDate("cdt"))).toOption,
              cur = Try(row.getString("cur")).toOption,
              odt = Try(dp.fmtLd(row.getLocalDate("odt"))).toOption,
              plan = Try(row.getString("plan")).toOption,
              s = Try(row.getString("s")).toOption,
              systp = Try(row.getString("systp")).toOption,
              t = Try(row.getString("t")).toOption,
              startBalance = None, 
              endBalance = None,
              transactions = List.empty 
            )
          }
          .withAttributes(ActorAttributes.supervisionStrategy(supervisorException))
          .runWith(Sink.collection)
          .map(_.toList)
      case None =>
        log.warn(s"No client found for tax ID: $tax")
        Future.successful(List.empty)
    }
  }

  private def fetchTransactionsAndBalancesForAllAccounts(accounts: List[AccountInfo]): Future[List[AccountInfo]] = {
    if (accounts.isEmpty) {
      Future.successful(List.empty)
    } else {
      val futures = accounts.map { account =>
        for {
          transactions <- fetchTransactionsForAccount(account.acc)
          mergedTransactions <- mergeTransactionWithBankStatement(transactions)
          startBalance <- fetchHistoricalBalance(account.acc, sd)
          endBalance <- fetchEndBalance(account.acc)
        } yield {
          account.copy(
            transactions = mergedTransactions,
            startBalance = startBalance.map(b => BalanceInfo(b.doper, b.bal, b.cur)),
            endBalance = endBalance.map(b => BalanceInfo(b.doper, b.bal, b.cur))
          )
        }
      }
      Future.sequence(futures)
    }
  }

  private def fetchTransactionsForAccount(acc: String): Future[List[TransactionInfo]] = {
    val query = "SELECT id, acc, tam, tcur, pdt, sicd, rrn, card, tcount, tcity " +
               "FROM raw_trn.rtway4trn WHERE acc = ? AND pdt >= ? AND pdt <= ?"

    CassandraSource(query, acc, sd, ed)
      .map { row =>
        TransactionInfo(
          id = row.getLong("id"),
          acc = row.getString("acc"),
          tam = Try(row.getDouble("tam")).toOption,
          tcur = Try(row.getString("tcur")).toOption,
          pdt = dp.fmtLd(row.getLocalDate("pdt")),
          sicd = Try(row.getString("sicd")).toOption,
          rrn = Try(row.getString("rrn")).toOption,
          card = Try(row.getString("card")).toOption,
          tcount = Try(row.getString("tcount")).toOption,
          tcity = Try(row.getString("tcity")).toOption
        )
      }
      .withAttributes(ActorAttributes.supervisionStrategy(supervisorException))
      .runWith(Sink.collection)
      .map(_.toList)
  }

  private def mergeTransactionWithBankStatement(
    transactionInfos: List[TransactionInfo]
  ): Future[List[MergedTransactionInfo]] = {
    if (transactionInfos.isEmpty) {
      Future.successful(List.empty)
    } else {
      val transactionIds = transactionInfos.map(_.id)
      
      fetchBankStatementInfoByIds(transactionIds).map { bankStatements =>
        val bankStatementMap = bankStatements.zipWithIndex.map { case (bs, idx) => 
          transactionIds(idx) -> bs 
        }.toMap
        
        transactionInfos.map { txn =>
          val matchingBankStatement = bankStatementMap.get(txn.id)
          
          MergedTransactionInfo(
            id = txn.id,
            tam = txn.tam,
            tcur = txn.tcur,
            pdt = txn.pdt,
            sicd = txn.sicd,
            rrn = txn.rrn,
            card = txn.card,
            tcount = txn.tcount,
            tcity = txn.tcity,
            acquire = matchingBankStatement.flatMap(_.acquire),
            cpid = matchingBankStatement.flatMap(_.cpid),
            cpna = matchingBankStatement.flatMap(_.cpna),
            cprn = matchingBankStatement.flatMap(_.cprn),
            terminal = matchingBankStatement.flatMap(_.terminal)
          )
        }
      }
    }
  }

  private def fetchBankStatementInfoByIds(ids: List[Long]): Future[List[BankStatementInfo]] = {
    if (ids.isEmpty) {
      Future.successful(List.empty)
    } else {
      val stringIds = ids.map(_.toString)
      
      val placeholders = stringIds.map(_ => "?").mkString(",")
      val query = s"SELECT rowkey, acquire, cpid, cpna, cprn, terminal " +
                 s"FROM raw_trn.bank_statement WHERE rowkey IN ($placeholders)"
      
      CassandraSource(query, stringIds: _*)
        .map { row =>
          BankStatementInfo(
            acquire = Try(row.getString("acquire")).toOption,
            cpid = Try(row.getString("cpid")).toOption,
            cpna = Try(row.getString("cpna")).toOption,
            cprn = Try(row.getString("cprn")).toOption,
            terminal = Try(row.getString("terminal")).toOption
          )
        }
        .withAttributes(ActorAttributes.supervisionStrategy(supervisorException))
        .runWith(Sink.collection)
        .map(_.toList)
    }
  }

  private def fetchBankStatementInfoByIdsAlternative(ids: List[Long]): Future[List[BankStatementInfo]] = {
    val futures = ids.map { id =>
      val query = "SELECT rowkey, acquire, cpid, cpna, cprn, terminal " +
                 "FROM raw_trn.bank_statement WHERE rowkey = ?"
      
      CassandraSource(query, id.toString)
        .map { row =>
          BankStatementInfo(
            acquire = Try(row.getString("acquire")).toOption,
            cpid = Try(row.getString("cpid")).toOption,
            cpna = Try(row.getString("cpna")).toOption,
            cprn = Try(row.getString("cprn")).toOption,
            terminal = Try(row.getString("terminal")).toOption
          )
        }
        .withAttributes(ActorAttributes.supervisionStrategy(supervisorException))
        .runWith(Sink.headOption)
    }
    
    Future.sequence(futures).map(_.flatten)
  }

  private def fetchCurrentBalance(acc: String): Future[Option[CurrentBalanceInfo]] = {
    val query = "SELECT acc, bal, cur FROM raw_trn.acc_bal WHERE acc = ?"
    
    CassandraSource(query, acc)
      .map { row =>
        CurrentBalanceInfo(
          bal = row.getDouble("bal"),
          cur = row.getString("cur")
        )
      }
      .withAttributes(ActorAttributes.supervisionStrategy(supervisorException))
      .runWith(Sink.headOption)
  }

  private def fetchHistoricalBalance(acc: String, date: LocalDate): Future[Option[HistoricalBalanceInfo]] = {
    val query = "SELECT acc, doper, bal, cur FROM raw_trn.rtaccbalatdt WHERE acc = ? AND doper = ?"
    
    CassandraSource(query, acc, date)
      .map { row =>
        HistoricalBalanceInfo(
          acc = row.getString("acc"),
          doper = dp.fmtLd(row.getLocalDate("doper")),
          bal = row.getDouble("bal"),
          cur = row.getString("cur")
        )
      }
      .withAttributes(ActorAttributes.supervisionStrategy(supervisorException))
      .runWith(Sink.headOption)
      .flatMap {
        case Some(balance) => Future.successful(Some(balance))
        case None => fetchClosestPreviousBalance(acc, date)
      }
  }

  private def fetchClosestPreviousBalance(acc: String, date: LocalDate): Future[Option[HistoricalBalanceInfo]] = {
    val query = "SELECT acc, doper, bal, cur FROM raw_trn.rtaccbalatdt WHERE acc = ? AND doper < ? ORDER BY doper DESC LIMIT 1"
    
    CassandraSource(query, acc, date)
      .map { row =>
        HistoricalBalanceInfo(
          acc = row.getString("acc"),
          doper = dp.fmtLd(row.getLocalDate("doper")),
          bal = row.getDouble("bal"),
          cur = row.getString("cur")
        )
      }
      .withAttributes(ActorAttributes.supervisionStrategy(supervisorException))
      .runWith(Sink.headOption)
  }

  private def fetchEndBalance(acc: String): Future[Option[HistoricalBalanceInfo]] = {
    fetchHistoricalBalance(acc, ed).flatMap {
      case Some(historicalBalance) =>
        Future.successful(Some(historicalBalance))
      case None =>
        // Fallback to current balance if historical balance for end date is not available
        fetchCurrentBalance(acc).map { currentBal =>
          currentBal.map(cb => HistoricalBalanceInfo(
            acc = acc,
            doper = ed.toString, // Use end date even though it's current balance
            bal = cb.bal,
            cur = cb.cur
          ))
        }
    }
  }
}
