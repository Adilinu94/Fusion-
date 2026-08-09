# Hardware- und Touch-Steuerung

Stand: 27.07.2026 (Musik-Workout-Kopplung Phase 5, F4).

DropSync empfaengt Wiedergabebefehle ausschliesslich ueber die eine
`MediaLibrarySession` im `PlaybackService` (Bauplan 3.3, ADR-0004/0007).
Es gibt genau einen sessionfuehrenden Player; jede externe Steuerung
(Sperrbildschirm, Benachrichtigung, Bluetooth/AVRCP, kabelgebundenes
Headset, Android Auto) wirkt auf dieselbe Instanz und landet ueber den
`Player.Listener` im App-Zustand (auch der Restore-Speicher, Schritt 5.5).

## Unterstuetzte Standardbefehle

Die Session meldet beim Verbinden (`onConnect`) die vollstaendigen
Standard-Player-Kommandos (`DEFAULT_PLAYER_COMMANDS`) plus die
Session-/Library-Kommandos (`DEFAULT_SESSION_AND_LIBRARY_COMMANDS`) und ein
eigenes Drop-Landungs-Kommando (`PlaybackCommands.ACTION_CROSSFADE_TO`,
nur intern genutzt). Damit stehen extern zur Verfuegung:

- Abspielen / Pause (`play`/`pause`),
- Weiter / Zurueck (`seekToNextMediaItem`/`seekToPreviousMediaItem`),
- Springen in der Zeitleiste (`seekTo`), Springen zu Warteschlangenindex,
- Shuffle- und Wiederholmodus,
- Browsen des Medienbaums (Android Auto / BT-Browsing): Root -> Titel /
  Alben / Interpreten / Ordner -> Songs.

Media3 erzeugt daraus automatisch die Medienbenachrichtigung mit den
Standardaktionen (Play/Pause, Weiter, Zurueck) und bedient die
System-Medientasten. Eine eigene, doppelte Fokus- oder Tastenverwaltung
gibt es bewusst nicht (Schritt 5.6).

## Lautstaerke

Die Lautstaerke laeuft ueber den System-Medienstream (der Player nutzt
`AudioAttributes` mit `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`). Hardware-
Lautstaerketasten und die Systemregler wirken direkt; DropSync fasst den
Systempegel nicht an. Die interne DSP-Lautstaerke (DVC/Preamp, ADR-0005)
ist davon getrennt und nur in den Audioeinstellungen relevant.

## Zusammenspiel mit der Rest-Musik-Automatik

Waehrend der Rest-Musik-Automatik (`REST_PLAYLIST`/`DROP_LANDING`, siehe
ADR-0012) gilt Nutzer-Vorrang:

- **Weiter / Zurueck** navigieren waehrend der Rest-Playlist innerhalb
  dieser Playlist — die App setzt die Playlist als Queue und mischt sich in
  die Navigation nicht ein.
- **Pause / Medientaste** waehrend des Pausen-Countdowns bricht eine
  geplante Drop-Landung sauber ab: der vorgezogene Work-Titel wird nicht per
  Crossfade gestartet, und am Pausenende wird nichts erzwungen
  (`RestMusicCoordinator` gibt die Steuerung ab).
- Bei Modus `NORMAL` (Standard) greift die App nie ein; Queue/Shuffle
  laufen unveraendert weiter.

## Grenze: proprietaere Gesten

Die proprietaere Tastenbelegung eines Kopfhoerers (z. B. Doppeltipp,
Halten, ANC-Umschaltung an Modellen wie dem ANC30C) liegt in dessen
Firmware bzw. Companion-App. DropSync empfaengt nur die daraus
resultierenden Standard-Medienbefehle (AVRCP) und belegt keine Gesten um.
Wer eine Geste einer bestimmten Funktion zuweisen will, tut das in der
Hersteller-App des Geraets.

## Geraeteabnahme (offen)

Die reale Abnahme mit Bluetooth-Kopfhoerern, kabelgebundenen Headsets und
Android Auto ist wie im Bauplan Schritt 13 geraeteabhaengig und in der
Sandbox nicht automatisierbar. Codeseitig sind die Standardkommandos,
die Notification-Aktionen und der Nutzer-Vorrang der Automatik verdrahtet
und durch Unit-Tests (`RestMusicCoordinatorTest`) abgesichert.
