package com.dropsync.data.timer

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TTS-Adapter (Bauplan Schritt 8.1-8.3):
 *
 * - Initialisierung vor der Timer-Session; Fehlschlag setzt nur
 *   `ttsAvailable = false`, nie den Timer auf FAILED.
 * - Attribute: CONTENT_TYPE_SPEECH + USAGE_ASSISTANCE_SONIFICATION
 *   (nie VOICE_COMMUNICATION, Bauplan 5.3).
 * - Jede Ansage traegt die cueSessionId als Utterance-ID; Callbacks
 *   handeln nur, wenn sie zur aktiven Sitzung gehoeren.
 */
class TtsSpeaker(
    private val context: Context,
    private val onCueFinished: (cueSessionId: String) -> Unit,
) {
    private var tts: TextToSpeech? = null
    private val available = AtomicBoolean(false)

    /** Nicht blockierend; Ergebnis kommt asynchron. */
    fun initialize(locale: Locale) {
        if (tts != null) return
        tts =
            TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    available.set(false)
                    return@TextToSpeech
                }
                val engine = tts ?: return@TextToSpeech
                val languageResult = engine.setLanguage(locale)
                val languageOk =
                    languageResult != TextToSpeech.LANG_MISSING_DATA &&
                        languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                if (languageOk) {
                    engine.setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .build(),
                    )
                    engine.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit

                            override fun onDone(utteranceId: String?) {
                                utteranceId?.let { onCueFinished(sessionIdOf(it)) }
                            }

                            @Deprecated("Basisklasse verlangt Override")
                            override fun onError(utteranceId: String?) {
                                utteranceId?.let { onCueFinished(sessionIdOf(it)) }
                            }

                            override fun onError(
                                utteranceId: String?,
                                errorCode: Int,
                            ) {
                                utteranceId?.let { onCueFinished(sessionIdOf(it)) }
                            }
                        },
                    )
                }
                available.set(languageOk)
            }
    }

    /** false => Timer laeuft mit Haptik/Ton weiter (Schritt 8.1). */
    fun isAvailable(): Boolean = available.get()

    fun speak(
        cueSessionId: String,
        text: String,
    ): Boolean {
        val engine = tts ?: return false
        if (!available.get()) return false
        val utteranceId = "$cueSessionId$SEPARATOR${System.nanoTime()}"
        return engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) ==
            TextToSpeech.SUCCESS
    }

    /** Stoppt laufende Ansagen sofort (Schritt 7.7). */
    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        available.set(false)
    }

    companion object {
        private const val SEPARATOR = '|'

        /** Utterance-ID -> Cue-Session-ID (Teil vor dem Separator). */
        fun sessionIdOf(utteranceId: String): String = utteranceId.substringBefore(SEPARATOR)
    }
}
