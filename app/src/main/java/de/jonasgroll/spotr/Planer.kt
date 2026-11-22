package de.jonasgroll.spotr

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
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
        Betrieb.ZEITPLAN -> Regeln.drin(sp.fenster, jetzt)
    }

    private fun naechsteGrenze(sp: Speicher, jetzt: LocalDateTime): LocalDateTime? =
        if (sp.betrieb == Betrieb.ZEITPLAN) Regeln.naechsteGrenze(sp.fenster, jetzt) else null

    /** Zuhause aus gilt nur, wenn eine Wohnung gespeichert ist und Spotr ohnehin steuert. */
    fun zuhauseZaehlt(sp: Speicher) = sp.zuhauseAktiv && sp.zuhauseLat != null && sp.betrieb != Betrieb.MANUELL

    /** Hauptarbeit: im Wecker, beim Start und nach jeder Änderung in der App. Nie gleichzeitig. */
    @Synchronized
    fun pruefen(ctx: Context, grund: String) {
        val sp = Speicher(ctx)
        val grundSoll = soll(sp)
        try {
            schalten(ctx, sp, grund, grundSoll)
        } catch (e: Exception) {
            sp.notieren("Prüfung abgebrochen: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            // Der nächste Wecker steht, auch wenn das Schalten schiefging.
            planen(ctx, sp, grundSoll)
            Kachel.auffrischen(ctx)
        }
    }

    private fun schalten(ctx: Context, sp: Speicher, grund: String, grundSoll: Boolean?) {
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
        UsbDienst.abgleichen(ctx)
    }

    // grundSoll, nicht das Ergebnis: zuhause muss der Wächter weiter nachsehen,
    // sonst merkt er das Weggehen nicht.
    private fun planen(ctx: Context, sp: Speicher, grundSoll: Boolean?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, Wecker::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val jetzt = Instant.now()
        val zone = ZoneId.systemDefault()
        val ziel = Regeln.wecker(
            jetzt, zone, naechsteGrenze(sp, LocalDateTime.ofInstant(jetzt, zone)),
            if (grundSoll == true) sp.waechter.toLong() else null,
        )
        if (ziel == null) {
            am.cancel(pi)
            return
        }
        val ms = ziel.toEpochMilli()
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
    private val erwartet = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
    )

    override fun onReceive(ctx: Context, intent: Intent) {
        // Exportiert: gezielt schicken kann hierher jede App, nur diese Meldungen nicht.
        if (intent.action !in erwartet) return
        val r = goAsync()
        Thread {
            try {
                // Nach dem Hochfahren braucht das Mobilfunknetz einen Moment.
                if (intent.action?.endsWith("BOOT_COMPLETED") == true) Thread.sleep(20_000)
                Planer.pruefen(ctx, if (intent.action == Intent.ACTION_BOOT_COMPLETED) "Gerätestart" else "System")
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
                if (intent.hasExtra("usb_auto")) sp.usbAuto = intent.getBooleanExtra("usb_auto", true)
                if (intent.hasExtra("usb")) {
                    val u = intent.getBooleanExtra("usb", true)
                    val f = Hotspot.setzen(ctx, u, Hotspot.USB)
                    sp.notieren(if (f == null) (if (u) "USB-Tethering an (adb)" else "USB-Tethering aus (adb)") else "USB (adb): $f")
                    r.resultData = f ?: "ok"
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
                    r.resultData = (if (an) "an" else "aus") + (if (Planer.zuhauseZaehlt(sp)) ", ${sp.zuhauseStand}" else "") +
                        ", USB: " + (if (Hotspot.usbAn(ctx)) "an" else if (Hotspot.usbRechner(ctx)) "Rechner verbunden" else "kein Rechner")
                }
            } finally { r.finish() }
        }.start()
    }
}
