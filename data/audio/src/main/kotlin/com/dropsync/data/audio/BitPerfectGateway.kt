package com.dropsync.data.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.dropsync.domain.audio.BitPerfectSupport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bit-Perfect-Faehigkeiten (ADR-0009): nur USB-DAC ab Android 14
 * (AudioMixerAttributes-API). UEber Bluetooth prinzipiell unmoeglich;
 * das UI kommuniziert diese Grenze ehrlich (Plan, "Ehrliche Grenzen").
 */
@Singleton
class BitPerfectGateway
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        private val mutableSupport = MutableStateFlow(currentSupport())

        /** Faehigkeiten des aktuell angeschlossenen USB-Ausgangs. */
        val support: StateFlow<BitPerfectSupport> = mutableSupport.asStateFlow()

        init {
            audioManager.registerAudioDeviceCallback(
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                        mutableSupport.value = currentSupport()
                    }

                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                        mutableSupport.value = currentSupport()
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }

        private fun currentSupport(): BitPerfectSupport {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return BitPerfectSupport.UNAVAILABLE
            }
            val usbDevice =
                audioManager
                    .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    } ?: return BitPerfectSupport.UNAVAILABLE
            val mixerAttributes = audioManager.getSupportedMixerAttributes(usbDevice)
            if (mixerAttributes.isEmpty()) {
                // Geraet vorhanden, aber der Mixer bietet keine
                // Bit-Perfect-Attribute an.
                return BitPerfectSupport(
                    available = false,
                    deviceName = usbDevice.productName?.toString(),
                )
            }
            return BitPerfectSupport(
                available = true,
                deviceName = usbDevice.productName?.toString(),
                sampleRatesHz =
                    mixerAttributes
                        .map { it.format.sampleRate }
                        .filter { it > 0 }
                        .distinct()
                        .sorted(),
                encodings =
                    mixerAttributes
                        .map { encodingName(it.format.encoding) }
                        .distinct(),
            )
        }

        private fun encodingName(encoding: Int): String =
            when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> "16-Bit PCM"
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-Bit PCM"
                AudioFormat.ENCODING_PCM_32BIT -> "32-Bit PCM"
                AudioFormat.ENCODING_PCM_FLOAT -> "32-Bit Float"
                else -> "Encoding $encoding"
            }

        /**
         * Verdrahtung (Befund 2.8): aktiviert Bit-Perfect am ersten
         * USB-Ausgang via `setPreferredMixerAttributes` (>= API 34). Wird
         * nach Service-Neustart beim ersten Track-Start gerufen; die Wahl
         * des Attributs ueberlaesst der AudioMixer dem Geraet.
         *
         * @return true, wenn die Attribute gesetzt werden konnten.
         */
        fun applyPreferredMixerAttributes(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
            val usbDevice =
                audioManager
                    .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    } ?: return false
            val mixerAttributes =
                audioManager.getSupportedMixerAttributes(usbDevice).firstOrNull() ?: return false
            return runCatching {
                audioManager.setPreferredMixerAttributes(
                    android.media.AudioAttributes
                        .Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                    usbDevice,
                    mixerAttributes,
                )
            }.getOrDefault(false)
        }

        /** Gibt die bevorzugten Mixer-Attribute wieder frei (Ausschalten). */
        fun clearPreferredMixerAttributes() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            val usbDevice =
                audioManager
                    .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    } ?: return
            runCatching {
                audioManager.clearPreferredMixerAttributes(
                    android.media.AudioAttributes
                        .Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                    usbDevice,
                )
            }
        }
    }
