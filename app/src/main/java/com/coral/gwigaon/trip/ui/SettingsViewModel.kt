package com.coral.gwigaon.trip.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coral.gwigaon.trip.ActiveTrip
import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.HomeKind
import com.coral.gwigaon.trip.HomeRule
import com.coral.gwigaon.trip.PinHasher
import com.coral.gwigaon.trip.PinResult
import com.coral.gwigaon.trip.PinSetup
import com.coral.gwigaon.trip.Settings
import com.coral.gwigaon.trip.SettingsAccess
import com.coral.gwigaon.trip.SettingsDraft
import com.coral.gwigaon.trip.SettingsFlow
import com.coral.gwigaon.trip.SettingsGate
import com.coral.gwigaon.trip.SettingsRepository
import com.coral.gwigaon.trip.SettingsStep
import com.coral.gwigaon.trip.destinationChoices
import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.DrivingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** First run walks the steps in order; edit shows the summary ([SettingsUiState.step] null) and one step at a time. */
enum class SettingsMode { FIRST_RUN, EDIT }

data class SettingsUiState(
    val mode: SettingsMode = SettingsMode.FIRST_RUN,
    val step: SettingsStep? = SettingsStep.INTRO,
    val saved: Settings = Settings(),
    val draft: SettingsDraft = SettingsDraft(),
    val pinSetup: PinSetup = PinSetup(),
    /** Opened from home's QR 확인하기: only the 비상 연락처 step, and closing it goes straight home. */
    val direct: Boolean = false,
    val link: LinkUiState = LinkUiState(),
    val choices: List<Destination> = emptyList(),
    val access: SettingsAccess = SettingsAccess.OPEN,
    val gateDigits: String = "",
    val gateResult: PinResult? = null,
    val gateSecondsLeft: Long = 0,
    val saving: Boolean = false,
    val saveError: String? = null,
) {
    val canAdvance: Boolean get() = step?.let { SettingsFlow.canAdvance(it, draft, saved, mode == SettingsMode.EDIT) } ?: false
    val gateLocked: Boolean get() = gateSecondsLeft > 0
}

/**
 * 설정 (`design/d2/proto/settings.html`, ADR 0015) over [SettingsRepository]. First run saves after each step, so
 * stopping halfway leaves a partial 설정 that home offers to continue. Who may open it is [SettingsGate]: parked, no
 * trip, and with the PIN on, only after the PIN (same lockout as ending a trip).
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    drivingState: StateFlow<DrivingState>,
    active: StateFlow<ActiveTrip?>,
    private val recents: suspend () -> List<Destination>,
    private val clock: Clock = Clock.System,
    /** After every successful save: asks for the spoken arrival sentence in the background (ADR 0021). */
    private val afterSave: () -> Unit = {},
) : ViewModel() {
    private val local = MutableStateFlow(SettingsUiState())
    private val unlocked = MutableStateFlow(false)
    private var lockoutTicker: Job? = null

    val state: StateFlow<SettingsUiState> =
        combine(local, repository.settings, drivingState, active, unlocked) { s, saved, driving, trip, open ->
            s.copy(saved = saved, access = SettingsGate.access(driving, trip != null, saved.pinOn, open))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    init {
        // A trip that starts locks 설정 again for whoever opens it next.
        viewModelScope.launch { active.collect { if (it != null) unlocked.value = false } }
    }

    /** Opens 설정 from home: the first run where it stopped, or the summary once it is complete. */
    fun open() {
        val saved = repository.settings.value
        val ready = HomeRule.kind(saved) == HomeKind.READY
        unlocked.value = false
        local.value = SettingsUiState(
            mode = if (ready) SettingsMode.EDIT else SettingsMode.FIRST_RUN,
            step = if (ready) null else SettingsFlow.firstRunEntry(saved),
            draft = SettingsDraft.from(saved),
            choices = local.value.choices,
        )
        loadChoices(saved.home)
    }

    private fun loadChoices(current: Destination?) {
        viewModelScope.launch {
            val list = runCatching { recents() }.onFailure { Log.w(TAG, "recents unavailable", it) }.getOrDefault(emptyList())
            local.update { it.copy(choices = destinationChoices(list, current = current)) }
        }
    }

    /** Home's QR 확인하기: the 비상 연락처 step alone (behind the same gate as 설정). */
    fun openLink() {
        val saved = repository.settings.value
        unlocked.value = false
        local.value = SettingsUiState(mode = SettingsMode.EDIT, step = SettingsStep.CONTACTS, direct = true, draft = SettingsDraft.from(saved))
    }

    /** 다음 (first run) or 저장 (editing one step): saves what the step changed, then moves on. */
    fun next(onDone: () -> Unit) {
        val s = state.value
        val step = s.step ?: return
        if (s.saving || !s.canAdvance) return
        if (step == SettingsStep.DONE) return onDone()
        if (step == SettingsStep.INTRO) return local.update { it.copy(step = SettingsFlow.next(step)) }
        local.update { it.copy(saving = true, saveError = null) }
        // Passing the 비상 연락처 step is what completes it (ADR 0019): nothing can check the QR was sent.
        val draft = if (step == SettingsStep.CONTACTS) s.draft.copy(linkShown = true) else s.draft
        viewModelScope.launch {
            repository.save(draft)
                .onSuccess { saved ->
                    afterSave()
                    if (s.direct) {
                        local.update { it.copy(saving = false) }
                        return@onSuccess onDone()
                    }
                    unlocked.value = true // whoever just saved may keep editing
                    local.update {
                        it.copy(
                            step = if (it.mode == SettingsMode.EDIT) null else SettingsFlow.next(step),
                            draft = SettingsDraft.from(saved),
                            pinSetup = PinSetup(),
                        )
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "settings not saved", e)
                    local.update { it.copy(saveError = "저장하지 못했어요. 다시 눌러 주세요.") }
                }
            local.update { it.copy(saving = false) }
        }
    }

    /** Back: one step back, or out of the step being edited; true when 설정 should close. */
    fun back(): Boolean {
        val s = local.value
        val step = s.step
        return when {
            s.direct -> true
            s.mode == SettingsMode.EDIT && step != null -> {
                local.update { it.copy(step = null, draft = SettingsDraft.from(state.value.saved), pinSetup = PinSetup()) }
                false
            }
            step == null -> true
            else -> SettingsFlow.back(step)?.let { prev ->
                local.update { it.copy(step = prev, pinSetup = PinSetup(), saveError = null) }
                false
            } ?: true
        }
    }

    /** Edit: 변경 on a summary row opens that step. */
    fun edit(step: SettingsStep) = local.update {
        it.copy(mode = SettingsMode.EDIT, step = step, pinSetup = PinSetup(), saveError = null)
    }

    fun setHome(d: Destination) = draft { it.copy(home = d) }

    fun setArrival(change: (ArrivalInstructions) -> ArrivalInstructions) = draft { it.copy(arrival = change(it.arrival)) }

    fun setCues(on: Boolean) = draft { it.copy(cues = on) }

    fun setPinOn(on: Boolean) = local.update { s ->
        val setup = PinSetup()
        s.copy(pinSetup = setup, draft = setup.applyTo(s.draft.copy(pinOn = on)))
    }

    fun pinDigit(d: Char) = pin { it.digit(d) }

    fun pinDelete() = pin { it.delete() }

    private fun pin(change: (PinSetup) -> PinSetup) = local.update { s ->
        if (!s.draft.pinOn) return@update s
        val setup = change(s.pinSetup)
        s.copy(pinSetup = setup, draft = setup.applyTo(s.draft))
    }

    fun gateDigit(d: Char) {
        // Digits come from [local], not the combined [state], which lags a tap behind on fast input.
        val s = local.value
        if (s.gateLocked || state.value.access != SettingsAccess.NEEDS_PIN || s.gateDigits.length >= PinHasher.PIN_LENGTH) return
        val digits = s.gateDigits + d
        local.update { it.copy(gateDigits = digits) }
        if (digits.length < PinHasher.PIN_LENGTH) return
        viewModelScope.launch {
            repository.unlock(digits)
                .onSuccess { result ->
                    local.update { it.copy(gateDigits = "", gateResult = result.takeIf { r -> r !is PinResult.Accepted }) }
                    when (result) {
                        is PinResult.Accepted -> unlocked.value = true
                        is PinResult.LockedOut -> countDown(result)
                        is PinResult.Wrong -> Unit
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "settings PIN not checked", e)
                    local.update { it.copy(gateDigits = "") }
                }
        }
    }

    fun gateDelete() = local.update { it.copy(gateDigits = it.gateDigits.dropLast(1)) }

    private fun countDown(locked: PinResult.LockedOut) {
        lockoutTicker?.cancel()
        lockoutTicker = viewModelScope.launch {
            while (true) {
                val left = (locked.until - clock.now()).inWholeSeconds
                local.update { it.copy(gateSecondsLeft = left.coerceAtLeast(0)) }
                if (left <= 0) break
                delay(1.seconds)
            }
            local.update { it.copy(gateResult = null) }
        }
    }

    private fun draft(change: (SettingsDraft) -> SettingsDraft) = local.update { it.copy(draft = change(it.draft)) }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
