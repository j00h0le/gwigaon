package com.coral.gwigaon.vehicle

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "VehicleSignals"
private const val NAVI_READY_TIMEOUT_MS = 5_000L
private const val GPS_INTERVAL_MS = 1_000L

/**
 * Pleos Connect adapter: Vehicle SDK (gear, turn signal, seats, doors), NaviHelper (destination, arrival)
 * and LocationManager (GPS). Verified on Pleos Connect Emulator 2.1.0 (T2, T3, T5 spikes).
 * Any registration that fails marks its [Signal] unavailable instead of crashing.
 */
class PleosVehicleSignals(context: Context, scope: CoroutineScope) : VehicleSignals {
    private val appContext = context.applicationContext
    private val vehicle = Vehicle(appContext)
    private val naviHelper = NaviHelper(appContext)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    private val _gear = MutableStateFlow<Gear?>(null)
    private val _turnSignal = MutableStateFlow(TurnSignal.OFF)
    private val seatStates = MutableStateFlow<Map<SeatValue, Boolean?>>(emptyMap())
    private val _seat = MutableStateFlow<Boolean?>(null)
    private val _doors = MutableStateFlow<Set<Door>>(emptySet())
    private val _location = MutableSharedFlow<GeoFix>(extraBufferCapacity = 64)
    private val _navi = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    private val _unavailable = MutableStateFlow<Set<Signal>>(emptySet())
    private val naviReady = CompletableDeferred<Boolean>()

    private val seatsInverted = SeatRule.isEmulator(Build.HARDWARE, Build.PRODUCT)

    @Volatile private var pendingDestination: Destination? = null
    @Volatile private var locationStarted = false

    override val gear: StateFlow<Gear?> = _gear.asStateFlow()
    override val drivingState: StateFlow<DrivingState> = DrivingStateRule.drivingState(_gear, _location, _unavailable)
        .stateIn(scope, SharingStarted.Eagerly, DrivingStateRule.decide(null, null))
    override val turnSignal: Flow<TurnSignal> = _turnSignal.asStateFlow()
    override val passengerSeatOccupied: StateFlow<Boolean?> = _seat.asStateFlow()
    override val doorsOpen: StateFlow<Set<Door>> = _doors.asStateFlow()
    override val location: Flow<GeoFix> = _location.asSharedFlow()
    override val navi: Flow<NaviEvent> = _navi.asSharedFlow()
    override val unavailable: StateFlow<Set<Signal>> = _unavailable.asStateFlow()

    init {
        scope.launch { drivingState.collect { Log.i(TAG, "drivingState=$it") } }
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            _gear.value = gear.toGear()
            available(Signal.GEAR)
            Log.i(TAG, "gear=${_gear.value}")
        }
        override fun onFailed(e: Exception) {
            _gear.value = null // the last gear is no longer known
            unavailable(Signal.GEAR, e)
        }
    }

    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            _turnSignal.value = when (state) {
                TurnSignalValue.LEFT -> TurnSignal.LEFT
                TurnSignalValue.RIGHT -> TurnSignal.RIGHT
                else -> TurnSignal.OFF
            }
            Log.i(TAG, "turnSignal=${_turnSignal.value}")
        }
        override fun onFailed(e: Exception) = unavailable(Signal.TURN_SIGNAL, e)
    }

    private val seatListeners = PASSENGER_SEATS.associateWith { seat ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
                val occupied = SeatRule.normalize(occupancy, seatsInverted)
                val states = seatStates.updateAndGet { it + (seat to occupied) }
                _seat.value = SeatRule.anyOccupied(states.values)
                Log.i(TAG, "seat $seat raw=$occupancy occupied=$occupied passengerSeatOccupied=${_seat.value}")
            }
            override fun onFailed(e: Exception) = unavailable(Signal.SEATS, e)
        }
    }

    private val doorListeners = DOORS.mapValues { (_, door) ->
        object : DoorPositionListener {
            override fun onPositionUpdated(position: Int?) {
                val before = _doors.value
                val after = _doors.updateAndGet { if ((position ?: 0) > 0) it + door else it - door }
                if (after != before) Log.i(TAG, "doorsOpen=$after")
            }
            override fun onFailed(e: Exception) = unavailable(Signal.DOORS, e)
        }
    }

    private val _naviRemaining = MutableStateFlow<NaviRemaining?>(null)
    override val naviRemaining: StateFlow<NaviRemaining?> = _naviRemaining.asStateFlow()

    @Volatile private var routeTotalM: Int? = null
    @Volatile private var naviUp = false

    /** The answer comes back in [NaviHelperEventListener.onDestinationInfo]; a failure only marks NAVI unavailable. */
    override fun pollNavi() {
        if (naviUp) attempt(Signal.NAVI) { naviHelper.getDestinationInfo() }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            pendingDestination?.let { _navi.tryEmit(NaviEvent.DestinationSet(it)) }
            routeTotalM = routeStartInfo.distance.takeIf { it > 0 }
            Log.i(TAG, "navi routeStarted distance=${routeStartInfo.distance}m duration=${routeStartInfo.duration}")
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            // remainTime is in seconds: the route start reports duration=60 for 50 m (ADR 0018).
            _naviRemaining.value = NaviRemaining(
                distanceM = destinationInfo.remainDistance,
                timeS = destinationInfo.remainTime,
                routeTotalM = routeTotalM,
                at = Clock.System.now(),
            )
            Log.i(TAG, "navi remaining ${destinationInfo.remainDistance}m ${destinationInfo.remainTime}s")
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            _navi.tryEmit(NaviEvent.Arrived)
            Log.i(TAG, "navi arrived")
        }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error $errorCode")
        }
    }

    private var fixCount = 0

    private val locationListener = LocationListener { loc: Location ->
        fixCount++
        if (fixCount == 1 || fixCount % 60 == 0) Log.i(TAG, "gps fixes=$fixCount") // no coordinates in logs
        _location.tryEmit(
            GeoFix(
                lat = loc.latitude,
                lng = loc.longitude,
                timeMillis = loc.time,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                speedMps = if (loc.hasSpeed()) loc.speed else null,
            ),
        )
    }

    /** Registers every listener. Call once; call [startLocation] again after a location permission grant. */
    fun start() {
        attempt(Signal.GEAR) { vehicle.initialize() }
        attempt(Signal.GEAR) { vehicle.getGear().registerGearSelection(gearListener) }
        attempt(Signal.TURN_SIGNAL) { vehicle.getTurnSignal().registerTurnSignalState(turnListener) }
        seatListeners.forEach { (seat, l) -> attempt(Signal.SEATS) { vehicle.getSeat().registerSeatOccupancy(SeatArea(seat), l) } }
        doorListeners.forEach { (value, l) -> attempt(Signal.DOORS) { vehicle.getDoor().registerDoorPosition(DoorArea(value), l) } }
        attempt(Signal.NAVI) {
            naviHelper.initialize(
                {
                    attempt(Signal.NAVI) { naviHelper.addListener(naviListener) }
                    naviUp = true
                    naviReady.complete(true)
                    Log.i(TAG, "navi ready")
                },
                { e ->
                    naviReady.complete(false)
                    unavailable(Signal.NAVI, e)
                },
            )
        }
        startLocation()
    }

    /** Starts GPS updates if the permission is granted; otherwise marks LOCATION unavailable. */
    fun startLocation() {
        if (locationStarted) return
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            unavailable(Signal.LOCATION, SecurityException("ACCESS_FINE_LOCATION not granted"))
            return
        }
        attempt(Signal.LOCATION) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper(),
            )
            locationStarted = true
            available(Signal.LOCATION)
            Log.i(TAG, "location started")
        }
    }

    fun close() {
        runCatching { locationManager.removeUpdates(locationListener) }
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(turnListener) }
        seatListeners.forEach { (seat, l) -> runCatching { vehicle.getSeat().unregisterSeatOccupancy(SeatArea(seat), l) } }
        doorListeners.forEach { (value, l) -> runCatching { vehicle.getDoor().unregisterDoorPosition(DoorArea(value), l) } }
        runCatching { naviHelper.removeListener(naviListener) }
        runCatching { naviHelper.release() }
        runCatching { vehicle.release() }
        locationStarted = false
        _gear.value = null
    }

    override suspend fun setDestination(dest: Destination): Result<Unit> {
        val ready = withTimeoutOrNull(NAVI_READY_TIMEOUT_MS) { naviReady.await() } ?: false
        if (!ready) return Result.failure(IllegalStateException("NaviHelper not ready"))
        return runCatching {
            pendingDestination = dest
            naviHelper.requestRoute(
                RouteInfo(
                    latitude = dest.lat,
                    longitude = dest.lng,
                    displayLatitude = null,
                    displayLongitude = null,
                    poiName = dest.name,
                    poiId = dest.poiId.orEmpty(),
                    poiSubId = "0",
                    address = dest.address.orEmpty(),
                ),
            )
            Log.i(TAG, "navi requestRoute sent")
            Unit
        }.onFailure { Log.w(TAG, "navi requestRoute failed", it) }
    }

    private fun attempt(signal: Signal, block: () -> Unit) {
        runCatching(block).onFailure { unavailable(signal, it) }
    }

    private fun unavailable(signal: Signal, e: Throwable) {
        _unavailable.update { it + signal }
        Log.w(TAG, "$signal unavailable: $e")
    }

    private fun available(signal: Signal) = _unavailable.update { it - signal }

    private companion object {
        /** Front passenger and rear seats; the driver's seat (ROW_1_LEFT) is always occupied while driving. */
        val PASSENGER_SEATS = listOf(SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)

        val DOORS = mapOf(
            DoorValue.ROW_1_LEFT to Door.FRONT_LEFT,
            DoorValue.ROW_1_RIGHT to Door.FRONT_RIGHT,
            DoorValue.ROW_2_LEFT to Door.REAR_LEFT,
            DoorValue.ROW_2_RIGHT to Door.REAR_RIGHT,
            DoorValue.REAR to Door.TRUNK,
        )

        fun VehicleGear?.toGear(): Gear? = when (this) {
            VehicleGear.GEAR_PARK -> Gear.PARK
            VehicleGear.GEAR_REVERSE -> Gear.REVERSE
            VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            null, VehicleGear.GEAR_UNKNOWN -> null
            else -> Gear.DRIVE // GEAR_DRIVE and GEAR_1..GEAR_9
        }
    }
}
