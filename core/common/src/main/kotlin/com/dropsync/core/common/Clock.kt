package com.dropsync.core.common

/**
 * Zeitquelle der App (Bauplan Schritt 2.5).
 *
 * - [elapsedRealtimeMs]: monotone Zeit seit Boot; einzige erlaubte Quelle
 *   fuer den normalen Timer (Bauplan 5.3). Systemzeitaenderungen haben keinen
 *   Einfluss.
 * - [epochMillis]: UTC-Wanduhrzeit fuer Persistenzzeitstempel (Bauplan 6).
 *
 * Produktionsimplementierung nutzt `SystemClock.elapsedRealtime()` und
 * `System.currentTimeMillis()`; Tests nutzen eine kontrollierbare FakeClock
 * aus `:core:testing`.
 */
interface Clock {
    fun elapsedRealtimeMs(): Long

    fun epochMillis(): Long
}
