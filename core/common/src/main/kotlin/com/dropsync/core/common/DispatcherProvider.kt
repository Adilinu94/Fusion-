package com.dropsync.core.common

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Zentraler Dispatcher-Vertrag (Bauplan Schritt 2.4).
 *
 * Use Cases und Repositories erhalten Dispatcher nur ueber diese
 * Abstraktion, damit Tests deterministisch mit einem TestDispatcher laufen.
 */
interface DispatcherProvider {
    /** IO-lastige Arbeit: Room, MediaStore, Dateizugriffe. */
    val io: CoroutineDispatcher

    /** CPU-lastige Arbeit: Parsen, Statistikberechnung. */
    val default: CoroutineDispatcher

    /** Main-Thread; nur fuer UI-nahe Koordination. */
    val main: CoroutineDispatcher
}
