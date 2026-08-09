package com.dropsync.core.database.seed

import com.dropsync.core.database.DropSyncDatabase
import com.dropsync.core.database.entity.ExerciseEntity
import com.dropsync.core.database.entity.ExerciseMuscleEntity
import com.dropsync.core.database.entity.ExerciseNameEntity
import com.dropsync.core.database.entity.MuscleGroupEntity
import com.dropsync.core.database.entity.SetRoleEntity
import com.dropsync.core.model.Equipment
import com.dropsync.core.model.ExerciseKind
import com.dropsync.core.model.MuscleGroup
import com.dropsync.core.model.SetRole
import org.json.JSONObject

/**
 * Spielt die versionierte Standarduebungsbibliothek idempotent ein
 * (Bauplan Schritt 3.6, 9.1, 9.2).
 *
 * - Vorhandene Zeilen werden nie ueberschrieben (OnConflict IGNORE);
 *   Benutzerdaten bleiben unberuehrt.
 * - Ungueltige Eintraege (unbekannte Enums, Prozente ausserhalb 1..100,
 *   fehlende de/en-Namen) lassen den Seed mit Ausnahme scheitern, damit
 *   ein fehlerhaftes Seed-Artefakt nie teilweise landet.
 */
class ExerciseSeeder(
    private val database: DropSyncDatabase,
) {
    suspend fun seed(seedJson: String) {
        val root = JSONObject(seedJson)
        val seedVersion = root.getInt("seedVersion")
        require(seedVersion == SUPPORTED_SEED_VERSION) {
            "Unbekannte seedVersion: $seedVersion"
        }

        val dao = database.exerciseDao()

        // Lookup-Tabellen aus den stabilen Enum-Schluesseln (Schritt 3.1).
        dao.insertMuscleGroupsIgnoring(MuscleGroup.entries.map { MuscleGroupEntity(it.name) })
        dao.insertSetRolesIgnoring(SetRole.entries.map { SetRoleEntity(it.name) })

        val exercises = root.getJSONArray("exercises")
        for (i in 0 until exercises.length()) {
            val entry = exercises.getJSONObject(i)
            seedExercise(entry)
        }
    }

    private suspend fun seedExercise(entry: JSONObject) {
        val canonicalName = entry.getString("canonicalName")
        require(canonicalName.matches(SLUG_REGEX)) {
            "canonicalName ist kein gueltiger Slug: $canonicalName"
        }
        val kind = ExerciseKind.valueOf(entry.getString("kind"))
        val equipment = Equipment.valueOf(entry.getString("equipment"))

        val names = entry.getJSONObject("names")
        require(names.has("de") && names.has("en")) {
            "Uebung $canonicalName braucht Namen fuer de und en (Schritt 9.1)"
        }

        val dao = database.exerciseDao()
        val insertedId =
            dao.insertExerciseIgnoring(
                ExerciseEntity(
                    canonicalName = canonicalName,
                    kind = kind.name,
                    equipment = equipment.name,
                    isCustom = false,
                    isArchived = false,
                ),
            )
        // -1 bedeutet: Zeile existierte bereits; ID nachschlagen.
        val exerciseId =
            if (insertedId == -1L) {
                requireNotNull(dao.getByCanonicalName(canonicalName)).id
            } else {
                insertedId
            }

        val nameEntities =
            names
                .keys()
                .asSequence()
                .map { locale ->
                    ExerciseNameEntity(
                        exerciseId = exerciseId,
                        locale = locale,
                        displayName = names.getString(locale),
                    )
                }.toList()
        dao.insertNamesIgnoring(nameEntities)

        val muscles = entry.getJSONArray("muscles")
        val muscleEntities =
            buildList {
                for (j in 0 until muscles.length()) {
                    val muscle = muscles.getJSONObject(j)
                    val group = MuscleGroup.valueOf(muscle.getString("group"))
                    val percent = muscle.getInt("percent")
                    require(percent in 1..100) {
                        "contributionPercent ausserhalb 1..100: $percent ($canonicalName)"
                    }
                    add(
                        ExerciseMuscleEntity(
                            exerciseId = exerciseId,
                            muscleGroupId = group.name,
                            contributionPercent = percent,
                        ),
                    )
                }
            }
        require(muscleEntities.isNotEmpty()) { "Uebung $canonicalName ohne Muskelgruppen" }
        dao.insertMusclesIgnoring(muscleEntities)
    }

    companion object {
        const val SUPPORTED_SEED_VERSION = 1
        const val ASSET_PATH = "seed/standard_exercises.json"
        private val SLUG_REGEX = Regex("[a-z0-9]+(_[a-z0-9]+)*")
    }
}
