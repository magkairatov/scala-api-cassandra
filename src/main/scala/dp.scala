package com.github.magkairatov

import java.text.SimpleDateFormat
import java.time.temporal.ChronoField
import java.time.{Instant, LocalDate, LocalTime}
import java.util.Calendar

import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.joda.time.{DateTime, DateTimeZone}


object dp {
  private lazy val parser: DateTimeFormatter =
    new org.joda.time.format.DateTimeFormatterBuilder().append(null,Array(
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'.'SSSSSS").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss'.'SSSSSS").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'.'SSSSSS'Z'").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss'.'SSSSSS'Z'").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'.'SSSSSSZ").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss'.'SSSSSSZ").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'.'SSS").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss'.'SSS").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'.'SSS'Z'").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss'.'SSS'Z'").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'.'SSSZ").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss'.'SSSZ").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss'Z'").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZ").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ssZ").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd HH").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm").getParser,
      DateTimeFormat.forPattern("E, d MMM yyyy HH:mm:ss z").getParser,
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss a z").getParser,
      DateTimeFormat.forPattern("yyyy-MM").getParser,
    )).toFormatter.withZone(DateTimeZone.forID("Asia/Almaty"))

  val fmt1 = {
    java.time.format.DateTimeFormatter.ISO_INSTANT
  }

  val fmt2 = {
    new java.time.format.DateTimeFormatterBuilder()
      .appendPattern("yyyy-MM-dd")
      .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
      .parseDefaulting(ChronoField.NANO_OF_SECOND, 0)
      .toFormatter()
  }

  def fmtI(instant: java.time.Instant) = {
    fmt1.format(instant.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
  }
  def fmtLd(instant: java.time.LocalDate) = {
    fmt2.format(instant)
  }
  def isod = DateTime.now.formatted("yyyy-MM-dd")
  def dt(time: String) = parser.parseDateTime(time)
  def mills(time: String) = dt(time).getMillis
  def ts(time: String) = Instant.ofEpochMilli(mills(time))
  def d(time: String) =  dt(time).toDate
  def ld(time: String): java.time.LocalDate = {
    val dateTimeUtc = dt(time)
    LocalDate.of(dateTimeUtc.getYear, dateTimeUtc.getMonthOfYear, dateTimeUtc.getDayOfMonth)
  }
  val hourFormat = new SimpleDateFormat("HH")
  def getCurrentHour = Integer.parseInt(hourFormat.format(Calendar.getInstance().getTime()))

  def secondsTill(hours: Int, minutes: Int) = {
    val time = LocalTime.of(hours, minutes).toSecondOfDay
    val now = LocalTime.now().toSecondOfDay
    val fullDay = 60 * 60 * 24
    val difference = time - now
    if (difference < 0) {
      fullDay + difference
    } else {
      time - now
    }
  }
}

