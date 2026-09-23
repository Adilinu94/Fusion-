# Pausen-Benachrichtigung: Welcher Foreground-Service-Typ? (Recherche 2)

Stand: 2026-09-19. Anlass: Verbesserungsplan MP-12 — der manuelle DropRest
laeuft heute ohne Foreground-Service und ohne Benachrichtigung. Adi hat im
Wizard entschieden: Benachrichtigung **ja, mit Abbrechen-Knopf**
(Entscheidung 8). Offen war, welcher `foregroundServiceType` dafuer noetig
ist und ob ein neuer Service gebaut werden muss.

## Befund: Der Service existiert schon, mit dem richtigen Typ

`data/timer/src/main/AndroidManifest.xml` deklariert bereits alles, was
gebraucht wird:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
...
<service
    android:name="com.dropsync.data.timer.TimerService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Rest timer countdown for workout sets" />
</service>
```

`TimerService.startForeground(...)` uebergibt passend
`ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
(`TimerService.kt:205-210`). Der Wiedergabe-Service nutzt getrennt
`mediaPlayback` (`data/playback/AndroidManifest.xml`).

## Bewertung

- **`specialUse` ist fuer einen Countdown-Timer korrekt.** Ab Android 14
  (API 34) muss jeder Foreground-Service einen Typ deklarieren; fuer eine
  Funktion ohne passgenauen Typ (Timer mit Cues) ist `specialUse` der
  vorgesehene Weg. Der Subtyp-Property-Eintrag ist die Voraussetzung fuer
  die Play-Store-Pruefung und ist bereits gesetzt.
- **`mediaPlayback` waere falsch am Timer.** Der Typ ist fuer das
  Abspielen von Medien gedacht; das macht hier der `PlaybackService`, nicht
  der Timer. Zwei Services mit demselben Typ fuer verschiedene Zwecke
  waeren zudem schwerer zu begruenden.
- **Kein neuer Service, keine neue Permission.** Der DropRest kann den
  vorhandenen `TimerService` mitbenutzen (`RestTimerServiceStarter`), die
  Benachrichtigung bekommt eine zusaetzliche Aktion "Plan abbrechen".
  `POST_NOTIFICATIONS` bleibt der bestehende Runtime-Request; ohne
  Erlaubnis laeuft der Service weiter (bestehendes Xiaomi-Verhalten).
- **Fortschrittsanzeige:** Der Service setzt bereits `setProgress`
  (`TimerService.kt:275-281`, C3). Fuer DROPSYNC muss die Projektion aus
  `TimerEngine.projectRemaining` gespeist werden, nicht aus der monotonen
  Frist (DROPSYNC hat keine eigene Frist).

## Konsequenz fuer die Umsetzung (MP-12 / P1-Paket 8)

1. `DropRestViewModel.startDropRest()` startet den Service ueber den
   bestehenden `RestTimerServiceStarter` (oder der neue Koordinator tut es
   zentral — dann faellt der Screen als Lebensdauer-Grenze weg).
2. `TimerService` behandelt DROPSYNC wie REST: Tick, Notification-Update,
   Fortschritt aus der projizierten Restzeit.
3. Neue Notification-Aktion `ACTION_CANCEL_PLAN` -> `TimerEngine.cancel(USER)`;
   die bestehenden Aktionen (`+15 s`) werden fuer DROPSYNC ausgeblendet
   (dort wirkungslos, siehe MP-6).
4. Kein Recovery-Snapshot fuer DROPSYNC (bewusst, MP-10) — der Fall bleibt
   sichtbar statt still.

## Verifikation

- Manifest-Merger-Report: `specialUse` + Subtyp unveraendert vorhanden.
- Robolectric-Test "DropRest startet den Service" (Muster
  `TimerServiceForegroundTest`).
- Geraetetest: Notification erscheint bei dunklem Bildschirm, Abbrechen
  beendet den Timer, Cues/Haptik feuern weiter.

## Quellen

- Repo: `data/timer/src/main/AndroidManifest.xml`, `TimerService.kt:205-281`.
- Android-Doku "Foreground service types" (API 34+), `specialUse` und
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` (Stand 2026-09, konsistent mit der
  bestehenden Deklaration im Repo).
