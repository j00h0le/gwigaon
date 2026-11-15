package com.coral.gwigaon.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coral.gwigaon.trip.StoredTrip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** The AI part of the report. Kept apart from the facts so a failure here never hides them. */
sealed interface AiState {
    data object Loading : AiState

    /** One plain summary (ADR 0017 amendment): no per-event lines, no caveat list. */
    data class Ready(val summary: String) : AiState

    data object Failed : AiState

    data object Off : AiState
}

data class ReportUiState(
    val kicker: String,
    val period: String,
    val outcome: ReportOutcome = ReportOutcome.UNKNOWN,
    /** The short 운행 로그 ([ReportLog]). */
    val timeline: List<TimelineItem> = emptyList(),
    val timelineLoaded: Boolean = false,
    val ai: AiState = AiState.Loading,
) {
    val status: String get() = ReportTexts.status(outcome)

    /** Amber status: the trip ended before the destination. */
    val caution: Boolean get() = outcome == ReportOutcome.ENDED_EARLY
}

/** Parked-only journey report for [trip] (SPEC journey-report). [onClose] goes back home. */
class ReportViewModel(
    private val trip: StoredTrip,
    private val repository: ReportRepository,
    private val zone: ZoneId,
    private val onClose: () -> Unit,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scope ?: viewModelScope
    private val _state = MutableStateFlow(
        ReportUiState(ReportTexts.kicker(trip.destination.name), ReportTexts.period(trip.startedAt, trip.endedAt, zone)),
    )
    val state: StateFlow<ReportUiState> = _state.asStateFlow()
    private var summaryJob: Job? = null

    init {
        this.scope.launch {
            val log = repository.events(trip.id).getOrDefault(emptyList())
            _state.update {
                it.copy(outcome = ReportLog.outcome(log), timeline = ReportTexts.shortLog(log, zone), timelineLoaded = true)
            }
        }
        loadSummary()
    }

    /** Only after a failure; a request already running isn't started twice. */
    fun retry() {
        if (_state.value.ai != AiState.Failed) return
        loadSummary()
    }

    fun close() = onClose()

    private fun loadSummary() {
        if (summaryJob?.isActive == true) return
        _state.update { it.copy(ai = AiState.Loading) }
        summaryJob = scope.launch {
            val ai = when (val o = repository.summary(trip.id)) {
                SummaryOutcome.NoCloud -> AiState.Off
                SummaryOutcome.Failed -> AiState.Failed
                is SummaryOutcome.Ready -> AiState.Ready(o.summary.summary)
            }
            _state.update { it.copy(ai = ai) }
        }
    }
}
