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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Oberflaeche() {
    val ctx = LocalContext.current
    val sp = remember { Speicher(ctx) }
    val scope = rememberCoroutineScope()
    var an by remember { mutableStateOf(Hotspot.istAn(ctx)) }
    var betrieb by remember { mutableStateOf(sp.betrieb) }
    var fenster by remember { mutableStateOf(sp.fenster) }
    var protokoll by remember { mutableStateOf(sp.protokoll) }
    var meldung by remember { mutableStateOf<String?>(null) }
    var arbeitet by remember { mutableStateOf(false) }
    var neuesFenster by remember { mutableStateOf(false) }
    var rechte by remember { mutableStateOf(0) }
    var heute by remember { mutableStateOf<Long?>(null) }
    var monat by remember { mutableStateOf<Long?>(null) }

    fun auffrischen() {
        an = Hotspot.istAn(ctx)
        protokoll = sp.protokoll
        heute = verbrauch(ctx, LocalDate.now())
        monat = verbrauch(ctx, LocalDate.now().withDayOfMonth(1))
        rechte++
    }

    fun anwenden() = scope.launch(Dispatchers.IO) {
        Planer.pruefen(ctx, "App")
        withContext(Dispatchers.Main) { auffrischen() }
    }

    val lebenszyklus = LocalLifecycleOwner.current
    DisposableEffect(lebenszyklus) {
        val b = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { auffrischen(); anwenden() } }
        lebenszyklus.lifecycle.addObserver(b)
        onDispose { lebenszyklus.lifecycle.removeObserver(b) }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(3000); an = Hotspot.istAn(ctx) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Spotr", fontWeight = FontWeight.Bold) }) }) { innen ->
        LazyColumn(
            Modifier.padding(innen).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------- Zustand ----------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = CircleShape,
                            color = if (an) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(96.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (an) Icons.Filled.WifiTethering else Icons.Filled.WifiTetheringOff, null,
                                    Modifier.size(52.dp),
                                    tint = if (an) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(if (an) "Hotspot läuft" else "Hotspot aus", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        val soll = Planer.soll(sp)
                        Text(
                            when (betrieb) {
                                Betrieb.MANUELL -> "Manuell"
                                Betrieb.IMMER -> "Dauerbetrieb, der Wächter schaltet ihn wieder ein"
                                Betrieb.ZEITPLAN -> if (soll == true) "Laut Zeitplan an" else "Laut Zeitplan aus"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            enabled = !arbeitet,
                            onClick = {
                                arbeitet = true
                                val ziel = !an
                                scope.launch(Dispatchers.IO) {
                                    val f = Hotspot.setzen(ctx, ziel)
                                    sp.notieren(if (f == null) (if (ziel) "eingeschaltet (App)" else "ausgeschaltet (App)") else "App: $f")
                                    delay(1200)
                                    withContext(Dispatchers.Main) {
                                        meldung = f
                                        if (f == null && betrieb != Betrieb.MANUELL && Planer.soll(sp) != ziel) {
                                            meldung = "Hinweis: Der Betrieb „${betrieb.name.lowercase()}“ stellt das beim nächsten Prüfen zurück."
                                        }
                                        arbeitet = false
                                        auffrischen()
                                    }
                                }
                            },
                        ) { Text(if (an) "Ausschalten" else "Einschalten") }
                        meldung?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // ---------- Rechte ----------
            item {
                key(rechte) {
                    val am = ctx.getSystemService(AlarmManager::class.java)
                    val pm = ctx.getSystemService(PowerManager::class.java)
                    val fehlt = listOfNotNull(
                        if (!Hotspot.darfSchalten(ctx)) "Systemeinstellungen ändern" to Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")) else null,
                        if (!am.canScheduleExactAlarms()) "Wecker und Erinnerungen" to Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")) else null,
                        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) "Akku nicht optimieren" to Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")) else null,
                        if (!darfNutzung(ctx)) "Nutzungsdaten (für den Verbrauch)" to Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) else null,
                    )
                    if (fehlt.isNotEmpty()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                                Text("Spotr braucht noch", fontWeight = FontWeight.SemiBold)
                                fehlt.forEach { (name, intent) ->
                                    TextButton(onClick = { ctx.startActivity(intent) }) { Text("→ $name") }
                                }
                            }
                        }
                    }
                }
            }

            // ---------- Betrieb ----------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Betrieb", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
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
                        if (betrieb != Betrieb.MANUELL) {
                            Spacer(Modifier.height(12.dp))
                            Text("Wächter prüft alle ${sp.waechter} Minuten", style = MaterialTheme.typography.bodySmall)
                            var w by remember { mutableFloatStateOf(sp.waechter.toFloat()) }
                            Slider(
                                value = w, valueRange = 1f..30f, steps = 28,
                                onValueChange = { w = it },
                                onValueChangeFinished = { sp.waechter = w.toInt(); anwenden() },
                            )
                        }
                    }
                }
            }

            // ---------- Zeitplan ----------
            if (betrieb == Betrieb.ZEITPLAN) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Zeitplan", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = { neuesFenster = true }) {
                            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Zeitfenster")
                        }
                    }
                    if (fenster.isEmpty()) {
                        Text("Noch kein Zeitfenster. Außerhalb aller Fenster bleibt der Hotspot aus.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(fenster) { f ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.text(), Modifier.weight(1f))
                            IconButton(onClick = {
                                fenster = fenster - f; sp.fenster = fenster; sp.notieren("Zeitfenster gelöscht: ${f.text()}"); anwenden()
                            }) { Icon(Icons.Filled.Delete, "Löschen") }
                        }
                    }
                }
            }

            // ---------- Verbrauch ----------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Mobile Daten", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        if (heute == null) {
                            Text("Ohne „Nutzungsdaten“ nicht verfügbar", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Row {
                                Column(Modifier.weight(1f)) { Text("Heute", style = MaterialTheme.typography.bodySmall); Text(mb(heute!!), fontSize = 20.sp) }
                                Column(Modifier.weight(1f)) { Text("Diesen Monat", style = MaterialTheme.typography.bodySmall); Text(mb(monat ?: 0), fontSize = 20.sp) }
                            }
                        }
                    }
                }
            }

            // ---------- Protokoll ----------
            item { Text("Protokoll", fontWeight = FontWeight.SemiBold) }
            items(protokoll.take(30)) { z ->
                Text(z, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
