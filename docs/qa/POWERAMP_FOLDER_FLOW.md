# Poweramp Folder Flow

**Geprüft am:** 2026-08-26
**Emulator:** Flowtest / emulator-5554
**Poweramp-Paket:** `com.maxmpz.audioplayer`

## Beobachteter Ablauf

1. Poweramp öffnet den Dialog **Folders Selection**.
2. Der Dialog zeigt Speicherquellen als Baumwurzeln, nicht nur erkannte Musikordner:
   - `SDCARD` / Virtual SD card
   - `sdk_gphone64_x86_64`
3. Nicht freigegebene Speicher haben rechts eine Aktion **Enable**.
4. `Enable` öffnet den Android Storage Access Framework Picker.
5. Der Picker zeigt den kompletten Ordnerbaum des Speichers, einschließlich Ordner ohne Musikdateien.
6. Der Speicher-Root ist durch Android aus Datenschutzgründen nicht direkt nutzbar. Unterordner können ausgewählt werden.
7. **Select Folder or Storage** öffnet zuerst einen Hinweisdialog und danach denselben Picker.
8. Im Picker kann über **Show roots** die Seitenleiste geöffnet und ein Speicher ausgewählt werden.
9. Ein konkreter Unterordner wird geöffnet und mit **USE THIS FOLDER** bestätigt.
10. Android zeigt anschließend die dauerhafte Freigabeabfrage:
    `Allow Poweramp to access files in <folder>?`
11. Nach **ALLOW** erscheint der Ordner in Poweramp als eigene Quelle:
    - relativer Anzeigename/Pfad
    - Entfernen-Aktion
    - aktivierte Checkbox
12. **Save and Scan** speichert die Quellen und startet den Scan.
13. Nach dem Scan schließt Poweramp den Dialog und zeigt die Bibliothek. Bei leerem Speicher erscheint ein eigener Empty State, nicht die Meldung, der Nutzer müsse zuerst aktualisieren.

## Für FlowRep übernehmen

- Ordnerquellen als persistente SAF-Tree-URIs speichern, nicht ausschließlich relative MediaStore-Pfade.
- Beim Öffnen der Ordnerverwaltung immer den aktuellen gespeicherten Baum plus alle bereits bekannten Quellen anzeigen.
- Vollständigen Baum ausschließlich über `ACTION_OPEN_DOCUMENT_TREE` bzw. SAF anzeigen; MediaStore bleibt ergänzende Quelle für erkannte Audiodateien.
- Pro Quelle anzeigen: Pfad/Name, aktiv/inaktiv, Entfernen.
- Freigabezustände über `takePersistableUriPermission` dauerhaft sichern.
- **Speicher hinzufügen** als primäre Aktion anbieten.
- **Speichern und scannen** als abschließende Aktion anbieten und den Scan sichtbar mit Ladezustand ausführen.
- Scan-Ergebnis und leerer Zustand klar trennen.
- Unterordner ohne Audiodateien dürfen als Quelle gespeichert werden; sie werden beim späteren Scan erneut berücksichtigt.
- Die aktuell vorhandene Meldung `Noch keine Ordner gefunden. Aktualisiere zuerst die Bibliothek.` darf in diesem Flow nicht erscheinen.

## Referenzartefakte

Die elf Screenshots dieses Flows lagen unter `ui-test/` und sind bewusst
nicht versioniert (siehe `.gitignore`) — der beobachtete Ablauf ist oben
vollstaendig in Worten festgehalten und am Emulator reproduzierbar:
Folders Selection mit Speicherwurzeln, Android-Picker nach Enable, Rueckkehr
zum Poweramp-Dialog, Speicherquellenliste, Hinweis fuer Select Folder or
Storage, vollstaendiger Systempicker mit Seitenleiste, ausgewaehlter
Unterordner, dauerhafte Android-Freigabeabfrage, gespeicherte aktivierte
Quelle, Rueckkehr nach Save and Scan.
