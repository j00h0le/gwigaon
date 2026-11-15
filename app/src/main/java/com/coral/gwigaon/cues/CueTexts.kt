package com.coral.gwigaon.cues

import com.coral.gwigaon.trip.CheckInAnswer
import com.coral.gwigaon.trip.CueKind
import com.coral.gwigaon.trip.TripTexts

/** Korean sentences for check-in events and the cue UI. Events say which cue fired, never what was said. */
object CueTexts {
    fun prompted(prompt: CheckInPrompt, config: CueConfig = CueConfig.DEFAULT): String {
        val cue = when (prompt.cue) {
            CueKind.LOUD -> "큰 소리가 ${TripTexts.duration(config.window * config.loudRunForLevel1)} 이상 이어짐"
            CueKind.DISTRESS -> "도움을 청하는 말이 들림"
            CueKind.PROFANITY -> "거친 말이 들림"
        }
        return if (prompt.level >= 2) "반복 감지 · $cue" else cue
    }

    fun answered(answer: CheckInAnswer, config: CueConfig = CueConfig.DEFAULT): String = when (answer) {
        CheckInAnswer.OK -> "기사가 괜찮다고 응답"
        CheckInAnswer.SOS -> "상태 확인에서 SOS 선택"
        CheckInAnswer.NO_RESPONSE -> "${TripTexts.duration(config.checkInTimeout)} 동안 응답 없음"
    }

    const val CARD_OK = "괜찮아요"
    const val INDICATOR = "AI 모니터링 중"
    const val INDICATOR_OFF_HINT = "눌러서 이번 운행 동안 끄기"

    /** The check-in card's chip: which cue asked (owner, D2: the check-in may name it). */
    fun cueChip(cue: CueKind): String = when (cue) {
        CueKind.LOUD -> "큰 목소리"
        CueKind.PROFANITY -> "폭언 감지"
        CueKind.DISTRESS -> "도움 요청 감지"
    }

    /**
     * The card's line to the driver: what was heard at level 2, then where SOS is. [sosBelow] false where no SOS bar
     * sits under the card (the 설정 preview, D3f H12).
     */
    fun cardText(cue: CueKind, level: Int, sosBelow: Boolean = true): String {
        val heard = when {
            level < 2 && cue == CueKind.LOUD -> null
            level < 2 && cue == CueKind.PROFANITY -> "거친 말이 들렸어요."
            level < 2 -> "도움을 청하는 말이 들렸어요."
            cue == CueKind.LOUD -> "큰 목소리가 다시 이어졌어요."
            cue == CueKind.PROFANITY -> "큰 목소리에 이어 폭언이 들렸어요."
            else -> "\"멈춰요\", \"내려줘요\" 같은 말이 들렸어요."
        }
        return listOfNotNull(heard, if (sosBelow) "위험하면 아래 SOS를 길게 누르세요." else "위험하면 SOS를 길게 누르세요.").joinToString(" ")
    }

    /** "18초 후 기록": when the unanswered check-in goes on the 보호자's timeline. */
    fun countdown(secondsLeft: Int): String = "${secondsLeft}초 후 기록"
}
