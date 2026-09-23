package com.dropsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paket 1.6: die Routen der Shell sind Routen-Vertraege — doppelte oder
 * kollidierende Zeichenketten brechen die Navigation erst zur Laufzeit.
 * Der Test prueft die Konstanten und die Formatierung der einzigen
 * dynamischen Route (Kalibrierung, vorher inline im Callback).
 */
class NavigationRoutesTest {
    @Test
    fun `top-level-routen sind eindeutig und nicht leer`() {
        val routes = TopLevelDestination.entries.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
        assertTrue(routes.none { it.isBlank() })
        assertEquals(listOf("music", "train", "history", "settings"), routes)
    }

    @Test
    fun `unterseiten-routen kollidieren nicht mit den Hauptzielen`() {
        val topLevel = TopLevelDestination.entries.map { it.route }.toSet()
        val subRoutes =
            listOf(
                ROUTE_AUDIO_SETTINGS,
                ROUTE_TIMER,
                ROUTE_NOW_PLAYING,
                ROUTE_ALL_SETS,
                ROUTE_EXERCISE_LIBRARY,
                ROUTE_ONBOARDING,
            )
        assertEquals(subRoutes.size, subRoutes.distinct().size)
        assertTrue(subRoutes.none { it in topLevel })
    }

    @Test
    fun `kalibrierungsroute traegt beide argumente in der vorlage`() {
        assertEquals("calibration/{exerciseId}/{deviceId}", ROUTE_CALIBRATION)
        assertTrue(ROUTE_CALIBRATION.contains("{$ARG_EXERCISE_ID}"))
        assertTrue(ROUTE_CALIBRATION.contains("{$ARG_DEVICE_ID}"))
    }

    @Test
    fun `kalibrierungsroute fuellt die argumente ein`() {
        assertEquals("calibration/42/polar-h10", calibrationRoute(exerciseId = 42L, deviceId = "polar-h10"))
    }

    @Test
    fun `jedes Hauptziel hat Icon und Label`() {
        TopLevelDestination.entries.forEach { destination ->
            assertTrue("${destination.name} braucht ein Icon", destination.iconRes != 0)
            assertTrue("${destination.name} braucht ein Label", destination.labelRes != 0)
        }
    }
}
