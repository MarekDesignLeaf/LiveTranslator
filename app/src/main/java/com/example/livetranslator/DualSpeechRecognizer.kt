package com.example.livetranslator

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Continuous speech recognizer — single instance, simple restart loop.
 *
 * Design rules that prevent the previous bugs:
 *
 * 1. ONE SpeechRecognizer, created once, never recreated unless truly broken.
 * 2. NO cancel() after onResults — the session is already finished, calling
 *    cancel() triggers a spurious onError(ERROR_CLIENT) which caused double-starts.
 * 3. isRestarting flag — only one restart can be in flight at a time.
 * 4. onError ERROR_CLIENT (5) is silently ignored — it comes from internal
 *    state changes, not a real failure.
 * 5. No MINIMUM_LENGTH extra — it breaks restart on Samsung/Pixel devices.
 */
class DualSpeechRecognizer(
    private val context  : Context,
    private val onPartial: (String) -> Unit,
    private val onFinal  : (String) -> Unit,
    private val onErrorCb: (Int) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    private var recognizer   : SpeechRecognizer? = null
    @Volatile var started     = false
    var languageTag           = "en"

    // Guard: only one pending restart allowed at a time
    private var isRestarting  = false

    // ── Public API ─────────────────────────────────────────────────────────

    fun start(langTag: String) {
        if (started) return
        languageTag = langTag
        started = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(listener)
        doStartListening()
    }

    fun stop() {
        started = false
        isRestarting = false
        main.removeCallbacksAndMessages(null)
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.cancel() }        catch (_: Exception) {}
        try { recognizer?.destroy() }       catch (_: Exception) {}
        recognizer = null
    }

    fun updateLanguage(langTag: String) { languageTag = langTag }

    // ── Private ────────────────────────────────────────────────────────────

    private fun doStartListening() {
        if (!started) return
        isRestarting = false
        try {
            recognizer?.startListening(buildIntent())
        } catch (e: Exception) {
            // Recognizer is broken — rebuild it
            rebuildAndRestart(600)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!started || isRestarting) return
        isRestarting = true
        main.postDelayed({ doStartListening() }, delayMs)
    }

    private fun rebuildAndRestart(delayMs: Long) {
        if (!started) return
        isRestarting = true
        main.postDelayed({
            if (!started) return@postDelayed
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(listener)
            doStartListening()
        }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(r: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEvent(t: Int, p: android.os.Bundle?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partials: android.os.Bundle?) {
            val text = partials
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotBlank()) onPartial(text)
        }

        override fun onResults(results: android.os.Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotBlank()) onFinal(text)
            // Session is done naturally — restart immediately, no cancel() needed
            scheduleRestart(100)
        }

        override fun onError(error: Int) {
            if (!started) return

            // ERROR_CLIENT (5) is a spurious internal error, not a real failure — ignore
            if (error == SpeechRecognizer.ERROR_CLIENT) {
                scheduleRestart(150)
                return
            }

            onErrorCb(error)

            val delayMs = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH          -> 100L
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT    -> 200L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY   -> 1200L
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                SpeechRecognizer.ERROR_SERVER            -> 2000L
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT   -> 3000L
                else                                     -> 400L
            }
            scheduleRestart(delayMs)
        }
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,        languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,     3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,            2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,   1500L)
            // NO MINIMUM_LENGTH — breaks restart on many devices
        }
}
