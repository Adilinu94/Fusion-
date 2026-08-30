# FlowRep UI-/Funktionsabnahme

**Datum:** 2026-08-26
**Build:** Debug, `com.dropsync.app.debug`
**Emulator:** Flowtest (Android 16, 1080×2400, 420 dpi, System-Locale `en-US`)
**Artefakte:** Screenshot-Serie und `window_*.xml`-UI-Dumps der Sitzung. Sie
lagen unter `ui-test/` und sind bewusst nicht versioniert (siehe
`.gitignore`): 42 MB Diagnostik, die einen Geraetezustand eines Tages belegt.
Dauerhaft gebraucht werden nur die Poweramp-Referenzbilder, die kuratiert
unter `../design/reference/` liegen. Die Befunde dieses Dokuments sind die
bleibende Auswertung; die Rohartefakte sind reproduzierbar.

## Ergebnisübersicht

| Bereich | Ergebnis |
|---|---|
| Debug-Build | PASS — `./gradlew :app:assembleDebug` |
| Installation/Start | PASS — APK installiert, App startet auf `emulator-5554` |
| Unit-Tests | PASS — `./gradlew test` |
| Android Lint | PASS — `./gradlew lintDebug` |
| Connected Android Tests | PASS — `./gradlew connectedDebugAndroidTest` |
| App-Logcat nach frischem Start | PASS — keine aktuelle App-FATAL-Exception |
| BLE-/echter Sensor-Test | SKIPPED — keine reale BLE-Hardware im Emulator |
| Audio-/DAC-/Bluetooth-Verifikation | EINGESCHRÄNKT — keine reale Medienquelle, kein DAC und kein BT-Codec verfügbar |
| Health-Connect-Verifikation | EINGESCHRÄNKT — Provider/Berechtigungsquelle im Emulator nicht vollständig vorhanden |

Der erste Connected-Lauf zeigte einen reproduzierbaren Testinfrastrukturfehler: mehreren Android-Library-Modulen fehlten `AndroidJUnitRunner` und die AndroidX-Test-Runner-Dependency. Das wurde in den betroffenen `data`-/`feature`-Modulen ergänzt. Der vollständige Lauf ist danach erfolgreich durchgelaufen; die beiden BLE-Tests wurden dabei korrekt übersprungen.

## Screenshot-/Flow-Matrix

Die Matrix nennt die Screenshot-Namen der Sitzung. Die Bilder selbst sind
nicht versioniert (siehe Artefakt-Hinweis oben); die Namen bleiben als
Nachweis, welcher Flow geprueft wurde.

| Screenshots | Abgedeckter Bereich | UI-/Funktionsprüfung |
|---|---|---|
| `01_start` | Start/Navigation | App-Shell, Tab-Bar, initiale Hierarchie |
| `02_music_permission_granted` | Musikberechtigung | Permission-Rationale und Weiterleitung |
| `03_playlists_empty` | Playlisten leer | Empty State, primäre Aktion |
| `04_playlist_create_dialog` | Playlist-Dialog | Eingabe, Abbrechen/Bestätigen |
| `05_playlist_created` | Playlist angelegt | Persistenz und Rückkehr |
| `06_playlists_with_item` | Playlist-Liste | Eintrag, Label/Bereich |
| `07_playlist_detail_empty` | Playlist-Detail | leerer Detailzustand |
| `08_settings_main` | Einstellungen | Hauptbereiche und Scroll-Struktur |
| `09_settings_dark` | Einstellungen dunkel | Theme-Umschaltung und Kontrast |
| `10_settings_accent_blue` | Akzentfarbe | aktiver Zustand, visuelle Konsistenz |
| `11_settings_mix_enabled` | Mix-Übergänge | Toggle, deaktivierte/aktive Folgesteuerung |
| `12_settings_audio_mix_scrolled` | Mix-Details | lange Settings-Seite, Scrollposition |
| `12_settings_scrolled` | Einstellungen tief | Erreichbarkeit unterer Bereiche |
| `13_audio_settings_top` | Audio/DSP | Master, Preamp, Limiter, Tone |
| `14_audio_settings_eq` | EQ | EQ-Schalter, Bänder, Preset-Bereich |
| `15_audio_settings_effects` | Effekte | Stereo, Crossfade, MusicFX, DVC |
| `16_audio_settings_reverb` | Reverb/Resampler | Slider, Quality-/Rate-Auswahl |
| `17_audio_settings_dither` | Dither | Auswahl und erklärender Text |
| `18_audio_settings_profiles` | Ausgabeprofile | Bit-Perfect, Geräte-/BT-Hinweise |
| `19_train_empty` | Training leer | Empty State und Startaktion |
| `20_train_notification_permission` | Benachrichtigung | Permission-Flow |
| `21_train_main` | Training aktiv | Session-Header, Sensor-/Übungsbereiche |
| `22_new_exercise_dialog` | Neue Übung | Dialog, Name-Eingabe |
| `23_train_new_exercise_added` | Übung hinzugefügt | Session-State nach Anlegen |
| `24_train_after_exercise` | Übungsansicht | Satzsteuerung und Restaktion |
| `25_exercise_selector` | Übungsauswahl | Auswahl und Rückkehr |
| `26_train_weight_input` | Gewichtseingabe | numerische Eingabe, Einheiten |
| `27_train_set_ready` | Satz bereit | Validierung und CTA |
| `28_train_set_completed` | Satz abgeschlossen | Persistenz, Undo/Status |
| `29_rest_plus_15` | Resttimer verlängert | Plus-15-Sekunden-Aktion |
| `30_rest_ended` | Resttimer beendet | Übergang zurück zum Training |
| `31_exercise_end_confirmation` | Übungsende | Bestätigungsdialog |
| `32_history_dashboard` | Verlauf | Dashboard, Kennzahlen, Empty-/Datenzustand |
| `33_all_sets` | Alle Sätze | Detailroute und Android-Back |
| `34_exercise_library` | Übungsbibliothek | Liste, Archivstatus, Aktionen |
| `35_train_exercise_pager` | Trainings-Pager | horizontales Paging und Ende |
| `36_train_pager_end` | Pager-Ende | Boundary-Verhalten |
| `37_history_bottom` | Verlauf unten | Scroll-Ende und untere Navigation |
| `38_exercise_library_from_progress` | Bibliothek aus Progress | Cross-route-Navigation |
| `39_exercise_library_search` | Bibliothekssuche | Suche, Treffer, Tastaturzustand |
| `40_exercise_goal_dialog` | Übungsziel | Ziel-Dialog, Eingabe und Speichern |
| `41_exercise_archived` | Archivieren | Statusänderung und Sichtbarkeit |
| `42_exercise_restored` | Wiederherstellen | Rückkehr aus Archivstatus |
| `43_library_new_exercise_dialog` | Bibliothek: neu | Create-Dialog |
| `44_exercise_type_menu` | Übungstyp | Auswahlmenü |
| `45_music_all_titles_empty` | Alle Musiktitel leer | Empty State |
| `46_music_actions_menu` | Musik-Aktionsmenü | Menüöffnung und Optionen |

## Reproduzierbare funktionale Befunde

### Behoben

1. **Android-Testinfrastruktur — initial BLOCKER**
   Mehrere Library-/Feature-Test-APKs deklarieren `androidx.test.runner.AndroidJUnitRunner`, enthielten den Runner aber nicht im Android-Test-Classpath. Betroffene Module wurden an die bestehende Projektkonvention angepasst: expliziter `testInstrumentationRunner` sowie `androidTestImplementation(libs.androidx.test.ext.junit)` und `androidTestImplementation(libs.androidx.test.runner)`. Nach der Reparatur ist der vollständige Connected-Lauf grün.

### Offen bzw. eingeschränkt verifizierbar

1. **Reale Medienwiedergabe nicht vollständig abnehmbar — P1 für Geräteabnahme**
   Der Emulator enthält keine geeignete lokale Musikquelle. Bibliothek, Playlist-CRUD und Aktionsmenüs konnten geprüft werden; tatsächliches Decoding, Seek, Audio-Cues, DSP-Durchsatz, Gapless-Verhalten und Crossfade konnten nicht mit realem Audiomaterial bestätigt werden.

2. **BLE-Sensorfluss nicht mit Hardware verifizierbar — P1 für Geräteabnahme**
   Die beiden Instrumentierungstests `scan_connect_mtu_streaming_mit_echtem_sensor` und `sensor_liefert_daten_nach_verbindung` wurden vom Testlauf korrekt als `SKIPPED` ausgewiesen. Ein echter Sensorlauf bleibt erforderlich.

3. **Health Connect nur eingeschränkt verifizierbar — P1 für Geräteabnahme**
   Permission-/Badge-Pfade sind im UI erreichbar, aber eine echte Datenquelle und Provider-Konfiguration waren im Emulator nicht belastbar vorhanden.

## UI-/Designkritik

### P1 — Release-Risiko

- **Inkonsistente Lokalisierung:** Die App-Shell und große Teile von Training/Bibliothek verwenden Deutsch, während Audio/DSP, Progress und zahlreiche Übungsbibliotheks-Aktionen auf dem getesteten `en-US`-System Englisch anzeigen. Zusätzlich mischen einzelne Resource-Dateien Deutsch und Englisch im Fallback (`values/strings.xml`). Beispiele aus den Dumps: `Back`, `Search`, `New exercise`, `Set goal`, `Archive`, `Reverb enabled`, `Room size`, `Target sample rate`. Das wirkt wie ein unfertiger Sprachzustand, nicht wie eine bewusste Sprachumschaltung. Primärsprache und Fallback müssen produktseitig festgelegt und danach vollständig vereinheitlicht werden.

### P2 — UX-/Polish-Befunde

- **Settings sind sehr lang und stark kartenbasiert:** Audio, Mix, Extras, Datenschutz und Daten liegen in einer langen vertikalen Strecke. Die wiederholten Section-Cards erzeugen viel Scroll- und Orientierungsaufwand; eine klarere Gruppierung bzw. progressive Offenlegung würde die Scanbarkeit verbessern.
- **Leere Musikzustände sind funktional verständlich, aber wenig handlungsleitend:** Ohne Medienquelle bleibt der zentrale Musikbereich fast vollständig leer. Ein stärkerer nächster Schritt (z. B. „Ordner auswählen“/„Bibliothek aktualisieren“ mit kurzer Erklärung) würde den Erstkontakt verbessern.
- **Audio-Controls im Leerlauf:** Im Audio-/Reverb-Zustand sind Controls ohne aktive Wiedergabe deaktiviert. Das ist technisch plausibel, sollte visuell jedoch eindeutiger erklären, *warum* sie deaktiviert sind und wann sie verfügbar werden.
- **Mehrere Back-/Accessibility-Beschriftungen bleiben Englisch:** Auch dort, wo die sichtbare Oberfläche Deutsch ist. Das betrifft mindestens Toolbar-Back-Aktionen in den UI-Dumps und verstärkt den Lokalisierungsbruch.
- **Dialog-/Menütexte sind nicht überall sprachlich konsistent:** Besonders bei Übungstyp, Ziel, Archiv und Suche ist der Wechsel zwischen deutschen Screen-Überschriften und englischen Aktionslabels unmittelbar sichtbar.

### Positiv

- Navigation zwischen Top-Level-Tabs und Unterrouten ist nachvollziehbar.
- Android-Back führt aus Detail-/Unterseiten zurück; der geprüfte Backstack verhielt sich erwartungsgemäß.
- Zustandswechsel für Playlist anlegen, Satzabschluss, Resttimer, Archivieren und Wiederherstellen waren im manuellen Lauf sichtbar und reproduzierbar.
- Dunkles Theme und blaue Akzentfarbe sind erreichbar; aktive Zustände bleiben erkennbar.
- Für Resttimer, Satzabschluss und Übungsende existieren eigenständige Zustände statt stiller Übergänge.

## Empfohlene nächste Schritte

1. Entscheidung treffen: Deutsch als Primärsprache oder Englisch als Primärsprache.
2. Alle App-/Feature-Resources auf diese Entscheidung ausrichten und die jeweils andere Sprache vollständig ergänzen; anschließend beide Locale-Läufe auf dem Emulator prüfen.
3. Geräteabnahme mit echter Musikdatei, Bluetooth-Ausgabe/DAC, BLE-Sensor und Health-Connect-Provider wiederholen.
4. Für die langen Einstellungen eine Informationsarchitektur-Iteration mit kompakteren Gruppen oder aufklappbaren Bereichen planen.
