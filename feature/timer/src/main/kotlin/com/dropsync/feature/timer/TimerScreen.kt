package com.dropsync.feature.timer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.dropsync.core.designsystem.icon.BrandIcons

/**
 * Standalone-Resttimer als Route (Verbesserungsplan B-ARCH-2 / P2-17).
 *
 * [TimerSection] lief seit Monaten ohne jeden Konsumenten — weder die App
 * noch ein anderes Feature hat das Modul erreicht. Diese Huelle gibt ihm
 * eine Route (`timer`), erreichbar aus dem Train-Tab waehrend einer Pause
 * (gleiche geteilte `TimerEngine` wie die Train-Pille, daher konsistenter
 * Zustand). Kein fuenfter Haupt-Tab: die 4-Tab-Struktur bleibt unangetastet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimerViewModel = hiltViewModel(),
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentPadding),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.timer_rest_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(BrandIcons.Back),
                        contentDescription = stringResource(R.string.timer_back),
                    )
                }
            },
        )
        TimerSection(viewModel = viewModel)
    }
}
