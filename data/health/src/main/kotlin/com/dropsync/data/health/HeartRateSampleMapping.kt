package com.dropsync.data.health

import androidx.health.connect.client.records.HeartRateRecord
import com.dropsync.domain.health.HeartRateSample

/**
 * Mapping der Health-Connect-Typen auf den Domain-Messwert (Plan 3.3/3).
 * Ein HeartRateRecord kann mehrere Samples mit eigenem Zeitstempel
 * enthalten — es werden alle uebersetzt; die Auswahl des neuesten trifft
 * [HealthConnectHeartRateSource].
 */
internal object HeartRateSampleMapping {
    fun toSamples(records: Iterable<HeartRateRecord>): List<HeartRateSample> =
        records.flatMap { it.samples }.map(::toSample)

    fun toSample(sample: HeartRateRecord.Sample): HeartRateSample =
        HeartRateSample(
            bpm = sample.beatsPerMinute.toInt(),
            recordedAtEpochMs = sample.time.toEpochMilli(),
        )
}
