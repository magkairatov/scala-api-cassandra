package com.github.magkairatov

import akka.stream.Supervision
import org.slf4j.Logger

trait Kafka_Source_Flow_Supervision {

  def globalSupervisorException(implicit log: Logger): Supervision.Decider = {
    case ex: java.lang.RuntimeException =>
      log.error(s"ERROR: ${this.getClass.toString} run time exception: ${ex.getClass} ${ex.getCause} ${ex.getMessage}")
      Supervision.Restart
    case ex: Exception =>
      log.error(s"ERROR: ${this.getClass.toString} exception occurred and stopping stream: ${ex.getClass} ${ex.getCause} ${ex.getMessage}")
      Supervision.Restart
    case _ => Supervision.Resume
  }

}
