# Volume-Rail

Kleine Android-App (ab Android 8.0) für feinere Lautstärkestufen, gedacht für das Sonoff NSPanel Pro.

## Was sie macht

- **Lautstärketasten abfangen:** Jeder Druck auf Lauter/Leiser ändert nur um eine Feinstufe.
- **Große Sprünge verkleinern:** Springt die Lautstärke um mehrere Stufen (z. B. über die +/- Tasten im Panel-Menü), setzt Volume-Rail sie sofort auf *eine* Feinstufe in dieselbe Richtung zurück. Wer am Schieberegler zieht oder weit springt (Grenze einstellbar), wird nicht gebremst.
- **Rail** (optional): + / − Tasten am Bildschirmrand, gedrückt halten wiederholt.
- **Zwischenstufen** (experimentell): teilt jede Systemstufe in 2–5 Teile, indem der Ton zusätzlich über einen globalen Equalizer leicht abgesenkt wird. Nur nötig, wenn schon eine einzelne Systemstufe zu grob ist.
- Wählbar für Medien, Wecker, Benachrichtigungen oder Klingelton.

Die App läuft als Bedienungshilfe-Dienst, startet also nach einem Neustart automatisch wieder. Sie liest keine Bildschirminhalte.

## APK bauen

Bei jedem Push auf `main` baut GitHub Actions die APK (unter *Actions → letzter Lauf → Artifacts*).
Mit einem Tag `v1.0` usw. landet sie zusätzlich als Release-Datei `Volume-Rail.apk` im Repo.

Die APK ist mit einem festen Schlüssel (`app/volumerail.keystore`) signiert, damit sich neue Versionen über die alte installieren lassen.

## Installieren auf dem NSPanel Pro

```
adb connect <IP-des-Panels>:5555
adb install -r Volume-Rail.apk
```

Danach den Dienst einschalten – entweder in der App über **Bedienungshilfen öffnen**, oder per ADB (falls das Panel das Menü versteckt):

```
adb shell settings put secure enabled_accessibility_services at.b05.volumerail/at.b05.volumerail.FineVolumeService
adb shell settings put secure accessibility_enabled 1
```

App öffnen: `adb shell am start -n at.b05.volumerail/.MainActivity`
