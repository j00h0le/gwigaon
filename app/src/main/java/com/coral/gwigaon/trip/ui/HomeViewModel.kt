package com.coral.gwigaon.trip.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coral.gwigaon.trip.ActiveTrip
import com.coral.gwigaon.trip.Settings
import com.coral.gwigaon.trip.StoredTrip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The parked, no-trip screens: home (two doors), the driver's start screen, and 설정. */
enum class HomeRoute { HOME, START, SETTINGS }

data class HomeUiState(
    val route: HomeRoute = HomeRoute.HOME,
    val settings: Settings = Settings(),
    val starting: Boolean = false,
    val startError: String? = null,
    /** A past trip exists, so home shows 운행 기록 (ADR 0017 amendment). */
    val hasHistory: Boolean = false,
)

/**
 * Which no-trip screen shows, and 운행 시작 from the start screen (ADR 0015). Gear start is allowed only on home and the
 * start screen ([gearStartAllowed], read by the container's gear watcher), never while 설정 is open. A trip that
 * starts (by button, gear or anything else) brings the next no-trip visit back to home.
 */
class HomeViewModel(
    settings: StateFlow<Settings>,
    active: StateFlow<ActiveTrip?>,
    /** [com.coral.gwigaon.trip.TripStarter.start] with the button source. */
    private val start: suspend () -> Result<ActiveTrip>,
    private val gearStartAllowed: MutableStateFlow<Boolean> = MutableStateFlow(true),
    /** The newest ended trip ([com.coral.gwigaon.trip.TripSessionRepository.latestEnded]); null hides 운행 기록. */
    latestEnded: StateFlow<StoredTrip?> = MutableStateFlow(null),
    /** Opens that trip's report ([com.coral.gwigaon.trip.TripSessionRepository.openLatestReport]). */
    private val openReport: () -> Unit = {},
) : ViewModel() {
    private val local = MutableStateFlow(HomeUiState())

    val state: StateFlow<HomeUiState> = combine(local, settings, latestEnded) { s, saved, last ->
        s.copy(settings = saved, hasHistory = last != null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value.copy(settings = settings.value, hasHistory = latestEnded.value != null))

    /** 운행 기록 on home: the last trip's report, which closes back to home. */
    fun openHistory() {
        if (state.value.hasHistory) openReport()
    }

    init {
        viewModelScope.launch {
            active.distinctUntilChangedBy { it?.trip?.id }.collect { if (it != null) open(HomeRoute.HOME) }
        }
    }

    fun open(route: HomeRoute) {
        local.update { it.copy(route = route, startError = null) }
        gearStartAllowed.value = route != HomeRoute.SETTINGS
    }

    fun startTrip() {
        if (local.value.starting) return
        local.update { it.copy(starting = true, startError = null) }
        viewModelScope.launch {
            start().onFailure { e ->
                Log.e(TAG, "trip start failed", e)
                local.update { it.copy(startError = "운행을 시작하지 못했어요. 다시 눌러 주세요.") }
            }
            local.update { it.copy(starting = false) }
        }
    }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
