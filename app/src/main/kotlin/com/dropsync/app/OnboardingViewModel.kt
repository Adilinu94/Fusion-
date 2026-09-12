package com.dropsync.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.domain.settings.OnboardingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * First-Run-Status (Ausbauplan B3). `null`, solange DataStore laedt — die App
 * zeigt dann weder Onboarding noch Inhalt (kein Flackern bei Update-Nutzern).
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val onboardingRepository: OnboardingRepository,
    ) : ViewModel() {
        val seen: StateFlow<Boolean?> =
            onboardingRepository.onboardingSeen
                .map { it as Boolean? }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        fun markSeen() {
            viewModelScope.launch { onboardingRepository.markOnboardingSeen() }
        }
    }
