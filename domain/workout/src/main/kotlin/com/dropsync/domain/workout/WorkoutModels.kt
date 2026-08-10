package com.dropsync.domain.workout

import com.dropsync.core.model.Equipment
import com.dropsync.core.model.ExerciseKind
import com.dropsync.core.model.MuscleGroup
import com.dropsync.core.model.PrType
import com.dropsync.core.model.PrValueUnit
import com.dropsync.core.model.RestMode
import com.dropsync.core.model.SetRole

// Domainmodelle des Trainingslogs (Bauplan 5.4, Abschnitt 6).
// Gewichte sind IMMER ganze Millikilogramm (Long); Double ist verboten.

/** Eingabe eines Satzsegments vor dem Speichern. */
data class SegmentInput(
    val externalLoadMilliKgPerImplement: Long?,
    /** Nur 1 oder 2 (5.4); 2 nur bei zwei gleich schweren Implementseiten. */
    val loadMultiplier: Int,
    val reps: Int?,
    val durationMs: Long? = null,
    val distanceM: Long? = null,
)

/**
 * Ein qualifiziertes, abgeschlossenes Segment aus der Historie einer
 * Uebung; Grundlage der vollstaendigen PR-Neuberechnung (Schritt 10.4).
 */
data class QualifiedSegment(
    val sessionId: Long,
    val sessionStartedAtEpochMs: Long,
    val clusterId: Long,
    val completedAtEpochMs: Long,
    val loadMilliKg: Long,
    val loadMultiplier: Int,
    val reps: Int,
)

/** Ergebnis der PR-Berechnung; Persistenz uebernimmt :data:workout. */
data class PrRecord(
    val type: PrType,
    val achievedSessionId: Long,
    val achievedClusterId: Long?,
    val valueLong: Long,
    val valueUnit: PrValueUnit,
    val comparableLoadMilliKg: Long?,
    val achievedAtEpochMs: Long,
)

/**
 * Optionale Musikreferenz einer Session (Schritt 11.1): Song-ID, Marker-ID
 * nullable, Playerposition, Zeitstempel. Append-only; keine Queuekopie.
 */
data class PlaybackSnapshotInfo(
    val id: Long,
    val sessionId: Long,
    val songId: Long,
    val markerId: Long?,
    val positionMs: Long,
    val capturedAtEpochMs: Long,
)

/**
 * Qualifikation fuer Volumen und PRs (Bauplan 5.4/1): setRole WORKING
 * oder FAILURE, Cluster abgeschlossen, Uebung STRENGTH, mindestens ein
 * Segment mit reps > 0 und externalLoadMilliKg >= 0. WARMUP nie.
 */
object Qualification {
    fun clusterQualifies(
        kind: ExerciseKind,
        setRole: SetRole,
        isCompleted: Boolean,
    ): Boolean =
        kind == ExerciseKind.STRENGTH &&
            isCompleted &&
            (setRole == SetRole.WORKING || setRole == SetRole.FAILURE)

    fun segmentQualifies(segment: SegmentInput): Boolean {
        val reps = segment.reps ?: return false
        val load = segment.externalLoadMilliKgPerImplement ?: return false
        return reps > 0 && load >= 0
    }
}

/** Strategie beim Uebungstausch mitten in der Session (Schritt 9). */
enum class SwapStrategy { KEEP, MOVE, DISCARD }

/** Pro Uebung gemerkter Resttimer (Abschnitt 8). */
data class RestPref(
    val restSeconds: Int,
    val restMode: RestMode,
)

/** Muskelbeteiligung in Prozent (1..100) einer Uebung (Schritt 9.2). */
data class MuscleContribution(
    val group: MuscleGroup,
    val percent: Int,
)

/** Eingabe zum Anlegen einer eigenen Uebung (Schritt 9.1/9.2). */
data class CustomExerciseInput(
    /** Mindestens "de" und "en" (analog Seeder). */
    val displayNames: Map<String, String>,
    val kind: ExerciseKind,
    val equipment: Equipment,
    val muscles: List<MuscleContribution>,
)

/** Listeneintrag der Uebungsbibliothek (Equipment sichtbar). */
data class ExerciseLibraryItem(
    val id: Long,
    val slug: String,
    val displayName: String,
    val equipment: Equipment,
    val isCustom: Boolean,
)

/** Vollstaendige Uebungsdetails inkl. Muskel-Mapping (Detailansicht). */
data class ExerciseDetail(
    val id: Long,
    val slug: String,
    val displayName: String,
    val kind: ExerciseKind,
    val equipment: Equipment,
    val muscles: List<MuscleContribution>,
)

/**
 * Waehrend einer Session gespielter Track (Schritt 11.1): Grundlage der
 * Auswertung "zu diesem PR lief dieser Track".
 */
data class PlayedTrackInfo(
    val songId: Long,
    val title: String,
    val artist: String?,
    val positionMs: Long,
    val capturedAtEpochMs: Long,
)
