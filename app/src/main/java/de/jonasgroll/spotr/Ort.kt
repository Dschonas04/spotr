package de.jonasgroll.spotr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * „Zuhause aus“: Liegt das Handy im Umkreis der gespeicherten Wohnung, bleibt
 * der Hotspot aus. Gemessen wird sparsam (Funkzellen und WLAN in der Nähe, kein
 * Dauer-GPS), und nur dann, wenn der Wächter ohnehin nachsieht.
 */
object Ort {
    fun darf(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun darfImHintergrund(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun eingeschaltet(ctx: Context) = ctx.getSystemService(LocationManager::class.java).isLocationEnabled

    /** Aktueller Ort oder null (Standort aus, keine Berechtigung, kein Ergebnis in 30 s). */
    fun aktuell(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (!lm.isLocationEnabled || !darf(ctx)) return null
        val anbieter = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { lm.hasProvider(it) && lm.isProviderEnabled(it) } ?: return null
        val fertig = CountDownLatch(1)
        var ergebnis: Location? = null
        val abbruch = CancellationSignal()
        val ausfuehrer = Executors.newSingleThreadExecutor()
        try {
            lm.getCurrentLocation(anbieter, abbruch, ausfuehrer) { ergebnis = it; fertig.countDown() }
            if (!fertig.await(30, TimeUnit.SECONDS)) abbruch.cancel()
        } catch (e: SecurityException) {
            return null
        } finally {
            ausfuehrer.shutdown()
        }
        if (ergebnis != null) return ergebnis
        val alt = runCatching { lm.getLastKnownLocation(anbieter) }.getOrNull() ?: return null
        val alterMs = (android.os.SystemClock.elapsedRealtimeNanos() - alt.elapsedRealtimeNanos) / 1_000_000
        return alt.takeIf { alterMs < 15 * 60_000 }
    }

    /**
     * true = zuhause, false = unterwegs, null = unklar (dann ändert sich nichts).
     * Mit Abstand: zuhause innerhalb des Umkreises, unterwegs erst ab dem
     * doppelten Umkreis, dazwischen bleibt der letzte Stand.
     */
    fun zuhause(ctx: Context, sp: Speicher): Boolean? {
        val lat = sp.zuhauseLat ?: return null
        val lon = sp.zuhauseLon ?: return null
        val l = aktuell(ctx) ?: return null.also { sp.zuhauseStand = "Standort nicht verfügbar" }
        if (l.accuracy > 1500) return null.also { sp.zuhauseStand = "Standort zu ungenau (${l.accuracy.toInt()} m)" }
        val abstand = FloatArray(1).also { Location.distanceBetween(lat, lon, l.latitude, l.longitude, it) }[0]
        val r = sp.zuhauseRadius.toFloat()
        val z = when {
            abstand <= maxOf(r, l.accuracy) -> true
            abstand > 2 * r + l.accuracy -> false
            else -> sp.zuhauseZuletzt
        }
        sp.zuhauseZuletzt = z
        sp.zuhauseStand = (if (z) "Zuhause" else "Unterwegs") + ", ${abstand.toInt()} m entfernt (± ${l.accuracy.toInt()} m)"
        return z
    }
}
