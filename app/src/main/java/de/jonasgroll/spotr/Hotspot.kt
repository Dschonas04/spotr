package de.jonasgroll.spotr

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Schaltet den WLAN-Hotspot über den TetheringManager des Systems. Die Methoden
 * sind nicht öffentlich, Android erlaubt den Aufruf aber jeder App, die
 * „Systemeinstellungen ändern“ (WRITE_SETTINGS) darf. Darauf verlässt sich auch
 * die Kachel in den Schnelleinstellungen.
 */
object Hotspot {
    const val WLAN = 0
    const val USB = 1

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("")
    }

    fun darfSchalten(ctx: Context) = Settings.System.canWrite(ctx)

    var letzterFehler: String? = null

    /** 13 heißt WIFI_AP_STATE_ENABLED. Erst direkt fragen, sonst die haftende Systemmeldung lesen. */
    fun istAn(ctx: Context): Boolean {
        try {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            return (WifiManager::class.java.getMethod("getWifiApState").invoke(wm) as Int) == 13
        } catch (e: Exception) {
            letzterFehler = (e as? java.lang.reflect.InvocationTargetException)?.targetException?.toString() ?: e.toString()
        }
        return try {
            val i = ctx.applicationContext.registerReceiver(null, android.content.IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"), Context.RECEIVER_NOT_EXPORTED)
            i?.getIntExtra("wifi_state", 11) == 13
        } catch (e: Exception) {
            false
        }
    }

    /** Ergebnis: null = erledigt, sonst die Fehlermeldung. Wartet höchstens 15 s. */
    fun setzen(ctx: Context, an: Boolean, typ: Int = WLAN): String? {
        if (!darfSchalten(ctx)) return "Spotr darf die Systemeinstellungen nicht ändern"
        val tm = ctx.applicationContext.getSystemService("tethering")
            ?: return "Tethering wird nicht unterstützt"
        val klasse = Class.forName("android.net.TetheringManager")
        return try {
            if (!an) {
                klasse.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, typ)
                return null
            }
            val rueckruf = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val fertig = CountDownLatch(1)
            var fehler: String? = "Keine Antwort vom System"
            val proxy = Proxy.newProxyInstance(rueckruf.classLoader, arrayOf(rueckruf)) { p, m, args ->
                when (m.name) {
                    "onTetheringStarted" -> { fehler = null; fertig.countDown() }
                    "onTetheringFailed" -> { fehler = fehlertext(args?.getOrNull(0) as? Int ?: -1); fertig.countDown() }
                    "hashCode" -> return@newProxyInstance System.identityHashCode(p)
                    "equals" -> return@newProxyInstance p === args?.getOrNull(0)
                    "toString" -> return@newProxyInstance "Spotr-Rueckruf"
                }
                null
            }
            val sofort = Executor { it.run() }
            klasse.getMethod("startTethering", Int::class.javaPrimitiveType, Executor::class.java, rueckruf)
                .invoke(tm, typ, sofort, proxy)
            fertig.await(15, TimeUnit.SECONDS)
            fehler
        } catch (e: java.lang.reflect.InvocationTargetException) {
            e.targetException?.message ?: e.toString()
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    /** Haftende Systemmeldung zum USB-Anschluss. */
    private fun usbZustand(ctx: Context) = try {
        ctx.applicationContext.registerReceiver(null, android.content.IntentFilter(USB_STATE), Context.RECEIVER_NOT_EXPORTED)
    } catch (e: Exception) {
        null
    }

    const val USB_STATE = "android.hardware.usb.action.USB_STATE"

    /** Ein Rechner (nicht nur ein Ladegerät) hängt am USB-Anschluss. */
    fun usbRechner(ctx: Context): Boolean {
        val i = usbZustand(ctx) ?: return false
        return i.getBooleanExtra("connected", false) && i.getBooleanExtra("configured", false)
    }

    fun usbAn(ctx: Context): Boolean {
        val i = usbZustand(ctx) ?: return false
        return i.getBooleanExtra("rndis", false) || i.getBooleanExtra("ncm", false)
    }

    private fun fehlertext(code: Int) = when (code) {
        1 -> "Unbekannte Schnittstelle"
        3 -> "Nicht verfügbar"
        4 -> "Tethering nicht unterstützt"
        5 -> "Fehler am Gerät"
        8 -> "Netzwerkfehler"
        11 -> "Netz des Anbieters erlaubt keinen Hotspot"
        12 -> "Mobilfunk-Prüfung fehlgeschlagen"
        14 -> "Keine Berechtigung"
        else -> "Fehler $code"
    }
}
