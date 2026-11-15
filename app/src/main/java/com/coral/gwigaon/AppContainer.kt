package com.coral.gwigaon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import com.coral.gwigaon.arrival.ArrivalAnnouncer
import com.coral.gwigaon.arrival.ArrivalLog
import com.coral.gwigaon.arrival.PleosTts
import com.coral.gwigaon.arrival.SafeArrivalMonitor
import com.coral.gwigaon.cues.AndroidMic
import com.coral.gwigaon.cues.ClipStore
import com.coral.gwigaon.cues.CueConfig
import com.coral.gwigaon.cues.CueLog
import com.coral.gwigaon.cues.CueMonitor
import com.coral.gwigaon.cues.CueSwitch
import com.coral.gwigaon.cues.PleosStt
import com.coral.gwigaon.cues.SpeechCues
import com.coral.gwigaon.report.ReportRepository
import com.coral.gwigaon.arrival.ArrivalPhrasing
import com.coral.gwigaon.arrival.PhraseCloud
import com.coral.gwigaon.report.SummaryCloud
import com.coral.gwigaon.route.RouteLog
import com.coral.gwigaon.route.RouteMonitor
import com.coral.gwigaon.sos.SosController
import com.coral.gwigaon.route.EtaTracker
import com.coral.gwigaon.sos.SosLog
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.PinHasher
import com.coral.gwigaon.trip.SettingsRepository
import com.coral.gwigaon.trip.TripStarter
import com.coral.gwigaon.trip.TripSessionRepository
import com.coral.gwigaon.trip.db.GwigaonDatabase
import com.coral.gwigaon.trip.db.RoomOwnerLinkStore
import com.coral.gwigaon.trip.db.RoomSettingsStore
import com.coral.gwigaon.trip.db.RoomSyncStore
import com.coral.gwigaon.trip.db.RoomTripStore
import com.coral.gwigaon.sync.CloudConfig
import com.coral.gwigaon.sync.Heartbeat
import com.coral.gwigaon.sync.OwnerLinkCloud
import com.coral.gwigaon.sync.OwnerLinkRepository
import com.coral.gwigaon.sync.SessionManager
import com.coral.gwigaon.sync.SupabaseClient
import com.coral.gwigaon.sync.SyncConfig
import com.coral.gwigaon.sync.SyncLog
import com.coral.gwigaon.sync.UploadQueue
import com.coral.gwigaon.sync.UrlConnectionTransport
import com.coral.gwigaon.vehicle.PleosVehicleSignals
import com.coral.gwigaon.vehicle.SeatRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Clock

/** Manual DI (SPEC: one AppContainer, no Hilt). App-scoped, so signals and the trip outlive any screen. */
class AppContainer(context: Context, val appScope: CoroutineScope) {
    private val appContext = context.applicationContext

    val clock: Clock = Clock.System
    /** Debuggable builds run the demo timings (safe-arrival check after 30 s); the release build doesn't (T23). */
    private val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val config: MonitoringConfig = MonitoringConfig.forMode(debuggable)

    val database: GwigaonDatabase by lazy { GwigaonDatabase.create(appContext) }

    val vehicleSignals: PleosVehicleSignals by lazy { PleosVehicleSignals(appContext, appScope).also { it.start() } }

    val tripSessions: TripSessionRepository by lazy { TripSessionRepository(RoomTripStore(database), PinHasher(), clock) }

    /** True while MainActivity is started: the home / start screen counts as "up" for a gear start (ADR 0015). */
    val appVisible = MutableStateFlow(false)

    /** False while 설정 is open: shifting out of P starts a trip only from home or the start screen (ADR 0015). */
    val gearStartScreen = MutableStateFlow(true)

    /** Starts the trip 설정 describes, by 운행 시작 or by gear out of P. */
    val tripStarter: TripStarter by lazy {
        TripStarter(tripSessions, settings, vehicleSignals::setDestination, { cues.enable(it) }) { m, e ->
            if (e == null) Log.i(START_TAG, m) else Log.w(START_TAG, m, e)
        }
    }

    /** The car owner's 설정 (ADR 0015); saving is refused while a trip runs. */
    val settings: SettingsRepository by lazy {
        SettingsRepository(RoomSettingsStore(database), PinHasher(), clock, tripActive = { tripSessions.active.value != null })
    }

    /**
     * Watches the turn signal for the SOS pattern for as long as the process lives (not only while the service
     * runs: Android refuses the service without the location grant, and SOS must work anyway).
     */
    val sos: SosController by lazy {
        SosController(tripSessions, clock, config, SosLog { m, e -> if (e == null) Log.w(SOS_TAG, m) else Log.e(SOS_TAG, m, e) })
            .also { it.watchTurnSignal(vehicleSignals.turnSignal, appScope) }
    }

    /** Deviation and long-stop rules, also for the process lifetime (same reason as [sos]). */
    val routeMonitor: RouteMonitor by lazy {
        RouteMonitor(
            tripSessions, vehicleSignals.drivingState, clock, config,
            RouteLog { m, e -> if (e == null) Log.i(ROUTE_TAG, m) else Log.e(ROUTE_TAG, m, e) },
        ).also { it.watch(vehicleSignals.location, appScope) }
    }

    private val arrivalLog = ArrivalLog { m, e -> if (e == null) Log.i(ARRIVAL_TAG, m) else Log.e(ARRIVAL_TAG, m, e) }

    val tts: PleosTts by lazy { PleosTts(appContext, arrivalLog).also { it.start() } }

    /** Near-destination announcement (card + speech), also for the process lifetime. */
    val arrivalAnnouncer: ArrivalAnnouncer by lazy {
        ArrivalAnnouncer(tripSessions, config, tts, arrivalLog)
            .also { it.watch(vehicleSignals.location, vehicleSignals.navi, appScope) }
    }

    /** Safe-arrival check and the trip's safe-arrival end, also for the process lifetime. */
    val safeArrival: SafeArrivalMonitor by lazy {
        SafeArrivalMonitor(
            tripSessions, vehicleSignals.gear, vehicleSignals.passengerSeatOccupied, clock, config, arrivalLog,
        ).also { it.watch(appScope) }
    }

    /**
     * Conflict cues (SPEC conflict-cues: stage 1 loudness, stage 2 on-device Korean words through the Pleos
     * SpeechToText SDK) and the check-in, for the process lifetime like [sos]. The SOS clip
     * goes to app-private `files/sos-clips` (no backup, never uploaded); clips older than
     * [CueConfig.clipRetention] are deleted at start-up.
     */
    val cues: CueMonitor by lazy {
        val cueConfig = CueConfig.DEFAULT.copy(logLevels = debuggable)
        val cueLog = CueLog { m, e -> if (e == null) Log.i(CUES_TAG, m) else Log.e(CUES_TAG, m, e) }
        val clips = ClipStore(File(appContext.filesDir, "sos-clips"), cueConfig.sampleRate)
        appScope.launch(Dispatchers.IO) {
            clips.purgeOlderThan(clock.now() - cueConfig.clipRetention)
                .onSuccess { if (it > 0) cueLog.log("deleted $it old SOS clip(s)", null) }
                .onFailure { cueLog.log("old SOS clips not deleted", it) }
        }
        CueMonitor(
            tripSessions, sos, AndroidMic(appContext, cueConfig.sampleRate),
            CueSwitch(File(appContext.filesDir, "cue-switch"), cueLog),
            clips,
            clock, cueConfig, cueLog,
            speech = SpeechCues(PleosStt(appContext, cueLog), clock, cueConfig, cueLog),
        ).also { it.watch(appScope) }
    }

    /** Supabase endpoints from the build; sync is off without a URL or with anything but a publishable key. */
    val cloud = CloudConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, BuildConfig.CONTACT_PAGE_URL)

    private val syncLog = SyncLog { m, e -> if (e == null) Log.i(SYNC_TAG, m) else Log.w(SYNC_TAG, "$m: ${e.message}") }
    private val syncConfig = SyncConfig()

    private val syncStore by lazy { RoomSyncStore(database) }
    private val supabase by lazy { SupabaseClient(cloud.supabaseUrl, cloud.supabaseKey, UrlConnectionTransport(), clock) }

    /** One anonymous session for uploads and reports: the function only summarizes trips this user owns. */
    private val cloudSessions by lazy { SessionManager(supabase, syncStore, clock, log = syncLog) }

    /** The cloud upload (SPEC cloud-sync), or null when this build has no cloud. */
    val uploadQueue: UploadQueue? by lazy {
        if (!cloud.syncEnabled) {
            Log.i(SYNC_TAG, "cloud sync off: $cloud")
            return@lazy null
        }
        UploadQueue(syncStore, supabase, cloudSessions, clock, syncConfig, syncLog)
    }

    /** The account's one contact link, shown as a QR (ADR 0019); without a cloud it is kept but never uploaded. */
    val ownerLinks: OwnerLinkRepository by lazy {
        OwnerLinkRepository(
            RoomOwnerLinkStore(database),
            if (cloud.syncEnabled) OwnerLinkCloud(cloudSessions, supabase) else null,
            clock,
            log = syncLog,
        )
    }

    /** Journey reports (T19): local timeline, plus the AI summary when this build has a cloud. */
    val reports: ReportRepository by lazy {
        ReportRepository(
            tripSessions::events,
            if (cloud.syncEnabled) SummaryCloud(cloudSessions, supabase) else null,
            log = syncLog,
        )
    }

    /** The spoken arrival sentence (ADR 0021); without a cloud the template is always spoken. */
    private val arrivalPhrasing by lazy {
        ArrivalPhrasing(
            { settings.settings.value },
            settings::setSpoken,
            if (cloud.syncEnabled) PhraseCloud(cloudSessions, supabase) else null,
            syncLog,
        )
    }

    /** Right after a 설정 save, parked: outlives the 설정 screen; a failure keeps the template. */
    fun phraseArrivalLater() {
        appScope.launch(Dispatchers.IO) { Log.i(SYNC_TAG, "phrase-arrival: ${arrivalPhrasing.phraseIfNeeded()}") }
    }

    private var syncStarted = false

    /**
     * A QR made or replaced offline goes up at start, whenever the network comes back, and when a trip starts, so a
     * replaced QR cuts the old phone off before the next trip (Checkpoint F review). Only once 설정 is done: before
     * that there is no QR to keep. Call after [SettingsRepository.load].
     */
    private fun keepOwnerLinkSynced() {
        if (!cloud.syncEnabled) return
        val tripStarts = tripSessions.active.map { it?.trip?.id }.distinctUntilChanged().filterNotNull().map { }
        appScope.launch {
            merge(flowOf(Unit), networkAvailable(), tripStarts).collect {
                if (settings.settings.value.isComplete) ownerLinks.sync()
            }
        }
    }

    /**
     * Uploads for the process lifetime: woken by every new queue entry and every SOS, drained in full when the
     * network comes back; the running trip's heartbeat every 30 s.
     */
    private fun startSync() {
        if (syncStarted) return
        syncStarted = true
        val queue = uploadQueue ?: return
        val wakes = merge(database.uploadQueue().observePending().map { }, sos.fired.map { })
        queue.run(appScope, wakes, networkAvailable())
        Heartbeat(queue, syncConfig.heartbeat).run(appScope, tripSessions.active.map { it?.trip?.id })
    }

    private fun networkAvailable(): Flow<Unit> = callbackFlow {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }

    /** Starts every always-on monitor and the cloud sync; safe to call more than once. */
    fun startMonitors() {
        if (!monitorsStarted) {
            monitorsStarted = true
            appScope.launch {
                settings.load().onFailure { Log.e(START_TAG, "settings unreadable", it) }
                keepOwnerLinkSynced()
                // Gear start: the home / start screen is what shows parked with no trip and no report.
                tripStarter.watchGear(vehicleSignals.gear, {
                    appVisible.value && gearStartScreen.value && tripSessions.active.value == null &&
                        tripSessions.lastEnded.value == null &&
                        settings.settings.value.isComplete
                }, appScope)
            }
        }
        sos
        routeMonitor
        arrivalAnnouncer
        safeArrival
        cues
        eta
        startSync()
    }

    /**
     * The driver panel's arrival time (ADR 0018): the navigation's remaining time and distance on a car, a straight-line
     * estimate from the app's GPS on the emulator, whose navigation is a mock that never moves.
     */
    val eta: EtaTracker by lazy {
        EtaTracker(
            tripSessions.active, { tripSessions.lastFix }, vehicleSignals.naviRemaining, vehicleSignals::pollNavi, clock,
            demo = SeatRule.isEmulator(Build.HARDWARE, Build.PRODUCT),
        ).also { it.run(appScope) }
    }

    private var monitorsStarted = false

    private companion object {
        const val START_TAG = "TripStart"
        const val SOS_TAG = "Sos"
        const val ROUTE_TAG = "Route"
        const val ARRIVAL_TAG = "Arrival"
        const val SYNC_TAG = "Sync"
        const val CUES_TAG = "Cues"
    }
}
