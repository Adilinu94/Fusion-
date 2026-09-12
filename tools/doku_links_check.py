#!/usr/bin/env python3
"""Doku-Link-Check (Testinfrastruktur-Umbauplan Schritt 5.3, T5).

Prueft, dass alle internen Anker-Links der Form [Text](datei.md#anker)
ein Ziel mit <a name="anker"></a> haben und dass relative Datei-Links
existieren.

Zahl-Verweise ("Abschnitt <n>") sind in den lebenden Design-Plaenen
(docs/design/*.md) verboten, damit Umnummerierungen nie wieder still
brechen (Umbauplan-Schritt-6-Regel). Historische Dateien (STATUS, ADR,
Archive) duerfen sie behalten - sie beschreiben Vergangenheit.

Exit-Code 0 = alles gruen, 1 = tote Links oder nummerierte Verweise.
"""
import re
import sys
from pathlib import Path

DOCS = Path(__file__).resolve().parents[1] / "docs"
ARCHIVE = DOCS / "archive"
REPO = DOCS.parent

LINK_RE = re.compile(r"\[([^\]]*)\]\(([^)\s]+)\)")
NUMMER_RE = re.compile(r"\bAbschnitt\s+\d+(?:a|b)?\b", re.IGNORECASE)
# Zaeune zuerst, dann Inline-Spans. Inline-Code kann in Markdown keine
# Zeile ueberspannen - deshalb `[^`\n]*`. Ohne das \n frisst der Ausdruck
# alles zwischen zwei beliebigen Backticks im ganzen Dokument und
# verschiebt damit jede gemeldete Zeilennummer.
FENCE_RE = re.compile(r"```.*?```", re.DOTALL)
CODE_SPAN_RE = re.compile(r"`[^`\n]*`")

# Dateien aus dem DropSync-Ursprungsrepo, die hier bewusst nicht
# gespiegelt sind (Kopf-Tabelle des Design-Dokuments).
KNOWN_EXTERNAL = {
    "OFFTRACK_AUDIO_UMBAUHANDBUCH.md",
    "UI_UX_UMBAUHANDBUCH_TRAIN_MUSIC_DROPSYNC.md",
}


def collect_md_files() -> list[Path]:
    return [p for p in DOCS.rglob("*.md") if ARCHIVE not in p.parents]


def strip_code_spans(text: str) -> str:
    """Entfernt Code-Inhalte, haelt aber die Zeilenzahl stabil.

    Codeblock-Inhalte werden durch ebenso viele Leerzeilen ersetzt, damit
    die Zeilennummer in einer Fehlermeldung der Datei entspricht.
    """
    def blank_out(match: re.Match[str]) -> str:
        return "\n" * match.group(0).count("\n")

    return CODE_SPAN_RE.sub("", FENCE_RE.sub(blank_out, text))


def main() -> int:
    errors: list[str] = []
    files = collect_md_files()
    anchors: dict[str, set[str]] = {}
    # Pass 1: alle benannten Anker einsammeln.
    for f in files:
        text = f.read_text(encoding="utf-8")
        anchors[str(f)] = set(re.findall(r'<a name="([^"]+)"></a>', text))
    # Pass 2: Verweise pruefen.
    for f in files:
        text = strip_code_spans(f.read_text(encoding="utf-8"))
        rel = str(f.relative_to(DOCS))
        # Zahl-Verweis-Regel nur fuer lebende Design-Plaene.
        if rel.startswith("design"):
            for m in NUMMER_RE.finditer(text):
                errors.append(f"{rel}:{text[:m.start()].count(chr(10)) + 1}: "
                              f"Zahl-Verweis '{m.group(0)}' - bitte benannten Anker verwenden")
        for m in LINK_RE.finditer(text):
            target, anchor = m.group(2).split("#", 1) if "#" in m.group(2) else (m.group(2), None)
            if target.startswith(("http://", "https://", "mailto:")):
                continue
            if Path(target).name in KNOWN_EXTERNAL:
                continue
            target_path = (f.parent / target).resolve() if target else f
            if not target_path.exists():
                errors.append(f"{rel}: toter Link '{m.group(2)}'")
                continue
            if anchor is not None and target_path.suffix == ".md":
                if str(target_path) not in anchors:
                    # Ziel ausserhalb von docs/ (z. B. Root-README oder
                    # VERBESSERUNGSPLAN): Anker bei Bedarf einlesen statt
                    # abzustuerzen (ValueError in relative_to).
                    try:
                        outside = target_path.read_text(encoding="utf-8")
                    except OSError:
                        outside = ""
                    anchors[str(target_path)] = set(re.findall(r'<a name="([^"]+)"></a>', outside))
                if anchor not in anchors[str(target_path)]:
                    try:
                        shown = target_path.relative_to(DOCS)
                    except ValueError:
                        shown = target_path.relative_to(REPO)
                    errors.append(f"{rel}: Anker '#{anchor}' fehlt in {shown}")
    if errors:
        print("Doku-Link-Check FEHLGESCHLAGEN:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"Doku-Link-Check gruen ({len(files)} Dateien geprueft).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
