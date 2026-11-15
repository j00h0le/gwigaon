package com.coral.gwigaon.cues

import java.io.File
import kotlin.time.Instant

/**
 * Where the SOS clip goes (SPEC conflict-cues): app-private storage (`filesDir`, excluded from backup), one WAV per
 * SOS, never uploaded. The only code that writes audio to storage, and only [CueMonitor] calls it, on SOS. Clips
 * are deleted after [CueConfig.clipRetention] by [purgeOlderThan] (Q7, ADR 0013).
 */
class ClipStore(private val dir: File, private val sampleRate: Int) {
    fun save(tripId: String, sosSeq: Int, pcm: ShortArray): Result<File> = runCatching {
        require(pcm.isNotEmpty()) { "nothing recorded" }
        require(tripId.all { it.isLetterOrDigit() || it == '-' }) { "unexpected trip id" }
        if (!dir.exists()) check(dir.mkdirs()) { "cannot create the clip folder" }
        val file = File(dir, "sos-$tripId-$sosSeq.wav")
        file.writeBytes(Wav.encode(pcm, sampleRate))
        file
    }

    fun list(): List<File> = dir.listFiles()?.sortedBy { it.name }.orEmpty()

    /** Deletes every clip last written before [cutoff]; other files in the folder are left alone. Returns the count. */
    fun purgeOlderThan(cutoff: Instant): Result<Int> = runCatching {
        val old = list().filter { it.isClip() && it.lastModified() < cutoff.toEpochMilliseconds() }
        old.forEach { check(it.delete()) { "cannot delete ${it.name}" } }
        old.size
    }

    private fun File.isClip() = isFile && name.startsWith("sos-") && name.endsWith(".wav")
}
