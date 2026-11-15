package com.coral.gwigaon.arrival

import com.coral.gwigaon.sync.CloudError
import com.coral.gwigaon.sync.PhraseApi
import com.coral.gwigaon.sync.SessionManager
import com.coral.gwigaon.sync.SyncLog
import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.Settings

/** The cloud side of [ArrivalPhrasing]: the anonymous session and the function. */
class PhraseCloud(val sessions: SessionManager, val api: PhraseApi)

/** What came of asking for the spoken sentence. */
enum class PhraseOutcome { STORED, NOT_NEEDED, NO_CLOUD, FAILED, STALE }

/**
 * ADR 0021: after 설정 is saved, asks `phrase-arrival` once for a polite spoken sentence for the arrival notes and
 * stores it with 설정. Runs parked, right after the save; never while driving. Any failure leaves the car's template
 * ([ArrivalTexts.template]) in place, so nothing waits on it.
 */
class ArrivalPhrasing(
    private val settings: () -> Settings,
    private val store: suspend (forNotes: ArrivalInstructions, sentence: String) -> Result<Boolean>,
    private val cloud: PhraseCloud?,
    private val log: SyncLog = SyncLog { _, _ -> },
) {
    suspend fun phraseIfNeeded(): PhraseOutcome {
        val notes = settings().arrival
        if (notes.isEmpty || notes.spoken.isNotBlank()) return PhraseOutcome.NOT_NEEDED
        val cloud = cloud ?: return PhraseOutcome.NO_CLOUD
        repeat(2) { attempt ->
            try {
                val sentence = cloud.api.phraseArrival(cloud.sessions.accessToken(), notes.entrance, notes.parking, notes.keyNote)
                val stored = store(notes, sentence).getOrElse {
                    log.log("spoken sentence not stored", it)
                    return PhraseOutcome.FAILED
                }
                return if (stored) PhraseOutcome.STORED else PhraseOutcome.STALE
            } catch (e: CloudError.Http) {
                // An expired session gets one more try with a fresh one; anything else keeps the template.
                if (e.status == UNAUTHORIZED && attempt == 0) {
                    cloud.sessions.invalidate()
                } else {
                    log.log("phrase-arrival refused", e)
                    return PhraseOutcome.FAILED
                }
            } catch (e: CloudError.Network) {
                log.log("phrase-arrival: no network", e)
                return PhraseOutcome.FAILED
            }
        }
        return PhraseOutcome.FAILED
    }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
