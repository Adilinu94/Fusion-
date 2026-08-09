# FFmpeg-Extension auf einem Linux-PC bauen (Schritt-fuer-Schritt)

Einfache Anleitung, um das FFmpeg-Decoder-Artefakt (`:libs:media3-ffmpeg`,
ADR-0006) direkt auf einem Linux-Rechner zu bauen und ueber Git zurueck in
das Projekt zu bringen. Hintergruende und der WSL-Weg fuer Windows stehen
in `docs/ffmpeg-build.md`.

> Ohne dieses Artefakt laeuft die App vollstaendig und nutzt die
> Plattformdecoder. Nur ALAC/AIFF/WMA/APE/TAK/TTA/DSD/WavPack brauchen
> die Extension.

## Schritt 1: Pakete installieren

Debian/Ubuntu/Mint:

```bash
sudo apt update
sudo apt install -y git make clang unzip pkg-config nasm openjdk-17-jdk
```

Fedora:

```bash
sudo dnf install -y git make clang unzip pkgconf-pkg-config nasm java-17-openjdk-devel
```

Arch:

```bash
sudo pacman -S --needed git make clang unzip pkgconf nasm jdk17-openjdk
```

## Schritt 2: Android SDK bereitstellen

Der AAR-Bau (Schritt 5 des Skripts) braucht ein Android SDK. Zwei Wege:

**A) Android Studio fuer Linux ist installiert:** nichts weiter noetig,
nur den SDK-Pfad merken (meist `~/Android/Sdk`).

**B) Nur Kommandozeile (ohne Android Studio):**

```bash
mkdir -p ~/Android/Sdk/cmdline-tools
cd ~/Android/Sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip && mv cmdline-tools latest
yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses
```

Danach in beiden Faellen exportieren (fehlende Plattformen/Build-Tools
laedt Gradle bei akzeptierten Lizenzen selbst nach):

```bash
export ANDROID_HOME=~/Android/Sdk
```

## Schritt 3: Android NDK r28 herunterladen

```bash
cd ~
wget https://dl.google.com/android/repository/android-ndk-r28c-linux.zip
unzip -q android-ndk-r28c-linux.zip
```

Wichtig: **r28 oder neuer** — erst r28 aligniert native Libraries
standardmaessig auf 16-KB-Pages (Google-Play-Pflicht ab targetSdk 35,
siehe `docs/ffmpeg-build.md`). Das Build-Skript prueft das Alignment am
Ende automatisch.

## Schritt 4: Projekt klonen

```bash
cd ~
git clone https://github.com/Adilinu94/DropSync-Timer.git
cd DropSync-Timer
```

(Repo schon vorhanden? Dann nur `git pull`.)

## Schritt 5: Build-Skript starten

```bash
export ANDROID_HOME=~/Android/Sdk
ANDROID_NDK_HOME=~/android-ndk-r28c bash scripts/build-ffmpeg.sh ~/ffmpeg-build
```

Das Skript erledigt alles selbst:

1. laedt androidx/media (1.10.1) und FFmpeg (n6.1.2),
2. baut nur die Audio-Decoder (LGPL-konform, kein GPL/nonfree),
3. baut die Extension als AAR,
4. kopiert das Ergebnis nach `libs/media3-ffmpeg/` im Repo,
5. verifiziert das 16-KB-Page-Alignment (`OK (16 KB): ...` je Library).

Endet es mit `Fertig. Aktivierung: ...`, ist alles gut gelaufen.

## Schritt 6: Artefakt committen und pushen

```bash
git add libs/media3-ffmpeg
git commit -m "FFmpeg-Decoder-Extension: natives Artefakt (NDK r28c, 16-KB-aligned)"
git push origin main
```

## Schritt 7: Auf dem Entwicklungsrechner aktivieren

Auf dem Windows-Rechner (oder wo die App gebaut wird):

1. `git pull`
2. In `gradle.properties` setzen: `dropsync.enableFfmpeg=true`
3. Bauen: `./gradlew :app:assembleDebug`
   (`settings.gradle.kts` bindet `:libs:media3-ffmpeg` automatisch ein,
   die `DspRenderersFactory` bevorzugt die Extension.)
4. Test: ALAC-/APE-/DSD-Datei abspielen; das Audioinformationen-Panel
   zeigt den aktiven Decoder.

## Fehlerbehebung

| Problem | Loesung |
| ------- | ------- |
| `Benoetigtes Werkzeug fehlt: clang` | Schritt 1 wiederholen |
| `ANDROID_NDK_HOME ist nicht gesetzt` | Pfad pruefen: `ls ~/android-ndk-r28c` muss Inhalte zeigen |
| Gradle findet kein SDK (`SDK location not found`) | `export ANDROID_HOME=~/Android/Sdk` vor Schritt 5; Lizenzen akzeptiert? (Schritt 2B) |
| `NICHT 16-KB-aligned` | Aelteres NDK erwischt — wirklich r28+ nutzen (Schritt 3) |
| FFmpeg-configure bricht ab | `rm -rf ~/ffmpeg-build` und Schritt 5 neu starten |
| Formate spielen trotzdem nicht | Flag gesetzt? `libs/media3-ffmpeg/` samt AAR/`jniLibs` vorhanden? |
