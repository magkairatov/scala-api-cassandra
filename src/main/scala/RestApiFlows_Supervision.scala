package com.github.magkairatov

import akka.stream.{Supervision}
import org.slf4j.Logger

trait RestApiFlows_Supervision {
  def supervisorException(implicit log: Logger): Supervision.Decider = {
    case ex: java.lang.RuntimeException =>
      log.error(s"ERROR in stream ${this.getClass.toString} run time exception: ${ex.getClass} ${ex.getCause} ${ex.getMessage}")
      //println(s"ERROR in stream ${this.getClass.toString} run time exception: ${ex.getClass} ${ex.getCause} ${ex.getMessage}")
      Supervision.Stop
    case ex: Exception =>
      log.error(s"Error in stream ${this.getClass.toString} exception occurred and stopping stream: ${ex.getClass} ${ex.getCause} ${ex.getMessage}")
      //println(s"Error in stream ${this.getClass.toString} exception occurred and stopping stream: ${ex.getClass} ${ex.getCause} ${ex.getMessage}")
      Supervision.Stop
  }
}
