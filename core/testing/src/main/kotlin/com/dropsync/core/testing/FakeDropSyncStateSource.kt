package com.dropsync.core.testing

import com.dropsync.domain.timer.DropSyncState
import com.dropsync.domain.timer.DropSyncStateSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fake des app-weiten DropSync-Zustands (P1-10): Tests koennen den Plan
 * setzen und pruefen, was die UI daraus macht.
 */
class FakeDropSyncStateSource(
    initial: DropSyncState = DropSyncState.Off,
) : DropSyncStateSource {
    private val stateFlow = MutableStateFlow(initial)
    override val state: StateFlow<DropSyncState> = stateFlow

    /** P1-10: wie oft die UI "Plan abbrechen" gerufen hat. */
    var cancelPlanCalls = 0
        private set

    /** C13: wie oft "Plan verloren" quittiert wurde. */
    var acknowledgePlanLostCalls = 0
        private set

    override fun cancelPlan() {
        cancelPlanCalls++
    }

    override fun acknowledgePlanLost() {
        acknowledgePlanLostCalls++
        if (stateFlow.value is DropSyncState.Failed &&
            (stateFlow.value as DropSyncState.Failed).reason ==
            com.dropsync.domain.timer.DropSyncFailureReason.PLAN_LOST
        ) {
            stateFlow.value = DropSyncState.Off
        }
    }

    /** C2: wie oft Undo nach einem Skip gerufen wurde. */
    var replanAfterOverrideCalls = 0
        private set

    /** C2: Ergebnis, das [replanAfterOverride] liefern soll. */
    var replanAfterOverrideResult = true

    override fun replanAfterOverride(): Boolean {
        replanAfterOverrideCalls++
        return replanAfterOverrideResult
    }

    fun set(state: DropSyncState) {
        stateFlow.value = state
    }
}
