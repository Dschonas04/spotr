package de.jonasgroll.spotr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Wartet auf einen Rechner am USB-Kabel und schaltet dann USB-Tethering ein.
 * Die USB-Meldung des Systems kommt nur bei laufenden Empfängern an, deshalb
 * ein Vordergrunddienst mit stiller Benachrichtigung. Läuft nur, solange
 * „USB automatisch“ an ist.
 */
class UsbDienst : Service() {
    private var zuletztVerbunden = false

    private val empfaenger = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val rechner = i.getBooleanExtra("connected", false) && i.getBooleanExtra("configured", false)
            val an = i.getBooleanExtra("rndis", false) || i.getBooleanExtra("ncm", false)
            if (rechner && !zuletztVerbunden && !an) {
                val p = goAsync()
                Thread {
                    try { einschalten(ctx) } finally { p.finish() }
                }.start()
            }
            zuletztVerbunden = rechner
        }
    }

    private fun einschalten(ctx: Context) {
        val sp = Speicher(ctx)
        if (!sp.usbAuto) return
        if (sp.zuhauseAktiv && sp.zuhauseLat != null && sp.zuhauseZuletzt) {
            sp.notieren("USB-Rechner erkannt, zuhause: kein Tethering")
            return
        }
        // Das System braucht nach dem Einstecken einen Moment, bis USB eingerichtet ist.
        Thread.sleep(1500)
        val f = Hotspot.setzen(ctx, true, Hotspot.USB)
        sp.notieren(if (f == null) "USB-Tethering an (Kabel)" else "USB-Tethering nicht an: $f")
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(KANAL, "USB-Tethering", NotificationManager.IMPORTANCE_MIN))
        val oeffnen = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, KANAL)
            .setSmallIcon(R.drawable.ic_kachel)
            .setContentTitle("Spotr")
            .setContentText("USB-Tethering, sobald ein Rechner angeschlossen wird")
            .setContentIntent(oeffnen)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        // Haftende Meldung: der aktuelle Zustand kommt sofort, ein schon
        // steckender Rechner wird also auch erkannt.
        registerReceiver(empfaenger, IntentFilter(Hotspot.USB_STATE), RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(empfaenger) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val KANAL = "usb"

        /** Dienst starten oder beenden, je nach Einstellung. */
        fun abgleichen(ctx: Context) {
            val i = Intent(ctx, UsbDienst::class.java)
            if (Speicher(ctx).usbAuto) {
                runCatching { ctx.startForegroundService(i) }
                    .onFailure { Speicher(ctx).notieren("USB-Dienst startet nicht: ${it.message}") }
            } else {
                ctx.stopService(i)
            }
        }
    }
}
