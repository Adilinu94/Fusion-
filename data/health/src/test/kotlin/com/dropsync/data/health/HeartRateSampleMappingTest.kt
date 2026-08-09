package com.dropsync.data.health

import androidx.health.connect.client.records.HeartRateRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Mapping der Health-Connect-Samples (Herzfrequenz-Plan Phase 1). */
class HeartRateSampleMappingTest {
    @Test
    fun `sample wird auf bpm und epoch millis abgebildet`() {
        val sample =
            HeartRateRecord.Sample(
                time = Instant.ofEpochMilli(1_753_600_000_000),
                beatsPerMinute = 128,
            )

        val mapped = HeartRateSampleMapping.toSample(sample)

        assertEquals(128, mapped.bpm)
        assertEquals(1_753_600_000_000, mapped.recordedAtEpochMs)
    }

    @Test
    fun `bpm ausserhalb int bereich ist ausgeschlossen weil hc long liefert`() {
        // Health Connect validiert beatsPerMinute auf 1..300; die
        // Int-Konvertierung ist damit verlustfrei.
        val mapped =
            HeartRateSampleMapping.toSample(
                HeartRateRecord.Sample(time = Instant.ofEpochMilli(1), beatsPerMinute = 300),
            )

        assertEquals(300, mapped.bpm)
    }
}
