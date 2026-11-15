package com.coral.gwigaon

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coral.gwigaon.report.ReportViewModel
import com.coral.gwigaon.report.ui.ReportRoute
import com.coral.gwigaon.trip.TripService
import com.coral.gwigaon.trip.StartSource
import com.coral.gwigaon.trip.ui.HomeViewModel
import com.coral.gwigaon.trip.ui.LinkViewModel
import com.coral.gwigaon.trip.ui.NoTripScreens
import com.coral.gwigaon.trip.ui.SettingsViewModel
import com.coral.gwigaon.trip.ui.TripViewModel
import com.coral.gwigaon.ui.AppNavigation
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val container get() = (application as GwigaonApp).container

    /** False until a trip that was running before a restart has been picked up, so home doesn't flash. */
    private var resumeChecked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container.startMonitors() // turn-signal SOS and route rules run with the app
        resumeTrip()
        keepServiceWithTrip()
        setContent {
            GwigaonTheme {
                val active by container.tripSessions.active.collectAsStateWithLifecycle()
                val driving by container.vehicleSignals.drivingState.collectAsStateWithLifecycle()
                val unavailable by container.vehicleSignals.unavailable.collectAsStateWithLifecycle()
                val ended by container.tripSessions.lastEnded.collectAsStateWithLifecycle()
                val listening by container.cues.listening.collectAsStateWithLifecycle()
                if (!resumeChecked) {
                    Box(Modifier.fillMaxSize().background(Gwigaon.colors.backgroundPrimary))
                } else {
                    AppNavigation(
                        active = active,
                        driving = driving,
                        unavailable = unavailable,
                        tripViewModel = viewModel {
                            TripViewModel(
                                container.tripSessions, container.vehicleSignals.drivingState, container.clock,
                                container.sos, container.config.sosHold, container.cues, container.eta.eta,
                                safeArrivalDelay = container.config.safeArrivalDelay, speaker = container.tts,
                            )
                        },
                        setup = {
                            NoTripScreens(
                                home = viewModel {
                                    HomeViewModel(
                                        container.settings.settings,
                                        container.tripSessions.active,
                                        { container.tripStarter.start(StartSource.BUTTON) },
                                        container.gearStartScreen,
                                        container.tripSessions.latestEnded,
                                        container.tripSessions::openLatestReport,
                                    )
                                },
                                settings = viewModel {
                                    SettingsViewModel(
                                        container.settings,
                                        container.vehicleSignals.drivingState,
                                        container.tripSessions.active,
                                        container.tripSessions::recentDestinations,
                                        container.clock,
                                        container::phraseArrivalLater,
                                    )
                                },
                                link = viewModel { LinkViewModel(container.ownerLinks, container.cloud.contactPageUrl) },
                                onLocationGranted = { container.vehicleSignals.startLocation() },
                            )
                        },
                        listening = listening,
                        report = ended?.let { trip ->
                            {
                                ReportRoute(
                                    viewModel(key = "report-${trip.id}") {
                                        ReportViewModel(
                                            trip, container.reports, ZoneId.systemDefault(),
                                            container.tripSessions::closeReport,
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    /** A trip that was running when the process died resumes now (gap logged). */
    private fun resumeTrip() {
        lifecycleScope.launch {
            container.tripSessions.resumeIfActive().exceptionOrNull()?.let { Log.e(TAG, "trip resume failed", it) }
            container.tripSessions.loadLatestEnded()
            resumeChecked = true
        }
    }

    /**
     * Whenever a trip is running and the app is visible, its foreground service runs too (start is idempotent;
     * each return to the foreground retries it). Android refuses the service without the location grant, so
     * while visible the activity also keeps the trip's last-seen time fresh: a later restart then logs the real
     * gap, not the whole time the app was open.
     */
    private fun keepServiceWithTrip() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.appVisible.value = true
                try {
                    awaitCancellation()
                } finally {
                    container.appVisible.value = false
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.vehicleSignals.startLocation() // picks up a grant made in system settings meanwhile
                container.cues.refreshPermission() // same for the mic
                launch {
                    container.tripSessions.active.distinctUntilChangedBy { it?.trip?.id }.collect { active ->
                        if (active != null) TripService.start(this@MainActivity)
                    }
                }
                while (isActive) {
                    delay(container.config.livenessInterval)
                    runCatching { container.tripSessions.heartbeat() }.onFailure { Log.w(TAG, "heartbeat failed", it) }
                }
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
