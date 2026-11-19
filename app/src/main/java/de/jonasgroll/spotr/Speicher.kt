package de.jonasgroll.spotr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class Betrieb { MANUELL, IMMER, ZEITPLAN }

/** Ein Zeitfenster: an diesen Wochentagen von bis. Endet es vor dem Anfang, läuft es über Mitternacht. */
data class Fenster(val tage: Set<Int>, val von: Int, val bis: Int) {
    fun text(): String {
        val namen = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        val t = when (tage) {
            (1..7).toSet() -> "Täglich"
            (1..5).toSet() -> "Mo bis Fr"
            setOf(6, 7) -> "Sa und So"
            else -> tage.sorted().joinToString(", ") { namen[it - 1] }
        }
        return "$t, ${uhr(von)} bis ${uhr(bis)}"
    }
}

fun uhr(min: Int) = "%02d:%02d".format(min / 60, min % 60)

class Speicher(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("spotr", Context.MODE_PRIVATE)

    var betrieb: Betrieb
        get() = runCatching { Betrieb.valueOf(p.getString("betrieb", "MANUELL")!!) }.getOrDefault(Betrieb.MANUELL)
        set(v) = p.edit().putString("betrieb", v.name).apply()

    var fenster: List<Fenster>
        get() {
            val a = JSONArray(p.getString("fenster", "[]"))
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val t = o.getJSONArray("tage")
                Fenster((0 until t.length()).map { t.getInt(it) }.toSet(), o.getInt("von"), o.getInt("bis"))
            }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { f -> a.put(JSONObject().put("tage", JSONArray(f.tage.sorted())).put("von", f.von).put("bis", f.bis)) }
            p.edit().putString("fenster", a.toString()).apply()
        }

    /** Wie oft der Wächter nachsieht, ob der Hotspot noch läuft, in Minuten. */
    var waechter: Int
        get() = p.getInt("waechter", 5)
        set(v) = p.edit().putInt("waechter", v).apply()

    var zuhauseAktiv: Boolean
        get() = p.getBoolean("zuhause_aktiv", false)
        set(v) = p.edit().putBoolean("zuhause_aktiv", v).apply()

    var zuhauseLat: Double?
        get() = p.getString("zuhause_lat", null)?.toDoubleOrNull()
        set(v) = p.edit().putString("zuhause_lat", v?.toString()).apply()

    var zuhauseLon: Double?
        get() = p.getString("zuhause_lon", null)?.toDoubleOrNull()
        set(v) = p.edit().putString("zuhause_lon", v?.toString()).apply()

    /** Umkreis um die Wohnung in Metern. */
    var zuhauseRadius: Int
        get() = p.getInt("zuhause_radius", 200)
        set(v) = p.edit().putInt("zuhause_radius", v).apply()

    var zuhauseZuletzt: Boolean
        get() = p.getBoolean("zuhause_zuletzt", false)
        set(v) = p.edit().putBoolean("zuhause_zuletzt", v).apply()

    var zuhauseStand: String
        get() = p.getString("zuhause_stand", "Noch nicht gemessen")!!
        set(v) = p.edit().putString("zuhause_stand", v).apply()

    val protokoll: List<String> get() = p.getString("protokoll", "")!!.lines().filter { it.isNotBlank() }

    fun notieren(text: String) {
        val zeit = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))
        val neu = (listOf("$zeit  $text") + protokoll).take(60)
        p.edit().putString("protokoll", neu.joinToString("\n")).apply()
    }
}
