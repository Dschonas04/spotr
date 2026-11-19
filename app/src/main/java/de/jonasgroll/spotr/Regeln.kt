package de.jonasgroll.spotr

import java.time.LocalDateTime

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
