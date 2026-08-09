# DropSync/FlowRep Herzfrequenz-Anbindung ueber Health Connect — Plan (korrigierte Fassung)

Stand: 28.07.2026, Repo-Stand `d4505a8` (Mix-Uebergaenge Phasen 2-4).
Status: Phase 1 umgesetzt; Phasen 2-3 offen. Diese Fassung
ersetzt den eingereichten Entwurf vom selben Tag; die Aenderungen sind
im Abschnitt "Review-Ergebnis" belegt. Geschrieben, um ohne weiteren
Chat-Kontext auszureichen, auch fuer eine andere KI-Instanz.

**Update (28.07.2026, Phase 1 umgesetzt):** `:domain:health` und
`:data:health` existieren. Eine Abweichung von der Skizze in 3.2 wurde
noetig: Domain-Module sind laut Architekturtest
(`ModuleDependencyRulesTest`, Regel 3.2/2) **reine JVM-Module** — der
Android-Typ `ActivityResultContract` darf dort nicht auftauchen. Der
Berechtigungs-Contract haengt deshalb nicht am `HeartRateSource`-
Interface, sondern wird von `:data:health` unter dem Hilt-Qualifier
`@HealthPermissionContract` (deklariert in `:domain:health`, nur
`javax.inject`) als generischer
`ActivityResultContract<Set<String>, Set<String>>` bereitgestellt;
Features injizieren ihn qualifiziert und registrieren ihn per
`rememberLauncherForActivityResult`. Zusaetzlich kapselt das interne
`HealthConnectGateway` alle SDK-Aufrufe, wodurch Zustandsautomat,
Initial-Read, Changes-Pfad und Token-Ablauf-Fallback rein auf der JVM
gegen ein Fake-Gateway getestet sind. Abschnitt 3.2 unten zeigt den
umgesetzten Vertrag.

Hinweis zum Vorgaenger: Der im Original referenzierte Plan
`LIVE_HERZFREQUENZ_ANBINDUNG_PLAN.md` (direkte Bluetooth-Anbindung an
den Heart-Rate-Service, Gadgetbridge-Recherche) wurde **nie committed**
und existiert nicht im Repo. Diese Fassung ist deshalb selbsttragend;
die Entscheidung gegen den BLE-Weg ist in Abschnitt 1 zusammengefasst.
Sollte echte Sekundentakt-Live-Herzfrequenz spaeter wichtiger werden
als die Offline-Reinheit, waere der BLE-Weg neu zu bewerten.

## Review-Ergebnis (28.07.2026, Verifikation gegen `d4505a8`)

Verifiziert und bestaetigt: `minSdk = 26` (`gradle/libs.versions.toml`),
`WorkoutRepository.activeSession` als Lebenszyklus-Anker existiert,
DataStore-Vorbild `DspSettingsStore` existiert, App-Manifest enthaelt
aktuell **keinerlei** Permissions (auch kein INTERNET — bleibt so),
Modulschnitt entspricht der Praezedenz (Charts/Waveform in
`:core:designsystem`, kein eigenes Feature-Modul). Korrigiert wurden
sechs Punkte des Originals:

1. **Tote Referenz aufgeloest:** Der Vorgaenger-Plan ist nicht im Repo;
   Projekt-Kontext jetzt selbsttragend (Abschnitt 2).
2. **Berechtigungs-Architektur repariert:** `suspend fun
   requestPermission()` im Domain-Interface ist nicht umsetzbar — der
   Health-Connect-Berechtigungsdialog laeuft ueber einen
   ActivityResultContract, nicht ueber einen Data-Layer-Aufruf.
   Ausserdem darf `:feature:settings` keine SDK-Typen aus
   `connect-client` importieren (Modulregel, sinngemaess zu "Features
   kennen weder Room noch ExoPlayer"). Neu: generische Contract-Fabrik
   im Domain-Vertrag (Abschnitt 3.2/3.3).
3. **Verfuegbarkeits-Enum vervollstaendigt:** `getSdkStatus` liefert
   auch "Provider-Update noetig"; ohne eigenen Enum-Zustand kann die
   UI "nicht verfuegbar" und "Update noetig" nicht unterscheiden.
   Neu: `UPDATE_REQUIRED`.
4. **Polling-Kontext eindeutig:** Das Original widersprach sich
   (3.3: "nur bei sichtbarem Screen"; 3.4: "waehrend Session aktiv" —
   Sessions laufen auch bei Bildschirm aus weiter). Entscheidung:
   **Foreground-only** fuer den ersten Ausbau; kein
   `READ_HEALTH_DATA_IN_BACKGROUND` (Abschnitt 3.4).
5. **`changesToken`-Ablauf behandelt:** Tokens verfallen (Groessenordnung
   30 Tage Inaktivitaet); `getChanges` meldet das als
   `changesTokenExpired` — Fallback ist Pflicht, nicht Randfall
   (Abschnitt 3.3, Punkt 4).
6. **Bibliotheksversion aktualisiert:** `connect-client` ist seit
   November 2025 stabil in **1.1.0** (offizieller
   Android-Developers-Blog) — kein Alpha-Risiko mehr.

## 1. Entscheidung und Begruendung

Gewaehlter Weg: Xiaomis offizielle Mi-Fitness-App schreibt Herzfrequenz
(und optional Schritte, Schlaf, Workouts) in Android Health Connect.
Die App liest ausschliesslich lokal aus Health Connect.

Warum dieser Weg statt der direkten Bluetooth-Anbindung:

- **Kein neues Protokoll in der App.** Health Connect ist eine
  System-API mit offizieller, stabiler Dokumentation — kein
  reverse-engineertes, fuer das Mi Band 9 nur teilweise unterstuetztes
  Protokoll wie im verworfenen Gadgetbridge-Weg.
- **Die App bleibt ohne eigenen Netzwerkzugriff und ohne eigenes
  Xiaomi-Konto.** Health Connect ist ein On-Device-Datenspeicher. Dass
  Mi Fitness selbst ein Xiaomi-Konto braucht, ist ein einmaliger
  Schritt ausserhalb dieser App.
- **Kein Abbruchrisiko durch Firmware-Updates.** Der Weg haengt nur an
  einer offiziellen, versionierten Google-API.

**Bewusst akzeptierter Nachteil:** keine echten Sekundentakt-Werte.
Berichte zu Xiaomi-Synchronisation in andere Health-Plattformen zeigen
Verzoegerungen von Minuten bis Stunden, abhaengig von Akku-Optimierung
und Hintergrund-Sync der Quell-App. Eine verlaessliche Sync-Frequenz
fuer Mi Fitness ist nicht dokumentiert und wird in Phase 3 am echten
Geraet gemessen (Abschnitt 6).

## 2. Projekt-Kontext (selbsttragend)

- Feature-/schichtorientierte Module (`:core:*`, `:data:*`,
  `:domain:*`, `:feature:*`); kein Feature importiert ein anderes
  Feature; Features kennen keine Implementierungs-SDKs (kein Room,
  kein ExoPlayer — sinngemaess auch kein Health-Connect-SDK).
- `:core:common` liefert `AppResult`, `AppError`, `Clock`,
  `DispatcherProvider`; DataStore-Persistenz nach dem Vorbild
  `DspSettingsStore` (`:data:audio`).
- Regeln: kein Netzwerk/Analytics, Zeiten als UTC-Epoch-Millis, Enums
  als stabile Strings, ASCII-Umlaute in Code-Kommentaren und Docs.
- `minSdk = 26`. Health Connect ist erst ab Android 9 (API 28)
  verfuegbar (ab Android 14/API 34 Teil des Systems, davor als
  installierbare App). Auf API 26/27 wird die Funktion sauber
  ausgeblendet statt abzustuerzen; die `connect-client`-Bibliothek
  selbst ist mit minSdk 26 kompatibel (bei Umsetzung verifizieren).
- Sichtbarer App-Name ist FlowRep; Projekt-/Paketname bleiben
  `com.dropsync` (README-Markenhinweis).

## 3. Zielarchitektur

### 3.1 Neue Module

`:domain:health` und `:data:health` (bewusst nicht "heartrate":
Health Connect deckt potenziell auch Schritte/Schlaf ab; spaetere
Erweiterung ohne Modul-Umbenennung). Kein eigenes Feature-Modul:
Anzeige-Komponente in `:core:designsystem`, eingebunden von
`:feature:player` und `:feature:workout`; Berechtigungs-/Status-UI in
`:feature:settings`.

### 3.2 Domain-Vertrag

```kotlin
// :domain:health — reines Kotlin (Regel 3.2/2), kein Android-/SDK-Typ.
interface HeartRateSource {
    val availability: Flow<HeartRateAvailability>
    val latestSample: Flow<HeartRateSample?>

    /** Permission-Strings fuer den Berechtigungs-Launcher. */
    val requiredPermissions: Set<String>

    /** Nach Dialog-Ergebnis oder App-Resume neu pruefen. */
    suspend fun refreshAvailability()

    /** Einmaliges Nachladen (Initial-Read bzw. Changes seit Token). */
    suspend fun refresh(): AppResult<Unit>
}

/**
 * Hilt-Qualifier: :data:health bindet darunter den generischen
 * ActivityResultContract<Set<String>, Set<String>> fuer den
 * Health-Connect-Berechtigungsdialog — Features kennen so weder das
 * SDK noch :data:health.
 */
@Qualifier
annotation class HealthPermissionContract

enum class HeartRateAvailability {
    /** API < 28 oder Health Connect nicht installierbar. */
    HEALTH_CONNECT_NOT_AVAILABLE,

    /** Provider installiert, braucht aber ein Update (Play-Store-Link). */
    UPDATE_REQUIRED,

    PERMISSION_REQUIRED,
    NO_RECENT_DATA,
    READY,
}

data class HeartRateSample(
    val bpm: Int,
    val recordedAtEpochMs: Long,
)
```

### 3.3 Data-Layer-Implementierung (`:data:health`)

Bibliothek: `androidx.health.connect:connect-client:1.1.0` (stabil
seit 11/2025; bei Umsetzung Release-Notes auf Patch-Versionen pruefen).

1. **Verfuegbarkeit:** `HealthConnectClient.getSdkStatus(context)` —
   `SDK_UNAVAILABLE` -> `HEALTH_CONNECT_NOT_AVAILABLE`,
   `SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED` -> `UPDATE_REQUIRED`
   (UI verlinkt den Play-Store-Eintrag des Providers; siehe
   Abschnitt 7 zur Netzwerk-Regel), sonst weiter zur
   Berechtigungspruefung via `getGrantedPermissions()`.
2. **Berechtigung:** Lesepermission fuer `HeartRateRecord`
   (`HealthPermission.getReadPermission(HeartRateRecord::class)`);
   `permissionContract()` reicht
   `PermissionController.createRequestPermissionResultContract()`
   als generischen Contract heraus. Manifest-Deklarationen siehe
   Abschnitt 7.
3. **Initiales Lesen:** `readRecords(ReadRecordsRequest(
   HeartRateRecord::class, TimeRangeFilter.between(now - 15 min,
   now)))`; ein `HeartRateRecord` kann mehrere `samples` mit eigenem
   Zeitstempel enthalten — es zaehlt das Sample mit dem neuesten
   Zeitstempel ueber alle Records.
4. **Laufende Aktualisierung ueber die Changes-API statt
   Vollzeitraum-Polling:** einmalig `getChangesToken` holen und im
   `HealthSettingsStore` (DataStore, Muster `DspSettingsStore`)
   persistieren; periodisch `getChanges(token)` und den Token
   fortschreiben. **Token-Ablauf ist Pflichtpfad:** meldet
   `getChanges` einen abgelaufenen Token (`changesTokenExpired`),
   wird der Zustand verworfen, ein Initial-Read (Punkt 3) ausgefuehrt
   und ein frischer Token gespeichert — das ist nach laengerer
   App-Pause der Normalfall, kein Randfall.
5. **Poll-Intervall:** 30-60 s, nur im Foreground (Abschnitt 3.4).
   Es gibt keinen Push-Mechanismus fuer Fremd-App-Daten; das Intervall
   respektiert das dokumentierte Health-Connect-Rate-Limiting und
   passt zur akzeptierten Einschraenkung aus Abschnitt 1.

### 3.4 Lebenszyklus — Foreground-only (Entscheidung)

Polling laeuft nur, waehrend die App im Vordergrund ist UND ein
relevanter Kontext aktiv ist: Now-Playing-Screen sichtbar oder
Workout-Session aktiv bei sichtbarer App. **Kein Lesen im
Hintergrund** — dafuer waere die Zusatzberechtigung
`android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` noetig und
auf aelteren Health-Connect-Staenden wuerde es schlicht blockiert.
Fuer eine reine Anzeige-Funktion lohnt dieser Aufwand nicht; laeuft
die Session bei ausgeschaltetem Bildschirm weiter, holt der naechste
Foreground-Moment den Stand ueber die Changes-API nach. (Sollte eine
spaetere HF-Zonen-Steuerung Hintergrund-Werte brauchen, ist das eine
eigene Erweiterung inkl. dieser Berechtigung.)

## 4. Datenmodell und Persistenz

Keine eigene Persistenz im ersten Ausbau — nur Anzeige des letzten
bekannten Werts; einzig der `changesToken` liegt im DataStore. Eine
Kopplung an Trainingssaetze (HF-Zonen) waere eine spaetere, separate
Erweiterung mit eigener Migration.

## 5. Phasenplan

| Phase | Inhalt | Status |
|---|---|---|
| 1 | `:domain:health` + `:data:health` Grundgeruest: Vertrag (3.2), `HealthConnectHeartRateSource` (3.3), `HealthSettingsStore` (Token), `HealthConnectGateway`-Kapselung; Tests: Sample-Mapping, Verfuegbarkeits-Zustandsautomat inkl. `UPDATE_REQUIRED`, Token-Ablauf-Fallback — alles reine JVM-Tests gegen Fakes | Umgesetzt |
| 2 | `:feature:settings`: Statusanzeige (Zustaende aus 3.2), Berechtigungs-Launcher ueber den qualifizierten Contract, Play-Store-Link bei `UPDATE_REQUIRED`; Manifest: Permission + Rationale-Intent-Filter (Abschnitt 7); Rationale-/Datenschutzseite (baut auf dem bestehenden `settings_privacy_body`-Text auf) | Offen |
| 3 | `HeartRateBadge` in `:core:designsystem` (bpm + "zuletzt aktualisiert vor X min" — bewusst nicht als Echtzeit dargestellt); Einbindung in Now-Playing (`:feature:player`) und Session-Screen (`:feature:workout`), FlowRep-Design-Tokens; Geraetetest inkl. Latenz-Messung (Abschnitt 6) | Offen |

## 6. Offener Beobachtungspunkt

Die reale Sync-Latenz Band -> Mi Fitness -> Health Connect ist nicht
verlaesslich dokumentiert. In Phase 3 bei der ersten echten
Trainingsnutzung messen (bewusste Belastungsspitze vs. Erscheinen in
der App) und das Ergebnis hier dokumentieren.

## 7. Berechtigungen und Manifest (vollstaendig)

Das aktuelle App-Manifest enthaelt keinerlei Permissions (auch kein
INTERNET) und nur `MainActivity` — alle folgenden Eintraege sind neu:

- `<uses-permission android:name="android.permission.health.READ_HEART_RATE" />`
- Rationale-Deklaration (Pflicht fuer den Berechtigungsdialog):
  eine Activity oder ein Activity-Alias mit Intent-Filter
  `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (Android <= 13)
  **und** `android.intent.action.VIEW_PERMISSION_USAGE` mit Kategorie
  `android.intent.category.HEALTH_PERMISSIONS` (Android 14+). Beide
  zeigen dieselbe Datenschutz-/Rationale-Seite (nur Herzfrequenz, nur
  lokal, nichts verlaesst das Geraet).
- Kein `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, kein `INTERNET`, kein
  `READ_HEALTH_DATA_IN_BACKGROUND`.
- **Netzwerk-Regel:** Der Play-Store-Link bei `UPDATE_REQUIRED`
  oeffnet nur die Play-Store-App per Intent (wie der bestehende Link
  zu den System-Toneinstellungen im Bit-Perfect-Panel); die App selbst
  bleibt ohne Netzwerkzugriff. Bewusste, dokumentierte Ausnahme.

## 8. Quellen

- Health Connect Uebersicht: https://developer.android.com/health-and-fitness/health-connect
- Einstieg/Berechtigungen/Lesen: https://developer.android.com/health-and-fitness/health-connect/get-started
- Rohdaten lesen, Rate-Limiting: https://developer.android.com/health-and-fitness/health-connect/read-data
- Vitaldaten (Herzfrequenz): https://developer.android.com/health-and-fitness/health-connect/experiences/vitals
- Codelab mit Differential-Changes-Beispiel: https://developer.android.com/codelabs/health-connect
- Release-Notes (1.1.0 stabil seit 11/2025): https://developer.android.com/jetpack/androidx/releases/health-connect
- Groessenordnung Xiaomi-Sync-Verzoegerung (Beispiel Google Fit): https://help.mibandtools.com/knowledge_base/topics/google-fit-synchronization
