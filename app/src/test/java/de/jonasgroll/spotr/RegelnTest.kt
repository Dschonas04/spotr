package de.jonasgroll.spotr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RegelnTest {
    // 16.11.2026 ist ein Montag
    private fun zeit(tag: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 15 + tag, h, m)
    private val werktags = (1..5).toSet()
    private val alle = (1..7).toSet()

    @Test fun fensterAmTag() {
        val f = Fenster(werktags, 7 * 60, 22 * 60)
        assertTrue(Regeln.drin(f, zeit(1, 7)))
        assertTrue(Regeln.drin(f, zeit(1, 21, 59)))
        assertFalse(Regeln.drin(f, zeit(1, 22)))
        assertFalse(Regeln.drin(f, zeit(1, 6, 59)))
        assertFalse("Samstag", Regeln.drin(f, zeit(6, 12)))
    }

    @Test fun fensterUeberMitternacht() {
        val f = Fenster(setOf(5), 22 * 60, 6 * 60) // Freitag 22 Uhr bis Samstag 6 Uhr
        assertTrue(Regeln.drin(f, zeit(5, 23)))
        assertTrue("Samstagmorgen gehört zum Freitag", Regeln.drin(f, zeit(6, 5, 59)))
        assertFalse(Regeln.drin(f, zeit(6, 6)))
        assertFalse("Freitagmorgen nicht, Donnerstag ist nicht dabei", Regeln.drin(f, zeit(5, 3)))
    }

    @Test fun sonntagNachtInDenMontag() {
        val f = Fenster(setOf(7), 23 * 60, 2 * 60)
        assertTrue(Regeln.drin(f, zeit(8, 1))) // Montag 23.11. 01:00
    }

    @Test fun anfangGleichEndeIstGanztags() {
        val f = Fenster(setOf(3), 0, 0)
        assertTrue(Regeln.drin(f, zeit(3, 0)))
        assertTrue(Regeln.drin(f, zeit(3, 23, 59)))
        assertFalse(Regeln.drin(f, zeit(4, 0)))
        assertEquals("Mi, ganztags", f.text())
    }

    @Test fun naechsteGrenze() {
        val l = listOf(Fenster(alle, 7 * 60, 22 * 60))
        assertEquals(zeit(1, 22), Regeln.naechsteGrenze(l, zeit(1, 12)))
        assertEquals(zeit(2, 7), Regeln.naechsteGrenze(l, zeit(1, 22)))
        assertEquals("genau auf der Grenze zählt die nächste", zeit(1, 22), Regeln.naechsteGrenze(l, zeit(1, 7)))
        assertNull(Regeln.naechsteGrenze(emptyList(), zeit(1, 7)))
    }

    @Test fun naechsteGrenzeMitSekunden() {
        val l = listOf(Fenster(alle, 7 * 60, 22 * 60))
        assertEquals(zeit(1, 22), Regeln.naechsteGrenze(l, zeit(1, 7).plusSeconds(30)))
    }

    @Test fun zuhauseMitAbstand() {
        assertEquals(true, Regeln.zuhause(150f, 20f, 200f, false))
        assertEquals("ungenaue Messung zählt als zuhause, wenn innerhalb der Genauigkeit", true, Regeln.zuhause(400f, 500f, 200f, false))
        assertEquals(false, Regeln.zuhause(500f, 20f, 200f, true))
        assertEquals("Zwischenzone behält zuhause", true, Regeln.zuhause(300f, 20f, 200f, true))
        assertEquals("Zwischenzone behält unterwegs", false, Regeln.zuhause(300f, 20f, 200f, false))
        assertNull("zu ungenau", Regeln.zuhause(10f, 2000f, 200f, true))
    }

    @Test fun fensterText() {
        assertEquals("Täglich, 07:00 bis 22:00", Fenster(alle, 420, 1320).text())
        assertEquals("Mo bis Fr, 08:30 bis 17:00", Fenster(werktags, 510, 1020).text())
        assertEquals("Sa und So, 10:00 bis 12:00", Fenster(setOf(6, 7), 600, 720).text())
        assertEquals("Mo, Mi, 10:00 bis 12:00", Fenster(setOf(3, 1), 600, 720).text())
    }

    private val berlin = java.time.ZoneId.of("Europe/Berlin")

    @org.junit.Test
    fun waechterInDerDoppeltenStunde() {
        val jetzt = java.time.Instant.parse("2026-10-25T01:10:00Z") // 02:10, zum zweiten Mal
        org.junit.Assert.assertEquals(jetzt.plusSeconds(300), Regeln.wecker(jetzt, berlin, null, 5))
    }

    @org.junit.Test
    fun grenzeInDerDoppeltenStunde() {
        val jetzt = java.time.Instant.parse("2026-10-25T01:10:00Z")
        val grenze = LocalDateTime.of(2026, 10, 25, 2, 30)
        org.junit.Assert.assertEquals(java.time.Instant.parse("2026-10-25T01:30:00Z"), Regeln.wecker(jetzt, berlin, grenze, null))
    }

    @org.junit.Test
    fun grenzeInDerFehlendenStunde() {
        val jetzt = java.time.Instant.parse("2027-03-28T00:50:00Z") // 01:50, gleich ist es 03:00
        val grenze = LocalDateTime.of(2027, 3, 28, 2, 30)
        org.junit.Assert.assertEquals(java.time.Instant.parse("2027-03-28T01:30:00Z"), Regeln.wecker(jetzt, berlin, grenze, null))
    }

    @org.junit.Test
    fun ohneZielKeinWecker() {
        org.junit.Assert.assertNull(Regeln.wecker(java.time.Instant.now(), berlin, null, null))
    }
}
