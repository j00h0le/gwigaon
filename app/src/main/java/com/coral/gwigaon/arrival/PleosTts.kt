package com.coral.gwigaon.arrival

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * [Speaker] over the Pleos TextToSpeech SDK 3.3.1 in `Mode.ON_DEVICE` with `Locale.KOREA` (SPEC: Android's
 * `TextToSpeech` can't bind the Pleos engine, research/findings/spike-tts-mic-net.md). API read from the AAR:
 * `TextToSpeech(context, mode)`, `initialize(OnInitializeListener)`, then (only once it succeeded; earlier calls throw
 * on the emulator) `addEventListener`, `setLocale`, `speak(text, isFinal)`.
 * Everything runs on the main thread. A sentence asked for before initialization finishes is spoken once it does;
 * if initialization fails it is dropped (the card still shows) and the next [speak] tries again.
 */
class PleosTts(context: Context, private val log: ArrivalLog) : Speaker {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    /** Initializes ahead of time, so the first announcement doesn't wait for the engine. */
    fun start() {
        main.post { ensureEngine() }
    }

    override fun speak(text: String) {
        main.post {
            if (ready) {
                say(text)
            } else {
                pending = text
                ensureEngine()
            }
        }
    }

    private fun ensureEngine() {
        if (tts != null) return
        val engine = runCatching { TextToSpeech(appContext, Mode.ON_DEVICE) }
            .getOrElse { e ->
                log.log("TTS unavailable", e)
                pending = null
                return
            }
        tts = engine
        runCatching { engine.initialize(Init(engine)) }.onFailure { e ->
            log.log("TTS initialize failed", e)
            drop(engine)
        }
    }

    private fun drop(engine: TextToSpeech) {
        runCatching { engine.release() }.onFailure { log.log("TTS release failed", it) }
        if (tts === engine) {
            tts = null
            ready = false
            pending = null
        }
    }

    private fun say(text: String) {
        val engine = tts ?: return
        runCatching { engine.speak(text, true) }
            .onSuccess { log.log("TTS speaking ${text.length} chars", null) }
            .onFailure { log.log("TTS speak failed", it) }
    }

    private inner class Init(private val engine: TextToSpeech) : OnInitializeListener {
        override fun onSuccess() {
            main.post {
                if (tts !== engine) return@post
                // Only after initialize succeeds: before it, the SDK's service manager isn't set up and throws.
                runCatching { engine.addEventListener(Events()) }.onFailure { log.log("TTS addEventListener failed", it) }
                runCatching { engine.setLocale(Locale.KOREA) }.onFailure { log.log("TTS setLocale failed", it) }
                ready = true
                log.log("TTS ready (on-device, ko_KR)", null)
                pending?.let {
                    pending = null
                    say(it)
                }
            }
        }

        override fun onFailure(error: ErrorType) {
            main.post {
                log.log("TTS initialize failed: $error", null)
                drop(engine)
            }
        }
    }

    private inner class Events : EventListener {
        override fun onReady() = log.log("TTS event ready", null)
        override fun onStart() = log.log("TTS event start", null)
        override fun onDone() = log.log("TTS event done", null)
        override fun onStop() = log.log("TTS event stop", null)
        override fun onError(error: ErrorType) = log.log("TTS error: $error", null)

        // Output level while speaking; nothing here needs it.
        override fun onUpdatedRms(rms: Double) = Unit
    }
}
