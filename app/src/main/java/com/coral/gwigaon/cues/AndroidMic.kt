package com.coral.gwigaon.cues

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Stage 1's recorder (ADR 0012): our own `AudioRecord`, 16 kHz 16-bit mono from the MIC source, read on an IO
 * thread in 100 ms chunks. Opened when collected, stopped and released when the collector stops. Thin on purpose:
 * everything it hands out is judged by [CueMonitor].
 */
class AndroidMic(private val context: Context, private val sampleRate: Int) : MicSource {
    override fun permitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun chunks(): Flow<ShortArray> = flow {
        // Checked here, next to the recorder, so a revoked grant fails the flow instead of crashing.
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO not granted")
        }
        val chunk = sampleRate / 10
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "recorder format not supported ($min)" }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, chunk * 2) * 4,
        )
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "recorder did not initialise" }
            recorder.startRecording()
            val buf = ShortArray(chunk)
            while (currentCoroutineContext().isActive) {
                val n = recorder.read(buf, 0, buf.size)
                check(n >= 0) { "recorder read failed ($n)" }
                if (n > 0) emit(buf.copyOf(n))
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)
}
