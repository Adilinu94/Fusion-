package com.dropsync.data.audio

import android.os.Process
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Drosselt die HINTERGRUND-Analyse (alle Profile ausser dem Waveform-Lauf des laufenden Titels):
 *
 * - **Hoechstens [parallelism] Laeufe zugleich.** WorkManager startet unbeschraenkte Arbeit sofort und
 *   begrenzt CoroutineWorker nicht: nach dem Import einer Bibliothek liefen so Hunderte Decoder
 *   gleichzeitig (CPU, Speicher, MediaCodec-Instanzen) und der Waveform-Lauf des gerade gespielten
 *   Titels musste mit ihnen um die CPU konkurrieren - Ruckeln der ganzen App.
 * - **Niedrige Thread-Prioritaet** ([ThreadPriority.runAtBackground]) waehrend des Laufs, damit
 *   UI-Thread und RenderThread Vorrang behalten.
 *
 * Wartende Laeufe parken als Coroutine (kein Thread blockiert). Der Lauf fuer den gerade
 * gespielten Titel nutzt das Gate NICHT.
 */
internal class AnalysisGate(
    parallelism: Int = BACKGROUND_PARALLELISM,
    private val priority: ThreadPriority = AndroidThreadPriority,
) {
    private val permits = Semaphore(parallelism)

    /** Fuehrt [block] aus, sobald ein Platz frei ist, mit abgesenkter Prioritaet des aktuellen Threads. */
    suspend fun <T> background(block: suspend () -> T): T = permits.withPermit { priority.runAtBackground { block() } }

    companion object {
        /** Ein Hintergrund-Decoder zugleich. */
        const val BACKGROUND_PARALLELISM = 1
    }
}

/** Senkt die Prioritaet des aktuellen Threads fuer die Dauer von [runAtBackground]. */
internal interface ThreadPriority {
    suspend fun <T> runAtBackground(block: suspend () -> T): T
}

/**
 * Setzt `THREAD_PRIORITY_BACKGROUND` und stellt den vorherigen Wert auf DEMSELBEN Thread wieder her
 * (ueber die Thread-ID gemerkt), auch bei Fehlern und Abbruch. Der Decode-Lauf suspendiert nirgends
 * wirklich (nur `ensureActive`), bleibt also auf diesem Thread.
 */
internal object AndroidThreadPriority : ThreadPriority {
    override suspend fun <T> runAtBackground(block: suspend () -> T): T {
        val tid = Process.myTid()
        val previous = Process.getThreadPriority(tid)
        Process.setThreadPriority(tid, Process.THREAD_PRIORITY_BACKGROUND)
        try {
            return block()
        } finally {
            Process.setThreadPriority(tid, previous)
        }
    }
}
