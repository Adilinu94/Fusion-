package com.dropsync.domain.sensor.sweep

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Lese-Schicht des Offline-Sweeps (Umbauplan 2026-09-04 Phase 6.2).
 *
 * Liest das Corpus-Format, das `JsonlShadowSessionRecorder` schreibt und
 * `tools/shadow_harness.py` bereits liest: `<session>.jsonl` mit
 * `set_window`-Zeilen (inkl. Profilfelder, Nachtrag Phase 0.7) plus
 * `sample`-Zeilen, und `<session>.jsonl.meta.json` mit Szenario-Tag und
 * Wahrheit (`known_active_reps`).
 *
 * Bewusst manueller Parser statt kotlinx-serialization: dasselbe Argument wie
 * beim Recorder ("fixed, small field set, not worth pulling a dependency").
 * Das Format ist flach — Zahlen, Strings, genau zwei Array-Felder
 * (`axis`, `bias` in `set_window`, `known_active_reps` im Manifest).
 */

/** Ein Rohsample aus einer JSONL-`sample`-Zeile. */
data class CorpusSample(
    val timestampMs: Long,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val gx: Double,
    val gy: Double,
    val gz: Double,
) {
    /** Magnitude der Beschleunigung in g — Basis der Accel-Abweichung. */
    val accelMagnitude: Double get() = sqrt(ax * ax + ay * ay + az * az)
}

/**
 * Ein Satzfenster aus der JSONL (`set_window` + alle zugehoerigen Samples).
 *
 * Die Profilfelder stammen aus dem Nachtrag Phase 0.7: ohne sie ist ein
 * Fenster fuer Sweeps unbrauchbar ([hasProfile] false), weil das Replay
 * sonst auf die Neutralachse projiziert und eine Pipeline misst, die live
 * nie gelaufen ist.
 */
data class CorpusWindow(
    val setIndex: Int,
    val exerciseId: Long,
    val rateHz: Double,
    val axis: List<Double>?,
    val bias: List<Double>?,
    val theta: Double,
    val prominence: Double,
    val durationMs: Double,
    val accelTheta: Double,
    val revision: Int,
    val samples: List<CorpusSample>,
) {
    val hasProfile: Boolean get() = axis != null && bias != null
}

/** Manifest daneben: `<session>.jsonl.meta.json`. */
data class CorpusManifest(
    val scenario: String,
    val exerciseId: String,
    val knownActiveReps: List<Int>,
) {
    /** Wahrheit fuer Satz [setIndex]; null, wenn das Manifest keine hat. */
    fun expectedReps(setIndex: Int): Int? = knownActiveReps.getOrNull(setIndex)
}

/** Eine geladene Corpus-Session: JSONL-Fenster + Manifest. */
data class CorpusSession(
    val name: String,
    val manifest: CorpusManifest,
    val windows: List<CorpusWindow>,
)

/**
 * Laedt alle `<name>.jsonl` + `<name>.jsonl.meta.json`-Paare aus [corpusDir].
 * Dateien ohne Partner werden gemeldet (Rueckgabe), nicht geworfen — ein
 * Sweep ueber einen halben Corpus waere still falsch, ein Abbruch ueber eine
 * zusaetzliche Aufnahme aber unnoetig streng.
 */
class CorpusLoader(
    private val corpusDir: Path,
) {
    fun load(): Pair<List<CorpusSession>, List<String>> {
        val issues = mutableListOf<String>()
        val sessions = mutableListOf<CorpusSession>()
        val jsonlFiles =
            Files.list(corpusDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jsonl") }.sorted().toList()
            }
        for (jsonl in jsonlFiles) {
            loadSession(jsonl, issues)?.let { sessions += it }
        }
        return sessions to issues
    }

    /** Laedt ein JSONL/Manifest-Paar; null bei fehlendem Partner (gemeldet). */
    private fun loadSession(
        jsonl: Path,
        issues: MutableList<String>,
    ): CorpusSession? {
        val meta = jsonl.resolveSibling(jsonl.fileName.toString() + ".meta.json")
        if (!Files.exists(meta)) {
            issues += "${jsonl.fileName}: kein Manifest (${meta.fileName}) — uebersprungen"
            return null
        }
        val manifest = parseManifest(Files.readString(meta))
        val windows = parseWindows(Files.readAllLines(jsonl))
        if (windows.isEmpty()) {
            issues += "${jsonl.fileName}: keine set_window-Fenster — uebersprungen"
            return null
        }
        return CorpusSession(jsonl.fileName.toString().removeSuffix(".jsonl"), manifest, windows)
    }

    private fun parseWindows(lines: List<String>): List<CorpusWindow> {
        val windows = mutableListOf<CorpusWindow>()
        var current: Pair<Map<String, String>, MutableList<CorpusSample>>? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || !line.startsWith("{")) continue
            val fields = JsonlLines.parseTopLevel(line)
            when (fields["t"]) {
                "set_window" -> {
                    current?.let { windows += finishWindow(it.first, it.second) }
                    current = fields to mutableListOf()
                }

                "sample" -> {
                    current = appendSample(current, fields)
                }
            }
        }
        current?.let { windows += finishWindow(it.first, it.second) }
        return windows
    }

    /**
     * Ordnet eine `sample`-Zeile dem offenen Fenster zu. Samples ohne
     * passendes Fenster (Formatfehler, den shadow_harness.py bereits meldet)
     * werden ignoriert — der Sweep darf auf keinen Fall Samples einem
     * falschen Satz zuordnen.
     */
    private fun appendSample(
        current: Pair<Map<String, String>, MutableList<CorpusSample>>?,
        fields: Map<String, String>,
    ): Pair<Map<String, String>, MutableList<CorpusSample>>? {
        val open = current ?: return current
        val setIndex = fields["setIndex"]?.toIntOrNull() ?: return current
        if (open.first["setIndex"]?.toIntOrNull() != setIndex) return current
        open.second += sampleOf(fields)
        return current
    }

    private fun finishWindow(
        windowFields: Map<String, String>,
        samples: MutableList<CorpusSample>,
    ): CorpusWindow =
        CorpusWindow(
            setIndex = windowFields["setIndex"]?.toIntOrNull() ?: -1,
            exerciseId = windowFields["exerciseId"]?.toLongOrNull() ?: 0L,
            rateHz = windowFields["rateHz"]?.toDoubleOrNull() ?: 50.0,
            axis = windowFields["axis"]?.toDoubleList(),
            bias = windowFields["bias"]?.toDoubleList(),
            theta = windowFields["theta"]?.toDoubleOrNull() ?: DEFAULT_THETA,
            prominence = windowFields["prominence"]?.toDoubleOrNull() ?: DEFAULT_PROMINENCE,
            durationMs = windowFields["durationMs"]?.toDoubleOrNull() ?: DEFAULT_DURATION_MS,
            accelTheta = windowFields["accelTheta"]?.toDoubleOrNull() ?: 0.0,
            revision = windowFields["revision"]?.toIntOrNull() ?: 0,
            samples = samples.toList(),
        )

    private fun sampleOf(fields: Map<String, String>): CorpusSample =
        CorpusSample(
            timestampMs = fields["ts"]?.toLongOrNull() ?: 0L,
            ax = fields["ax"]?.toDoubleOrNull() ?: 0.0,
            ay = fields["ay"]?.toDoubleOrNull() ?: 0.0,
            az = fields["az"]?.toDoubleOrNull() ?: 0.0,
            gx = fields["gx"]?.toDoubleOrNull() ?: 0.0,
            gy = fields["gy"]?.toDoubleOrNull() ?: 0.0,
            gz = fields["gz"]?.toDoubleOrNull() ?: 0.0,
        )

    private fun parseManifest(text: String): CorpusManifest {
        // Manifest ist eingerueckt (json.dump indent=2); fuer dieses flache
        // Schema reicht: Whitespace entfernen, dann Feldweise extrahieren.
        val flat = text.replace("\n", "").replace(" ", "")
        return CorpusManifest(
            scenario = JsonlLines.stringAt(flat, "scenario") ?: "unknown",
            exerciseId = JsonlLines.stringAt(flat, "exercise_id") ?: "unknown",
            knownActiveReps = JsonlLines.arrayAt(flat, "known_active_reps").mapNotNull { it.toIntOrNull() },
        )
    }

    private companion object {
        const val DEFAULT_THETA = 32.5
        const val DEFAULT_PROMINENCE = 1.0
        const val DEFAULT_DURATION_MS = 1_000.0
    }
}

/**
 * Minimal-Parser fuer die JSONL-Zeilen des Recorders (flache Objekte, genau
 * ein Level Arrays). Kein genereller JSON-Parser — das Format ist Vertrag
 * (`JsonlShadowSessionRecorder`; "protocol: eine Quelle der Wahrheit").
 */
internal object JsonlLines {
    /** Zerlegt eine flache JSON-Zeile in rohe Feldwerte (Strings unquoted). */
    fun parseTopLevel(line: String): Map<String, String> {
        val body = line.trim().removePrefix("{").removeSuffix("}")
        val fields = mutableMapOf<String, String>()
        var depth = 0
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (c in body) {
            when {
                c == '[' || c == '{' -> {
                    depth++
                    current.append(c)
                }

                c == ']' || c == '}' -> {
                    depth--
                    current.append(c)
                }

                c == ',' && depth == 0 -> {
                    parts += current.toString()
                    current.setLength(0)
                }

                else -> {
                    current.append(c)
                }
            }
        }
        if (current.isNotEmpty()) parts += current.toString()
        for (part in parts) {
            val colon = part.indexOf(':')
            if (colon <= 0) continue
            val key = part.substring(0, colon).trim().removeSurrounding("\"")
            val value = part.substring(colon + 1).trim()
            fields[key] = value.removeSurrounding("\"")
        }
        return fields
    }

    /** `"key":"value"` aus einem flachen, whitespace-bereinigten JSON-Text. */
    fun stringAt(
        flat: String,
        key: String,
    ): String? {
        val needle = "\"$key\":"
        val start = flat.indexOf(needle)
        if (start < 0) return null
        val valueStart = start + needle.length + 1
        val valueEnd = flat.indexOf('"', valueStart)
        if (valueEnd < 0) return null
        return flat.substring(valueStart, valueEnd)
    }

    /** `"key":[a,b,c]` als Liste roher Werte. */
    fun arrayAt(
        flat: String,
        key: String,
    ): List<String> {
        val needle = "\"$key\":"
        val start = flat.indexOf(needle)
        if (start < 0) return emptyList()
        val open = flat.indexOf('[', start)
        if (open < 0) return emptyList()
        val close = flat.indexOf(']', open)
        if (close < 0) return emptyList()
        val content = flat.substring(open + 1, close)
        if (content.isBlank()) return emptyList()
        return content.split(',')
    }
}

private fun String.toDoubleList(): List<Double> =
    removeSurrounding("[", "]").split(',').mapNotNull { it.trim().toDoubleOrNull() }
