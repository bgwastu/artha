package net.wastu.artha.core.model

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

object AccountingTime {
    val zone: ZoneId = ZoneId.of("Asia/Jakarta")
    val clock: Clock = Clock.system(zone)
    fun pickerDay(utcMillis: Long): Long = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC)
        .toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    fun dayAfter(localDayMillis: Long): Long = Instant.ofEpochMilli(localDayMillis).atZone(zone)
        .toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
