package de.jonasgroll.spotr

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.WifiTetheringOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dunkel = androidx.compose.foundation.isSystemInDarkTheme()
            val farben = if (dunkel) {
                darkColorScheme(primary = Color(0xFFFF9F43), onPrimary = Color.Black, background = Color(0xFF111111), surface = Color(0xFF111111))
            } else {
                lightColorScheme(primary = Color(0xFFD9740A), onPrimary = Color.White)
            }
            MaterialTheme(colorScheme = farben) { Oberflaeche() }
        }
    }
}

private fun mb(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.2f GB".format(bytes / 1073741824.0) else "%.0f MB".format(bytes / 1048576.0)

private fun verbrauch(ctx: Context, ab: LocalDate): Long? = try {
    val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
    val start = ab.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val b = nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, System.currentTimeMillis())
    b.rxBytes + b.txBytes
} catch (e: Exception) {
    null
}

private fun darfNutzung(ctx: Context): Boolean {
    val ao = ctx.getSystemService(AppOpsManager::class.java)
    return ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
}

private val WAECHTER = listOf(1, 5, 10, 15, 30)

/** Eine Zeile der Einstellungsliste: Titel, Unterzeile, rechts ein Schalter oder Text. */
@Composable
private fun Zeile(titel: String, unter: String? = null, klick: (() -> Unit)? = null, rechts: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().then(if (klick != null) Modifier.clickable(onClick = klick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(titel, style = MaterialTheme.typography.bodyLarge)
            if (unter != null) Text(unter, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rechts()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Oberflaeche() {
    val ctx = LocalContext.current
    val sp = remember { Speicher(ctx) }
    val scope = rememberCoroutineScope()
    var an by remember { mutableStateOf(Hotspot.istAn(ctx)) }
    var usbAn by remember { mutableStateOf(Hotspot.usbAn(ctx)) }
    var usbRechner by remember { mutableStateOf(Hotspot.usbRechner(ctx)) }
    var betrieb by remember { mutableStateOf(sp.betrieb) }
    var fenster by remember { mutableStateOf(sp.fenster) }
    var protokoll by remember { mutableStateOf(sp.protokoll) }
    var zuhause by remember { mutableStateOf(sp.zuhauseAktiv) }
    var usbAuto by remember { mutableStateOf(sp.usbAuto) }
    var waechter by remember { mutableIntStateOf(sp.waechter) }
    var radius by remember { mutableIntStateOf(sp.zuhauseRadius) }
    var meldung by remember { mutableStateOf<String?>(null) }
    var arbeitet by remember { mutableStateOf(false) }
    var ortSucht by remember { mutableStateOf(false) }
    var neuesFenster by remember { mutableStateOf(false) }
    var zuhauseOffen by remember { mutableStateOf(false) }
    var alleEintraege by remember { mutableStateOf(false) }
    var rechte by remember { mutableIntStateOf(0) }
    var heute by remember { mutableStateOf<Long?>(null) }
    var monat by remember { mutableStateOf<Long?>(null) }
    val fragen = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { rechte++ }
    val ortRechte = arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)

    fun auffrischen() {
        an = Hotspot.istAn(ctx)
        usbAn = Hotspot.usbAn(ctx)
        usbRechner = Hotspot.usbRechner(ctx)
        protokoll = sp.protokoll
        heute = verbrauch(ctx, LocalDate.now())
        monat = verbrauch(ctx, LocalDate.now().withDayOfMonth(1))
        rechte++
    }

    fun anwenden() = scope.launch(Dispatchers.IO) {
        Planer.pruefen(ctx, "App")
        withContext(Dispatchers.Main) { auffrischen() }
    }

    fun schalten(ziel: Boolean, typ: Int) {
        arbeitet = true
        scope.launch(Dispatchers.IO) {
            val f = Hotspot.setzen(ctx, ziel, typ)
            val name = if (typ == Hotspot.USB) "USB-Tethering" else "Hotspot"
            sp.notieren(if (f == null) "$name ${if (ziel) "an" else "aus"} (App)" else "$name: $f")
            delay(1500)
            withContext(Dispatchers.Main) {
                meldung = f ?: if (typ == Hotspot.WLAN && betrieb != Betrieb.MANUELL && Planer.soll(sp) != ziel) {
                    "Der Betrieb stellt das beim nächsten Prüfen zurück"
                } else null
                arbeitet = false
                auffrischen()
            }
        }
    }

    val lebenszyklus = LocalLifecycleOwner.current
    DisposableEffect(lebenszyklus) {
        val b = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { auffrischen(); anwenden() } }
        lebenszyklus.lifecycle.addObserver(b)
        onDispose { lebenszyklus.lifecycle.removeObserver(b) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            an = Hotspot.istAn(ctx); usbAn = Hotspot.usbAn(ctx); usbRechner = Hotspot.usbRechner(ctx); protokoll = sp.protokoll
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Spotr", fontWeight = FontWeight.Bold) }) }) { innen ->
        LazyColumn(
            Modifier.padding(innen).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- Zustand ----------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = if (an) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(56.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (an) Icons.Filled.WifiTethering else Icons.Filled.WifiTetheringOff, null,
                                    tint = if (an) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (an) "Hotspot an" else "Hotspot aus", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            val soll = Planer.soll(sp)
                            val grund = when {
                                betrieb == Betrieb.MANUELL -> "Manuell"
                                soll == true && Planer.zuhauseZaehlt(sp) && sp.zuhauseZuletzt -> "Du bist zuhause"
                                betrieb == Betrieb.IMMER -> "Immer an"
                                soll == true -> "Laut Zeitplan an"
                                else -> "Laut Zeitplan aus"
                            }
                            Text(grund, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = an, enabled = !arbeitet, onCheckedChange = { schalten(it, Hotspot.WLAN) })
                    }
                    HorizontalDivider()
                    Zeile(
                        "USB-Tethering",
                        when {
                            usbAn -> "An"
                            usbRechner -> "Rechner verbunden"
                            else -> "Kein Rechner am Kabel"
                        },
                    ) {
                        Switch(checked = usbAn, enabled = (usbRechner || usbAn) && !arbeitet, onCheckedChange = { schalten(it, Hotspot.USB) })
                    }
                    if (heute != null) {
                        HorizontalDivider()
                        Zeile("Mobile Daten", "Heute ${mb(heute!!)}, diesen Monat ${mb(monat ?: 0)}")
                    }
                    meldung?.let {
                        Text(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---------- Fehlende Rechte ----------
            item {
                key(rechte) {
                    val am = ctx.getSystemService(AlarmManager::class.java)
                    val pm = ctx.getSystemService(PowerManager::class.java)
                    val paket = Uri.parse("package:${ctx.packageName}")
                    val fehlt = listOfNotNull(
                        if (!Hotspot.darfSchalten(ctx)) "Systemeinstellungen ändern" to Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, paket) else null,
                        if (!am.canScheduleExactAlarms()) "Wecker und Erinnerungen" to Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, paket) else null,
                        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) "Akku nicht optimieren" to Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, paket) else null,
                        if (!darfNutzung(ctx)) "Nutzungsdaten (für den Verbrauch)" to Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) else null,
                        if (zuhause && !Ort.eingeschaltet(ctx)) "Standort einschalten" to Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS) else null,
                        if (zuhause && Ort.darf(ctx) && !Ort.darfImHintergrund(ctx)) "Standort: „Immer zulassen“" to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, paket) else null,
                    )
                    if (fehlt.isNotEmpty()) {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text("Spotr braucht noch", Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.SemiBold)
                                fehlt.forEach { (name, intent) -> Zeile(name, klick = { ctx.startActivity(intent) }) }
                            }
                        }
                    }
                }
            }

            // ---------- Betrieb ----------
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val namen = listOf(Betrieb.MANUELL to "Manuell", Betrieb.IMMER to "Immer an", Betrieb.ZEITPLAN to "Zeitplan")
                    namen.forEachIndexed { i, (b, n) ->
                        SegmentedButton(
                            selected = betrieb == b,
                            onClick = { betrieb = b; sp.betrieb = b; sp.notieren("Betrieb: $n"); anwenden() },
                            shape = SegmentedButtonDefaults.itemShape(i, namen.size),
                        ) { Text(n) }
                    }
                }
            }

            // ---------- Zeitplan ----------
            if (betrieb == Betrieb.ZEITPLAN) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        if (fenster.isEmpty()) {
                            Zeile("Noch kein Zeitfenster", "Außerhalb aller Fenster bleibt der Hotspot aus")
                        }
                        fenster.forEach { f ->
                            Zeile(f.text()) {
                                IconButton(onClick = {
                                    fenster = fenster - f; sp.fenster = fenster; sp.notieren("Zeitfenster gelöscht: ${f.text()}"); anwenden()
                                }) { Icon(Icons.Filled.Close, "Löschen") }
                            }
                            HorizontalDivider()
                        }
                        TextButton(onClick = { neuesFenster = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Zeitfenster")
                        }
                    }
                }
            }

            // ---------- Einstellungen ----------
            item {
                Card(Modifier.fillMaxWidth()) {
                    if (betrieb != Betrieb.MANUELL) {
                        Zeile(
                            "Zuhause aus",
                            if (!zuhause) "Daheim bleibt der Hotspot aus" else if (sp.zuhauseLat == null) "Noch kein Ort gespeichert" else sp.zuhauseStand,
                            klick = { if (zuhause) zuhauseOffen = !zuhauseOffen },
                        ) {
                            Switch(checked = zuhause, onCheckedChange = {
                                zuhause = it; sp.zuhauseAktiv = it; zuhauseOffen = it
                                sp.notieren("Zuhause aus: ${if (it) "an" else "aus"}")
                                if (it && !Ort.darf(ctx)) fragen.launch(ortRechte)
                                anwenden()
                            })
                        }
                        if (zuhause && zuhauseOffen) {
                            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                                OutlinedButton(enabled = !ortSucht, onClick = {
                                    if (!Ort.darf(ctx)) { fragen.launch(ortRechte); return@OutlinedButton }
                                    ortSucht = true
                                    scope.launch(Dispatchers.IO) {
                                        val l = Ort.aktuell(ctx)
                                        if (l != null) {
                                            sp.zuhauseLat = l.latitude; sp.zuhauseLon = l.longitude; sp.zuhauseZuletzt = true
                                            sp.notieren("Zuhause gespeichert (± ${l.accuracy.toInt()} m)")
                                        }
                                        withContext(Dispatchers.Main) {
                                            ortSucht = false
                                            meldung = if (l == null) "Kein Standort. Ist der Standort eingeschaltet?" else null
                                            anwenden()
                                        }
                                    }
                                }) { Text(if (ortSucht) "Suche Standort …" else "Ich bin jetzt zuhause") }
                                Spacer(Modifier.height(8.dp))
                                Text("Umkreis", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(100, 200, 500, 1000).forEach { r ->
                                        FilterChip(selected = radius == r, onClick = { radius = r; sp.zuhauseRadius = r; anwenden() }, label = { Text("$r m") })
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                    Zeile("USB automatisch", "Tethering, sobald ein Rechner am Kabel hängt") {
                        Switch(checked = usbAuto, onCheckedChange = {
                            usbAuto = it; sp.usbAuto = it
                            sp.notieren("USB automatisch: ${if (it) "an" else "aus"}")
                            if (it && android.os.Build.VERSION.SDK_INT >= 33) fragen.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
                            UsbDienst.abgleichen(ctx)
                        })
                    }
                    if (betrieb != Betrieb.MANUELL) {
                        HorizontalDivider()
                        Zeile("Prüfen alle $waechter Minuten", "Antippen zum Ändern", klick = {
                            waechter = WAECHTER[(WAECHTER.indexOf(waechter) + 1) % WAECHTER.size]
                            sp.waechter = waechter; anwenden()
                        })
                    }
                }
            }

            // ---------- Protokoll ----------
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Protokoll", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (protokoll.size > 5) TextButton(onClick = { alleEintraege = !alleEintraege }) { Text(if (alleEintraege) "Weniger" else "Alle") }
                }
            }
            items(if (alleEintraege) protokoll else protokoll.take(5)) { z ->
                Text(z, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (neuesFenster) {
        FensterDialog(
            schliessen = { neuesFenster = false },
            speichern = { f ->
                fenster = fenster + f; sp.fenster = fenster; sp.notieren("Zeitfenster neu: ${f.text()}")
                neuesFenster = false; anwenden()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FensterDialog(schliessen: () -> Unit, speichern: (Fenster) -> Unit) {
    var tage by remember { mutableStateOf((1..7).toSet()) }
    val von = rememberTimePickerState(7, 0, is24Hour = true)
    val bis = rememberTimePickerState(22, 0, is24Hour = true)
    var seite by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = schliessen,
        title = { Text("Zeitfenster") },
        text = {
            Column {
                val namen = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    namen.forEachIndexed { i, n ->
                        FilterChip(
                            selected = (i + 1) in tage,
                            onClick = { tage = if ((i + 1) in tage) tage - (i + 1) else tage + (i + 1) },
                            label = { Text(n) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(seite == 0, { seite = 0 }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Von ${uhr(von.hour * 60 + von.minute)}") }
                    SegmentedButton(seite == 1, { seite = 1 }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Bis ${uhr(bis.hour * 60 + bis.minute)}") }
                }
                Spacer(Modifier.height(8.dp))
                TimeInput(if (seite == 0) von else bis)
            }
        },
        confirmButton = {
            TextButton(enabled = tage.isNotEmpty(), onClick = {
                speichern(Fenster(tage, von.hour * 60 + von.minute, bis.hour * 60 + bis.minute))
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = schliessen) { Text("Abbrechen") } },
    )
}
