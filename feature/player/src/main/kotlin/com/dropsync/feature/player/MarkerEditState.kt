package com.dropsync.feature.player

/**
 * P2-21 (UI-Handbuch 14.5): Zustand der Marker-Feinjustierung im
 * Marker-Sheet. Die Originalposition bleibt erhalten, damit der Nutzer
 * eine Korrektur nachvollziehen und zuruecknehmen kann; [isDirty] zeigt,
 * ob die aktuelle Position von der Auto-/Ausgangsposition abweicht.
 */
data class MarkerEditState(
    val originalPositionMs: Long,
    val editedPositionMs: Long,
) {
    val isDirty: Boolean get() = editedPositionMs != originalPositionMs

    /** Feinjustierung um [deltaMs], geklemmt auf die Trackgrenzen. */
    fun adjustedBy(
        deltaMs: Long,
        durationMs: Long,
    ): MarkerEditState =
        copy(
            editedPositionMs =
                (editedPositionMs + deltaMs).coerceIn(0L, durationMs.coerceAtLeast(0L)),
        )

    /** Zurueck auf die urspruengliche Position (Auto-Position). */
    fun reverted(): MarkerEditState = copy(editedPositionMs = originalPositionMs)

    /**
     * B4: automatischer Beat-Snap beim Oeffnen des Sheets. Die
     * Originalposition bleibt erhalten, damit "Zurueck auf Original" den
     * Snap rueckgaengig machen kann; danach ist die Korrektur wieder frei
     * (adjustBy arbeitet auf der gerasteten Position).
     */
    fun snappedTo(targetMs: Long): MarkerEditState = copy(editedPositionMs = targetMs.coerceAtLeast(0L))

    companion object {
        /** Startzustand eines Markers: original == aktuell, nicht schmutzig. */
        fun of(positionMs: Long): MarkerEditState =
            MarkerEditState(originalPositionMs = positionMs, editedPositionMs = positionMs)
    }
}
