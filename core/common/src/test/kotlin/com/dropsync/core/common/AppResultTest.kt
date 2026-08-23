package com.dropsync.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vertragstests fuer den App-Result-Typ (Bauplan 2.3): map/flatMap/Side-
 * Effects muessen den Fehlerfall unverändert durchreichen.
 */
class AppResultTest {
    @Test
    fun `map transformiert Erfolg`() {
        val result: AppResult<Int> = AppResult.success(2)
        assertEquals(4, result.map { it * 2 }.getOrNull())
    }

    @Test
    fun `map reicht Fehler unverändert durch`() {
        val error = AppError.Unknown("x")
        val result: AppResult<Int> = AppResult.failure(error)
        val mapped = result.map { it * 2 }
        assertSame(error, mapped.errorOrNull())
    }

    @Test
    fun `flatMap verkettet Erfolge`() {
        val result: AppResult<Int> = AppResult.success(2)
        val chained = result.flatMap { AppResult.success(it.toString()) }
        assertEquals("2", chained.getOrNull())
    }

    @Test
    fun `flatMap bricht beim ersten Fehler ab`() {
        val error = AppError.Unknown("stop")
        val result: AppResult<Int> = AppResult.failure(error)
        var transformCalled = false
        val chained =
            result.flatMap {
                transformCalled = true
                AppResult.success("nie")
            }
        assertNull(chained.getOrNull())
        assertSame(error, chained.errorOrNull())
        assertTrue(!transformCalled)
    }

    @Test
    fun `onSuccess ruft nur bei Erfolg`() {
        var called = false
        AppResult.success(1).onSuccess { called = true }
        assertTrue(called)

        var failureCalled = false
        AppResult.success(1).onFailure { failureCalled = true }
        assertTrue(!failureCalled)
    }

    @Test
    fun `onFailure liefert den Fehler`() {
        var received: AppError? = null
        val error = AppError.Unknown("e")
        AppResult.failure(error).onFailure { received = it }
        assertSame(error, received)
    }

    @Test
    fun `getOrNull und errorOrNull sind disjunkt`() {
        val success: AppResult<Int> = AppResult.success(7)
        assertEquals(7, success.getOrNull())
        assertNull(success.errorOrNull())

        val failure: AppResult<Int> = AppResult.failure(AppError.Unknown("f"))
        assertNull(failure.getOrNull())
        assertTrue(failure.errorOrNull() is AppError.Unknown)
    }
}
