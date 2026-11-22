package de.jonasgroll.spotr

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Reine Regeln ohne Android, damit sie sich ohne Gerät testen lassen. */
object Regeln {

    /**
     * Liegt der Zeitpunkt im Fenster? Endet das Fenster vor dem Anfang, läuft es
     * über Mitternacht und zählt zum Starttag. Anfang gleich Ende heißt ganzer Tag.
     */
    fun drin(f: Fenster, jetzt: LocalDateTime): Boolean {
        val tag = jetzt.dayOfWeek.value
        val gestern = if (tag == 1) 7 else tag - 1
        val m = jetzt.hour * 60 + jetzt.minute
        return when {
            f.von == f.bis -> tag in f.tage
            f.von < f.bis -> tag in f.tage && m >= f.von && m < f.bis
            else -> (tag in f.tage && m >= f.von) || (gestern in f.tage && m < f.bis)
        }
    }

    fun drin(fenster: List<Fenster>, jetzt: LocalDateTime) = fenster.any { drin(it, jetzt) }

    /** Nächster Zeitpunkt nach jetzt, an dem sich das Ergebnis von drin() ändern kann. */
    fun naechsteGrenze(fenster: List<Fenster>, jetzt: LocalDateTime): LocalDateTime? {
        if (fenster.isEmpty()) return null
        val punkte = fenster.flatMap { listOf(it.von, it.bis) }.toSortedSet()
        for (tag in 0..7) {
            val d = jetzt.toLocalDate().plusDays(tag.toLong())
            punkte.map { d.atTime(it / 60, it % 60) }.firstOrNull { it.isAfter(jetzt) }?.let { return it }
        }
        return null
    }

    /**
     * Der Zeitpunkt für den Wecker: der Wächter in echten Minuten, die Grenze des
     * Plans nach der Uhr an der Wand, beides immer in der Zukunft. Bei der
     * Umstellung auf Winterzeit gibt es eine Stunde zweimal, und atZone nimmt die
     * erste: in der zweiten läge der Wecker sonst hinter uns und löste sofort
     * wieder aus.
     */
    fun wecker(jetzt: Instant, zone: ZoneId, grenze: LocalDateTime?, waechterMinuten: Long?): Instant? {
        val ziele = mutableListOf<Instant>()
        if (waechterMinuten != null) ziele += jetzt.plusSeconds(maxOf(1L, waechterMinuten) * 60)
        if (grenze != null) {
            val kommend = zone.rules.getValidOffsets(grenze).map { grenze.toInstant(it) }
                .ifEmpty { listOf(grenze.atZone(zone).toInstant()) }
                .filter { it.isAfter(jetzt) }
            ziele += kommend.minOrNull() ?: jetzt.plusSeconds(60)
        }
        return ziele.minOrNull()
    }

    /**
     * Zuhause oder unterwegs, mit Abstand gegen Hin und Her: zuhause innerhalb des
     * Umkreises (oder der Messgenauigkeit), unterwegs erst ab doppeltem Umkreis
     * plus Genauigkeit, dazwischen bleibt der letzte Stand. null bei zu ungenauer Messung.
     */
    fun zuhause(abstand: Float, genauigkeit: Float, radius: Float, vorher: Boolean): Boolean? = when {
        genauigkeit > 1500f -> null
        abstand <= maxOf(radius, genauigkeit) -> true
        abstand > 2 * radius + genauigkeit -> false
        else -> vorher
    }
}
