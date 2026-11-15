package com.coral.gwigaon.cues

import com.coral.gwigaon.trip.CueKind

/**
 * Stage 2's word list (SPEC conflict-cues): distress phrases and profanity, matched against the speech engine's
 * partial and final results with spaces and punctuation removed. One utterance gives at most one hit: the first word
 * found, whether in a partial or the final. A final whose text was rewritten (the spike saw `개새끼야` become
 * `제습기가`) doesn't undo a partial's hit, and a word first found in the final still counts. A final closes the
 * utterance. Distress is checked before profanity.
 *
 * Stems are chosen so ordinary trip talk doesn't match: `내려줘` but not `내려주세요` ("please let me off here"),
 * `미친놈` but not `미친`, `하지마` but not `하지만`.
 */
class WordMatcher(
    private val distress: List<String> = DISTRESS,
    private val profanity: List<String> = PROFANITY,
) {
    private var hitThisUtterance = false

    /** One result from the engine. Returns the cue to report, if this utterance has none yet. */
    fun onResult(text: String, completed: Boolean): CueKind? {
        val hit = if (hitThisUtterance) null else match(text)
        if (hit != null) hitThisUtterance = true
        if (completed) hitThisUtterance = false
        return hit
    }

    /** The engine ended or failed mid-utterance, or listening stopped: the next result starts a new utterance. */
    fun reset() {
        hitThisUtterance = false
    }

    private fun match(text: String): CueKind? {
        val flat = normalise(text)
        if (flat.isEmpty()) return null
        return when {
            distress.any(flat::contains) -> CueKind.DISTRESS
            profanity.any(flat::contains) -> CueKind.PROFANITY
            else -> null
        }
    }

    companion object {
        val DISTRESS = listOf("살려주", "살려줘", "하지마", "하지말", "멈춰", "내려줘", "경찰", "도와주세요", "도와줘")
        val PROFANITY = listOf(
            "개새끼", "개새기", "미친놈", "미친년", "미친새끼", "꺼져", "씨발", "시발", "씨팔", "병신", "좆같",
        )

        /** Letters and digits only: `하지 마!` and `하지마` are the same. */
        fun normalise(text: String): String = text.filter(Char::isLetterOrDigit)
    }
}
