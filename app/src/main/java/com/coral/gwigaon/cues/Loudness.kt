package com.coral.gwigaon.cues

import kotlin.math.log10
import kotlin.math.sqrt

/** Loudness of 16-bit PCM in dBFS (0 = full scale). Silence is clamped to [SILENCE_DBFS]. */
object Loudness {
    const val SILENCE_DBFS = -120.0
    private const val FULL_SCALE = 32768.0

    fun dbfs(sumOfSquares: Double, count: Int): Double {
        if (count <= 0 || sumOfSquares <= 0.0) return SILENCE_DBFS
        val rms = sqrt(sumOfSquares / count)
        return (20 * log10(rms / FULL_SCALE)).coerceAtLeast(SILENCE_DBFS)
    }

    fun dbfs(samples: ShortArray, count: Int = samples.size): Double = dbfs(sumOfSquares(samples, count), count)

    fun sumOfSquares(samples: ShortArray, count: Int = samples.size): Double {
        var sum = 0.0
        for (i in 0 until count.coerceAtMost(samples.size)) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sum
    }
}

/**
 * Cuts the recorder's chunks (any size) into fixed windows of [samplesPerWindow] and returns each finished window's
 * dBFS. A chunk can finish more than one window, or none.
 */
class LoudnessMeter(private val samplesPerWindow: Int) {
    init {
        require(samplesPerWindow > 0) { "window must hold at least one sample" }
    }

    private var sum = 0.0
    private var count = 0

    fun add(chunk: ShortArray, size: Int = chunk.size): List<Double> {
        val done = mutableListOf<Double>()
        for (i in 0 until size.coerceAtMost(chunk.size)) {
            val v = chunk[i].toDouble()
            sum += v * v
            if (++count == samplesPerWindow) {
                done += Loudness.dbfs(sum, count)
                sum = 0.0
                count = 0
            }
        }
        return done
    }

    fun reset() {
        sum = 0.0
        count = 0
    }
}

/**
 * The trip's own baseline (SPEC: median of the last 2 min) and the loud verdict: a window is loud when it is at
 * least [CueConfig.loudDeltaDb] above the median of the windows before it and above [CueConfig.loudFloorDbfs].
 * Every window joins the baseline, loud ones too; the median keeps a 10 s shout from moving it much.
 */
class LoudnessBaseline(private val config: CueConfig) {
    private val history = ArrayDeque<Double>()

    /** The current median, or null until [CueConfig.minBaselineWindows] windows have been seen. */
    fun median(): Double? {
        if (history.size < config.minBaselineWindows) return null
        val sorted = history.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Judges [dbfs] against the baseline so far, then adds it. */
    fun onWindow(dbfs: Double): Boolean {
        val base = median()
        val loud = base != null && dbfs >= config.loudFloorDbfs && dbfs - base >= config.loudDeltaDb
        history.addLast(dbfs)
        while (history.size > config.baselineWindows) history.removeFirst()
        return loud
    }

    fun reset() = history.clear()
}
