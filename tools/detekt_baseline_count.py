#!/usr/bin/env python3
"""Detekt-Wachstumsbremse (D2, Review-Befund I-5).

Die Baseline `config/detekt/baseline.xml` fixiert den Ist-Zustand der
Altlasten. Ohne Bremse waechst sie still mit: ein neuer Smell wird einfach
in die Baseline aufgenommen, statt ihn zu beheben. Dieses Gate bricht ab,
sobald die Anzahl der Eintraege den vereinbarten Stand uebersteigt.

Regeln:
  - `MAX_ENTRIES` ist der vereinbarte Stand (bei Abbau senken!).
  - Weniger Eintraege sind ausdruecklich in Ordnung (Abbau ist Fortschritt).
  - Gezaehlt werden nur echte Baseline-Zeilen (`<ID>...` am Zeilenanfang),
    nicht Vorkommen in Kommentaren.
  - Der Exit-Code ist 1, sobald die Zahl zu gross ist.

Exit-Code 0 = Baseline im Rahmen, 1 = Baseline gewachsen.
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
BASELINE = REPO / "config" / "detekt" / "baseline.xml"

# D2 (2026-09-22): 23 Eintraege nach dem Detekt-Teilpaket (C16-Koordinator
# zweimal, sonst Altlasten aus A/B). Zahl beim Abbau mit senken.
MAX_ENTRIES = 23

ENTRY = re.compile(r"^\s*<ID>", re.MULTILINE)


def main() -> int:
    text = BASELINE.read_text(encoding="utf-8")
    count = len(ENTRY.findall(text))
    if count > MAX_ENTRIES:
        print(
            f"detekt-Baseline: {count} Eintraege > erlaubt {MAX_ENTRIES}.\n"
            "Neue Fundstellen nicht in die Baseline aufnehmen, sondern beheben\n"
            "(oder die Regel fuer Produktivcode gezielt anpassen).",
        )
        return 1
    print(f"detekt-Baseline: {count} Eintraege (erlaubt {MAX_ENTRIES}) — ok.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
