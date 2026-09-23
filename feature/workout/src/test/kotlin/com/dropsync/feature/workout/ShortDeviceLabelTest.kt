package com.dropsync.feature.workout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RC-8: Kurz-Kennung des verbundenen Chips fuer die Kalibrier-Zeile an der
 * Uebungszeile ("Kalibriert fuer FlowRep #EEFF — neu kalibrieren").
 */
class ShortDeviceLabelTest {
    @Test
    fun `nimmt die letzten vier zeichen der adresse`() {
        assertEquals("#EEFF", shortDeviceLabel("AA:BB:CC:DD:EE:FF"))
        assertEquals("#C0DE", shortDeviceLabel("aa:bb:cc:dd:c0:de"))
    }

    @Test
    fun `leere oder unbrauchbare kennung bleibt leer`() {
        assertEquals("", shortDeviceLabel(""))
        assertEquals("", shortDeviceLabel(":::"))
    }
}
