package com.coral.gwigaon.trip.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Parked with no trip and no report (ScreenGate SETUP): home, the driver's start screen, or 설정. 운행 시작 asks for
 * location and notifications (and the mic when 대화 감지 is on) first; a refusal still starts the trip.
 */
@Composable
fun NoTripScreens(home: HomeViewModel, settings: SettingsViewModel, link: LinkViewModel?, onLocationGranted: () -> Unit) {
    val state by home.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (state.settings.cues) add(Manifest.permission.RECORD_AUDIO)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) onLocationGranted()
        home.startTrip()
    }
    val openSettings = {
        settings.open()
        home.open(HomeRoute.SETTINGS)
    }
    val openQr = {
        settings.openLink()
        home.open(HomeRoute.SETTINGS)
    }
    val goHome = { home.open(HomeRoute.HOME) }
    when (state.route) {
        HomeRoute.HOME -> HomeScreen(
            state.settings, onSettings = openSettings, onStart = { home.open(HomeRoute.START) },
            onHistory = home::openHistory.takeIf { state.hasHistory },
            onQr = openQr.takeIf { link != null },
        )
        HomeRoute.START -> {
            BackHandler(onBack = goHome)
            StartScreen(
                state,
                onStart = {
                    val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                    if (missing.isEmpty()) home.startTrip() else launcher.launch(missing.toTypedArray())
                },
                onBack = goHome,
                onOpenSettings = openSettings,
            )
        }
        HomeRoute.SETTINGS -> {
            BackHandler { if (settings.back()) goHome() }
            SettingsRoute(settings, link, onClose = goHome)
        }
    }
}
