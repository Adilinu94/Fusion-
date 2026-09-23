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
import os
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

# A7/I-4: Inline-Code-Pfade wie `feature/player/.../Foo.kt:123` muessen auf
# eine existierende Datei zeigen. Bewusst konservativ: nur bekannte
# Quell-Endungen, nur Pfade mit Verzeichnisanteil oder bekanntem Wurzelordner,
# keine Platzhalter (`...`, `<...>`, `*`).
PATH_SPAN_RE = re.compile(
    r"`([A-Za-z0-9_./-]+\.(?:kt|kts|py|md|xml|yml|yaml|json|toml))(?::\d+(?:-\d+)?)?`"
)
PATH_ROOTS = (
    "app/",
    "core/",
    "data/",
    "domain/",
    "feature/",
    "training-core/",
    "tools/",
    "docs/",
    "gradle/",
    ".github/",
    "benchmarks/",
)

# Dateien aus dem DropSync-Ursprungsrepo, die hier bewusst nicht
# gespiegelt sind (Kopf-Tabelle des Design-Dokuments).
KNOWN_EXTERNAL = {
    "OFFTRACK_AUDIO_UMBAUHANDBUCH.md",
    "UI_UX_UMBAUHANDBUCH_TRAIN_MUSIC_DROPSYNC.md",
}


def collect_md_files() -> list[Path]:
    """Alle lebenden Doku-Dateien: docs/ (ohne Archiv) plus Root-Markdown.

    A7/I-4: Die Root-Plaene (README, VERBESSERUNGSPLAN, BAUPLAN, Handbuecher)
    liefen bisher an keinem Check vorbei - genau dort lebten die toten
    Verweise.
    """
    docs = [p for p in DOCS.rglob("*.md") if ARCHIVE not in p.parents]
    root = [p for p in REPO.glob("*.md")]
    return sorted(set(docs + root))


_REPO_FILES: list[str] | None = None


def repo_files() -> list[str]:
    """Alle Repo-Dateien (ohne Build-/VCS-Ordner), einmalig gesammelt."""
    global _REPO_FILES
    if _REPO_FILES is None:
        files: list[str] = []
        for dirpath, dirnames, filenames in os.walk(REPO):
            dirnames[:] = [d for d in dirnames if d not in {".git", ".gradle", "build", ".idea"}]
            for name in filenames:
                files.append(os.path.join(dirpath, name).replace("\\", "/"))
        _REPO_FILES = files
    return _REPO_FILES


def path_exists(candidate: str) -> bool:
    """Existiert der Pfad direkt oder als abgekuerzter Modulpfad?

    Doku kuerzt oft ab: `data/playback/Foo.kt` meint
    `data/playback/src/main/kotlin/com/dropsync/data/playback/Foo.kt`.
    Ein Suffix-Vergleich auf Dateiebene deckt das ab, ohne echte tote
    Verweise zu verstecken.
    """
    if (REPO / candidate).exists():
        return True
    needle = "/" + candidate.replace("\\", "/")
    return any(p.endswith(needle) for p in repo_files())


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
        raw = f.read_text(encoding="utf-8")
        text = strip_code_spans(raw)
        rel = str(f.relative_to(REPO))
        # Zahl-Verweis-Regel nur fuer lebende Design-Plaene.
        if rel.startswith("docs/design") or rel.startswith("design"):
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
        # A7/I-4: Inline-Code-Pfade der LEBENDEN Root-Doku muessen auf
        # existierende Dateien zeigen. Historische docs/-Plaene duerfen
        # geplante oder spaeter entfernte Pfade nennen (Vergangenheit).
        # D7: Die Root-Plaene sind nach docs/plans/ gewandert und behalten
        # die Pruefung dort (sonst haette der Umzug sie still abgeschwaecht).
        if f.parent == REPO or f.parent == DOCS / "plans":
            for m in PATH_SPAN_RE.finditer(raw):
                candidate = m.group(1)
                if "..." in candidate:
                    continue
                if not any(candidate.startswith(root) for root in PATH_ROOTS):
                    continue
                if Path(candidate).name in KNOWN_EXTERNAL:
                    continue
                if not path_exists(candidate):
                    errors.append(f"{rel}: toter Inline-Pfad '{candidate}'")
    if errors:
        print("Doku-Link-Check FEHLGESCHLAGEN:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"Doku-Link-Check gruen ({len(files)} Dateien geprueft).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
