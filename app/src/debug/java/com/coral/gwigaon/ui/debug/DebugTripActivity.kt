package com.coral.gwigaon.ui.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.coral.gwigaon.GwigaonApp
import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.CheckInAnswer
import com.coral.gwigaon.trip.CueKind
import com.coral.gwigaon.trip.PinResult
import com.coral.gwigaon.trip.SettingsDraft
import com.coral.gwigaon.trip.StartSource
import com.coral.gwigaon.trip.SosSource
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripService
import com.coral.gwigaon.trip.TripSetup
import com.coral.gwigaon.vehicle.Destination
import kotlinx.coroutines.launch

/**
 * Debug builds only (src/debug). Drives a trip from adb, e.g.
 * `adb shell am start -n com.coral.gwigaon/.ui.debug.DebugTripActivity --es cmd start`
 * with `cmd` = settings | go | start | sos | end, or a panel state: deviate [--ei m] | back | stop [--ei s] | go-on |
 * near | checkin [--ei level --es cue LOUD|PROFANITY|DISTRESS] | noresp. `settings` saves 설정 (home from `--es dest --ef lat --ef lng`, 보호자
 * "보호자", `--es pin` turns the PIN on, `--ez cues true`); `go` starts from 설정 like 운행 시작. Otherwise `cmd` (`--es pin` for start and end; default 1234; start also takes `--es dest <name>
 * --ef lat <lat> --ef lng <lng>`, default 강남역, `--ez cues true` for conflict cues, and arrival instructions `--es parking/entrance/key <text>`). The result goes to logcat under the tag
 * DebugTrip. End goes through the PIN like the real button.
 */
class DebugTripActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as GwigaonApp).container
        val sessions = container.tripSessions
        val cmd = intent.getStringExtra("cmd")
        lifecycleScope.launch {
            val result = when (cmd) {
                "start" -> sessions.start(
                    TripSetup(
                        Destination(
                            intent.getStringExtra("dest") ?: "데모 목적지",
                            intent.getFloatExtra("lat", 37.4979f).toDouble(),
                            intent.getFloatExtra("lng", 127.0276f).toDouble(),
                        ),
                        contactLabel = "보호자",
                        pin = intent.getStringExtra("pin") ?: "1234",
                        arrival = ArrivalInstructions(
                            intent.getStringExtra("parking").orEmpty(),
                            intent.getStringExtra("entrance").orEmpty(),
                            intent.getStringExtra("key").orEmpty(),
                        ),
                    ),
                ).onSuccess {
                    // Same as setup's switch: needs RECORD_AUDIO granted beforehand (`pm grant`).
                    if (intent.getBooleanExtra("cues", false)) container.cues.enable(it.trip.id)
                    TripService.start(this@DebugTripActivity)
                }
                "settings" -> container.settings.save(
                    SettingsDraft(
                        home = Destination(
                            intent.getStringExtra("dest") ?: "데모 목적지",
                            intent.getFloatExtra("lat", 37.4979f).toDouble(),
                            intent.getFloatExtra("lng", 127.0276f).toDouble(),
                        ),
                        contacts = listOf("보호자"),
                        arrival = ArrivalInstructions(
                            intent.getStringExtra("parking").orEmpty(),
                            intent.getStringExtra("entrance").orEmpty(),
                            intent.getStringExtra("key").orEmpty(),
                        ),
                        cues = intent.getBooleanExtra("cues", false),
                        pinOn = intent.getStringExtra("pin") != null,
                        pin = intent.getStringExtra("pin").orEmpty(),
                        pinConfirm = intent.getStringExtra("pin").orEmpty(),
                    ),
                )
                "go" -> container.tripStarter.start(StartSource.BUTTON).onSuccess { TripService.start(this@DebugTripActivity) }
                "sos" -> sessions.log(TripEvent.Sos(SosSource.ON_SCREEN, "디버그 SOS"))
                // Panel states for screenshots (D3b): the monitors may clear these again on the next fix.
                "deviate" -> sessions.log(TripEvent.RouteDeviation(intent.getIntExtra("m", 420), "디버그 경로 이탈"))
                "back" -> sessions.log(TripEvent.DeviationCleared("디버그 복귀"))
                "stop" -> sessions.log(TripEvent.LongStop(intent.getIntExtra("s", 300), "디버그 정차"))
                "go-on" -> sessions.log(TripEvent.LongStopCleared("디버그 출발"))
                "near" -> sessions.log(TripEvent.NearDestination("디버그 목적지 근처"))
                "noresp" -> sessions.log(TripEvent.CheckInAnswered(CheckInAnswer.NO_RESPONSE, "디버그 응답 없음"))
                "checkin" -> sessions.log(
                    TripEvent.CheckInPrompted(
                        intent.getIntExtra("level", 1),
                        CueKind.valueOf(intent.getStringExtra("cue") ?: "LOUD"),
                        "디버그 확인",
                    ),
                )
                "end" -> sessions.endByPassenger(intent.getStringExtra("pin") ?: "1234")
                else -> Result.failure(IllegalArgumentException("unknown cmd $cmd"))
            }
            Log.i(TAG, "$cmd -> ${result.fold({ if (it is PinResult && it !is PinResult.Accepted) "refused: $it" else "ok" }, { "failed: ${it.message}" })} active=${sessions.active.value?.trip?.id}")
            finish()
        }
    }

    private companion object {
        const val TAG = "DebugTrip"
    }
}
