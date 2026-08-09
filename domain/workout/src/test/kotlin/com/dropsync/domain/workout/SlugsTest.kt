package com.dropsync.domain.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slug-Erzeugung fuer eigene Uebungen (Schritt 9.1/9.2). */
class SlugsTest {
    @Test
    fun `displayname wird zu gueltigem slug`() {
        assertEquals("barbell_back_squat", Slugs.fromDisplayName("Barbell Back Squat"))
        assertTrue(Slugs.isValid(Slugs.fromDisplayName("Barbell Back Squat")))
    }

    @Test
    fun `deutsche umlaute werden transliteriert`() {
        assertEquals("ueberzuege_am_kabel", Slugs.fromDisplayName("\u00dcberz\u00fcge am Kabel"))
        assertEquals("kreuzheben_gestreckt", Slugs.fromDisplayName("Kreuzheben (gestreckt)"))
        assertEquals("grosse_masse", Slugs.fromDisplayName("gro\u00dfe Masse"))
    }

    @Test
    fun `sonderzeichen kollabieren zu einzelnen trennern`() {
        assertEquals("cable_fly_low", Slugs.fromDisplayName("  Cable -- Fly !! low  "))
        assertFalse(Slugs.isValid("_leading"))
        assertFalse(Slugs.isValid("double__underscore"))
        assertFalse(Slugs.isValid("Upper"))
    }

    @Test
    fun `leerer name faellt auf exercise zurueck`() {
        assertEquals("exercise", Slugs.fromDisplayName("!!!"))
        assertTrue(Slugs.isValid("exercise"))
    }
}
