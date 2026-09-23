# ADR-0023: Sensordaten-Aufzeichnung nur in debuggable Builds

Datum: 2026-09-19
Status: Akzeptiert (Nutzerentscheidung, Entscheidung 3)

## Problem

`JsonlShadowSessionRecorder` schreibt pro geloggtem Satz alle Rohsamples
plus Kalibrierdaten (Achse, Bias, Schwellen) nach
`getExternalFilesDir()/recordings/<session>.jsonl`
(`JsonlShadowSessionRecorder.kt:39-89`, `:118-128`). Gebunden war er in
**jedem** Build (`ShadowRecorderModule` als `@Binds`, ohne Build-Typ-Gate).
Die Doku beschrieb das als gewollt ("der JSONL-Recorder ist im normalen
Build aktiv", `tools/golden_shadow_corpus/README.md:86-88`), nannte aber
auch ~0,4 MB je Minute. Ein Aufraeumer oder Deckel existierte nicht.

Damit sammelt die ausgelieferte App dauerhaft Bewegungsprofile in einem
per USB/Dateimanager zugaenglichen Verzeichnis — Speicherlast und ein
Datenschutzthema, auch wenn nichts das Geraet verlaesst. Fuer die
Gate-11b-Kampagne ist der Recorder dagegen unverzichtbar.

## Optionen

1. Immer aufzeichnen (Status quo).
2. Nur in debuggable Builds aufzeichnen; Release bekommt den NoOp.
3. Sichtbarer Diagnose-Schalter in der Release-App.

## Entscheidung

**Option 2.** `ShadowRecorderModule` ist jetzt ein `@Provides`-Modul: Ist
`ApplicationInfo.FLAG_DEBUGGABLE` gesetzt (Debug- und Benchmark-Builds),
wird der echte Recorder gebunden, sonst `NoOpShadowSessionRecorder`. Die
Entscheidung ist als reine Funktion `isRecordingEnabled(flags)` testbar
(`ShadowRecorderModule.kt`, Test in
`JsonlShadowSessionRecorderTest`).

Option 3 wurde verworfen: Ein Schalter in der Release-App waere ein
weiteres sichtbares Datenschutz-Thema im normalen Nutzerpfad, ohne dass es
dafuer einen Anwendungsfall gibt. Die Kampagne laeuft ohnehin auf einem
Debug-Build (so steht es bereits in der Corpus-Anleitung).

## Folgen

- Release-APKs enthalten keinen aktiven Schreibpfad fuer Sensordaten.
- Fuer Gate 11b und den Offline-Sweep wird weiterhin ein Debug-Build
  verwendet; die Anleitung in `tools/golden_shadow_corpus/README.md`
  bleibt gueltig.
- Die `recordings/`-Dateien aus Debug-Laeufen sind Rohdaten und gehoeren
  nicht in Commits oder ins Repo (Corpus-Ordner ist kuratiert; das gilt
  auch fuer Transfer-Artefakte).
- Ein spaeterer Bedarf an Aufzeichnung in der Release-App (z. B.
  Nutzer-Support) braucht einen neuen ADR mit Loeschkonzept, Rotation und
  Einwilligung.
