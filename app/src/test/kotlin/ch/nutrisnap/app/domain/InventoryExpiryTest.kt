package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class InventoryExpiryTest {
    private val tz = TimeZone.getTimeZone("UTC")
    private val now = InventoryExpiry.parseDate("15.06.2026", tz)!!

    private fun d(text: String) = InventoryExpiry.parseDate(text, tz)

    @Test fun parseGueltigUndUngueltig() {
        assertEquals("01.02.2027", InventoryExpiry.format(d("1.2.27")!!, tz))
        assertNull(d("31.02.2026"))
        assertNull(d("abc"))
        assertNull(d("15-06-2026"))
    }

    @Test fun tageBisAblauf() {
        assertEquals(0, InventoryExpiry.daysLeft(d("15.06.2026"), now, tz))
        assertEquals(2, InventoryExpiry.daysLeft(d("17.06.2026"), now, tz))
        assertEquals(-1, InventoryExpiry.daysLeft(d("14.06.2026"), now, tz))
        assertNull(InventoryExpiry.daysLeft(null, now, tz))
    }

    @Test fun gruppen() {
        assertEquals(InventoryExpiry.Group.EXPIRED, InventoryExpiry.groupOf(d("14.06.2026"), now, tz))
        assertEquals(InventoryExpiry.Group.SOON, InventoryExpiry.groupOf(d("15.06.2026"), now, tz))
        assertEquals(InventoryExpiry.Group.SOON, InventoryExpiry.groupOf(d("18.06.2026"), now, tz))
        assertEquals(InventoryExpiry.Group.LATER, InventoryExpiry.groupOf(d("19.06.2026"), now, tz))
        assertEquals(InventoryExpiry.Group.NONE, InventoryExpiry.groupOf(null, now, tz))
    }

    @Test fun beschriftung() {
        assertEquals("läuft heute ab", InventoryExpiry.label(0))
        assertEquals("läuft morgen ab", InventoryExpiry.label(1))
        assertEquals("läuft in 5 Tagen ab", InventoryExpiry.label(5))
        assertEquals("seit gestern abgelaufen", InventoryExpiry.label(-1))
        assertEquals("seit 3 Tagen abgelaufen", InventoryExpiry.label(-3))
    }

    @Test fun inTagen() {
        assertEquals("22.06.2026", InventoryExpiry.format(InventoryExpiry.inDays(7, now, tz), tz))
    }
}
