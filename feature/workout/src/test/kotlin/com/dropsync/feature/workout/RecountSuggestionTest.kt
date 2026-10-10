package com.dropsync.feature.workout

import com.dropsync.domain.sensor.SetRecount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecountSuggestionTest {
    private fun result(
        count: Int,
        live: Int,
        confidence: SetRecount.Confidence,
    ) = SetRecount.Result(
        count = count,
        liveCount = live,
        periodMs = 2_400.0,
        confidence = confidence,
        interpolatedFraction = 0.0,
        hasUnbridgedGap = false,
    )

    @Test
    fun `abweichende Anzahl bei hoher Sicherheit wird zum Vorschlag`() {
        val suggestion = result(count = 10, live = 9, confidence = SetRecount.Confidence.HIGH).toSuggestionOrNull()
        assertEquals(RecountSuggestion(liveReps = 9, analysisReps = 10), suggestion)
    }

    @Test
    fun `uebereinstimmende Anzahl ergibt keinen Vorschlag`() {
        assertNull(result(count = 10, live = 10, confidence = SetRecount.Confidence.HIGH).toSuggestionOrNull())
    }

    @Test
    fun `geringe Sicherheit ergibt nie einen Vorschlag`() {
        assertNull(result(count = 12, live = 9, confidence = SetRecount.Confidence.LOW).toSuggestionOrNull())
    }
}
