package com.github.magkairatov

import io.confluent.ksql.api.client.{Client, ClientOptions}
import kz.altyn.bi.msv005.WsSession.AccBal
import org.slf4j.Logger

import scala.concurrent.ExecutionContext

/**
 * Api взаимодействия с Ksql - сервером напрямую
 */

class Ksql_Interop(implicit log: Logger, context: ExecutionContext) {

  def accBal(acc: String) = {
    val cp = Entry.ksqldbContactPoint
    println(s"ip {$cp.ip}, {$cp.port}")
    val client = Client.create(ClientOptions.create.setHost(cp.ip).setPort(cp.port))
    try {
      val rows = client.executeQuery(s"select bal, cur, stamp from table_rt_acc_bals where acc='$acc' limit 1;").get
      if (!rows.isEmpty) {
        val row = rows.get(0)
        Some(AccBal(acc, row.getDouble(1), row.getString(2), row.getLong(3), Some(cp.host)))
      } else None
    } finally {
      client.close()
    }
  }

  def nbrkRate(cur: String) = {
    val cp = Entry.ksqldbContactPoint
    val client = Client.create(ClientOptions.create.setHost(cp.ip).setPort(cp.port))
    try {
      val rows = client.executeQuery(s"select rate from table_cbs_rate_lates where cur='$cur' limit 1;").get
      if (!rows.isEmpty) {
        val row = rows.get(0)
        Some(row.getDouble(1).doubleValue())
      } else None
    } finally {
      client.close()
    }
  }
}
