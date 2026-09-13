# ADR-0020: System-Media ueber MediaSession und Notification-Fortschritt, kein media3-ui-compose

Datum: 2026-09-13
Status: Akzeptiert

## Problem

Der Ausbauplan (C3, "System-Media-Erlebnis") wollte den Player ausserhalb der
App sichtbar und steuerbar machen: `media3-ui-compose`-MiniController
(Dynamic Color) plus `ProgressSlider` fuer die App, fortschrittszentrierte
Notifications (Android 16) fuer Timer/Rest und einen Output-Switcher
(Personal Audio Sharing).

Zwei Randbedingungen stehen dem direkten Weg entgegen:

1. Architekturregel **3.2/4** (`docs/ARCHITEKTURREGELN.md`) verbietet
   `androidx.media3` in `:feature:*`; der Test
   `feature module kennen weder room noch media3 noch andere features` haelt
   das fest. `media3-ui-compose` laesst sich also nicht einfach in
   `:feature:player` einbinden. Ein Wrapper-Modul muesste zudem
   `MediaController` kapseln — der Typ selbst darf nicht ueber die
   Feature-Grenze leaken, sonst importiert das Feature doch wieder media3.
2. Der eigene Mini-Player und der Now-Playing-Screen sind bereits vollstaendig
   auf das Designsystem gezogen (ADR-0018). Der M3-Standard-MiniController
   braechte die Markenoptik (Lime-Akzent, eigene Typo, Waveform) zurueck auf
   Standard-Material und waere eine Design-Regression.

## Optionen

1. `media3-ui-compose` direkt in `:feature:player` — verletzt 3.2/4.
2. Neues Wrapper-Modul `:core:mediaui` mit media3-freier API — sauber, aber
   eigener Modul- und API-Schnitt (eigenes Arbeitspaket).
3. MediaSession + System-Controls behalten, nur den Notification-Fortschritt
   ergaenzen; Output-Switcher als System-UI belassen.

## Entscheidung

Option 3 fuer C3. Konkret:

- Die System-Integration bleibt die `MediaSession` des `PlaybackService`
  (Media-Controls auf Lock Screen, Kopfhoerern und in System-UI). Der eigene
  Mini-Player bleibt die In-App-Bedienung.
- Die Timer-/Rest-Foreground-Notification traegt jetzt einen
  Fortschrittsbalken (`setProgress`), damit der verbleibende Anteil ohne
  Oeffnen der App sichtbar ist (progress-zentriert). Geprueft in
  `TimerServiceForegroundTest`.
- Der Output-Switcher (Persoenliche Audio-Freigabe unter Android 16) ist
  System-UI und wird nicht nachgebaut; die Abnahme bleibt manual am Geraet
  (Runbook `docs/HARDWARE_TESTPLAN.md`).

Option 2 wird nicht verworfen, aber vertagt: Ein `:core:mediaui`-Wrapper lohnt
sich erst, wenn media3-ui-compose einen echten Mehrwert gegenueber der
designsystem-eigenen Bedienung bringt. Bis dahin bleibt der Medien-Weg ueber
die Session.

## Folgen

- Kein neues Modul, keine neue Abhaengigkeit; die Modulregeln bleiben gruen.
- Die Notification zeigt den Rest-Fortschritt; die drei Aktionen
  (Skip / +15 s / Finish) bleiben unveraendert.
- Sichtbare System-Media-Funktionen (Notification-Aktionen, Switcher,
  Lautstaerke/Output) werden am Geraet abgenommen, nicht per JVM-Test.
- Wenn media3-ui-compose eingefuehrt wird, geschieht das ueber ein
  `:core:mediaui`-Modul mit media3-freier Fassade; dann ist eine neue ADR mit
  dem API-Zuschnitt faellig.
