package com.dropsync.data.timer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dropsync.domain.timer.PlannedCue
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerSession
import com.dropsync.domain.timer.TimerSnapshot
import com.dropsync.domain.timer.TimerSnapshotStore
import com.dropsync.domain.timer.TimerStatus
import kotlinx.coroutines.flow.first

/**
 * DataStore-Persistenz fuer [TimerSnapshot] (Kill-Fallback 5b).
 *
 * Das Snapshot wird als kompaktes JSON ohne Reflection serialisiert (stabiles
 * Schema, kein Moshi/Gson im Timer-Pfad). Nur REST/NORMAL werden gespeichert;
 * DROPSYNC-Snapshots lehnt die Engine ohnehin ab. Bei Ruecksprung der
 * monotonen Uhr (Reboot) loescht der Aufrufer ueber [clear].
 */
class DataStoreTimerSnapshotStore(
    private val dataStore: DataStore<Preferences>,
) : TimerSnapshotStore {
    override suspend fun load(): TimerSnapshot? =
        dataStore.data.first()[KEY_SNAPSHOT]?.let(::deserialize)

    override suspend fun save(snapshot: TimerSnapshot) {
        dataStore.edit { it[KEY_SNAPSHOT] = serialize(snapshot) }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY_SNAPSHOT) }
    }

    private fun serialize(s: TimerSnapshot): String = buildString {
        append("{\"mode\":\"").append(s.session.mode.name).append("\"")
        append(",\"status\":\"").append(s.status.name).append("\"")
        append(",\"id\":\"").append(s.session.id).append("\"")
        append(",\"durationMs\":").append(s.session.durationMs)
        append(",\"started\":").append(s.session.startedElapsedRealtimeMs ?: -1)
        append(",\"end\":").append(s.endElapsedRealtimeMs ?: -1)
        append(",\"paused\":").append(s.pausedRemainingMs ?: -1)
        append(",\"cues\":[")
        s.session.plannedCues.forEachIndexed { i, c ->
            if (i > 0) append(",")
            append("[").append(c.thresholdMs).append(",")
                .append(if (c.speak) 1 else 0).append(",")
                .append(if (c.haptic) 1 else 0).append(",")
                .append(if (c.tone) 1 else 0).append("]")
        }
        append("]}")
    }

    private fun deserialize(json: String): TimerSnapshot? = runCatching {
        fun str(key: String): String =
            Regex("\"$key\":\"([^\"]*)\"").find(json)!!.groupValues[1]

        fun lng(key: String): Long =
            Regex("\"$key\":(-?\\d+)").find(json)!!.groupValues[1].toLong()

        fun opt(key: String): Long? = lng(key).takeIf { it >= 0 }

        val cues =
            Regex("\\[(\\d+),(\\d),(\\d),(\\d)]")
                .findAll(json)
                .map { m ->
                    PlannedCue(
                        thresholdMs = m.groupValues[1].toLong(),
                        speak = m.groupValues[2] == "1",
                        haptic = m.groupValues[3] == "1",
                        tone = m.groupValues[4] == "1",
                    )
                }.toList()

        TimerSnapshot(
            session =
                TimerSession(
                    id = str("id"),
                    mode = TimerMode.valueOf(str("mode")),
                    durationMs = lng("durationMs"),
                    startedElapsedRealtimeMs = opt("started"),
                    markerPositionMs = null,
                    plannedCues = cues,
                ),
            status = TimerStatus.valueOf(str("status")),
            endElapsedRealtimeMs = opt("end"),
            pausedRemainingMs = opt("paused"),
        )
    }.getOrNull()

    companion object {
        const val DATA_STORE_NAME = "timer_snapshot"
        private val KEY_SNAPSHOT = stringPreferencesKey("timer_snapshot_json")
    }
}
