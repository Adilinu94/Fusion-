package com.dropsync.data.audio

import android.bluetooth.BluetoothCodecConfig
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
    /**
     * A2DP-Codec-Name (BluetoothCodecConfig.CODEC_*) falls bekannt; null
     * fuer Nicht-BT-Routen. Latenzprofile unterscheiden damit SBC/AAC/
     * LDAC statt pauschal SBC anzunehmen (Poweramp-Muster
     * PaBluetoothCodecConfig, Triage-Lektion 2026-08-13).
     */
    val bluetoothCodec: String? = null,
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
                bluetoothCodec = bluetoothCodecName(ranked),
            )
        }

        /**
         * Aktueller A2DP-Codec fuer [device] bzw. null ausserhalb von
         * Bluetooth oder wenn die Abfrage nicht verfuegbar ist (null =
         * SBC-Annahme im RouteProfileStore, dem A2DP-Fallback).
         *
         * `AudioManager.getBluetoothCodecStatus()` ist in vielen
         * SDK-Builds eine versteckte API (nicht im android.jar); Poweramp
         * ruft genau solche Methoden per Reflection mit Exception-Fallback
         * auf (sein Log "AudioManager.getProperty java exception" zeigt das
         * Muster). Die Typ-Konstanten kommen aus der public Klasse
         * [BluetoothCodecConfig], deshalb kein API-Level-Guard noetig.
         */
        private fun bluetoothCodecName(device: AudioDeviceInfo?): String? {
            if (device?.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return null
            return runCatching {
                val codec =
                    runCatching {
                        val getter = AudioManager::class.java.getMethod("getBluetoothCodecStatus")
                        val status = getter.invoke(audioManager) ?: return null
                        val configGetter = status.javaClass.getMethod("getCodecConfig")
                        val config = configGetter.invoke(status) ?: return null
                        val typeGetter = config.javaClass.getMethod("getCodecType")
                        typeGetter.invoke(config) as? Int
                    }.getOrNull() ?: return null
                when (codec) {
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "APTX"
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "APTX_HD"
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3 -> "LC3"
                    else -> null
                }
            }.getOrNull()
        }
    }
