package de.jonasgroll.spotr

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Entscheidet, ob der Hotspot gerade laufen soll, schaltet ihn entsprechend und
 * stellt den nächsten Wecker: zur nächsten Grenze des Zeitplans und, solange der
 * Hotspot laufen soll, im Takt des Wächters.
 */
object Planer {

    /** null heißt: Spotr greift nicht ein (Betrieb manuell). */
    fun soll(sp: Speicher, jetzt: LocalDateTime = LocalDateTime.now()): Boolean? = when (sp.betrieb) {
        Betrieb.MANUELL -> null
        Betrieb.IMMER -> true
        Betrieb.ZEITPLAN -> sp.fenster.any { drin(it, jetzt) }
    }

    fun drin(f: Fenster, jetzt: LocalDateTime): Boolean {
        val tag = jetzt.dayOfWeek.value
        val gestern = if (tag == 1) 7 else tag - 1
        val m = jetzt.hour * 60 + jetzt.minute
        return if (f.von <= f.bis) {
            tag in f.tage && m >= f.von && m < f.bis
        } else {
            (tag in f.tage && m >= f.von) || (gestern in f.tage && m < f.bis)
        }
    }

    /** Nächster Zeitpunkt, an dem sich soll() ändern kann. */
    private fun naechsteGrenze(sp: Speicher, jetzt: LocalDateTime): LocalDateTime? {
        if (sp.betrieb != Betrieb.ZEITPLAN || sp.fenster.isEmpty()) return null
        val basis = jetzt.withSecond(0).withNano(0)
        val punkte = sp.fenster.flatMap { listOf(it.von, it.bis) }.toSet()
        for (tag in 0..7) {
            val d = basis.toLocalDate().plusDays(tag.toLong())
            val kandidaten = punkte.map { d.atTime(it / 60, it % 60) }.filter { it.isAfter(jetzt) }.sorted()
            if (kandidaten.isNotEmpty()) return kandidaten.first()
        }
        return null
    }

    /** Hauptarbeit: im Wecker, beim Start und nach jeder Änderung in der App. */
    /** Zuhause aus gilt nur, wenn eine Wohnung gespeichert ist und Spotr ohnehin steuert. */
    fun zuhauseZaehlt(sp: Speicher) = sp.zuhauseAktiv && sp.zuhauseLat != null && sp.betrieb != Betrieb.MANUELL

    fun pruefen(ctx: Context, grund: String) {
        val sp = Speicher(ctx)
        val grundSoll = soll(sp)
        var soll = grundSoll
        var warum = grund
        if (grundSoll == true && zuhauseZaehlt(sp)) {
            val vorher = sp.zuhauseZuletzt
            val z = Ort.zuhause(ctx, sp)
            if (z != null && z != vorher) sp.notieren(if (z) "zuhause angekommen" else "von zuhause weg")
            if (z ?: vorher) { soll = false; warum = "zuhause" }
        }
        if (soll != null) {
            val ist = Hotspot.istAn(ctx)
            if (soll != ist) {
                val fehler = Hotspot.setzen(ctx, soll)
                val was = if (soll) "eingeschaltet" else "ausgeschaltet"
                sp.notieren(if (fehler == null) "$was ($warum)" else "nicht $was: $fehler ($warum)")
            }
        }
        // Zuhause muss der Wächter weiter nachsehen, sonst merkt er das Weggehen nicht.
        planen(ctx, sp, if (grundSoll == true) true else soll)
    }

    private fun planen(ctx: Context, sp: Speicher, soll: Boolean?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, Wecker::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val jetzt = LocalDateTime.now()
        val ziele = mutableListOf<LocalDateTime>()
        naechsteGrenze(sp, jetzt)?.let { ziele += it }
        if (soll == true) ziele += jetzt.plusMinutes(sp.waechter.toLong())
        val ziel = ziele.minOrNull()
        if (ziel == null) {
            am.cancel(pi)
            return
        }
        val ms = ziel.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }
}

class Wecker : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val r = goAsync()
        Thread {
            try { Planer.pruefen(ctx, "Zeitplan") } finally { r.finish() }
        }.start()
    }
}

class Systemstart : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val r = goAsync()
        Thread {
            try {
                // Nach dem Hochfahren braucht das Mobilfunknetz einen Moment.
                if (intent.action?.endsWith("BOOT_COMPLETED") == true) Thread.sleep(20_000)
                Planer.pruefen(ctx, "Gerätestart")
            } finally { r.finish() }
        }.start()
    }
}

class Steuerung : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val r = goAsync()
        Thread {
            try {
                val sp = Speicher(ctx)
                intent.getStringExtra("betrieb")?.let { b ->
                    runCatching { Betrieb.valueOf(b.uppercase()) }.getOrNull()?.let { sp.betrieb = it; sp.notieren("Betrieb: ${it.name.lowercase()} (adb)") }
                }
                if (intent.getBooleanExtra("zuhause_hier", false)) {
                    val l = Ort.aktuell(ctx)
                    if (l != null) {
                        sp.zuhauseLat = l.latitude; sp.zuhauseLon = l.longitude; sp.zuhauseAktiv = true
                        sp.notieren("Zuhause gespeichert (± ${l.accuracy.toInt()} m, adb)")
                    } else {
                        sp.notieren("Zuhause nicht gespeichert: kein Standort (adb)")
                    }
                }
                if (intent.hasExtra("zuhause")) sp.zuhauseAktiv = intent.getBooleanExtra("zuhause", true)
                if (intent.hasExtra("an")) {
                    val an = intent.getBooleanExtra("an", true)
                    val fehler = Hotspot.setzen(ctx, an)
                    sp.notieren(if (fehler == null) (if (an) "eingeschaltet (adb)" else "ausgeschaltet (adb)") else "adb: $fehler")
                    r.resultData = fehler ?: "ok"
                }
                Planer.pruefen(ctx, "adb")
                val an = Hotspot.istAn(ctx)
                r.resultCode = if (an) 1 else 0
                if (!intent.hasExtra("an")) {
                    r.resultData = (if (an) "an" else "aus") + (if (Planer.zuhauseZaehlt(sp)) ", ${sp.zuhauseStand}" else "")
                }
            } finally { r.finish() }
        }.start()
    }
}
