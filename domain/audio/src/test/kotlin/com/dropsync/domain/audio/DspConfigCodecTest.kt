package com.dropsync.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profil-Persistenzcodec und Profil-Schluessel (ADR-0008). */
class DspConfigCodecTest {
    @Test
    fun `roundtrip erhaelt alle werte`() {
        val config =
            DspConfig(
                enabled = true,
                preampDb = -3.5,
                limiterEnabled = false,
                eq =
                    EqSettings(
                        enabled = true,
                        mode = EqMode.PARAMETRIC,
                        bands = EqSettings.graphicBands(15),
                    ),
                bassGainDb = 4.0,
                trebleGainDb = -2.0,
                stereoWidthPercent = 150,
                reverb = ReverbSettings(enabled = true, roomSize = 0.7, damping = 0.4, wet = 0.2),
                resampler = ResamplerSettings(targetRateHz = 96_000, quality = ResamplerQuality.SINC),
                ditherMode = DitherMode.SHAPED,
                dvcEnabled = true,
                dvcVolume = 0.8,
                crossfadeSeconds = 6,
                mixPreset = MixPreset.SLAM,
                useSystemEffects = true,
                bitPerfectEnabled = true,
                // 2026-09-27, Befund 1.12: `replayGainEnabled` und
                // `restDuckDb` fehlten im Test-Config. Sie stehen damit auf
                // Default, und der Roundtrip verglich Default mit Default —
                // der Test konnte den Fehler "Feld wird nicht kodiert"
                // strukturell nicht finden. Genau das ist passiert:
                // `replayGainEnabled` wurde nicht geschrieben, aber gelesen.
                restDuckDb = -6.0,
                replayGainEnabled = true,
            )
        val decoded = DspConfigCodec.decode(DspConfigCodec.encode(config))
        assertEquals(DspConfig.sanitized(config), decoded)
        // Ausdruecklich, damit ein Wegfall hier als Testfehler auffaellt
        // statt als "beide Seiten gleich".
        assertTrue("replayGainEnabled ging verloren", decoded!!.replayGainEnabled)
        assertEquals(-6.0, decoded.restDuckDb, 1e-9)
    }

    @Test
    fun `fehlende schluessel fallen auf standards zurueck`() {
        val decoded = DspConfigCodec.decode("enabled=false")
        assertEquals(DspConfig.sanitized(DspConfig(enabled = false)), decoded)
        assertFalse(decoded!!.bitPerfectEnabled)
    }

    @Test
    fun `defekte werte liefern null`() {
        assertNull(DspConfigCodec.decode(""))
        assertNull(DspConfigCodec.decode("enabled=vielleicht"))
        assertNull(DspConfigCodec.decode("preampDb=abc"))
        assertNull(DspConfigCodec.decode("kein-trenner"))
        assertNull(DspConfigCodec.decode("mixPreset=QUATSCH"))
    }

    @Test
    fun `fehlendes mix preset faellt auf fade zurueck`() {
        // Vorwaertskompatibilitaet: alte Profile ohne mixPreset-Schluessel
        // (vor Mix-Uebergaenge-Plan Phase 2) bleiben gueltig.
        val decoded = DspConfigCodec.decode("crossfadeSeconds=4")
        assertEquals(MixPreset.FADE, decoded!!.mixPreset)
        assertEquals(4, decoded.crossfadeSeconds)
    }

    @Test
    fun `profil schluessel ist stabil und datastore-sicher`() {
        val bt =
            OutputProfileKey(
                kind = OutputDeviceKind.BLUETOOTH_A2DP,
                address = "AA:BB:CC:DD:EE:FF",
            ).storageKey()
        assertEquals("BLUETOOTH_A2DP:aa_bb_cc_dd_ee_ff", bt)
        assertTrue(bt.substringAfter(':').all { it.isLetterOrDigit() || it in "._-" })
        assertEquals(
            "SPEAKER:default",
            OutputProfileKey(OutputDeviceKind.SPEAKER, null).storageKey(),
        )
        assertEquals(
            "USB:default",
            OutputProfileKey(OutputDeviceKind.USB, "   ").storageKey(),
        )
    }
}
