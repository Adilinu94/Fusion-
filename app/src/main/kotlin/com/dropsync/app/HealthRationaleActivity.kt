package com.dropsync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.core.model.AccentColor
import com.dropsync.core.model.ThemeMode
import com.dropsync.domain.settings.AccentColorRepository
import com.dropsync.domain.settings.ThemeSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Begruendungsseite fuer die Health-Connect-Berechtigung
 * (Herzfrequenz-Plan Abschnitt 7).
 *
 * Health Connect verlangt sie als Voraussetzung fuer den
 * Berechtigungsdialog und startet sie selbst — ueber
 * `ACTION_SHOW_PERMISSIONS_RATIONALE` (Android <= 13) oder
 * `VIEW_PERMISSION_USAGE` mit Kategorie `HEALTH_PERMISSIONS` (Android 14+).
 * Ohne diese Deklaration lehnt Health Connect die Anfrage ab; der Knopf
 * "Puls erlauben" im Train-Tab bliebe wirkungslos.
 *
 * Bewusst eine eigene Activity und kein `activity-alias` auf
 * [MainActivity]: ein Alias wuerde die normale App oeffnen, statt die
 * Begruendung zu zeigen — der Nutzer bekaeme die Antwort auf "warum will
 * diese App meinen Puls?" nie zu sehen. Die Seite ist reiner Text ohne
 * Eingaben und ohne Datenzugriff, weshalb `exported="true"` hier
 * unbedenklich ist.
 */
@AndroidEntryPoint
class HealthRationaleActivity : ComponentActivity() {
    @Inject
    lateinit var themeSettings: ThemeSettingsRepository

    @Inject
    lateinit var accentColorSettings: AccentColorRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Dasselbe Design wie die App: wer Dunkelmodus gewaehlt hat, soll
            // aus Health Connect kommend nicht auf eine helle Seite fallen.
            val themeMode by
                themeSettings.themeMode.collectAsStateWithLifecycle(
                    initialValue = ThemeMode.SYSTEM,
                )
            val systemDark = isSystemInDarkTheme()
            val darkTheme =
                when (themeMode) {
                    ThemeMode.SYSTEM -> systemDark
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            val accent by
                accentColorSettings.accentColor.collectAsStateWithLifecycle(
                    initialValue = AccentColor.LIME,
                )
            FlowRepTheme(darkTheme = darkTheme, accent = accent) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HealthRationaleContent()
                }
            }
        }
    }
}

@Composable
private fun HealthRationaleContent() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.health_rationale_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.health_rationale_purpose),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.health_rationale_scope),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.health_rationale_offline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.health_rationale_revoke),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
