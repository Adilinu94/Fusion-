package com.dropsync.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Drop-Erkennung gegen SYNTHETISCHES Audio (Kick, Bass, Hi-Hats, Pad, Riser). Zeigt, dass die
 * Bass-Rueckkehr einen Drop findet, den die Fullband-RMS verfehlt, und dass ein Refrain-Einsatz
 * ohne Bass-Aenderung ihn nicht verdraengt. Echte Tracks sind damit NICHT bewertet.
 */
class DropDetectionTest {
    private val sampleRate = 11_025
    private val windowMs = 25L
    private val bpm = 128.0
    private val beatMs = 60_000.0 / bpm

    /** Abschnitt eines synthetischen Tracks. */
    private class Section(
        val fromS: Double,
        val toS: Double,
        val kick: Double = 0.0,
        val bass: Double = 0.0,
        val hats: Double = 0.1,
        val pad: Double = 0.0,
        val riserTo: Double = 0.0,
        val highs: Double = 0.0,
    )

    private class Analysis(val fullband: List<Double>, val bass: List<Double>)

    private fun render(
        lengthS: Double,
        sections: List<Section>,
        seed: Long = 1,
    ): Analysis {
        val rnd = Random(seed)
        val n = (lengthS * sampleRate).toInt()
        val samplesPerWindow = (sampleRate * windowMs / 1_000L).toInt()
        val full = EnergyAccumulator(samplesPerWindow)
        val bassAcc = BassEnergyAccumulator(sampleRate, samplesPerWindow)
        var lowNoise = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val s = sections.firstOrNull { t >= it.fromS && t < it.toS }
            var x = 0.0
            if (s != null) {
                val sinceBeat = (t * 1_000.0) % beatMs / 1_000.0
                // Kick: 60-Hz-Burst mit exponentiellem Abklingen auf jedem Beat
                x += s.kick * sin(2 * PI * 60 * sinceBeat) * exp(-sinceBeat * 18.0)
                // Bass: 55 Hz Dauerton, pumpt zwischen den Kicks (Sidechain)
                x += s.bass * sin(2 * PI * 55 * t) * (1.0 - exp(-sinceBeat * 14.0))
                // Hats: gefiltertes Rauschen auf Achteln
                val sinceEighth = (t * 1_000.0) % (beatMs / 2) / 1_000.0
                val hat = rnd.nextGaussian() * exp(-sinceEighth * 60.0)
                x += s.hats * hat
                // Pad: Akkord um 400-800 Hz
                x += s.pad * (sin(2 * PI * 440 * t) + sin(2 * PI * 554 * t) + sin(2 * PI * 659 * t)) / 3.0
                // Riser: Rauschen mit steigender Amplitude, Hochpass-artig (Differenz)
                if (s.riserTo > 0.0) {
                    val progress = (t - s.fromS) / (s.toS - s.fromS)
                    val white = rnd.nextGaussian()
                    val high = white - lowNoise
                    lowNoise += 0.2 * (white - lowNoise)
                    x += s.riserTo * progress * high
                }
                // Highs: Gesang/Leads (1-3 kHz) als Refrain-Lautstaerke
                x += s.highs * (sin(2 * PI * 1_200 * t) + sin(2 * PI * 2_300 * t)) / 2.0
            }
            full.accept(x)
            bassAcc.accept(x)
        }
        return Analysis(full.finish(), bassAcc.finish())
    }

    private fun oldDetector(a: Analysis) = OnsetDetection.detectOnsets(a.fullband, windowMs)

    private fun hit(
        positions: List<Long>,
        targetMs: Long,
        toleranceMs: Long = 100L,
    ) = positions.any { abs(it - targetMs) <= toleranceMs }

    // ---------------------------------------------------------------

    @Test
    fun `Bass-Filter laesst 55 Hz durch und daempft 2 kHz stark`() {
        val spw = (sampleRate * windowMs / 1_000L).toInt()
        fun rms(freq: Double): Double {
            val acc = BassEnergyAccumulator(sampleRate, spw)
            for (i in 0 until sampleRate * 2) acc.accept(sin(2 * PI * freq * i / sampleRate))
            val windows = acc.finish().drop(20) // Einschwingen
            return windows.average()
        }
        val low = rms(55.0)
        val high = rms(2_000.0)
        assertTrue("55 Hz muss im Bassband bleiben (RMS $low)", low > 0.6)
        assertTrue("2 kHz muss um mindestens 40 dB faellen (RMS $high)", high < low / 100.0)
    }

    @Test
    fun `klassischer lauter Drop wird gefunden`() {
        val a =
            render(
                60.0,
                listOf(
                    Section(0.0, 20.0, hats = 0.1),
                    Section(20.0, 60.0, kick = 0.8, bass = 0.35, hats = 0.1),
                ),
            )
        // Hinweis: Die Fullband-Novelty feuert in einem gleichmaessigen Groove bei JEDEM Kick;
        // welche drei Kicks in den Top-3 landen, ist dann fast zufaellig. Der alte Detektor
        // trifft die 20 s hier also nur zufaellig - deshalb keine Behauptung ueber ihn.
        println("Klassisch: alt=${oldDetector(a)}")
        val candidates = DropDetection.detect(a.fullband, a.bass, windowMs)
        assertEquals(DropDetection.Source.BASS_RETURN, candidates.first().source)
        assertTrue("neu: ${candidates.map { it.positionMs }}", hit(candidates.map { it.positionMs }, 20_000, 150))
    }

    @Test
    fun `Drop nach Bass-Break mit steigendem Riser - Fullband verfehlt, Bass-Rueckkehr trifft`() {
        // 0-20 s: Kick+Bass; 20-44 s: Break ohne Bass/Kick, Pad + Riser bringen die Gesamtlautstaerke
        // nach oben; 44 s: Kick+Bass kehren zurueck. Die Fullband-RMS steht davor schon auf dem Niveau.
        val a =
            render(
                70.0,
                listOf(
                    Section(0.0, 20.0, kick = 0.5, bass = 0.2, hats = 0.1),
                    Section(20.0, 44.0, hats = 0.05, pad = 0.55, riserTo = 0.9),
                    Section(44.0, 70.0, kick = 0.5, bass = 0.2, hats = 0.1),
                ),
            )
        val old = oldDetector(a)
        val candidates = DropDetection.detect(a.fullband, a.bass, windowMs)
        val top = candidates.first()
        assertEquals(DropDetection.Source.BASS_RETURN, top.source)
        assertTrue("Drop bei 44 s (gefunden: ${top.positionMs})", abs(top.positionMs - 44_000) <= 100)
        println("Bass-Break/Riser: alt=$old  neu=${candidates.map { "${it.positionMs}/${it.source}" }}")
    }

    @Test
    fun `Refrain-Einsatz ohne Bass-Aenderung verdraengt den echten Drop nicht`() {
        val a =
            render(
                100.0,
                listOf(
                    Section(0.0, 20.0, kick = 0.5, bass = 0.2, hats = 0.1),
                    Section(20.0, 44.0, hats = 0.05, pad = 0.4, riserTo = 0.5),
                    Section(44.0, 75.0, kick = 0.5, bass = 0.2, hats = 0.1),
                    // Refrain: Leads/Gesang springen 75 s deutlich an, Bass bleibt gleich
                    Section(75.0, 100.0, kick = 0.5, bass = 0.2, hats = 0.1, highs = 0.9),
                ),
            )
        val candidates = DropDetection.detect(a.fullband, a.bass, windowMs)
        assertEquals("echter Drop steht vorn", DropDetection.Source.BASS_RETURN, candidates.first().source)
        assertTrue(abs(candidates.first().positionMs - 44_000) <= 100)
        val old = oldDetector(a)
        println("Refrain: alt=$old  neu=${candidates.map { "${it.positionMs}/${it.source}" }}")
    }

    @Test
    fun `ohne Bass-Energie gilt das reine Fullband-Verhalten`() {
        val energy = List(120) { 0.05 } + List(80) { 0.9 }
        val bassOff = List(energy.size) { 0.0 }
        assertEquals(
            OnsetDetection.detectOnsets(energy, windowMs),
            DropDetection.candidatePositions(energy, bassOff, windowMs),
        )
        assertEquals(
            OnsetDetection.detectOnsets(energy, windowMs),
            DropDetection.candidatePositions(energy, emptyList(), windowMs),
        )
    }

    @Test
    fun `Stille und gleichmaessiges Signal liefern keine Kandidaten`() {
        assertTrue(DropDetection.detect(List(800) { 0.0 }, List(800) { 0.0 }, windowMs).isEmpty())
        val steady = render(40.0, listOf(Section(0.0, 40.0, kick = 0.5, bass = 0.2, hats = 0.1)))
        assertTrue(
            "Dauer-Groove ohne Break: keine Bass-Rueckkehr",
            DropDetection.detect(steady.fullband, steady.bass, windowMs).none { it.source == DropDetection.Source.BASS_RETURN },
        )
    }

    @Test
    fun `Taktraster rastet nur nahe Beats und laesst weit entfernte Positionen unberuehrt`() {
        val a =
            render(
                70.0,
                listOf(
                    Section(0.0, 20.0, kick = 0.5, bass = 0.2, hats = 0.1),
                    Section(20.0, 44.0, hats = 0.05, pad = 0.55, riserTo = 0.9),
                    Section(44.0, 70.0, kick = 0.5, bass = 0.2, hats = 0.1),
                ),
            )
        val plain = DropDetection.detect(a.fullband, a.bass, windowMs).first().positionMs
        // Raster mit Beat bei 0: Position 44 s liegt auf 44000/468.75 = 93.87 Beats -> naechster Beat 94 = 44062 ms
        val snapped = DropDetection.detect(a.fullband, a.bass, windowMs, beatGrid = DropDetection.BeatGrid(bpm, 0L)).first().positionMs
        assertTrue("gerastet liegt auf einem Beat", abs(((snapped / beatMs) - Math.round(snapped / beatMs)) * beatMs) < 2.0)
        val farGrid = DropDetection.detect(a.fullband, a.bass, windowMs, beatGrid = DropDetection.BeatGrid(bpm, 230L)).first().positionMs
        assertTrue("Grid-Offset 230 ms ist weit weg: Position bleibt (plain=$plain, far=$farGrid)", farGrid == plain || abs(farGrid - plain) <= 60)
    }

    @Test
    fun `Mindestabstand und Maximalzahl werden eingehalten`() {
        val sections = ArrayList<Section>()
        // vier Break/Drop-Zyklen im Abstand von 30 s
        var t = 0.0
        repeat(4) {
            sections += Section(t, t + 10.0, kick = 0.5, bass = 0.2)
            sections += Section(t + 10.0, t + 20.0, hats = 0.05, pad = 0.3)
            t += 20.0
        }
        sections += Section(t, t + 10.0, kick = 0.5, bass = 0.2)
        val a = render(t + 10.0, sections)
        val candidates = DropDetection.detect(a.fullband, a.bass, windowMs, maxCandidates = 2)
        assertEquals(2, candidates.size)
        assertTrue(abs(candidates[0].positionMs - candidates[1].positionMs) >= 5_000)
    }
}
