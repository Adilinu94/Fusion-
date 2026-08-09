package com.dropsync.feature.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Laufzeitberechtigung fuer Audio je nach API-Level (Schritt 4.1). */
private val audioPermission: String
    get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

/**
 * Musik-Tab: Kernflow Berechtigung -> Bibliothek -> Play (Schritt 12.3).
 * Kein stiller leerer Screen: fehlende Berechtigung wird erklaert (4.1).
 * Nach der Berechtigung zeigt [LibraryContent] die Ansichten aus Phase 6
 * (Titel/Kuenstler/Alben/Genres/Ordner/Favoriten/zuletzt/meistgespielt,
 * Suche, Sortierung, Hi-Res-Filter).
 */
@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenNowPlaying: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val error by viewModel.error.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
            if (granted) viewModel.refresh(force = true)
        }

    LaunchedEffect(hasPermission) {
        if (hasPermission) viewModel.refresh()
    }

    when {
        !hasPermission || error == LibraryError.PERMISSION_MISSING -> {
            PermissionExplainer(
                contentPadding = contentPadding,
                onRequest = { permissionLauncher.launch(audioPermission) },
                modifier = modifier,
            )
        }

        else -> {
            LibraryContent(
                viewModel = viewModel,
                contentPadding = contentPadding,
                scanFailed = error == LibraryError.SCAN_FAILED,
                onOpenNowPlaying = onOpenNowPlaying,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun PermissionExplainer(
    contentPadding: PaddingValues,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.library_permission_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.library_permission_rationale),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        // Grosses Touch-Ziel (Schritt 12.5).
        Button(
            onClick = onRequest,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.library_permission_button))
        }
    }
}
