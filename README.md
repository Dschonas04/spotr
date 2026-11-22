<p align="center"><img src="docs/logo.svg" width="76" alt=""></p>
<h1 align="center">Spotr</h1>
<p align="center">Macht ein Android-Handy zum zuverlässigen Hotspot. Ohne Server, ohne Konto, ohne Internetzugriff der App.</p>

## Was es kann

| Bereich | Funktionen |
|---|---|
| Schalten | per Knopf, per Kachel in den Schnelleinstellungen, per adb |
| Betrieb | **Manuell**, **Immer an** mit Wächter oder **Zeitplan** je Wochentag, auch über Mitternacht |
| Zuhause aus | im Umkreis der Wohnung bleibt der Hotspot aus, unterwegs geht er an |
| USB-Tethering | per Knopf oder von selbst, sobald ein Rechner am Kabel hängt |
| Überblick | mobile Daten heute und im Monat, Protokoll der Schaltvorgänge |

Spotr startet nach dem Hochfahren von selbst. Der Standort kommt sparsam aus
Funkzellen und WLAN-Suche und bleibt auf dem Gerät: die App hat keine
Internet-Berechtigung.

## Bauen und installieren

Mit JDK 17 und Android SDK 36:

```bash
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Oder im Container auf einem Docker-Host: `BAUHOST=benutzer@host ./bauen.sh`
(braucht dort ein Abbild mit Android SDK namens `nexora-android:1`).

Rechte einmalig, alternativ in der App antippen:

```bash
P=de.jonasgroll.spotr
adb shell appops set $P WRITE_SETTINGS allow
adb shell appops set $P SCHEDULE_EXACT_ALARM allow
adb shell appops set $P GET_USAGE_STATS allow
adb shell dumpsys deviceidle whitelist +$P
```

## Steuern per adb

```bash
Z="adb shell am broadcast -f 0x20 -n de.jonasgroll.spotr/.Steuerung"
$Z                              # Zustand: result=1 an, 0 aus
$Z --ez an true                 # einschalten, false schaltet aus
$Z --es betrieb immer           # manuell | immer | zeitplan
$Z --ez zuhause_hier true       # jetziger Ort ist Zuhause
$Z --ez zuhause false           # "Zuhause aus" abschalten
$Z --ez usb true                # USB-Tethering an
$Z --ez usb_auto true           # USB von selbst beim Einstecken
```

Der Empfänger verlangt die Berechtigung DUMP, die nur System und adb-Shell haben.

## Wie das Schalten geht

Apps mit "Systemeinstellungen ändern" (WRITE_SETTINGS) dürfen Tethering über den
TetheringManager starten. Die Methoden sind nicht öffentlich, Spotr ruft sie per
Reflection auf (HiddenApiBypass).

## Grenzen

* Getestet auf einem Galaxy S21 mit Android 15 und One UI 7, andere Geräte können anders schalten.
* Das S21 kann über USB nur RNDIS: Windows und Linux ja, macOS nein.
* Nicht für den Play Store: der nutzt keine versteckten Schnittstellen.

## Lizenz

MIT, siehe [LICENSE](LICENSE).
