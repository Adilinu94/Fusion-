#!/usr/bin/env python3
"""Design-Gate (Ausbauplan C1): keine hartcodierten Farben in Features.

Prueft, dass produktiver UI-Code ausserhalb des Designsystems keine
Compose-Farb-Literale mehr traegt. Farben gehoeren als Tokens in
`core/designsystem/.../theme` (siehe `BrandTokens.kt`, `Theme.kt`), damit
Palette, Hell/Dunkel und Akzent zentral pflegbar bleiben.

Erlaubt:
  - `core/designsystem/**` ist die Token-Quelle selbst.
  - Echte Ausnahmen stehen in `ALLOWLIST` (Datei -> Begruendung). Aktuell
    nur die cover-abgeleiteten Player-Farben: sie werden aus dem Bildinhalt
    berechnet und sind bewusst keine Marken-Tokens.

Nicht gemeldet: `Color.Transparent`/`Color.Unspecified` (semantische
Konstanten), `android.graphics.Color.*` und Kommentare.

Exit-Code 0 = alles gruen, 1 = gefundene Literale.
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

# Dateien (Repo-relativ, mit /), die bewusst Farben berechnen duerfen.
ALLOWLIST = {
    "feature/player/src/main/kotlin/com/dropsync/feature/player/PlayerArtworkColors.kt":
        "Farben werden aus dem Cover-Bitmap berechnet, keine Marken-Palette.",
}

HEX_RE = re.compile(r"\bColor\((0x[0-9A-Fa-f]+)\)")
NAMED_RE = re.compile(
    r"\bColor\.(White|Black|Red|Green|Blue|Yellow|Cyan|Magenta|Gray|LightGray|DarkGray)\b"
)


def code_lines(text: str):
    """Zeilen ohne Kommentaranteil (Zeilennummern bleiben stabil)."""
    in_block = False
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if in_block:
            if "*/" in stripped:
                in_block = False
            continue
        if stripped.startswith("/*"):
            if "*/" not in stripped:
                in_block = True
            continue
        if stripped.startswith("*") or stripped.startswith("//"):
            continue
        cut = line.find("//")
        if cut != -1:
            line = line[:cut]
        yield number, line


def main() -> int:
    sources = sorted(REPO.glob("*/src/main/**/*.kt")) + sorted(
        REPO.glob("*/*/src/main/**/*.kt")
    )
    findings: list[str] = []
    for path in sources:
        rel = str(path.relative_to(REPO)).replace("\\", "/")
        if rel.startswith("core/designsystem/") or rel in ALLOWLIST:
            continue
        for number, line in code_lines(path.read_text(encoding="utf-8")):
            for match in (HEX_RE.search(line), NAMED_RE.search(line)):
                if match is not None:
                    findings.append(f"{rel}:{number}: {match.group(0)}")

    if findings:
        print("Design-Gate rot: hartcodierte Farben ausserhalb des Themes.")
        for finding in findings:
            print(f"  {finding}")
        print("Loesung: Token in core/designsystem/.../theme anlegen (oder "
              "begruendet in ALLOWLIST von tools/design_check.py aufnehmen).")
        return 1

    print("Design-Gate gruen: keine hartcodierten Farben in Features.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
