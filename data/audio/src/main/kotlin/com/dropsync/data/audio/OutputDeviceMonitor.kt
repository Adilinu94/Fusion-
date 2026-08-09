package com.dropsync.data.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.dropsync.domain.audio.OutputDeviceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Aktiver Ausgang plus Anzeigename (Heuristik ueber die Geraeteliste). */
data class OutputDeviceSnapshot(
    val kind: OutputDeviceKind,
    val name: String?,
    /** Geraeteadresse (BT/USB) fuer Profil-Schluessel (ADR-0008). */
    val address: String? = null,
)

/**
 * Beobachtet die Ausgabegeraete (Plan Phase 1; Grundlage fuer die
 * Profile aus ADR-0008). Android liefert das tatsaechlich geroutete
 * Geraet nicht direkt; die uebliche Prioritaet BT > USB > Kabel >
 * Lautsprecher bildet das Routing des Systemmixers ab.
 */
@Singleton
class OutputDeviceMonitor
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        private val mutableDevice = MutableStateFlow(currentSnapshot())
        val device: StateFlow<OutputDeviceSnapshot> = mutableDevice.asStateFlow()

        private val callback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    mutableDevice.value = currentSnapshot()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    mutableDevice.value = currentSnapshot()
                }
            }

        init {
            audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }

        private fun currentSnapshot(): OutputDeviceSnapshot {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val ranked =
                outputs.minByOrNull { device ->
                    when (device.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0

                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        -> 1

                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        -> 2

                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 3

                        else -> 4
                    }
                }
            return OutputDeviceSnapshot(
                kind =
                    when (ranked?.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputDeviceKind.BLUETOOTH_A2DP

                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        -> OutputDeviceKind.USB

                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        -> OutputDeviceKind.WIRED

                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputDeviceKind.SPEAKER

                        null -> OutputDeviceKind.SPEAKER

                        else -> OutputDeviceKind.OTHER
                    },
                name = ranked?.productName?.toString(),
                address = ranked?.address?.takeIf { it.isNotBlank() },
            )
        }
    }
