package com.dropsync.data.audio

import com.dropsync.domain.audio.OutputProfileKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Automatischer Profilwechsel je Ausgabegeraet (ADR-0008, Plan Phase 5):
 * - Geraetewechsel laedt das gespeicherte Profil und aktiviert es;
 *   ohne Profil bleibt die aktuelle Konfiguration bestehen und wird als
 *   neues Profil dieses Geraets uebernommen.
 * - Jede Einstellungsaenderung wird in das Profil des gerade aktiven
 *   Geraets zurueckgeschrieben (Save-Through).
 *
 * Ein Mutex serialisiert Wechsel und Save-Through, damit beim
 * Umstecken keine alten Werte in das neue Profil laufen.
 */
class OutputProfileController(
    private val deviceSnapshots: Flow<OutputDeviceSnapshot>,
    private val settingsStore: DspSettingsStore,
    private val profileStore: DeviceProfileStore,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val mutableActiveKey = MutableStateFlow<String?>(null)

    /** Schluessel des aktiven Profils (Anzeige "Aktives Profil"). */
    val activeProfileKey: StateFlow<String?> = mutableActiveKey.asStateFlow()

    fun start() {
        scope.launch {
            deviceSnapshots
                .distinctUntilChanged()
                .collectLatest { snapshot -> switchTo(snapshot) }
        }
        scope.launch {
            // Erste Emission ist der Bestand beim Start; erst danach sind
            // es echte Nutzeraenderungen fuer das aktive Profil.
            settingsStore.config.drop(1).collectLatest { config ->
                mutex.withLock {
                    val key = mutableActiveKey.value ?: return@withLock
                    profileStore.write(key, config)
                }
            }
        }
    }

    private suspend fun switchTo(snapshot: OutputDeviceSnapshot) {
        mutex.withLock {
            val key =
                OutputProfileKey(
                    kind = snapshot.kind,
                    address = snapshot.address ?: snapshot.name,
                ).storageKey()
            if (key == mutableActiveKey.value) return
            val profile = profileStore.read(key)
            if (profile != null) {
                settingsStore.save(profile)
            } else {
                // Neues Geraet: aktuelle Einstellungen werden Startprofil.
                profileStore.write(key, settingsStore.config.first())
            }
            mutableActiveKey.value = key
        }
    }
}
