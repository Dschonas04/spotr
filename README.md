# Spotr

Android-App, die das Handy zum reinen Hotspot macht. Läuft komplett auf dem Gerät, ohne Server, ohne Konto, ohne Internetzugriff der App selbst.

- Hotspot an/aus per Knopf oder Kachel in den Schnelleinstellungen
- Betrieb: **Manuell**, **Immer an** (Wächter schaltet ihn wieder ein) oder **Zeitplan** (Zeitfenster je Wochentag, auch über Mitternacht)
- Startet nach dem Hochfahren von selbst
- Mobile Daten heute und im Monat, Protokoll der Schaltvorgänge

## Bauen und installieren

    ./bauen.sh                      # APK nach ausgabe/spotr-debug.apk (Bau im Container auf 10.0.2.7)
    adb install -r ausgabe/spotr-debug.apk

Rechte einmalig (alternativ in der App antippen):

    P=de.jonasgroll.spotr
    adb shell appops set $P WRITE_SETTINGS allow
    adb shell appops set $P SCHEDULE_EXACT_ALARM allow
    adb shell appops set $P GET_USAGE_STATS allow
    adb shell dumpsys deviceidle whitelist +$P

## Steuerung per adb

    adb shell am broadcast -f 0x20 -n de.jonasgroll.spotr/.Steuerung --ez an true    # einschalten
    adb shell am broadcast -f 0x20 -n de.jonasgroll.spotr/.Steuerung --ez an false   # ausschalten
    adb shell am broadcast -f 0x20 -n de.jonasgroll.spotr/.Steuerung --es betrieb immer   # manuell|immer|zeitplan
    adb shell am broadcast -f 0x20 -n de.jonasgroll.spotr/.Steuerung                 # Zustand: result=1 an, 0 aus

Der Empfänger verlangt die Berechtigung DUMP, die nur System und adb-Shell haben.

## Wie das Schalten geht

Android lässt Apps mit „Systemeinstellungen ändern“ (WRITE_SETTINGS) das Tethering über den TetheringManager starten. Die Methoden sind nicht öffentlich, Spotr ruft sie per Reflection auf (HiddenApiBypass). Getestet auf Galaxy S21, Android 15 / One UI 7.
