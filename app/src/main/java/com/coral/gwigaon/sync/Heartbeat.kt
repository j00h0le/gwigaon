package com.coral.gwigaon.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Sends the running trip's `last_seen_at` (with its status) every [interval] (SPEC: 30 s), so the contact page
 * can tell "monitoring stopped" (no heartbeat for 90 s) from a quiet trip. The time sent is the car's own
 * last-seen time, so a stalled app is not reported as alive. Stops when no trip runs.
 */
class Heartbeat(private val queue: UploadQueue, private val interval: Duration) {
    fun run(scope: CoroutineScope, activeTripId: Flow<String?>): Job = scope.launch {
        activeTripId.distinctUntilChanged().collectLatest { id ->
            if (id == null) return@collectLatest
            while (true) {
                queue.pushTrip(id)
                delay(interval)
            }
        }
    }
}
