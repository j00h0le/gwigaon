package com.coral.gwigaon.trip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.coral.gwigaon.GwigaonApp
import com.coral.gwigaon.MainActivity
import com.coral.gwigaon.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Foreground service (type location, plus microphone while conflict cues listen) that keeps a trip monitored while the app is in the background
 * (SPEC trip-core). It resumes an interrupted trip, feeds GPS fixes to the session, records liveness,
 * and shows the trip status in an ongoing notification. Rules (T11–T14) hook in here.
 * Sticky: if the system kills the process, it restarts the service and the trip resumes with the gap logged.
 * After the trip ends it stays until the trip's uploads are done (bounded), so swiping the app away doesn't strand them.
 */
class TripService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null
    private var withMic = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as GwigaonApp).container
        if (!enterForeground(placeholderNotification(), container.cues.listening.value)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running?.isActive == true) return START_STICKY

        val sessions = container.tripSessions
        container.startMonitors() // a sticky restart without the activity still runs SOS and route rules
        running = scope.launch {
            val resumed = sessions.resumeIfActive()
            resumed.exceptionOrNull()?.let { Log.e(TAG, "resume failed; not monitoring", it) }
            if (resumed.getOrNull() == null) {
                Log.i(TAG, "no active trip; stopping")
                stopSelf()
                return@launch
            }
            launch {
                val queue = container.uploadQueue
                val settle: (suspend (String, Duration) -> Boolean)? =
                    if (queue == null) null else { tripId, timeout -> queue.awaitSettled(tripId, timeout) }
                val endedUploads = EndedTripUploads(
                    scope = this,
                    settle = settle,
                    online = ::online,
                    grace = UPLOAD_GRACE,
                    onWaiting = {
                        notify(baseNotification().setContentTitle(getString(R.string.app_name)).setContentText(TripTexts.NOTIFICATION_UPLOADING).build())
                    },
                    stop = { outcome ->
                        Log.i(TAG, "trip ended (${outcome.name.lowercase()}); stopping")
                        stopSelf()
                    },
                )
                sessions.active.collect { active ->
                    endedUploads.onActive(active?.trip?.id)
                    if (active != null) notify(statusNotification(active))
                }
            }
            launch { container.vehicleSignals.location.collect { sessions.lastFix = it } }
            // Conflict cues: the service also holds the microphone type while the mic is on, and drops it after.
            launch { container.cues.listening.collect { mic -> if (mic != withMic) enterForeground(statusOrPlaceholder(), mic) } }
            launch {
                while (isActive) {
                    delay(container.config.livenessInterval)
                    sessions.heartbeat()
                    Log.d(TAG, "alive")
                }
            }
        }
        return START_STICKY
    }

    /** Whether there is a network that could reach the cloud now; offline, an ended trip's wait is skipped. */
    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Android 14 refuses a location FGS without the location grant; then the trip only runs while the app is open.
     * With [mic] it adds the microphone type (needs `RECORD_AUDIO` and the app in front, Android 14); when that is
     * refused, e.g. on a sticky restart in the background, it keeps location only and the mic waits for the app.
     */
    private fun enterForeground(notification: Notification, mic: Boolean = false): Boolean {
        if (mic && startForegroundTyped(notification, mic = true)) {
            withMic = true
            return true
        }
        withMic = false
        return startForegroundTyped(notification, mic = false)
    }

    private fun startForegroundTyped(notification: Notification, mic: Boolean): Boolean = runCatching {
        // Service types exist from API 29; on 28 (minSdk) the manifest types are enough.
        val type = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
            mic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }.onFailure { Log.e(TAG, "could not start foreground monitoring (mic=$mic)", it) }.isSuccess

    private fun statusOrPlaceholder(): Notification {
        val active = (application as GwigaonApp).container.tripSessions.active.value
        return if (active != null) statusNotification(active) else placeholderNotification()
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun placeholderNotification() = baseNotification().setContentTitle(getString(R.string.app_name)).build()

    private fun statusNotification(active: ActiveTrip) = baseNotification()
        .setContentTitle(TripTexts.notificationTitle(active.trip.destination.name))
        .setContentText(TripTexts.notificationStatus(active.state))
        .build()

    private fun baseNotification(): NotificationCompat.Builder {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.trip_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.trip_channel_description) },
        )
    }

    companion object {
        private const val TAG = "TripService"
        private const val CHANNEL_ID = "trip"
        private const val NOTIFICATION_ID = 1

        /** Long enough for a few backed-off retries on a weak link; the queue keeps anything left for later. */
        private val UPLOAD_GRACE = 2.minutes

        /** Call from the foreground (an activity); Android blocks starting it from the background. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TripService::class.java))
        }
    }
}
