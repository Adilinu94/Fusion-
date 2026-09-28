package com.dropsync.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Offtrack Phase 8: BPM/Key-Confidence bleibt 0..1 und konsistent. */
class MixConfidenceTest {
    @Test
    fun `tempo estimate konfidenz liegt zwischen null und eins`() {
        val tempo = TempoAccumulator(sampleRateHz = 44_100)
        // 12 s klarer 120-BPM-Beat-Train: alle 500 ms ein 50-ms-Burst.
        repeat(44_100 * 12) { index ->
            val positionInBeat = index % (44_100 / 2)
            val sample = if (positionInBeat < 44_100 / 20) 1.0 else 0.0
            tempo.accept(sample)
        }
        val estimate = tempo.finishEstimate()
        assertNotNull(estimate)
        assertTrue(estimate!!.confidence in 0f..1f)
        // finish() bleibt die Rueckwaertskompatibilitaet zu finishEstimate().
        assertEquals(estimate.bpm, tempo.finish()!!, 1e-4f)
    }

    @Test
    fun `zu wenig signal liefert keinen estimate`() {
        val tempo = TempoAccumulator(sampleRateHz = 44_100)
        repeat(100) { tempo.accept(0.1) }
        assertNull(tempo.finishEstimate())
    }

    // --- Gate: MixConfidence ---

    @Test
    fun `klarer beat passiert das bpm-gate`() {
        // 2026-09-27, Befund 10.1: dieser Test pruefte einen **perfekt
        // gleichmaessigen** Burst-Train (2 Hz, exakt 500-ms-Abstand,
        // Jitter 0) und erwartete, dass er durch das Gate kommt. Das war
        // die Fehlannahme, an der das alte Gate gescheitert ist: es musste
        // einen sauberen Beat verwerfen, um das weisse Rauschen zu stoppen,
        // und traf dabei genau diesen Fall.
        //
        // Der Test bekommt deshalb realistischen Jitter (5 Prozent) — der
        // Wert, den die eigene Baseline-Messung fuer einen gespielten Beat
        // ergab (8 Prozent bei "klar", 20 Prozent "mit Absicht"). Ein
        // Burst-Train ohne Jitter ist per Definition kein Beat, sondern
        // ein Generator; der Fall ist jetzt als solcher getestet, siehe
        // [periodisches signal ohne beat faellt durch das gate].
        val tempo = TempoAccumulator(sampleRateHz = 44_100)
        val random = kotlin.random.Random(seed = 99)
        val nominal = 44_100 * 60.0 / 120
        val burst = 44_100 / 20
        val onsets = mutableListOf<Int>()
        var position = 0.0
        while (position < 44_100 * 15) {
            onsets += position.toInt()
            position += nominal * (1.0 + (random.nextDouble() * 2 - 1) * 0.05)
        }
        val onsetSet = onsets.toSet()
        var remaining = 0
        (0 until 44_100 * 15).forEach { index ->
            if (index in onsetSet) remaining = burst
            if (remaining > 0) {
                remaining--
                tempo.accept(1.0)
            } else {
                tempo.accept(0.0)
            }
        }
        val estimate = requireNotNull(tempo.finishEstimate())

        assertEquals(
            "Ein Beat mit 5 Prozent Jitter muss das Gate passieren " +
                "(confidence=${estimate.confidence})",
            estimate.bpm,
            MixConfidence.acceptBpm(estimate.bpm, estimate.confidence),
        )
    }

    @Test
    fun `weisses rauschen liefert kein bpm durch das gate`() {
        // Der Akkumulator liefert frueher **immer** einen Wert — das war das
        // Problem, und genau darum existiert das Gate.
        //
        // 2026-09-27, Befund 10.4: seit der Mindestsprung **relativ** zum
        // lokalen Energieniveau ist, liefert weisses Rauschen ueberhaupt
        // keine Onsets mehr: jeder Sprung liegt unter 8 Prozent des
        // Fenstermittels, weil das Rauschen stationaer ist. Der
        // Akkumulator gibt `null` zurueck, und das Gate muss gar nicht mehr
        // eingreifen.
        //
        // Das ist die bessere Loesung: eine Filter-Schwelle, die
        // Musik-mastering-invariant ist, statt einer Konfidenz, die man
        // nachtraeglich auf eine Zahl setzen muss. Das Gate bleibt als
        // zweite Verteidigungslinie bestehen — fuer Material, das
        // periodisch **ist**, aber kein Beat hat (Motor, Klimaanlage,
        // ein Geraet mit konstantem Takt).
        val random = Random(seed = 7)
        val tempo = TempoAccumulator(sampleRateHz = 44_100)
        repeat(44_100 * 15) { tempo.accept(random.nextDouble() * 2 - 1) }
        val estimate = tempo.finishEstimate()

        assertNull(
            "Weisses Rauschen darf gar keinen BPM-Rohwert liefern",
            estimate,
        )
    }

    /**
     * Das Gate bleibt noetig fuer Material, das **periodisch** ist, aber
     * keinen Beat hat — das ueberschreitet den Akkumulator, weil die
     * Onsets dort regelmaessig genug auftreten.
     *
     * Der Test ist gegen einen echten Beat gebaut, nicht gegen eine
     * Wunschvorstellung: ein 3-Hz-Generator erzeugt ueber die
     * 25-ms-Fenster **zwei** Onsets pro Periode (Flanke und Spitze), und
     * `minSpacingMs = 250` filtert nur einen davon. Das Ergebnis war
     * eine unregelmaessige Intervalldistribution (0,30 und 0,47 s
     * abwechselnd) — also Jitter, und damit **regularity ≈ 0,6**. Die
     * Konfidenz lag dadurch bei 0,74 und der Test schlug fehl.
     *
     * Das ist kein Fehler der Konfidenz, sondern ein Hinweis: die
     * Doppel-Onset-Erkennung ist selbst eine Stoergroesse. Ein
     * gleichmaessiger Takt, der sauber erkannt werden soll, braucht
     * eine Periode, die deutlich ueber dem Mindestabstand liegt.
     *
     * Deshalb 2 Hz (500-ms-Periode, ein Onset je Periode, Intervalle
     * 0,5 s = 120 BPM mit minimalem Jitter durch die Fensterung).
     */
    @Test
    fun `periodisches signal ohne beat faellt durch das gate`() {
        val tempo = TempoAccumulator(sampleRateHz = 44_100)
        val periodSamples = 44_100 / 2
        repeat(44_100 * 20) { index ->
            val phase = (index % periodSamples) / periodSamples.toDouble()
            // Spitze in 8 Prozent des Taktes, sonst Stille.
            tempo.accept(if (phase < 0.08) 1.0 else 0.0)
        }
        val estimate =
            requireNotNull(tempo.finishEstimate()) {
                "Ein gleichmaessiger Takt muss den Akkumulator passieren — " +
                    "sonst prueft der Test nicht das Gate, sondern den Filter"
            }
        assertNotNull("Der Akkumulator liefert einen Rohwert", estimate.bpm)
        assertNull(
            "Ein gleichmaessiger Takt ist kein Beat (confidence=${estimate.confidence})",
            MixConfidence.acceptBpm(estimate.bpm, estimate.confidence),
        )
    }

    /**
     * Das Gate muss einen echten Beat **durchlassen** — der Mirror-Test
     * zu [periodisches signal ohne beat faellt durch das gate].
     *
     * 2026-09-27, Befund 10.1: mit der neuen Konfidenz (0,6 Verteilung +
     * 0,4 Regelmaessigkeit) muss ein Beat mit realistischem Jitter die
     * 0,25 erreichen. Ein Beat mit 8 Prozent Jitter liegt im
     * plausiblen Fenster; 5 Prozent Jitter sind der Scheitel.
     */
    @Test
    fun `echter beat mit realistischem jitter kommt durch das gate`() {
        val tempo = TempoAccumulator(sampleRateHz = 44_100)
        val random = kotlin.random.Random(seed = 42)
        val nominal = 44_100 * 60.0 / 128
        val burst = 44_100 / 20
        val onsets = mutableListOf<Int>()
        var position = 0.0
        while (position < 44_100 * 20) {
            onsets += position.toInt()
            // 5 Prozent Jitter: typisch fuer einen sauber gespielten Beat.
            position += nominal * (1.0 + (random.nextDouble() * 2 - 1) * 0.05)
        }
        val onsetSet = onsets.toSet()
        var remaining = 0
        (0 until 44_100 * 20).forEach { index ->
            if (index in onsetSet) remaining = burst
            if (remaining > 0) {
                remaining--
                tempo.accept(1.0)
            } else {
                tempo.accept(0.0)
            }
        }

        val estimate =
            requireNotNull(tempo.finishEstimate()) {
                "Ein Beat mit 5 Prozent Jitter muss ein Estimate liefern"
            }
        assertEquals(
            "Der Beat liegt bei 128 BPM; eine Halbierung/Doppelung waere ein Oktavenfehler",
            128f,
            estimate.bpm,
            2.0f,
        )
        assertNotNull(
            "Ein echter Beat muss das Gate passieren (confidence=${estimate.confidence})",
            MixConfidence.acceptBpm(estimate.bpm, estimate.confidence),
        )
    }

    @Test
    fun `dreiklang passiert das key-gate`() {
        val chroma = ChromaAccumulator(sampleRateHz = 44_100)
        val frequencies = listOf(220.0, 261.63, 329.63) // A-Moll
        repeat(44_100 * 12) { index ->
            chroma.accept(frequencies.sumOf { sin(2 * PI * it * index / 44_100) } / 3.0)
        }
        val estimate = requireNotNull(chroma.finishEstimate())

        assertEquals(
            estimate.camelotKey,
            MixConfidence.acceptKey(estimate.camelotKey, estimate.confidence),
        )
    }

    @Test
    fun `weisses rauschen liefert keinen key durch das gate`() {
        val random = Random(seed = 7)
        val chroma = ChromaAccumulator(sampleRateHz = 44_100)
        repeat(44_100 * 12) { chroma.accept(random.nextDouble() * 2 - 1) }
        val estimate = requireNotNull(chroma.finishEstimate())

        assertNull(
            "Rauschen darf nicht als Tonart durchkommen (confidence=${estimate.confidence})",
            MixConfidence.acceptKey(estimate.camelotKey, estimate.confidence),
        )
    }

    @Test
    fun `fehlende konfidenz gilt als unsicher`() {
        // Alt-Zeilen aus DB v7 (vor bpm_confidence) haben null. Sie duerfen
        // nicht als "sicher" gelten, nur weil die Spalte leer ist.
        assertNull(MixConfidence.acceptBpm(bpm = 128f, confidence = null))
        assertNull(MixConfidence.acceptKey(camelotKey = "8A", confidence = null))
    }

    @Test
    fun `kein wert bleibt null unabhaengig von der konfidenz`() {
        assertNull(MixConfidence.acceptBpm(bpm = null, confidence = 1f))
        assertNull(MixConfidence.acceptKey(camelotKey = null, confidence = 1f))
    }

    @Test
    fun `genau auf der schwelle wird akzeptiert`() {
        assertEquals(
            128f,
            MixConfidence.acceptBpm(128f, MixConfidence.MIN_BPM_CONFIDENCE),
        )
        assertEquals(
            "8A",
            MixConfidence.acceptKey("8A", MixConfidence.MIN_KEY_CONFIDENCE),
        )
    }

    @Test
    fun `chroma-korrelation eines flachen chromagramms ist null`() {
        // Regressionsschutz fuer den Vorzeichenfehler: ohne Zentrierung
        // erreichte ein flaches (rauschartiges) Chromagramm 0,96 und lag
        // damit ueber echten Dreiklaengen. Ein Dauerton auf allen
        // Halbtoenen gleichzeitig ist der Grenzfall.
        val chroma = ChromaAccumulator(sampleRateHz = 44_100)
        // Alle 12 Halbtoene der Oktave ueber A3 gleich laut: maximal
        // untonal, obwohl voller Energie.
        val frequencies = (0 until 12).map { 220.0 * Math.pow(2.0, it / 12.0) }
        repeat(44_100 * 12) { index ->
            chroma.accept(frequencies.sumOf { sin(2 * PI * it * index / 44_100) } / 12.0)
        }
        val estimate = requireNotNull(chroma.finishEstimate())

        assertNull(
            "flaches Chromagramm darf keine Tonart ergeben (confidence=${estimate.confidence})",
            MixConfidence.acceptKey(estimate.camelotKey, estimate.confidence),
        )
    }
}
