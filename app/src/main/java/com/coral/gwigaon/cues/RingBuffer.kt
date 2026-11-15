package com.coral.gwigaon.cues

/**
 * The last [capacity] samples of raw audio, in memory only (SPEC: 30 s, written as a clip only when SOS fires).
 * Nothing here touches storage; [snapshot] hands a copy to whoever saves it.
 */
class PcmRingBuffer(val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val data = ShortArray(capacity)
    private var next = 0
    private var filled = 0

    val size: Int get() = filled

    @Synchronized
    fun write(chunk: ShortArray, count: Int = chunk.size) {
        val n = count.coerceAtMost(chunk.size)
        // Only the newest `capacity` samples of a big chunk can survive.
        val from = (n - capacity).coerceAtLeast(0)
        for (i in from until n) {
            data[next] = chunk[i]
            next = (next + 1) % capacity
        }
        filled = (filled + n - from).coerceAtMost(capacity)
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): ShortArray = tail(filled)

    /** The newest [count] samples (fewer if fewer were written), oldest first. */
    @Synchronized
    fun tail(count: Int): ShortArray {
        val n = count.coerceIn(0, filled)
        val out = ShortArray(n)
        val start = (next - n + capacity) % capacity
        for (i in 0 until n) out[i] = data[(start + i) % capacity]
        return out
    }

    /** Forgets everything (new trip, or the mic turned off: audio from before must never end up in a clip). */
    @Synchronized
    fun clear() {
        data.fill(0)
        next = 0
        filled = 0
    }
}

/** 16-bit mono PCM as a WAV file (RIFF, little-endian), so the clip plays anywhere. */
object Wav {
    private const val HEADER = 44

    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = pcm.size * 2
        val out = ByteArray(HEADER + dataBytes)
        fun ascii(at: Int, s: String) = s.forEachIndexed { i, ch -> out[at + i] = ch.code.toByte() }
        fun int32(at: Int, v: Int) {
            for (i in 0 until 4) out[at + i] = (v shr (8 * i)).toByte()
        }
        fun int16(at: Int, v: Int) {
            out[at] = v.toByte()
            out[at + 1] = (v shr 8).toByte()
        }
        ascii(0, "RIFF")
        int32(4, 36 + dataBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        int32(16, 16) // fmt chunk size
        int16(20, 1) // PCM
        int16(22, 1) // mono
        int32(24, sampleRate)
        int32(28, sampleRate * 2) // byte rate
        int16(32, 2) // block align
        int16(34, 16) // bits per sample
        ascii(36, "data")
        int32(40, dataBytes)
        pcm.forEachIndexed { i, s -> int16(HEADER + 2 * i, s.toInt()) }
        return out
    }
}
