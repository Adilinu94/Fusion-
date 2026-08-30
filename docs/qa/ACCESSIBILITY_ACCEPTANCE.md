# Accessibility Acceptance

Status: Infrastruktur vorhanden; physische TalkBack-Abnahme ausstehend.

Diese Checkliste schliesst P3 #29 ab. Die automatisierbaren Voraussetzungen
liegen im Code; die letzte Abnahme braucht ein Android-Geraet, weil Emulator
und Compose-Semantik weder die reale TalkBack-Sprachausgabe noch Fokusfallen
mit Hardwaretasten verlaesslich abbilden.

## Automatisierte Voraussetzungen

- Alle Icon-Aktionen haben `contentDescription`; Zustandsbeschreibung ersetzt
  keinen Elementnamen mehr (`NowPlayingScreen.PowerampModeButton`).
- Der Live-Zaehler ist `LiveRegionMode.Polite` und meldet die lokalisierte
  Wiederholungszahl.
- Gewichts- und Wiederholungs-Schalter haben handlungsbezogene Namen, nicht
  nur `+`/`-`.
- Kalibrierung und Now Playing besitzen sichtbare, 48-dp-grosse
  Zurueck-Aktionen. Swipe-Down bleibt Komfortgeste, ist aber nicht mehr der
  einzige Ausgang.
- Die Sensor-Waveform besitzt eine Semantikbeschreibung; ihre Grafik ist
  nicht die einzige Informationsquelle.
- UI-Texte in Train/Kalibrierung liegen in `values` und `values-de`; dadurch
  liest TalkBack die aktive App-Sprache statt fest eingebautem Deutsch.

## Vorbereitung

1. Debug-App installieren:

   ```powershell
   .\gradlew :app:installDebug
   ```

2. Geraet per USB verbinden und den Helfer starten:

   ```powershell
   .\tools\accessibility-check.ps1 -FontScale 2.0
   ```

3. TalkBack auf dem Geraet unter **Einstellungen > Bedienungshilfen >
   TalkBack** aktivieren. Der Helfer schaltet TalkBack bewusst nicht per
   Component-Name an: der Name ist je nach Hersteller/Version verschieden,
   und ein falscher `enabled_accessibility_services`-Wert kann vorhandene
   Bedienungshilfen des Nutzers abschalten.

## TalkBack-Check

Jede Zeile muss mit **Pass** oder einer Issue-ID dokumentiert werden.

| Screen | Aktion | Erwartung |
|---|---|---|
| Hauptnavigation | Durch alle Tabs wischen | Name und Auswahlzustand werden angesagt; keine namenlosen Buttons |
| Train | Gewicht +/- fokussieren | „Gewicht um 2,5 Kilogramm …“, nicht nur „Minus“/„Plus“ |
| Train | Wiederholungen +/- fokussieren | Handlungsname wird angesagt |
| Train | Live-Satz starten | Countdown und Zaehler bleiben bedienbar |
| Train | Wiederholung erkannt | Zaehler wird hoeflich angesagt, Fokus springt nicht |
| Train | Schwaches Signal | Hinweis wird einmal angesagt, keine Endlosschleife |
| Kalibrierung | Zurueck fokussieren | „Kalibrierung schliessen“, 48-dp-Ziel |
| Kalibrierung | Alle vier Stufen | Titel, Anleitung, Fortschritt und Fehler in sinnvoller Reihenfolge |
| Now Playing | Zurueck fokussieren | „Zurueck“, ohne Swipe-Geste erreichbar |
| Now Playing | Previous/Repeat/Shuffle/Next | Jede Aktion hat einen Namen; aktiv/inaktiv bleibt visuell unterscheidbar |
| Now Playing | Play/Pause | Aktuelle Aktion wird angesagt |
| Now Playing | Waveform | Markeranzahl und Waveform-Bedeutung werden angesagt |

## 200-Prozent-Check

Mit `font_scale=2.0` in Hochformat (kleinste unterstuetzte Breite):

- Kein Text ueberlappt eine Aktion.
- Primaeraktionen bleiben sichtbar und mindestens 48 dp hoch.
- Train: „Satz fertig“, Gewicht, Wiederholungen und Pausenaktionen bleiben
  ohne horizontales Scrollen bedienbar.
- Kalibrierung: Anleitung darf umbrechen; „Weiter“/„Profil speichern“ und
  Zurueck bleiben sichtbar.
- Now Playing: Titel darf ellipsieren, Interpret darf umbrechen; Zurueck,
  Transport und Play/Pause bleiben erreichbar.
- Deutsch und Englisch jeweils einmal pruefen, weil deutsche Texte meist
  laenger sind.

## Abschluss und Wiederherstellung

1. Semantikbaum sichern:

   ```powershell
   .\tools\accessibility-check.ps1 -DumpOnly
   ```

   Ausgabe: `build/accessibility/window.xml`.

2. Font-Skalierung wiederherstellen:

   ```powershell
   .\tools\accessibility-check.ps1 -Restore
   ```

3. TalkBack manuell deaktivieren, falls er vor dem Test aus war.

## Rest-Risiko

Der Code und die reproduzierbare Abnahme-Infrastruktur sind fertig. Die
endgueltige Freigabe bleibt bis zu einem Lauf auf echter Hardware offen;
insbesondere Aussprache, Fokusreihenfolge mit OEM-TalkBack und 200-%-Layout
koennen nicht durch JVM-Tests bewiesen werden.
