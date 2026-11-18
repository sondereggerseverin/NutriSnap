package ch.nutrisnap.app.domain

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Ablauf-Logik für den Vorrat (rein, ohne Android-Abhängigkeiten → unit-testbar). */
object InventoryExpiry {

    enum class Group { EXPIRED, SOON, LATER, NONE }

    /** Ab dieser Restlaufzeit (Tage, inkl.) gilt eine Position als «läuft bald ab». */
    const val SOON_DAYS = 3

    private const val DAY_MS = 86_400_000.0

    fun startOfDay(ms: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Kalendertage bis zum Ablauf (0 = heute, negativ = abgelaufen), null ohne Datum. */
    fun daysLeft(expiresAt: Long?, now: Long, tz: TimeZone = TimeZone.getDefault()): Int? =
        expiresAt?.let { Math.round((startOfDay(it, tz) - startOfDay(now, tz)) / DAY_MS).toInt() }

    fun groupOf(expiresAt: Long?, now: Long, tz: TimeZone = TimeZone.getDefault()): Group {
        val d = daysLeft(expiresAt, now, tz) ?: return Group.NONE
        return when {
            d < 0 -> Group.EXPIRED
            d <= SOON_DAYS -> Group.SOON
            else -> Group.LATER
        }
    }

    fun label(days: Int): String = when {
        days < -1 -> "seit ${-days} Tagen abgelaufen"
        days == -1 -> "seit gestern abgelaufen"
        days == 0 -> "läuft heute ab"
        days == 1 -> "läuft morgen ab"
        else -> "läuft in $days Tagen ab"
    }

    /** «TT.MM.JJJJ» (auch «T.M.JJ») → Tagesbeginn in ms, null bei ungültiger Eingabe. */
    fun parseDate(text: String, tz: TimeZone = TimeZone.getDefault()): Long? {
        val m = Regex("""^\s*(\d{1,2})\.(\d{1,2})\.(\d{2}|\d{4})\s*$""").find(text) ?: return null
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        var year = m.groupValues[3].toInt()
        if (m.groupValues[3].length == 2) year += 2000
        val c = Calendar.getInstance(tz)
        c.isLenient = false
        c.clear()
        return try {
            c.set(year, month - 1, day, 0, 0, 0)
            c.timeInMillis
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun format(ms: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = ms
        return String.format(
            Locale.GERMAN, "%02d.%02d.%04d",
            c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR)
        )
    }

    /** Heute + [days] Tage, als Tagesbeginn. */
    fun inDays(days: Int, now: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = startOfDay(now, tz)
        c.add(Calendar.DAY_OF_YEAR, days)
        return c.timeInMillis
    }
}
