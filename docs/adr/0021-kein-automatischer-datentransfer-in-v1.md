# ADR-0021: Kein automatischer Transfer, kein Cloud-Backup, kein verschluesselter Export in V1

Datum: 2026-09-13
Status: Akzeptiert

## Problem

Der Ausbauplan (C5, T6) verlangt eine Entscheidung zum Datentransfer, bevor
irgendwo Backup- oder Export-Code entsteht. Zwei Wege standen zur Wahl:

1. Verschluesselter Voll-Export (Room-Datenbank + DataStore) als Datei, die
   der Nutzer selbst weitergibt (kein Cloud-Sync, Offline-First bleibt).
2. Bewusst kein automatischer Transfer; `allowBackup=false` bleibt, dazu ein
   UI-Hinweis, dass App-Daten nicht ueber Google-Backup oder Geraetetransfer
   mitwandern.

Randbedingungen: Das Projekt ist **Offline-First ohne Konto** (Leitplanke).
App-Daten sind Trainingsverlauf, Musik-Metadaten und Einstellungen; sie
verlassen das Geraet nur, wenn der Nutzer sie aktiv exportiert. Der aktuelle
Stand deaktiviert Auto Backup bereits vollstaendig
(`app/src/main/res/xml/data_extraction_rules.xml`, `android:allowBackup="false"`,
`fullBackupContent="false"`).

## Optionen

1. Verschluesselter Voll-Export jetzt bauen: Nutzer kann sein Training
   sichern und auf ein neues Geraet bringen. Erfordert ein Verschluesselungs-
   Format, Schluesselverwaltung (Passwort oder Keystore), Import-Validierung
   und Migrationspfad — mehrere Tage plus Sicherheits-Review.
2. Kein Transfer, kein Export in V1: Daten bleiben lokal; dokumentierter
   Hinweis in der App, dass ein Geraetewechsel die Historie nicht mitnimmt.

## Entscheidung

Option 2 fuer V1. Begruendung:

- Es gibt noch keinen Export-Code; ein ADR vor jedem Backup-Code ist die
  Leitplanke, und der einfachste sichere Zustand ist "gar kein Transfer".
- Ein selbstgebautes Verschluesselungsformat ohne Review ist riskanter als
  kein Format: falsch umgesetzt entsteht ein Datenleck, richtig umgesetzt
  verlangt es Schluesselverwaltung und Migration.
- Offline-First ohne Konto bedeutet: kein Server, der Nutzer traegt die
  Verantwortung. Ohne belastbaren Bedarf (Nutzer fragen nach Umzug) ist der
  Aufwand nicht gerechtfertigt.

Die Extraktion bleibt hart aus: `allowBackup="false"`,
`dataExtractionRules` schliesst alle Domaenen fuer `cloud-backup` und
`device-transfer` aus, `fullBackupContent="false"`.

## Folgen

- Ein Geraetetransfer via Android-Bordmittel uebertraegt **keine**
  App-Daten. Ein verschluesselter Voll-Export bleibt eine bewusste
  Folge-Option (neue ADR mit Format- und Schluesselentscheidung), sobald
  Nutzer ihn brauchen.
- Der UI-Hinweis dazu ist als Folgearbeit notiert (Einstellungen, Abschnitt
  "Daten"): er darf keinen Export versprechen, den es nicht gibt.
- Wird Export-Code gebaut, gilt: keine Cloud, keine automatische
  Uebertragung, Verschluesselung nur mit dokumentiertem Format und
  Import-Validierung, plus Migrationstest.
