package com.coral.gwigaon.cues

import com.coral.gwigaon.trip.CheckInAnswer
import com.coral.gwigaon.trip.CueKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Check-in copy speaks to the driver (D2 handoff, D3b). */
class CueCardTextsTest {
    @Test
    fun theChipNamesTheCue() {
        assertEquals("큰 목소리", CueTexts.cueChip(CueKind.LOUD))
        assertEquals("폭언 감지", CueTexts.cueChip(CueKind.PROFANITY))
        assertEquals("도움 요청 감지", CueTexts.cueChip(CueKind.DISTRESS))
    }

    @Test
    fun levelOneOnlyPointsAtSosForLoudness() {
        assertEquals("위험하면 아래 SOS를 길게 누르세요.", CueTexts.cardText(CueKind.LOUD, 1))
        assertEquals("거친 말이 들렸어요. 위험하면 아래 SOS를 길게 누르세요.", CueTexts.cardText(CueKind.PROFANITY, 1))
        assertEquals("도움을 청하는 말이 들렸어요. 위험하면 아래 SOS를 길게 누르세요.", CueTexts.cardText(CueKind.DISTRESS, 1))
    }

    @Test
    fun levelTwoSaysWhatFollowed() {
        assertEquals("큰 목소리가 다시 이어졌어요. 위험하면 아래 SOS를 길게 누르세요.", CueTexts.cardText(CueKind.LOUD, 2))
        assertEquals("큰 목소리에 이어 폭언이 들렸어요. 위험하면 아래 SOS를 길게 누르세요.", CueTexts.cardText(CueKind.PROFANITY, 2))
        assertEquals(
            "\"멈춰요\", \"내려줘요\" 같은 말이 들렸어요. 위험하면 아래 SOS를 길게 누르세요.",
            CueTexts.cardText(CueKind.DISTRESS, 2),
        )
    }

    @Test
    fun cardTextWithoutSosBarDropsWhere() {
        // The 설정 preview has no SOS bar under the card (D3f H12).
        assertEquals("위험하면 SOS를 길게 누르세요.", CueTexts.cardText(CueKind.LOUD, 1, sosBelow = false))
        assertEquals("거친 말이 들렸어요. 위험하면 SOS를 길게 누르세요.", CueTexts.cardText(CueKind.PROFANITY, 1, sosBelow = false))
    }

    @Test
    fun countdownAndTheDriversAnswer() {
        assertEquals("18초 후 기록", CueTexts.countdown(18))
        assertEquals("기사가 괜찮다고 응답", CueTexts.answered(CheckInAnswer.OK))
    }
}
