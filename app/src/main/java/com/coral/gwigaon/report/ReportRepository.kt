package com.coral.gwigaon.report

import com.coral.gwigaon.sync.CloudError
import com.coral.gwigaon.sync.SessionManager
import com.coral.gwigaon.sync.SummaryApi
import com.coral.gwigaon.sync.SyncLog
import com.coral.gwigaon.sync.TripSummary
import com.coral.gwigaon.trip.EventRecord
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What came of asking for the AI part of a report. */
sealed interface SummaryOutcome {
    data class Ready(val summary: TripSummary) : SummaryOutcome

    /** Couldn't get it now; the passenger can retry. The timeline stands without it. */
    data object Failed : SummaryOutcome

    /** This build has no cloud, so there is never an AI part. */
    data object NoCloud : SummaryOutcome
}

/**
 * [attempts] tries [wait] apart cover the upload catching up: right after the trip ends, the function answers
 * 403 (trip row not there yet) or 409 (TRIP_ENDED not uploaded yet) for a few seconds.
 */
data class ReportConfig(val attempts: Int = 6, val wait: Duration = 3.seconds)

/** The cloud side of a report: the anonymous session that owns the trip, and the function. */
class SummaryCloud(val sessions: SessionManager, val api: SummaryApi)

/**
 * The journey report's data (SPEC journey-report): the measured timeline from the local log, never from the
 * cloud, and the AI summary from the `summarize-trip` Edge Function. The summary never blocks the facts.
 */
class ReportRepository(
    private val localEvents: suspend (tripId: String) -> Result<List<EventRecord>>,
    private val cloud: SummaryCloud?,
    private val config: ReportConfig = ReportConfig(),
    private val log: SyncLog = SyncLog { _, _ -> },
) {
    suspend fun events(tripId: String): Result<List<EventRecord>> = localEvents(tripId)

    suspend fun summary(tripId: String): SummaryOutcome {
        val cloud = cloud ?: return SummaryOutcome.NoCloud
        repeat(config.attempts) { attempt ->
            if (attempt > 0) delay(config.wait)
            try {
                return SummaryOutcome.Ready(cloud.api.summarizeTrip(cloud.sessions.accessToken(), tripId))
            } catch (e: CloudError.Network) {
                log.log("summary: no network (try ${attempt + 1})", e)
            } catch (e: CloudError.Http) {
                when (e.status) {
                    UNAUTHORIZED -> cloud.sessions.invalidate()
                    // The upload hasn't caught up yet: wait for it.
                    FORBIDDEN, CONFLICT -> Unit
                    else -> {
                        // Model errors and anything else won't change by waiting a few seconds: hand it to the passenger.
                        log.log("summary failed", e)
                        return SummaryOutcome.Failed
                    }
                }
                log.log("summary not ready (try ${attempt + 1})", e)
            }
        }
        return SummaryOutcome.Failed
    }

    private companion object {
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val CONFLICT = 409
    }
}
