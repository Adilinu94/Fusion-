package com.dropsync.core.testing

import com.dropsync.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * DispatcherProvider fuer Tests: alle Rollen zeigen auf denselben
 * TestDispatcher, damit virtuelle Zeit deterministisch gesteuert wird.
 */
class TestDispatcherProvider(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : DispatcherProvider {
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val main: CoroutineDispatcher get() = dispatcher
}
