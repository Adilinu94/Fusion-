package com.dropsync.data.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * RenderersFactory der eigenen Audio-Pipeline (ADR-0005):
 * - Float-Output aktiv, damit Hi-Res-Quellen (24/32 Bit) ohne
 *   16-Bit-Zwischenschritt zum System gelangen; im Bit-Perfect-Modus
 *   (ADR-0009) wird Float-Output abgeschaltet, damit der Mixer die
 *   Quelldaten unveraendert durchreicht;
 * - die DSP-Kette laeuft laut DefaultAudioSink.configure vor der
 *   Float-/Int16-Konvertierung und damit in beiden Pfaden;
 * - die selbstgebaute FFmpeg-Extension (ADR-0006) wird bevorzugt, sobald
 *   sie im Build liegt (ALAC/AIFF/WMA/APE/TAK/TTA/DSD); fehlt sie, greift
 *   automatisch der Plattformdecoder.
 */
@OptIn(UnstableApi::class)
class DspRenderersFactory(
    context: Context,
    private val audioProcessors: Array<AudioProcessor>,
    private val floatOutput: Boolean = true,
) : DefaultRenderersFactory(context) {
    init {
        // Extension bevorzugen und bei Bedarf auf Plattformdecoder
        // zurueckfallen (Plan Phase 3, Punkt 2).
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink
            .Builder(context)
            .setEnableFloatOutput(floatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(audioProcessors)
            .build()
}
