package com.dropsync.core.database

import androidx.room.withTransaction

/**
 * Abstraktion ueber Room-Transaktionen (Bauplan Schritt 3.4), damit
 * Repository-Tests auf der JVM ohne Datenbank laufen koennen.
 * Wirft der Block, wird die gesamte Transaktion verworfen.
 */
interface TransactionRunner {
    suspend operator fun <T> invoke(block: suspend () -> T): T
}

class RoomTransactionRunner(
    private val database: DropSyncDatabase,
) : TransactionRunner {
    override suspend fun <T> invoke(block: suspend () -> T): T = database.withTransaction { block() }
}
