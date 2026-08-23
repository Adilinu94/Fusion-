package com.dropsync.domain.sensor

/**
 * Unveraenderlicher Mitschnitt eines gezaehlten Sets (Umbauplan Phase 7.1):
 * die einzige Eingabe fuer den Lernpfad. Wird vom [ActiveSetController] beim
 * Set-Abschluss eingefroren; keine Coroutine darf danach eine mutable Liste
 * lesen, die spaeter geleert wird.
 */
data class SetTrace(
    val exerciseId: Long,
    val deviceId: String,
    /** Pipeline-Version, mit der dieses Set gezaehlt wurde. */
    val engineVersion: RepEngineVersion,
    /** Revision des Profils, das beim Set-Start aktiv war. */
    val profileRevision: Int,
    /** Unveraenderliche Sample-Kopie (Learn-Loop-Grundlage). */
    val samples: List<SensorSample>,
    /** Alle gezaehlten Rep-Events in Reihenfolge. */
    val repEvents: List<RepEvent>,
    /** Vom Live-Engine gezaehlte Wiederholungen. */
    val predictedReps: Int,
    /** Signalqualitaet beim Set-Abschluss (eingefroren). */
    val signalQuality: SignalQuality,
    /** Monotone Startzeit des Sets (fuer Diagnose). */
    val startedAtMs: Long,
    /** Dauer des Sets in Millisekunden (Start -> Abschluss). */
    val durationMs: Long,
)
