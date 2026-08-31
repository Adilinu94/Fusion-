# ADR-0019: Unsichere BPM/Key-Schaetzungen werden an der Leseseite verworfen

Datum: 2026-08-31
Status: akzeptiert

## Kontext

`TempoAccumulator` und `ChromaAccumulator` (`domain/audio/MixAnalysis.kt`)
liefern **immer** einen Wert, sobald genug Signal vorhanden ist. Sie haben
keinen Begriff von "dieses Material hat kein Tempo" oder "dieses Material
ist nicht tonal". Gemessen (`MixConfidenceBaselineTest`, synthetische
Signale bei 44,1 kHz):

| Eingang | Tempo-Konfidenz | Key-Konfidenz |
|---|---|---|
| klarer 120/160-BPM-Beat | 1,00 | — |
| Beat mit 8 % Jitter | 0,39 | — |
| Beat mit 20 % Jitter | 0,18 | — |
| Sprache-aehnliche Huellkurve | 0,18 (bei "77 BPM") | — |
| weisses Rauschen | 0,15 (bei "160 BPM") | 0,64 |
| Dur-/Moll-Dreiklang | — | 0,83..0,89 |

Weisses Rauschen ergibt "160 BPM", Sprache "77 BPM". Diese Werte gingen
bisher ungefiltert in die UI und - schwerwiegender - in den BPM-Lock:
`PlayerViewModel` rechnet aus Ziel-Kadenz / Track-BPM einen Tempo-Faktor
und setzt ihn auf dem Player. Ein erfundener BPM verstimmt damit hoerbar
die Wiedergabe, ohne dass der Nutzer die Ursache sieht.

Die Konfidenzspalten (`bpm_confidence`, `key_confidence`, DB v8) waren
vorhanden, wurden geschrieben - und von niemandem gelesen.

## Entscheidung

Ein Konfidenz-Gate (`MixConfidence` in `:domain:audio`) entscheidet, ob
eine Schaetzung ueberhaupt herausgegeben wird:

- `MIN_BPM_CONFIDENCE = 0,25` - liegt im Tal zwischen Rauschen/Sprache
  (0,15..0,18) und einem noch brauchbaren Beat mit 8 % Jitter (0,39).
- `MIN_KEY_CONFIDENCE = 0,70` - trennt Rauschen (0,64) von Dreiklaengen
  (0,83..0,89).

Angewendet wird das Gate in `TrackAnalysisRepositoryImpl.observeAnalysis`,
also auf der **Leseseite**. Die Rohwerte inklusive Konfidenz bleiben in
der Datenbank.

Ein fehlender Konfidenzwert (`null`, wie in DB-v7-Zeilen vor Einfuehrung
der Spalten) gilt als unsicher. Er stammt aus einem Lauf ohne
Konfidenzmessung und ist nicht vertrauenswuerdig.

Die beiden Schwellen greifen unabhaengig: ein untanzbarer Track kann eine
klare Tonart haben und umgekehrt.

## Begruendung

**Warum verwerfen statt anzeigen mit Warnung?** Ohne BPM ist der Lock
deaktiviert (`enabled = trackBpm != null`, `TempoSheet.kt:123`) und die
UI sagt, dass kein verlaesslicher Wert vorliegt. Das ist ein
verstaendlicher Zustand. Ein angezeigter, aber als "unsicher" markierter
Wert waere schlechter: der Lock muesste ihn entweder trotzdem benutzen
(gleiches Problem) oder ihn anzeigen und verweigern (verwirrend).

**Warum an der Leseseite und nicht beim Schreiben?** Die Schwellen sind
vorlaeufig (siehe Konsequenzen). Filtert der Worker schon beim Schreiben,
braucht jede Nachkalibrierung eine Neuanalyse der ganzen Bibliothek -
technisch nur ueber einen Bump von `ANALYZER_VERSION`, der auch alle
Waveform-Caches wegwirft. Auf der Leseseite kostet eine neue Schwelle
nichts.

**Warum bleibt die Konfidenz selbst unfiltriert?** Sonst koennte die UI
nie erklaeren, warum kein BPM da ist. `bpmConfidence` wird
durchgereicht, auch wenn `bpm` verworfen wurde.

## Nebenbefund: Vorzeichenfehler in der Chroma-Korrelation

Bei der Messung fiel auf, dass weisses Rauschen mit **0,96** korrelierte,
echte Dreiklaenge nur mit 0,73 - die Konfidenz war invers und als
Qualitaetsmass wertlos. Ursache: `ChromaAccumulator.correlation` rechnete
eine Kosinus-Aehnlichkeit zweier rein positiver Vektoren statt der
Pearson-Korrelation, die Krumhansl-Schmuckler verlangt. Ohne Zentrierung
hat ein flaches Chromagramm (Rauschen verteilt Energie gleichmaessig auf
alle 12 Halbtoene) mit **jedem** Profil hohe Aehnlichkeit.

Mit Zentrierung wird ein flacher Vektor zum Nullvektor, die Varianz 0 und
das Ergebnis 0 - keine Tonart ohne tonale Struktur. Danach: Rauschen
0,64, Dreiklaenge 0,83..0,89.

Ohne die Messung waere der Fehler unentdeckt geblieben, und jede
Schwelle darauf gesetzt haette genau das Falsche gefiltert. Kein Bump von
`ANALYZER_VERSION`: die Waveform ist unberuehrt, und die Key-Werte
sind entweder unter der Schwelle (werden verworfen) oder werden beim
naechsten regulaeren Lauf korrekt neu berechnet.

## Konsequenzen

**Die Schwellen sind vorlaeufig.** Sie trennen synthetische Extremfaelle,
und synthetische Signale sind der einfachste denkbare Fall: ein
Burst-Train ist rhythmisch praeziser als jede Live-Aufnahme, ein reiner
Dreiklang tonal klarer als ein Track mit Drums und Bass. Echte Musik
liegt niedriger. Die Schwellen sind daher bewusst **permissiv** - sie
werfen weg, was messbar Muell ist, statt zu riskieren, dass korrekte
Werte echter Tracks verschwinden.

Endgueltige Kalibrierung braucht echte Titel mit bekanntem BPM/Key (etwa
ein Dutzend mit Rekordbox- oder Mixed-In-Key-Referenz). Bis dahin ist das
Gate eine Muellabfuhr, keine Qualitaetsgarantie. Das ist die gleiche
Luecke wie bei den Sensor-Ground-Truth-Traces (ADR-0017): die Mechanik
steht, die Referenzdaten fehlen.

**Ein zu hohes Gate ist unsichtbar.** Verwirft es korrekte Werte, sieht
der Nutzer nur einen deaktivierten Lock - nicht, dass die Analyse
funktioniert haette. Deshalb die permissive Seite. `MixConfidenceBaselineTest`
druckt die Verteilung bei jedem Lauf, damit eine Algorithmus-Aenderung,
die die Werte verschiebt, auffaellt.

## Alternativen

- **Gate im Analyzer, `null` statt Wert schreiben.** Verliert die
  Rohdaten; Nachkalibrierung nur per `ANALYZER_VERSION`-Bump samt
  Verlust aller Waveform-Caches.
- **Kein Gate, dafuer Konfidenz in der UI anzeigen.** Verschiebt die
  Entscheidung auf den Nutzer, der sie nicht treffen kann - und loest das
  BPM-Lock-Problem nicht.
- **Zwei Schwellen pro Groesse (anzeigen ab X, automatisieren ab Y).**
  Sinnvoll, sobald echte Referenzdaten existieren. Heute waere die
  zweite Schwelle genauso geraten wie die erste.
