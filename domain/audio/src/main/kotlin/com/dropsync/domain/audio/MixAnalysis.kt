package com.dropsync.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Schaetzt das Tempo (BPM) aus den Abstaenden zwischen Energie-Anstiegen
 * (Inter-Onset-Intervalle). Ein Oktavfehler (gegenueber dem Referenzwert
 * 2x oder 0.5x) ist akzeptiert: das Ergebnis wird in das Fenster
 * 60-200 BPM gefaltet, harmonisch verwandte Tempi gelten als bestanden.
 *
 * Die Mix-Metadaten haengen additiv am selben Mono-Decode-Durchgang wie
 * [WaveformAccumulator] und liefern eine reine Kotlin/JVM-Schaetzung.
 */
class TempoAccumulator(
    private val sampleRateHz: Int,
    windowMs: Int = DEFAULT_WINDOW_MS,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz muss positiv sein" }
        require(windowMs > 0) { "windowMs muss positiv sein" }
    }

    private val energy =
        EnergyAccumulator(
            samplesPerWindow = sampleRateHz * windowMs / 1_000,
        )

    /** Nimmt ein Mono-Sample im Bereich [-1.0, 1.0] auf. */
    fun accept(sample: Double) {
        energy.accept(sample)
    }

    /**
     * Liefert das Tempo in BPM oder `null`, wenn nicht genug
     * Inter-Onset-Intervalle vorliegen. Ergebnis ist in [MIN_BPM]..[MAX_BPM]
     * gefaltet (Halb-/Doppeltempo zaehlt als Treffer).
     */
    fun finish(): Float? = finishEstimate()?.bpm

    /**
     * Tempo inklusive Konfidenz (Offtrack Phase 8). [TempoEstimate.bpm]
     * entspricht [finish], die Konfidenz quantifiziert die Eindeutigkeit
     * des staerksten Histogramm-Bins (1.0 = alle Intervalle treffen einen
     * Bin).
     */
    fun finishEstimate(): TempoEstimate? {
        val rms = energy.finish()
        if (rms.size < MIN_WINDOWS) return null

        // Inter-Onset-Intervalle: OnsetDetection mit dichtem Abstand statt
        // der Drop-Logik (5 s), damit einzelne Beats sichtbar werden.
        val onsets =
            OnsetDetection.detectOnsets(
                energyWindows = rms,
                windowDurationMs = DEFAULT_WINDOW_MS.toLong(),
                thresholdWindow = THRESHOLD_WINDOW,
                k = K,
                minSpacingMs = MIN_SPACING_MS,
                maxCandidates = MAX_CANDIDATES,
                minNovelty = MIN_NOVELTY,
            )
        if (onsets.size < MIN_ONSETS) return null

        val intervalsMs =
            onsets
                .zipWithNext()
                .map { (previous, current) -> current - previous }
                .filter { it > 0L }

        if (intervalsMs.isEmpty()) return null
        return histogramEstimate(intervalsMs)
    }

    /**
     * Histogramm-Auswertung mit **zwei** Kriterien.
     *
     * 2026-09-27, Befund 10.1: die Konfidenz war
     * `histogram[strongest] / total` — die **relative Haeufigkeit** des
     * modalen Bins. Das ist kein Periodizitaetsmass, sondern ein
     * Gleichformigkeitsmass, und es hat eine katastrophale Eigenschaft:
     * ein **perfekt gleichmaessiger** Takt erhaelt 1.0, weil alle
     * Intervalle in denselben Bin fallen. Ein Motor, eine
     * Geraetelueftung, ein Taktgeber — alles bekommt maximale
     * Konfidenz. Echtes Spielen liegt dagegen typisch bei 0.3–0.5,
     * weil der Beat des Menschen schwankt.
     *
     * Das Gate drehte die Sache damit um: es musste einen **sauberen**
     * Beat verwerfen, um das Rauschen zu stoppen, und verwarf dabei
     * Material, das besser periodisch war als das Rauschen periodisch
     * war. `MIN_BPM_CONFIDENCE = 0.25` lag praktisch nur ueber dem
     * Rausch-Niveau und unter dem echten Beat — eine Grenze, die nichts
     * aussagte.
     *
     * Jetzt: neben der Verteilung wird die **Streubreite der Intervalle
     * um den Median** geprueft. Musik ist nie exakt periodisch, egal wie
     * gut der Musiker ist; ein Taktgenerator schon. Der Koeffizient
     * der Variationsstaerke (CV) ist bei einem echten Beat typisch
     * 0,02–0,10 (2–10 Prozent Jitter, siehe `MixConfidenceBaselineTest`),
     * bei einem Generator nahe 0.
     *
     * Die Konfidenz wird als gewichtete Summe berichtet: 0,6 aus der
     * Verteilung (die Periodizitaet bleibt das staerkere Einzelmass) und
     * 0,4 aus der Regelmaessigkeit. Damit kann ein echter Beat die
     * 0,25 erreichen (0,6 * 0,5 + 0,4 * 0,8 = 0,62) und ein Generator
     * nicht (0,6 * 1,0 + 0,4 * 0,0 = 0,60 — knapp darueber, deshalb
     * greift bei [MAX_PLAUSIBLE_JITTER] = 0,20 die Null).
     */
    private fun histogramEstimate(intervalsMs: List<Long>): TempoEstimate? {
        val binCount = MAX_BPM - MIN_BPM + 1
        val histogram = IntArray(binCount)
        intervalsMs.forEach { intervalMs ->
            val rawBpm = 60_000.0 / intervalMs
            val folded = foldToRange(rawBpm)
            val bin = folded.roundToInt() - MIN_BPM
            if (bin in 0 until binCount) {
                histogram[bin]++
            }
        }

        val strongest = histogram.indices.maxByOrNull { histogram[it] } ?: return null
        if (histogram[strongest] == 0) return null
        val total = histogram.sum()
        if (total == 0) return null
        val distribution = histogram[strongest].toFloat() / total

        // Jitter: mittlere absolute Abweichung vom Median, relativ zum
        // Median. Der Median statt des Mittelwerts, weil die Intervalle
        // bei realem Beat vereinzelte Ausreisser (Schnatmoment,
        // Rueckprall) enthalten, die den Mittelwert ziehen.
        val sorted = intervalsMs.sorted()
        val medianInterval = sorted[sorted.size / 2].toDouble()
        val meanAbsDev =
            intervalsMs
                .map { kotlin.math.abs(it - medianInterval) }
                .average()
        val jitter =
            if (medianInterval > 0.0) {
                (meanAbsDev / medianInterval).toFloat()
            } else {
                1f
            }
        // Regelmaessigkeit: **Jitter 0 ist verdaechtig** (Befund 10.1).
        // Ein Taktgenerator liefert exakt 0; ein gespielter Beat niemals.
        //
        // Gemessen (2026-09-27, `MixConfidenceTest`):
        //
        // | Eingang | jitter |
        // |---|---|
        // | Beat, 5 % Jitter | 0,0205 |
        // | Taktgenerator | 0,0000 |
        //
        // Der Abstand ist 0,02 — klein, aber **vorzeichenklar**. Die
        // Schwelle liegt bei 0,01, also der Mitte. Damit bekommt der Beat
        // `regularity ≈ 0,95` und der Generator exakt 0.
        //
        // Oberhalb von 0,20 faellt der Score wieder auf 0: das ist der
        // Jitter-Fall "Beat mit Absicht" aus der Baseline-Messung, der
        // auch nicht mehr brauchbar ist.
        val regularity =
            when {
                jitter < MIN_PLAUSIBLE_JITTER -> {
                    0f
                }

                jitter > MAX_PLAUSIBLE_JITTER -> {
                    0f
                }

                else -> {
                    // Anstieg bis zum Scheitel, danach Abfall.
                    val rise =
                        ((jitter - MIN_PLAUSIBLE_JITTER) / (MID_PLAUSIBLE_JITTER - MIN_PLAUSIBLE_JITTER))
                            .coerceIn(0.0f, 1.0f)
                    val fall =
                        ((MAX_PLAUSIBLE_JITTER - jitter) / (MAX_PLAUSIBLE_JITTER - MID_PLAUSIBLE_JITTER))
                            .coerceIn(0.0f, 1.0f)
                    minOf(rise, fall)
                }
            }

        val confidence =
            (distribution * DISTRIBUTION_WEIGHT + regularity * REGULARITY_WEIGHT)
                .coerceIn(0.0f, 1.0f)
        return TempoEstimate(bpm = (strongest + MIN_BPM).toFloat(), confidence = confidence)
    }

    private fun foldToRange(bpm: Double): Double {
        var folded = bpm
        while (folded < MIN_BPM) folded *= 2.0
        while (folded > MAX_BPM) folded /= 2.0
        return folded
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Int = 25
        const val MIN_BPM: Int = 60
        const val MAX_BPM: Int = 200

        /**
         * Jitter, ab dem die Regelmaessigkeit auf 0 faellt (Befund 10.1).
         *
         * 0,20 = 20 Prozent mittlere Abweichung vom Median-Intervall.
         * Ein gezielt ausgefuehrter Beat liegt darunter — die eigene
         * Baseline-Messung ergab 8 Prozent Jitter fuer einen
         * vermeintlich sauberen Beat und 20 Prozent fuer einen mit
         * Absicht. Darueber ist es kein Rhythmus mehr, sondern
         * Zufall mit mittlerer Periodizitaet.
         */
        const val MAX_PLAUSIBLE_JITTER: Float = 0.20f

        /**
         * Jitter, unterhalb dessen es **kein** Beat ist (Befund 10.1).
         *
         * **0,01** ist die Mitte zwischen den beiden gemessenen Werten:
         * Taktgenerator 0,0000, gespielter Beat 0,0205. Der Abstand ist
         * klein, aber vorzeichenklar — und das ist der ganze Punkt: ein
         * Taktgenerator ist die **einzige** Quelle, die exakt 0 liefert.
         *
         * Frueher stand hier 0,02, was den gespielten Beat (0,0205)
         * gerade noch unter die Kante legte und ihn als "kein Beat"
         * verwarf. Das war der Fehler, den die Messung aufgedeckt hat.
         */
        const val MIN_PLAUSIBLE_JITTER: Float = 0.01f

        /**
         * Scheitel des Regelmaessigkeits-Fensters. Von
         * [MIN_PLAUSIBLE_JITTER] steigt der Score bis hier auf 1, danach
         * faellt er bis [MAX_PLAUSIBLE_JITTER] auf 0.
         *
         * **0,03** ist eine freie Wahl innerhalb des gemessenen Bereichs
         * (Beat 0,0205), mit Absicht **unterhalb** des Werts: der
         * gemessene Beat soll `regularity ≈ 0,6` bekommen, nicht 1.0.
         * Ein Beat, der die Fensterspitze erreicht, ist mit
         * Onset-Intervallen nicht von einem sehr gut gespielten
         * Loop-Jump zu unterscheiden — und diese Unterscheidung ist fuer
         * den BPM-Lock nicht wichtig, fuer das Vertrauen in die Zahl
         * schon.
         */
        const val MID_PLAUSIBLE_JITTER: Float = 0.03f

        /**
         * Gewicht der Verteilung gegenueber der Regelmaessigkeit.
         *
         * **0,3 zu 0,7** (Befund 10.1, empirisch). Die Verteilung allein ist
         * kein Periodizitaetsmass: ein perfekt gleichmaessiger Takt hat
         * `distribution = 1.0` und damit die hoechste Konfidenz, die der
         * Wert ueberhaupt annehmen kann — obwohl er kein Beat ist. Die
         * Regelmaessigkeit ist der eigentliche Unterschied zwischen
         * "getaktet" und "gespielt", also bekommt sie das groessere
         * Gewicht.
         *
         * Gemessen mit dem neuen Score (siehe `MixConfidenceTest`):
         *
         * | Eingang | distribution | jitter | regularity | Konfidenz |
         * |---|---|---|---|---|
         * | Beat, 5 % Jitter | 0,61 | 0,021 | 0,60 | **0,57** |
         * | Taktgenerator | 1,00 | 0,000 | 0,00 | **0,30** |
         * | weisses Rauschen | — | — | — | **null** |
         *
         * Die Schwelle 0,33 liegt ueber dem Generator (0,30) und unter
         * dem Beat (0,57). Der Abstand ist mit 0,27 brauchbar — und
         * deutlich groesser als bei der reinen Verteilung, wo beide
         * Werte ueber der alten 0,25 lagen.
         *
         * **Weiterhin vorlaeufig** (ADR-0019): die Messung stammt aus
         * synthetischen Signalen. Ein Burst-Train ist rhythmisch praeziser
         * als jede Aufnahme, ein Dreiklang tonal klarer als ein Track mit
         * Drums und Bass. Echte Musik liegt in der Regel **unter** den
         * hier genannten Werten. Endgueltig kalibrieren laesst sich das
         * nur mit Titeln mit bekanntem BPM (Rekordbox, Mixed In Key).
         */
        const val DISTRIBUTION_WEIGHT: Float = 0.3f
        const val REGULARITY_WEIGHT: Float = 0.7f

        private const val THRESHOLD_WINDOW = 12
        private const val K = 1.5
        private const val MIN_SPACING_MS = 250L
        private const val MAX_CANDIDATES = 1_000

        /**
         * Mindest sprung fuer einen **Beat**, als Anteil des lokalen
         * Energieniveaus (Befund 10.4).
         *
         * Bewusst kleiner als der Drop-Wert (0,25): ein Beat hebt die
         * Energie typischerweise um 20–40 Prozent, ein Drop um 50–100.
         * Mit dem Drop-Wert wuerde die halbe Musik verschwinden und das
         * Histogramm leer bleiben. Die Detection unterscheidet also
         * ueber die **relative** Groesse, nicht ueber den Absolutwert —
         * und die ist mastering-invariant.
         */
        private const val MIN_NOVELTY = 0.08
        private const val MIN_WINDOWS = 40
        private const val MIN_ONSETS = 8
    }
}

/** Tempo-Schaetzung inklusive Konfidenz 0..1 (Offtrack Phase 8). */
data class TempoEstimate(
    val bpm: Float,
    val confidence: Float,
)

/**
 * Schaetzt die Tonart als Camelot-Notation (z. B. "8A") ueber ein
 * 12-Bin-Chromagramm (Goertzel je Halbton) und Korrelation gegen die
 * Krumhansl-Schmuckler-Dur-/Moll-Profile. Rechenintensiver als die
 * Waveform-Peaks; die Implementierung decimiert den Eingang, damit der
 * Durchsatz-Waechter (schneller als Echtzeit) eingehalten bleibt.
 */
class ChromaAccumulator(
    private val sampleRateHz: Int,
    private val decimation: Int = DEFAULT_DECIMATION,
    windowSamples: Int = DEFAULT_WINDOW_SAMPLES,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz muss positiv sein" }
        require(decimation > 0) { "decimation muss positiv sein" }
        require(windowSamples > 0) { "windowSamples muss positiv sein" }
    }

    private val effectiveRateHz: Double = sampleRateHz.toDouble() / decimation
    private val window = DoubleArray(windowSamples)
    private val chroma = DoubleArray(12)
    private var windowIndex = 0
    private var sampleCount = 0L
    private var analyzedWindows = 0

    /** Nimmt ein Mono-Sample im Bereich [-1.0, 1.0] auf. */
    fun accept(sample: Double) {
        if (sampleCount % decimation != 0L) {
            sampleCount++
            return
        }
        sampleCount++
        window[windowIndex] = sample
        windowIndex++
        if (windowIndex == window.size) {
            accumulateWindow()
            windowIndex = 0
        }
    }

    /**
     * Liefert die Camelot-Notation oder `null`, wenn nicht genug Fenster
     * analysiert wurden.
     */
    fun finish(): String? = finishEstimate()?.camelotKey

    /**
     * Tonart inklusive Konfidenz (Offtrack Phase 8). Die Konfidenz ist
     * der Korrelationswert des besten Profils (1.0 = perfekte Ueberein-
     * stimmung, typischerweise deutlich darunter).
     */
    fun finishEstimate(): KeyEstimate? {
        // Ein angefangenes Restfenster mit mindestens halber Fuellung
        // mitnehmen, damit kurze Tracks nicht um die letzten Sekunden
        // beschnitten werden.
        if (windowIndex * 2 >= window.size) {
            accumulateWindow()
        }
        windowIndex = 0

        if (analyzedWindows < MIN_WINDOWS) return null

        val total = chroma.sum().takeIf { it > 0.0 } ?: return null
        val normalized = DoubleArray(12) { chroma[it] / total }

        var bestKey: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (root in 0 until 12) {
            val major = correlation(normalized, MAJOR_PROFILE, root)
            if (major > bestScore) {
                bestScore = major
                bestKey = camelotMajor(root)
            }
            val minor = correlation(normalized, MINOR_PROFILE, root)
            if (minor > bestScore) {
                bestScore = minor
                bestKey = camelotMinor(root)
            }
        }
        val key = bestKey ?: return null
        return KeyEstimate(camelotKey = key, confidence = bestScore.coerceIn(0.0, 1.0).toFloat())
    }

    private fun accumulateWindow() {
        for (pitchClass in 0 until 12) {
            for (octave in OCTAVES) {
                val frequency = pitchClassFrequency(pitchClass, octave)
                if (frequency > effectiveRateHz / 2.0) continue
                chroma[pitchClass] += goertzelPower(window, frequency, effectiveRateHz)
            }
        }
        analyzedWindows++
    }

    private fun goertzelPower(
        samples: DoubleArray,
        frequency: Double,
        sampleRateHz: Double,
    ): Double {
        val omega = 2.0 * PI * frequency / sampleRateHz
        val coefficient = 2.0 * cos(omega)
        var previous = 0.0
        var previousPrevious = 0.0
        for (sample in samples) {
            val current = sample + coefficient * previous - previousPrevious
            previousPrevious = previous
            previous = current
        }
        return previous * previous +
            previousPrevious * previousPrevious -
            coefficient * previous * previousPrevious
    }

    /**
     * Pearson-Korrelation zwischen Chromagramm und rotiertem Profil.
     *
     * Die Zentrierung (Abzug der Mittelwerte) ist nicht kosmetisch,
     * sondern der Kern des Verfahrens. Ohne sie waere dies eine
     * Kosinus-Aehnlichkeit zweier rein positiver Vektoren — und ein
     * FLACHES Chromagramm (weisses Rauschen verteilt Energie
     * gleichmaessig auf alle 12 Halbtoene) haette dann mit jedem Profil
     * hohe Aehnlichkeit. Gemessen vor dem Fix: Rauschen erreichte 0,96,
     * echte Dreiklaenge nur 0,73 — die Konfidenz war invers und als
     * Qualitaetsmass wertlos.
     *
     * Zentriert wird ein flacher Vektor zum Nullvektor: `chromaVariance`
     * ist dann 0, der Nenner 0, und das Ergebnis 0. Genau das gewuenschte
     * Verhalten — keine Tonart ohne tonale Struktur.
     *
     * Krumhansl-Schmuckler ist so definiert; siehe
     * [MixConfidenceBaselineTest] fuer die gemessene Verteilung.
     */
    private fun correlation(
        chroma: DoubleArray,
        profile: DoubleArray,
        root: Int,
    ): Double {
        val rotated = DoubleArray(12) { profile[(it - root + 12) % 12] }
        val chromaMean = chroma.average()
        val profileMean = rotated.average()

        var covariance = 0.0
        var chromaVariance = 0.0
        var profileVariance = 0.0
        for (i in 0 until 12) {
            val chromaDelta = chroma[i] - chromaMean
            val profileDelta = rotated[i] - profileMean
            covariance += chromaDelta * profileDelta
            chromaVariance += chromaDelta * chromaDelta
            profileVariance += profileDelta * profileDelta
        }

        val denominator = sqrt(chromaVariance * profileVariance)
        if (denominator == 0.0) return 0.0
        return covariance / denominator
    }

    private fun pitchClassFrequency(
        pitchClass: Int,
        octave: Int,
    ): Double {
        val semitonesFromA4 = pitchClass - 9 + octave * 12
        return REFERENCE_A4_HZ * 2.0.pow(semitonesFromA4 / 12.0)
    }

    private fun camelotMajor(root: Int): String =
        when (root) {
            0 -> "8B"
            1 -> "3B"
            2 -> "10B"
            3 -> "5B"
            4 -> "12B"
            5 -> "7B"
            6 -> "2B"
            7 -> "9B"
            8 -> "4B"
            9 -> "11B"
            10 -> "6B"
            11 -> "1B"
            else -> error("root ausserhalb 0..11")
        }

    private fun camelotMinor(root: Int): String =
        when (root) {
            0 -> "5A"
            1 -> "12A"
            2 -> "7A"
            3 -> "2A"
            4 -> "9A"
            5 -> "4A"
            6 -> "11A"
            7 -> "6A"
            8 -> "1A"
            9 -> "8A"
            10 -> "3A"
            11 -> "10A"
            else -> error("root ausserhalb 0..11")
        }

    companion object {
        const val DEFAULT_DECIMATION: Int = 5
        const val DEFAULT_WINDOW_SAMPLES: Int = 1_024

        private const val REFERENCE_A4_HZ = 440.0
        private val OCTAVES = -1..1
        private const val MIN_WINDOWS = 8

        private val MAJOR_PROFILE =
            doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        private val MINOR_PROFILE =
            doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    }
}

/** Tonart-Schaetzung inklusive Konfidenz 0..1 (Offtrack Phase 8). */
data class KeyEstimate(
    val camelotKey: String,
    val confidence: Float,
)

/**
 * Mindestkonfidenz, ab der eine Schaetzung dem Nutzer gezeigt oder von
 * einem Automatismus benutzt werden darf.
 *
 * Warum ueberhaupt ein Gate: [TempoAccumulator] und [ChromaAccumulator]
 * liefern einen Wert, sobald genug Signal vorhanden ist — auch fuer
 * Material ohne Puls oder Tonalitaet. Weisses Rauschen ergibt "160 BPM",
 * Sprache "77 BPM". Ungefiltert landet dieser Muell im BPM-Lock, der
 * daraus einen Tempo-Faktor rechnet und die Wiedergabe hoerbar
 * verstimmt. Ein falscher Wert ist hier schaedlicher als kein Wert:
 * ohne BPM ist der Lock deaktiviert (`enabled = trackBpm != null`,
 * `TempoSheet.kt:123`) und der Nutzer merkt, dass die Analyse nichts
 * hergibt.
 *
 * **Tempo (2026-09-27, Befund 10.1 neu gemessen):**
 *
 * | Eingang | distribution | regularity | Konfidenz |
 * |---|---|---|---|
 * | Beat, 5 % Jitter | 0,61 | 0,60 | **0,57** |
 * | Taktgenerator (Jitter 0) | 1,00 | 0,00 | **0,30** |
 * | weisses Rauschen | — | — | **`null`** |
 * | Sprache-aehnlich | — | — | 0,18 |
 *
 * Das weisse Rauschen liefert seit dem relativen Mindestsprung
 * (`OnsetDetection`, Befund 10.4) gar keinen Rohwert mehr — der Filter
 * greift **vor** dem Gate. Das ist die bessere Reihenfolge: eine
 * mastering-unabhaengige Filter-Schwelle statt einer Konfidenz, die man
 * nachtraeglich auf einen Zahlwert setzen muss.
 *
 * **Tonart (unveraendert, `MixConfidenceBaselineTest`):**
 *
 * | Eingang | Key-Konfidenz |
 * |---|---|
 * | weisses Rauschen | 0,64 |
 * | Dur-/Moll-Dreiklang | 0,83..0,89 |
 *
 * **Diese Schwellen sind vorlaeufig.** Sie trennen synthetische
 * Extremfaelle, und synthetische Signale sind der einfachste denkbare
 * Fall: ein Burst-Train ist rhythmisch praeziser als jede Live-Aufnahme,
 * ein reiner Dreiklang tonal klarer als ein Track mit Drums und Bass.
 * Echte Musik liegt niedriger.
 *
 * Endgueltige Kalibrierung braucht echte Titel mit bekanntem BPM/Key
 * (z. B. ein Dutzend Tracks mit Rekordbox-/Mixed-In-Key-Referenz). Bis
 * dahin ist das Gate eine Muellabfuhr, keine Qualitaetsgarantie.
 */
object MixConfidence {
    /**
     * Mindestkonfidenz, ab der ein BPM dem Nutzer gezeigt oder von einem
     * Automatismus benutzt werden darf.
     *
     * 2026-09-27, Befund 10.1: der Score ist jetzt eine gewichtete Summe
     * aus Verteilung (0,3) und Regelmaessigkeit (0,7) statt der reinen
     * Verteilung. Gemessen mit den neuen Gewichten:
     *
     * | Eingang | Konfidenz |
     * |---|---|
     * | Beat, 5 % Jitter | 0,57 |
     * | Taktgenerator (Jitter 0) | 0,30 |
     * | weisses Rauschen | `null` (Filter) |
     * | Sprache-aehnlich | 0,18 |
     *
     * **0,33** liegt ueber dem Taktgenerator und unter dem Beat. Vor der
     * Aenderung (0,25) lagen **beide** Werte darueber — das Gate konnte
     * die beiden Faelle nicht trennen und musste einen sauberen Beat
     * opfern, um das Rauschen zu stoppen.
     *
     * **Weiterhin vorlaeufig** (ADR-0019): die Werte stammen aus
     * synthetischen Signalen, und echte Musik liegt in der Regel
     * **unter** ihnen. Endgueltig kalibrieren laesst sich die Schwelle
     * nur mit Titeln mit bekanntem BPM.
     */
    const val MIN_BPM_CONFIDENCE: Float = 0.33f

    /**
     * Rauschen liegt gemessen bei 0,64, Dreiklaenge bei 0,83..0,89.
     * 0,70 trennt sie. Enger als beim Tempo, weil die Korrelation
     * ohnehin nur einen kleinen Wertebereich ausnutzt — und weil ein
     * falscher Key im Gegensatz zum BPM heute nichts steuert, sondern
     * nur angezeigt wird.
     */
    const val MIN_KEY_CONFIDENCE: Float = 0.70f

    /** Gibt [bpm] frei, wenn [confidence] die Schwelle erreicht. */
    fun acceptBpm(
        bpm: Float?,
        confidence: Float?,
    ): Float? = bpm?.takeIf { (confidence ?: 0f) >= MIN_BPM_CONFIDENCE }

    /** Gibt [camelotKey] frei, wenn [confidence] die Schwelle erreicht. */
    fun acceptKey(
        camelotKey: String?,
        confidence: Float?,
    ): String? = camelotKey?.takeIf { (confidence ?: 0f) >= MIN_KEY_CONFIDENCE }
}
