#!/usr/bin/env bash
#
# build-ffmpeg.sh — baut die Media3-FFmpeg-Audiodecoder-Extension fuer
# DropSync (Plan Phase 3, ADR-0006) und legt das lokale Gradle-Modul
# libs/media3-ffmpeg/ an. Es wird ein rein auf Audio reduzierter,
# LGPL-konformer FFmpeg-Build genutzt (keine Encoder, kein GPL/nonfree).
#
# Dieses Skript laeuft NICHT in der CI-Sandbox (kein NDK). Es ist fuer
# einen Entwickler-Host mit Android NDK r28+ (16-KB-Page-Alignment ist
# dort Default; r27 braeuchte -Wl,-z,max-page-size=16384), make/clang/bash
# gedacht (Windows: WSL). Ausfuehrliche Erklaerung: docs/ffmpeg-build.md.
#
# Nutzung:
#   ANDROID_NDK_HOME=/pfad/zum/ndk \
#     scripts/build-ffmpeg.sh [ARBEITSVERZEICHNIS]
#
# Optionale Umgebungsvariablen:
#   MEDIA_VERSION   Git-Tag/Branch von androidx/media (Default: 1.10.1)
#   FFMPEG_REF      Git-Tag/Branch von FFmpeg (Default: n6.1.2)
#   ANDROID_ABIS    Leerzeichenliste der ABIs (Default: arm64-v8a armeabi-v7a x86_64)
#   ANDROID_API     Mindest-API des nativen Builds (Default: 24)
#
set -euo pipefail

# --- Zielformate: nur Audio-Decoder, LGPL-konform (siehe docs/ffmpeg-build.md).
ENABLED_DECODERS=(
  alac aiff wmav1 wmav2 wmapro ape tak tta
  dsd_lsbf dsd_msbf dsd_lsbf_planar dsd_msbf_planar wavpack
)

MEDIA_VERSION="${MEDIA_VERSION:-1.10.1}"
FFMPEG_REF="${FFMPEG_REF:-n6.1.2}"
ANDROID_API="${ANDROID_API:-24}"
read -r -a ANDROID_ABIS <<<"${ANDROID_ABIS:-arm64-v8a armeabi-v7a x86_64}"

# Repo-Wurzel unabhaengig vom Aufrufort bestimmen.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
WORK_DIR="${1:-${REPO_ROOT}/.ffmpeg-build}"
DEST_DIR="${REPO_ROOT}/libs/media3-ffmpeg"

log() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31mFehler:\033[0m %s\n' "$*" >&2; exit 1; }

# --- Voraussetzungen pruefen ------------------------------------------------
[ -n "${ANDROID_NDK_HOME:-}" ] || die "ANDROID_NDK_HOME ist nicht gesetzt (NDK r28+ erwartet)."
[ -d "${ANDROID_NDK_HOME}" ] || die "ANDROID_NDK_HOME zeigt nicht auf ein Verzeichnis: ${ANDROID_NDK_HOME}"
for tool in git make clang; do
  command -v "${tool}" >/dev/null 2>&1 || die "Benoetigtes Werkzeug fehlt: ${tool}"
done

log "Arbeitsverzeichnis: ${WORK_DIR}"
mkdir -p "${WORK_DIR}"
cd "${WORK_DIR}"

# --- 1. androidx/media-Quellen holen ---------------------------------------
if [ ! -d media ]; then
  log "Klone androidx/media (${MEDIA_VERSION}) ..."
  git clone --depth 1 --branch "${MEDIA_VERSION}" https://github.com/androidx/media.git
else
  log "androidx/media bereits vorhanden — ueberspringe Klonen."
fi

FFMPEG_MODULE_PATH="${WORK_DIR}/media/libraries/decoder_ffmpeg/src/main"
[ -d "${FFMPEG_MODULE_PATH}/jni" ] || die "decoder_ffmpeg/src/main/jni nicht gefunden — passt MEDIA_VERSION?"

# --- 2. FFmpeg-Quellen holen -----------------------------------------------
if [ ! -d ffmpeg ]; then
  log "Klone FFmpeg (${FFMPEG_REF}) ..."
  git clone --depth 1 --branch "${FFMPEG_REF}" https://git.ffmpeg.org/ffmpeg.git
else
  log "FFmpeg bereits vorhanden — ueberspringe Klonen."
fi

# --- 3. FFmpeg in das JNI-Verzeichnis verlinken ----------------------------
log "Verlinke FFmpeg in das JNI-Verzeichnis ..."
ln -sfn "${WORK_DIR}/ffmpeg" "${FFMPEG_MODULE_PATH}/jni/ffmpeg"

# --- 4. Nativen Build ausfuehren (LGPL: KEIN --enable-gpl/--enable-nonfree) -
log "Baue FFmpeg-Decoder: ${ENABLED_DECODERS[*]}"
log "Ziel-ABIs: ${ANDROID_ABIS[*]} (API ${ANDROID_API})"
(
  cd "${FFMPEG_MODULE_PATH}/jni"
  ./build_ffmpeg.sh \
    "${FFMPEG_MODULE_PATH}" \
    "${ANDROID_NDK_HOME}" \
    "android-${ANDROID_API}" \
    "${ANDROID_ABIS[@]}" \
    "${ENABLED_DECODERS[@]}"
)

# --- 5. AAR der Extension bauen --------------------------------------------
log "Baue die Decoder-Extension (AAR) ..."
(
  cd "${WORK_DIR}/media"
  ./gradlew :lib-decoder-ffmpeg:assembleRelease
)

# --- 6. Artefakt in das DropSync-Modul kopieren ----------------------------
log "Uebernehme Artefakte nach ${DEST_DIR}"
mkdir -p "${DEST_DIR}/libs"
find "${WORK_DIR}/media/libraries/decoder_ffmpeg/buildout" \
  -name '*.aar' -exec cp -v {} "${DEST_DIR}/libs/" \; 2>/dev/null || true
# Fallback: rohe .so-Dateien uebernehmen, falls keine AAR erzeugt wurde.
if ! ls "${DEST_DIR}/libs/"*.aar >/dev/null 2>&1; then
  log "Keine AAR gefunden — kopiere jniLibs (.so) direkt."
  mkdir -p "${DEST_DIR}/src/main/jniLibs"
  cp -R "${FFMPEG_MODULE_PATH}/libs/." "${DEST_DIR}/src/main/jniLibs/" 2>/dev/null || \
    die "Weder AAR noch .so gefunden — Build fehlgeschlagen."
fi

# --- 7. Gradle-Modul anlegen (falls noch nicht vorhanden) ------------------
if [ ! -f "${DEST_DIR}/build.gradle.kts" ]; then
  log "Erzeuge ${DEST_DIR}/build.gradle.kts"
  cat >"${DEST_DIR}/build.gradle.kts" <<'GRADLE'
// :libs:media3-ffmpeg — selbstgebaute Media3-FFmpeg-Audiodecoder-Extension
// (Plan Phase 3, ADR-0006). Wird nur eingebunden, wenn dropsync.enableFfmpeg=true
// (siehe settings.gradle.kts). Erzeugt durch scripts/build-ffmpeg.sh.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.dropsync.libs.media3ffmpeg"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
    }
}

dependencies {
    // Prebuilt-AARs aus scripts/build-ffmpeg.sh (falls vorhanden).
    val aars = fileTree("libs") { include("*.aar") }
    if (!aars.isEmpty) api(aars)
    implementation(libs.androidx.media3.exoplayer)
}
GRADLE
fi

# --- 8. 16-KB-Page-Alignment verifizieren (Play-Pflicht ab targetSdk 35) --
# Jede LOAD-Zeile der .so muss auf 0x4000 aligned sein; mit NDK r28+ ist
# das Default. Details: docs/ffmpeg-build.md.
READELF="$(find "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt" -name llvm-readelf -type f 2>/dev/null | head -n1 || true)"
if [ -n "${READELF}" ]; then
  log "Pruefe 16-KB-Page-Alignment der nativen Libraries ..."
  CHECK_DIR="${WORK_DIR}/.page-size-check"
  rm -rf "${CHECK_DIR}" && mkdir -p "${CHECK_DIR}"
  # .so-Kandidaten einsammeln (direkt oder aus den AARs entpackt).
  cp -R "${DEST_DIR}/src/main/jniLibs/." "${CHECK_DIR}/" 2>/dev/null || true
  for aar in "${DEST_DIR}/libs/"*.aar; do
    [ -f "${aar}" ] && unzip -qo "${aar}" 'jni/*' -d "${CHECK_DIR}" 2>/dev/null || true
  done
  BAD=0
  while IFS= read -r -d '' so; do
    # POSIX-awk: Alignment steht als Hexwert am Zeilenende der LOAD-Zeilen;
    # 0x1000/0x2000 (< 16 KB) sind die Verletzungen.
    if "${READELF}" -l "${so}" | awk '$1=="LOAD" && ($NF=="0x1000" || $NF=="0x2000") { bad=1 } END { exit bad }'; then
      log "OK (16 KB): ${so#${CHECK_DIR}/}"
    else
      printf '\033[1;31mNICHT 16-KB-aligned:\033[0m %s\n' "${so#${CHECK_DIR}/}" >&2
      BAD=1
    fi
  done < <(find "${CHECK_DIR}" -name '*.so' -print0)
  rm -rf "${CHECK_DIR}"
  [ "${BAD}" -eq 0 ] || die "Mind. eine .so ist nicht 16-KB-aligned — NDK r28+ verwenden (siehe docs/ffmpeg-build.md)."
else
  log "Warnung: llvm-readelf nicht gefunden — 16-KB-Pruefung uebersprungen."
fi

log "Fertig. Aktivierung:"
cat <<EOF
  1. In gradle.properties setzen: dropsync.enableFfmpeg=true
  2. Build: ./gradlew :app:assembleDebug
     (settings.gradle.kts bindet :libs:media3-ffmpeg dann automatisch ein,
      die DspRenderersFactory bevorzugt die Extension.)
EOF
