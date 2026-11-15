package com.coral.gwigaon.arrival

import com.coral.gwigaon.sync.CloudError
import com.coral.gwigaon.sync.FakeAuth
import com.coral.gwigaon.sync.MemorySessionStore
import com.coral.gwigaon.sync.PhraseApi
import com.coral.gwigaon.sync.SessionManager
import com.coral.gwigaon.sync.offline
import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.FakeSettingsStore
import com.coral.gwigaon.trip.PinHasher
import com.coral.gwigaon.trip.SettingsDraft
import com.coral.gwigaon.trip.SettingsRepository
import com.coral.gwigaon.trip.TestClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom
import kotlin.time.Instant

/** ADR 0021: Claude's sentence is asked for once after a save and stored only for the notes it was written for. */
class ArrivalPhrasingTest {
    private val notes = ArrivalInstructions(parking = "지하 2층", entrance = "101동 정문", keyNote = "승객에게 직접")
    private val sentence = "101동 정문으로 들어가 지하 2층에 주차해 주시고, 차 키는 승객에게 직접 전해 주세요. 감사합니다."
    private val repo = SettingsRepository(FakeSettingsStore(), PinHasher(SecureRandom(), iterations = 1), TestClock(), tripActive = { false })

    private class ScriptedPhraseApi(vararg answers: Any) : PhraseApi {
        private val queue = ArrayDeque(answers.toList())
        val calls = mutableListOf<List<String>>()
        var beforeAnswer: suspend () -> Unit = {}

        override suspend fun phraseArrival(accessToken: String, entrance: String, parking: String, key: String): String {
            calls += listOf(entrance, parking, key)
            beforeAnswer()
            return when (val a = queue.removeFirst()) {
                is Throwable -> throw a
                else -> a as String
            }
        }
    }

    private fun phrasing(api: PhraseApi?) = ArrivalPhrasing(
        { repo.settings.value },
        repo::setSpoken,
        api?.let { PhraseCloud(SessionManager(FakeAuth(), MemorySessionStore(), TestClock(Instant.parse("2026-11-15T09:00:00Z"))), it) },
    )

    private suspend fun saveNotes(n: ArrivalInstructions = notes) = repo.save(SettingsDraft(arrival = n)).getOrThrow()

    @Test
    fun storesTheSentenceForTheSavedNotesInTheOrder출입문주차차키() = runTest {
        saveNotes()
        val api = ScriptedPhraseApi(sentence)
        assertEquals(PhraseOutcome.STORED, phrasing(api).phraseIfNeeded())
        assertEquals(listOf(listOf("101동 정문", "지하 2층", "승객에게 직접")), api.calls)
        assertEquals(sentence, repo.settings.value.arrival.spoken)
        assertEquals("목적지 근처입니다. $sentence", ArrivalTexts.spoken(repo.settings.value.arrival))
    }

    @Test
    fun noNotesOrASentenceAlreadyStoredAsksNothing() = runTest {
        val api = ScriptedPhraseApi(sentence, sentence)
        assertEquals(PhraseOutcome.NOT_NEEDED, phrasing(api).phraseIfNeeded())
        saveNotes()
        phrasing(api).phraseIfNeeded()
        assertEquals(PhraseOutcome.NOT_NEEDED, phrasing(api).phraseIfNeeded())
        assertEquals(1, api.calls.size)
    }

    @Test
    fun withoutACloudOrWhenItFailsTheTemplateIsSpoken() = runTest {
        saveNotes()
        assertEquals(PhraseOutcome.NO_CLOUD, phrasing(null).phraseIfNeeded())
        assertEquals(PhraseOutcome.FAILED, phrasing(ScriptedPhraseApi(offline)).phraseIfNeeded())
        assertEquals(PhraseOutcome.FAILED, phrasing(ScriptedPhraseApi(CloudError.Http(502, "model_output_rejected"))).phraseIfNeeded())
        assertEquals("", repo.settings.value.arrival.spoken)
        assertEquals(
            "목적지 근처입니다. 출입문은 101동 정문, 주차는 지하 2층, 차 키는 승객에게 직접 부탁드립니다. 감사합니다.",
            ArrivalTexts.spoken(repo.settings.value.arrival),
        )
    }

    @Test
    fun anExpiredSessionGetsOneRetry() = runTest {
        saveNotes()
        val api = ScriptedPhraseApi(CloudError.Http(401, null), sentence)
        assertEquals(PhraseOutcome.STORED, phrasing(api).phraseIfNeeded())
        assertEquals(2, api.calls.size)
    }

    @Test
    fun notesChangedWhileWaitingKeepTheAnswerOut() = runTest {
        saveNotes()
        val api = ScriptedPhraseApi(sentence)
        api.beforeAnswer = { saveNotes(notes.copy(parking = "지상 1층")) }
        assertEquals(PhraseOutcome.STALE, phrasing(api).phraseIfNeeded())
        assertEquals("", repo.settings.value.arrival.spoken)
    }
}
