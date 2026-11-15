package com.coral.gwigaon.trip

/**
 * The parked, no-trip screens (`design/d2/proto/home.html`, `start.html`, `settings.html`; ADR 0015): what the home
 * screen offers, what the driver's start screen tells them, and how 설정's first run moves. Pure, so every state the
 * prototypes show is a unit-tested value; the screens only draw it.
 */

/** Which home the car owner sees: never set up, set up halfway, or ready for the driver. */
enum class HomeKind { FIRST, PARTIAL, READY }

object HomeRule {
    fun kind(settings: Settings): HomeKind = when {
        settings.isComplete -> HomeKind.READY
        missing(settings).size == REQUIRED.size -> HomeKind.FIRST
        else -> HomeKind.PARTIAL
    }

    /** The required parts still missing, in first-run order (주소, then the 비상 연락처 QR step). */
    fun missing(settings: Settings): List<SettingsField> = buildList {
        if (settings.home == null) add(SettingsField.HOME)
        if (!settings.contactsDone) add(SettingsField.CONTACTS)
    }

    private val REQUIRED = listOf(SettingsField.HOME, SettingsField.CONTACTS)
}

/** One fact on the driver's start screen ("귀가온은 이렇게 지켜줘요"). */
enum class BriefRow { RECORD, CUES, END_AUTO, END_PIN, SOS }

object StartBriefing {
    /** What this trip will do, by 설정: always recorded, cues if on, how it ends, SOS last (closest to the button). */
    fun rows(settings: Settings): List<BriefRow> = buildList {
        add(BriefRow.RECORD)
        if (settings.cues) add(BriefRow.CUES)
        add(if (settings.pinOn) BriefRow.END_PIN else BriefRow.END_AUTO)
        add(BriefRow.SOS)
    }
}

/** 설정's first run: [INTRO], five numbered steps, [DONE]. Editing opens one numbered step at a time. */
enum class SettingsStep(val number: Int?) {
    INTRO(null),
    HOME(1),
    CONTACTS(2),
    ARRIVAL(3),
    CUES(4),
    PIN(5),
    DONE(null),
    ;

    companion object {
        const val NUMBERED = 5
    }
}

object SettingsFlow {
    fun next(step: SettingsStep): SettingsStep = SettingsStep.entries[(step.ordinal + 1).coerceAtMost(SettingsStep.DONE.ordinal)]

    /** The step before, or null when back leaves 설정 (from [SettingsStep.INTRO] or [SettingsStep.DONE]). */
    fun back(step: SettingsStep): SettingsStep? = when (step) {
        SettingsStep.INTRO, SettingsStep.DONE -> null
        else -> SettingsStep.entries[step.ordinal - 1]
    }

    /** Where 설정 opens from home: never saved → the intro; halfway → the first missing step ("설정 이어서 하기"). */
    fun firstRunEntry(settings: Settings): SettingsStep {
        if (HomeRule.kind(settings) == HomeKind.FIRST) return SettingsStep.INTRO
        return when (HomeRule.missing(settings).firstOrNull()) {
            SettingsField.HOME -> SettingsStep.HOME
            SettingsField.CONTACTS -> SettingsStep.CONTACTS
            else -> SettingsStep.DONE
        }
    }

    /**
     * Whether the step's 다음 / 저장 is enabled for [draft] (the inline rules of [SettingsDraft.errors]). The 비상 연락처
     * step only shows the account's QR (ADR 0019), so it always moves on; passing it marks it done.
     */
    fun canAdvance(step: SettingsStep, draft: SettingsDraft, existing: Settings, editing: Boolean = false): Boolean {
        val errors = draft.errors(existing).keys.map { it.field }.toSet()
        return when (step) {
            SettingsStep.HOME -> draft.home != null
            SettingsStep.CONTACTS -> true
            SettingsStep.ARRIVAL -> SettingsField.ARRIVAL !in errors
            SettingsStep.PIN -> SettingsField.PIN !in errors
            SettingsStep.INTRO, SettingsStep.CUES, SettingsStep.DONE -> true
        }
    }
}

/**
 * Setting a new PIN on the keypad (settings.html "PIN 입력" / "PIN 불일치"): four digits, then the same four again.
 * A different second entry starts over with [mismatch] set.
 */
data class PinSetup(val first: String = "", val confirm: String = "", val mismatch: Boolean = false) {
    val confirming: Boolean get() = first.length == PinHasher.PIN_LENGTH
    val complete: Boolean get() = confirming && confirm == first

    /** Dots to fill: the entry in progress (all four once [complete]). */
    val filled: Int get() = if (confirming) confirm.length else first.length

    fun digit(d: Char): PinSetup = when {
        d !in '0'..'9' || complete -> this
        !confirming -> copy(first = first + d, mismatch = false)
        else -> {
            val next = confirm + d
            if (next.length == PinHasher.PIN_LENGTH && next != first) PinSetup(mismatch = true) else copy(confirm = next)
        }
    }

    /** One digit back; from an empty confirmation it goes back into the first entry. */
    fun delete(): PinSetup = when {
        confirm.isNotEmpty() -> copy(confirm = confirm.dropLast(1))
        else -> copy(first = first.dropLast(1), mismatch = false)
    }

    /** Into the draft: [SettingsDraft.errors] then refuses an unfinished or mismatched PIN. */
    fun applyTo(draft: SettingsDraft): SettingsDraft = draft.copy(pin = first, pinConfirm = confirm)
}

/** The 설정 summary rows and the home door's one-line summary (settings.html 완료 / 편집, home.html). */
object SettingsTexts {
    /** Home header link to the last trip's report (ADR 0017 amendment). */
    const val HISTORY = "운행 기록"
    fun home(settings: Settings): String? = settings.home?.name

    /** The 설정 row and step for the account QR (ADR 0019). */
    const val CONTACTS = "모니터링 QR"

    /** The step's name in the 설정 header and intro (owner, 2026-11-15). */
    const val CONTACTS_STEP = "모니터링 QR 공유"

    fun arrival(arrival: ArrivalInstructions): String =
        listOf(arrival.parking, arrival.entrance, arrival.keyNote).map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" · ").ifEmpty { "없음 · 건너뛰어도 돼요" }

    fun cues(on: Boolean): String = if (on) "켜짐 · 기사님 화면에도 표시돼요" else "꺼짐"

    fun pin(on: Boolean): String = if (on) "켜짐 · 설정을 열 때도 PIN이 필요해요" else "꺼짐 · 내가 내리면 운행이 끝나요"

    /** Home's 설정 door when ready: "양재역 · PIN 꺼짐". */
    fun doorSummary(settings: Settings): String =
        "${settings.home?.name ?: "집"} · PIN ${if (settings.pinOn) "켜짐" else "꺼짐"}"

    /** What the gate says after a wrong PIN or during a lockout. */
    fun gate(result: PinResult?, secondsLeft: Long): String = when (result) {
        is PinResult.Wrong -> "PIN이 맞지 않아요 · ${result.triesLeft}번 남았어요"
        is PinResult.LockedOut -> "${secondsLeft.coerceAtLeast(1)}초 뒤에 다시 입력해 주세요"
        else -> "5번 틀리면 30초 동안 잠겨요"
    }
}

/** Home and start screen copy (D3f batch 2, owner's wording). */
object HomeTexts {
    const val READY_LEDE = "안전한 대리운전을 위한 AI, 귀가온"
    const val FIRST_TITLE = "환영합니다"
    const val FIRST_LEDE = "귀가온은 대리운전 중 안전한 귀가를 돕는 AI 지킴이입니다"
    const val FIRST_DOOR = "주소, 비상 연락처 등을 설정해 주세요"
    const val PARTIAL_TITLE = "설정을 마저 해 주세요"
    const val START_LOCKED = "설정이 아직 안됐어요"

    /** Gear start (ADR 0015), on home and under the half-panel 운행 시작. */
    const val GEAR_HINT = "기어를 D로 바꾸면 자동으로 시작해요"

    const val GO_ALONE = "바로 출발할게요"
    const val NOT_SET_UP_TITLE = "아직 귀가온 설정이 안 돼 있어요"
    const val NOT_SET_UP_LEDE = "차 주인이 아직 설정을 마치지 않았어요."
    const val OPEN_SETTINGS = "설정 열기"
    const val SPLIT_HINT = "오른쪽 끝의 세로 막대를 왼쪽으로 끌어 주세요.\n화면이 나뉘면 이 안내는 사라지고,\n운행 시작을 누르면 내비게이션이 옆에 열려요."
}
