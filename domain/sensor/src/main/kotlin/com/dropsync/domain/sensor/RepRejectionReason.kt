package com.dropsync.domain.sensor

/**
 * RC-17: klassifizierte Ablehnungsgruende der Rep-Pipeline.
 *
 * Vorher stand nur ein Freitext in [RepResult.rejectionReason] — "27
 * Abweichungen" liessen sich damit nicht in ihre Ursachen zerlegen. Dieses
 * Enum macht die Mechanismen zaehlbar (je Satz und im JSONL), damit
 * Verbesserungen am richtigen Mechanismus ansetzen.
 *
 * Bewusst nur die Mechanismen auf Top-Level-Ebene; die Untergruende der
 * Phasen-Validierung (halbe Rep, zu kurz, asymmetrisch) bleiben im
 * Freitext-Detail von [RepResult.rejectionReason] erhalten.
 */
enum class RepRejectionReason {
    /** Punkt 4: Gyro-Peak ohne Accel-Peak im Vote-Fenster (Voting gegen Erschuetterung). */
    ACCEL_VOTING,

    /** Formvergleich gegen den Template-Pool (NCC/DTW unter der Schwelle). */
    TEMPLATE_MATCH,

    /** Phasen-Validierung: kein voller Zyklus (exzentrisch + konzentrisch). */
    PHASE_VALIDATION,

    /** Qualitaetsscore unter der kalibrierten Mindestschwelle. */
    QUALITY,
}
