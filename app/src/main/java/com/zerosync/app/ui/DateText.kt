package com.zerosync.app.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Short date for list rows: "1 Jan" when it's this year, "1 Jan 2015" otherwise.
 * [millis] is epoch milliseconds.
 */
fun shortDate(millis: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameYear = then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
    val pattern = if (sameYear) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}
