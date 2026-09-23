package com.dropsync.domain.audio

import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Phase des Beat-Rasters aus der Low-Band-Energie (B4/RC-22; Research
 * `2026-09-19-downbeat-offset.md`). Fuer ein Beat-Intervall wird die
 * tiefe Bandenergie (Low-Pass bei [CUTOFF_HZ]) phasen-gefaltet; die
 * Phase mit der hoechsten mittleren Energie traegt Kick/Bass und ist
 * damit der Anker des Rasters.
 *
 * **Abgrenzung (Umbauplan Phase 3.2):** Das ist die **Beat**-Phase, kein
 * musikalischer Taktanfang/Downbeat. Fuer das Marker-Snap genuegt sie:
 * der Marker soll auf *einen* Beat rasten, nicht auf den ersten Beat
 * eines Takts. Die Feldnamen (`downbeatOffsetMs`) folgen dem Bauplan B4;
 * die Bedeutung bleibt die Beat-Phase.
 *
 * **Ablauf:** [accept] filtert und puffert die Low-Band-Huellkurve in
 * [DEFAULT_WINDOW_MS]-Fenstern; [finish] wertet sie mit dem FINALEN BPM
 * aus. Das BPM steht erst am Ende des Ein-Pass-Decodes fest, daher der
 * Puffer (5 min ≈ 30.000 Doubles ≈ 240 KB, vernachlaessigbar gegen die
 * Waveform-Buckets).
 *
 * **Aufloesung:** [PHASE_STEPS] Phasenkandidaten ueber ein Beat-Intervall
 * (bei 128 BPM ≈ 10 ms je Schritt). Vier Phasen laut Research haetten den
 * Snap um bis zu einer Viertel-Beatperiode (117 ms bei 128 BPM)
 * verschoben und damit das Abnahmekriterium "ein Marker, der ohne Snap
 * richtig lag, wird nicht verschlechtert" verletzt.
 *
 * **Keine Aussage (null)** gibt es ohne BPM, ohne messbaren Low-Band-
 * Anteil (Stille vor Vermutung) und ohne Fensterdaten. Die Konfidenz
 * liefert die Schaetzung trotzdem mit; das Gate sitzt an der LESESEITE
 * ([DownbeatConfidence.acceptOffset]), damit die Rohwerte in der DB
 * bleiben und eine spaetere Schwellen-Kalibrierung ohne Neuanalyse
 * greift (gleiches Muster wie [MixConfidence]).
 */
class DownbeatAccumulator(
    private val sampleRateHz: Int,
    private val windowMs: Int = DEFAULT_WINDOW_MS,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz muss positiv sein" }
        require(windowMs > 0) { "windowMs muss positiv sein" }
    }

    private val lowPass =
        BiquadFilter(
            BiquadCoefficients.of(
                type = BiquadType.LOW_PASS,
                frequencyHz = CUTOFF_HZ,
                sampleRateHz = sampleRateHz.toDouble(),
                gainDb = 0.0,
                q = LOW_PASS_Q,
            ),
        )

    private val samplesPerWindow: Int = (sampleRateHz * windowMs / 1_000).coerceAtLeast(1)

    /** Low-Band-Huellkurve (RMS je Fenster) in Trackreihenfolge. */
    private val lowBandEnergies = ArrayList<Double>()

    private var lowBandSumOfSquares: Double = 0.0
    private var rawSumOfSquares: Double = 0.0

    /**
     * Gesamtenergie des Low-Bands (Summe der Fenster-Summen) fuer den
     * Anteils-Check. Bewusst getrennt von [lowBandEnergies]: die Liste
     * fuehrt RMS-Werte (normalisiert, fuer die Phasen-Faltung), der
     * Anteil vergleicht Energie mit Energie.
     */
    private var lowBandWindowEnergy: Double = 0.0
    private var rawWindowEnergy: Double = 0.0
    private var samplesInWindow: Int = 0

    /** Nimmt ein Mono-Sample im Bereich [-1.0, 1.0] auf. */
    fun accept(sample: Double) {
        val low = lowPass.process(sample)
        lowBandSumOfSquares += low * low
        rawSumOfSquares += sample * sample
        samplesInWindow++
        if (samplesInWindow == samplesPerWindow) {
            closeWindow()
        }
    }

    /**
     * Liefert den Raster-Offset in ms samt Konfidenz 0..1 oder null, wenn
     * keine belastbare Aussage moeglich ist. [bpm] ist der finale
     * Tempo-Wert der Analyse (gefaltet, 60..200); Werte ausserhalb
     * 30..300 gelten als unbrauchbar (gleicher Waechter wie das Snap).
     */
    fun finish(bpm: Float?): DownbeatEstimate? {
        // Angefangenes Restfenster wie beim EnergyAccumulator mitnehmen.
        if (samplesInWindow * 2 >= samplesPerWindow) {
            closeWindow()
        }
        if (bpm == null || bpm !in MIN_BPM..MAX_BPM) return null
        if (lowBandEnergies.isEmpty()) return null

        val totalLow = lowBandWindowEnergy
        val totalRaw = rawWindowEnergy
        if (totalRaw <= 0.0) return null
        if (totalLow < MIN_LOW_BAND_SHARE * totalRaw) return null

        val beatMs = 60_000.0 / bpm
        val sums = DoubleArray(PHASE_STEPS)
        val counts = IntArray(PHASE_STEPS)
        lowBandEnergies.forEachIndexed { index, energy ->
            val centerMs = (index + 0.5) * windowMs
            val phase = (centerMs % beatMs) / beatMs
            val bin = (phase * PHASE_STEPS).toInt().coerceIn(0, PHASE_STEPS - 1)
            sums[bin] += energy
            counts[bin]++
        }

        var bestIndex = -1
        var bestMean = 0.0
        for (bin in 0 until PHASE_STEPS) {
            if (counts[bin] == 0) continue
            val mean = sums[bin] / counts[bin]
            if (mean > bestMean) {
                bestMean = mean
                bestIndex = bin
            }
        }
        if (bestIndex < 0 || bestMean <= 0.0) return null

        val secondMean = secondBestMean(sums, counts, bestIndex)
        val confidence = ((bestMean - secondMean) / bestMean).coerceIn(0.0, 1.0).toFloat()
        // Bin-Mittelpunkt als Offset: der Angriff liegt irgendwo im besten
        // Bin, der Mittelpunkt haelt den systematischen Versatz bei
        // hoechstens einer halben Bin-Breite (≈ 10 ms bei 128 BPM).
        val offsetMs = ((bestIndex + 0.5) * beatMs / PHASE_STEPS).roundToLong()
        return DownbeatEstimate(offsetMs = offsetMs, confidence = confidence)
    }

    private fun closeWindow() {
        lowBandEnergies += sqrt(lowBandSumOfSquares / samplesInWindow)
        lowBandWindowEnergy += lowBandSumOfSquares
        rawWindowEnergy += rawSumOfSquares
        lowBandSumOfSquares = 0.0
        rawSumOfSquares = 0.0
        samplesInWindow = 0
    }

    /**
     * Zweitbeste Phase ausserhalb der Naehe des Siegers (zirkulaer):
     * direkt benachbarte Bins tragen bei feiner Aufloesung immer Energie
     * desselben Angriffs und wuerden die Konfidenz systematisch druecken.
     */
    private fun secondBestMean(
        sums: DoubleArray,
        counts: IntArray,
        bestIndex: Int,
    ): Double {
        var second = 0.0
        // Die Naehe des Siegers ist ausgeschlossen (zirkulaer); nur die
        // Bins mit Daten zaehlen.
        for (offset in (EXCLUSION_BINS + 1) until (PHASE_STEPS - EXCLUSION_BINS)) {
            val bin = (bestIndex + offset) % PHASE_STEPS
            if (counts[bin] == 0) continue
            val mean = sums[bin] / counts[bin]
            if (mean > second) second = mean
        }
        return second
    }

    companion object {
        /** Low-Pass-Grenze des Bassbands (Research: 40-120 Hz). */
        const val CUTOFF_HZ: Double = 120.0

        /** Butterworth-Guete des Low-Pass (flach, keine Resonanz). */
        const val LOW_PASS_Q: Double = 0.707

        /**
         * Huellkurven-Fenster: feiner als die 25-ms-Onset-Fenster, weil
         * die Angriffszeit direkt in den Offset eingeht.
         */
        const val DEFAULT_WINDOW_MS: Int = 10

        /**
         * Phasenkandidaten je Beat-Intervall (gleicher Wert wie die
         * Onset-Variante im Umbauplan Phase 3.2).
         */
        const val PHASE_STEPS: Int = 48

        /** Bins links/rechts des Siegers, die nicht als "zweitbeste" zaehlen. */
        const val EXCLUSION_BINS: Int = 4

        /**
         * Mindestanteil der Low-Band-Energie an der Gesamtenergie. Darunter
         * gibt es keine Aussage: ein Track ohne Bass/Kick hat kein
         * tiefes Raster, und ein geratener Offset waere schaedlicher als
         * kein Snap. Vorlaeufig, Kalibrierung folgt mit echten Titeln.
         */
        const val MIN_LOW_BAND_SHARE: Double = 0.05

        const val MIN_BPM: Float = 30f
        const val MAX_BPM: Float = 300f
    }
}

/** Raster-Offset in ms samt Konfidenz 0..1 (Phase des Beat-Rasters). */
data class DownbeatEstimate(
    val offsetMs: Long,
    val confidence: Float,
)

/**
 * Konfidenz-Gate des Raster-Offsets an der Leseseite (Muster
 * [MixConfidence]): die Rohwerte bleiben in der DB, erst der Konsument
 * (Marker-Snap) verwirft unsichere Schaetzungen.
 *
 * Die Schwelle ist vorlaeufig und bewusst PERMISSIV: sie wirft nur weg,
 * was messbar keine Phase traegt (flache Huellkurve, Rauschen). Echte
 * Kalibrierung braucht Aufnahmen mit gehoertem Beat (Gate 11b).
 */
object DownbeatConfidence {
    /**
     * Gemessene synthetische Faelle: klarer Kick auf dem Beat ≈ 0,5..0,9,
     * flaches Low-Band ≈ 0,0. 0,2 trennt mit Abstand.
     */
    const val MIN_SNAP_CONFIDENCE: Float = 0.2f

    /** Gibt [offsetMs] frei, wenn [confidence] die Schwelle erreicht. */
    fun acceptOffset(
        offsetMs: Long?,
        confidence: Float?,
    ): Long? = offsetMs?.takeIf { (confidence ?: 0f) >= MIN_SNAP_CONFIDENCE }
}
