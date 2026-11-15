package com.coral.gwigaon.cues

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * [SpeechEngine] over the Pleos SpeechToText SDK 3.3.1, `Mode.ON_DEVICE`, `Locale.KOREA` (ADR 0012). API as used and
 * measured in the 2026-11-12 spike (research/findings/spike-stt-stage2.md): `SpeechToText(context, mode)`,
 * `initialize(OnInitializeListener)`, then, only once it succeeded, `addListener(ResultListener)` and `setLocale`;
 * audio goes in through `sendAudio(ByteArray)` (16-bit little-endian PCM) and `completeAudioSend()`. `request()` is
 * never called, so the SDK never opens the mic.
 *
 * Set-up runs on the main thread; audio may be sent from any thread and is dropped until the engine is ready. Any
 * failure is logged (the SDK's error type only, never text) and leaves the engine down, so stage 1 carries on and the
 * next [warmUp] tries again.
 */
class PleosStt(context: Context, private val log: CueLog) : SpeechEngine {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var stt: SpeechToText? = null
    private var listener: SpeechListener? = null

    @Volatile private var ready: SpeechToText? = null
    @Volatile private var sent = false

    override fun listen(listener: SpeechListener) {
        this.listener = listener
    }

    override fun warmUp() {
        main.post { ensureEngine() }
    }

    override fun send(pcm: ShortArray) {
        val engine = ready ?: return
        if (pcm.isEmpty()) return
        runCatching { engine.sendAudio(littleEndian(pcm)) }
            .onSuccess { sent = true }
            .onFailure { fail(engine, "STT sendAudio failed", it) }
    }

    override fun complete() {
        val engine = ready ?: return
        if (!sent) return
        sent = false
        runCatching { engine.completeAudioSend() }.onFailure { fail(engine, "STT completeAudioSend failed", it) }
    }

    private fun ensureEngine() {
        if (stt != null) return
        val engine = runCatching { SpeechToText(appContext, Mode.ON_DEVICE) }
            .getOrElse { return log.log("STT unavailable; stage 2 off", it) }
        stt = engine
        runCatching { engine.initialize(Init(engine)) }.onFailure { fail(engine, "STT initialize failed", it) }
    }

    private fun fail(engine: SpeechToText, message: String, error: Throwable?) {
        log.log("$message; stage 2 off until the next warm-up", error)
        main.post {
            if (stt !== engine) return@post
            ready = null
            stt = null
            runCatching { engine.release() }.onFailure { log.log("STT release failed", it) }
        }
    }

    private inner class Init(private val engine: SpeechToText) : OnInitializeListener {
        override fun onSuccess() {
            main.post {
                if (stt !== engine) return@post
                runCatching {
                    engine.addListener(Results())
                    engine.setLocale(Locale.KOREA)
                }.onFailure { return@post fail(engine, "STT set-up failed", it) }
                // The spike waited for setLocale to reach the service, then the first session took ~2.5 s to warm
                // up: run that session now on silence, so the first real sample isn't late.
                main.postDelayed({
                    if (stt !== engine) return@postDelayed
                    runCatching {
                        engine.sendAudio(ByteArray(WARM_UP_BYTES))
                        engine.completeAudioSend()
                    }.onSuccess {
                        ready = engine
                        log.log("STT ready (on-device, ko-KR)", null)
                    }.onFailure { fail(engine, "STT warm-up failed", it) }
                }, LOCALE_SETTLE_MS)
            }
        }

        override fun onFailure(error: ErrorType) = fail(engine, "STT initialize failed ($error)", null)
    }

    private inner class Results : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) {
            listener?.onText(stt, completed)
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        // Measured on the emulator: the session's end comes before its final result, so it closes nothing; the final
        // does.
        override fun onEndedRecognition() = Unit
        override fun onError(error: ErrorType) {
            log.log("STT error $error", null)
            listener?.onAborted()
        }
    }

    private companion object {
        const val LOCALE_SETTLE_MS = 500L
        const val WARM_UP_BYTES = 32_000 // 1 s of silence at 16 kHz, 16-bit

        fun littleEndian(pcm: ShortArray): ByteArray {
            val out = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                val v = pcm[i].toInt()
                out[2 * i] = v.toByte()
                out[2 * i + 1] = (v shr 8).toByte()
            }
            return out
        }
    }
}
