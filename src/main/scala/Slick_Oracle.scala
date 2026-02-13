package com.github.magkairatov

import akka.stream.alpakka.slick.scaladsl.SlickSession
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

trait Slick_Oracle {
  val databaseConfig = DatabaseConfig.forConfig[JdbcProfile]("slick-oracle")
  implicit val slickSession = SlickSession.forConfig(databaseConfig)
}
