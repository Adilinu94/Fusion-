# ADR-0017: Accel-Voting aktiviert sich per kalibrierter Schwelle statt per Feature-Flag

Datum: 2026-08-29
Status: Akzeptiert

## Problem

`docs/design/REP_ZAEHLUNG_UMBAUPLAN_2026-08-12.md` Punkt 4 fuehrt den
Accel-Kanal als zweiten, unabhaengigen Kanal ein und sieht dafuer ein
Feature-Flag `accelEnabled: Boolean = false` vor, das "fuer den Rollout"
gesetzt wird — praktisch also nach dem Durchlaufen von Gate 11b (Punkt 7).

Der Kanal war vollstaendig implementiert (`SignalChain`-Accel-Zweig,
zweiter `PeakDetector`, Voting im `RepCounter`), lief aber seit der
Portierung dauerhaft mit `accelEnabled = false`. Der Grund steht im Code
selbst: die Schwelle des Accel-`PeakDetector` war die Konstante `0.1625`.
Dieser Wert ist die Gyro-Schwelle `32.5` geteilt durch `200` — eine reine
Zahlenoperation ohne physikalischen Bezug zur gemessenen Groesse. Der
Accel-Kanal fuehrt naemlich die Abweichung der Beschleunigungs-Magnitude
von 1 g (Einheit: g, Ruhewert ~0), waehrend die Gyro-Schwelle in deg/s
liegt.

Mit einer geratenen Schwelle hat der Kanal genau zwei moegliche
Fehlverhalten, und beide sind schlimmer als kein zweiter Kanal:

- Schwelle zu tief: der Accel-Kanal peakt bei jedem Rauschen, das Voting
  laesst alles durch und der Kanal kostet nur Rechenzeit.
- Schwelle zu hoch: der Accel-Kanal peakt nie, das Voting verwirft JEDE
  Wiederholung und die Live-Zaehlung faellt auf 0.

Das Flag war damit faktisch kein Rollout-Schalter, sondern eine
Umgehung eines fehlenden Kalibrierungsschritts. Ein Aktivieren nach
Gate 11b haette dasselbe Problem gehabt, weil Gate 11b die Schwelle nicht
misst.

## Optionen

1. Feature-Flag beibehalten und erst nach Gate 11b manuell aktivieren.
   Die Schwelle bleibt geraten; der Kanal wird nach der Freigabe mit einem
   physikalisch unbegruendeten Wert scharfgeschaltet.
2. Schwelle in der Guided Calibration messen und den Kanal aktivieren,
   sobald ein belastbarer Wert vorliegt. Das Flag wird zur Konsequenz der
   Kalibrierung statt zu einer manuellen Entscheidung.
3. Accel-Kanal ganz entfernen. Die Forschung (Balestra et al. 2021,
   95.6 % mit Accel+Gyro gegen ~90 % Gyro allein) spricht dagegen.

## Entscheidung

Option 2. Die `CalibrationController.finalize()`-Stufe misst die
Accel-Spitzen an den bereits validierten Rep-Positionen des KNOWN_SET und
legt die Schwelle bei 35 % des Medians dieser Spitzen ab, sofern sie
zugleich mindestens den vierfachen Wert des im Ruhe-Gate gemessenen
Accel-Rauschens erreicht. Findet sich keine solche Trennung, liefert die
Kalibrierung `accelThreshold = 0.0` und der Kanal bleibt aus.

`ExerciseEngineConfig` erzwingt diese Kopplung per `require`:
`accelEnabled = true` ohne `accelThreshold > 0` ist ein
Programmierfehler, kein stiller Fehlzustand. `ActiveSetController` setzt
`accelEnabled = profile.accelVotingAvailable`, also genau dann, wenn das
Profil eine gemessene Schwelle traegt.

Die Abweichung von Punkt 4 ("Feature-Flag fuer Rollout") wird damit
formal dokumentiert. Sie liegt auf derselben Linie wie ADR-0014: Gate 11b
bleibt das Ziel, ist aber keine Voraussetzung fuer das Scharfschalten
eines Kanals, dessen Parameter aus der Kalibrierung selbst stammen.

## Folgen

- Das Profil-Schema steigt auf v5 (`accelThreshold` als 16. Feld).
  v4-Bloebe werden lesend uebernommen und erhalten `accelThreshold = 0.0`;
  niemand muss wegen dieser Aenderung neu kalibrieren. Der Kanal bleibt
  bei Altprofilen aus und schaltet sich bei der naechsten Kalibrierung von
  selbst zu.
- `CalibrationRefiner.revalidates()` konfiguriert die Revalidierungs-
  Pipeline jetzt mit denselben Kanaelen wie die Live-Pipeline. Ohne diese
  Angleichung wuerde der Refiner einen Count reproduzieren, den es live
  nicht gibt.
- Die Konstanten der Accel-Kalibrierung (`ACCEL_PEAK_FRACTION`,
  `ACCEL_NOISE_MARGIN`, `ACCEL_PEAK_WINDOW_S`) sind an synthetischen
  Signalen gesetzt und an echten M5StickC-Traces noch nicht geprueft. Sie
  gehoeren in den Golden-Corpus-Abgleich (Punkt 18 der Verbesserungsliste).
- Wer `accelEnabled` kuenftig anfasst, muss die `require`-Kopplung
  mitdenken: das Flag ist kein freier Schalter mehr.
- `ExerciseEnginePipelineIsolationTest` gibt die Schwelle im Test fest vor
  (`accelThreshold = 0.1625`), damit die vorhandenen Voting-Tests weiter
  genau das Voting pruefen und nicht die Kalibrierung.
