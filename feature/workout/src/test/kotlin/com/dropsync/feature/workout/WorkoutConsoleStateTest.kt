package com.dropsync.feature.workout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2-20/RC-5: Die Konsolen-Ableitungen sind reine Funktionen und werden hier
 * ohne Compose geprueft — die UI-Handbuch-Regeln (4.1 Modi, 7.4 Quellen)
 * sollen nicht in der Compose-Schicht versteckt sein.
 */
class WorkoutConsoleStateTest {
    @Test
    fun `ohne Uebung ist die Konsole IDLE`() {
        assertEquals(
            WorkoutConsoleMode.IDLE,
            workoutConsoleMode(hasExercise = false, restActive = false, dropLanded = false),
        )
    }

    @Test
    fun `mit Uebung und ohne Pause ist die Konsole SET_ENTRY`() {
        assertEquals(
            WorkoutConsoleMode.SET_ENTRY,
            workoutConsoleMode(hasExercise = true, restActive = false, dropLanded = false),
        )
    }

    @Test
    fun `laufende Pause ist REST_RUNNING`() {
        assertEquals(
            WorkoutConsoleMode.REST_RUNNING,
            workoutConsoleMode(hasExercise = true, restActive = true, dropLanded = false),
        )
    }

    @Test
    fun `Landung waehrend der Pause ist GO_CUE`() {
        assertEquals(
            WorkoutConsoleMode.GO_CUE,
            workoutConsoleMode(hasExercise = true, restActive = true, dropLanded = true),
        )
    }

    @Test
    fun `eine Landung ohne laufende Pause ist kein GO_CUE`() {
        // Der Landungs-Zustand kann den Timer ueberleben; ohne Pause gibt es
        // aber kein GO-Overlay, also bleibt die Konsole bei der Satz-Eingabe.
        assertEquals(
            WorkoutConsoleMode.SET_ENTRY,
            workoutConsoleMode(hasExercise = true, restActive = false, dropLanded = true),
        )
    }

    @Test
    fun `gezaehlte unberuehrte Zahl ist AUTO`() {
        assertEquals(
            RepsSource.Sensor,
            repsSourceOf(edited = false, lastCounted = 7, streaming = true, dropped = false),
        )
    }

    @Test
    fun `gezaehlte korrigierte Zahl traegt den Original-Zaehlstand`() {
        assertEquals(
            RepsSource.SensorWithManualCorrection(7),
            repsSourceOf(edited = true, lastCounted = 7, streaming = true, dropped = false),
        )
    }

    @Test
    fun `Handeingabe ohne Zaehlstand ist MANUELL`() {
        assertEquals(
            RepsSource.Manual,
            repsSourceOf(edited = true, lastCounted = null, streaming = true, dropped = false),
        )
    }

    @Test
    fun `Abriss ohne Zaehlstand ist SENSOR_DISCONNECTED`() {
        assertEquals(
            RepsSource.SensorDisconnected,
            repsSourceOf(edited = false, lastCounted = null, streaming = false, dropped = true),
        )
    }

    @Test
    fun `Abriss mit vorhandenem Zaehlstand bleibt die Korrektur`() {
        // Eine korrigierte Zahl bleibt "korrigiert", auch wenn der Chip
        // inzwischen weg ist — der Zaehlstand ist die staerkere Aussage.
        assertEquals(
            RepsSource.SensorWithManualCorrection(7),
            repsSourceOf(edited = true, lastCounted = 7, streaming = false, dropped = true),
        )
    }

    @Test
    fun `Abriss nach Reconnect ist wieder MANUELL`() {
        assertEquals(
            RepsSource.Manual,
            repsSourceOf(edited = false, lastCounted = null, streaming = true, dropped = true),
        )
    }
}
