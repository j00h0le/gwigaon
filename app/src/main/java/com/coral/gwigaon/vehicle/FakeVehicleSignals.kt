package com.coral.gwigaon.vehicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

/** A car driven by code: unit tests and the hidden demo panel set every signal by hand. */
class FakeVehicleSignals(
    scope: CoroutineScope,
    initialGear: Gear? = Gear.PARK,
) : VehicleSignals {
    private val _gear = MutableStateFlow(initialGear)
    private val _turnSignal = MutableStateFlow(TurnSignal.OFF)
    private val _seat = MutableStateFlow<Boolean?>(false)
    private val _doors = MutableStateFlow<Set<Door>>(emptySet())
    private val _location = MutableSharedFlow<GeoFix>(extraBufferCapacity = 64)
    private val _navi = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    private val _unavailable = MutableStateFlow<Set<Signal>>(emptySet())

    override val gear: StateFlow<Gear?> = _gear.asStateFlow()
    override val drivingState: StateFlow<DrivingState> = DrivingStateRule.drivingState(_gear, _location, _unavailable)
        .stateIn(scope, SharingStarted.Eagerly, DrivingStateRule.decide(initialGear, null))
    override val turnSignal: Flow<TurnSignal> = _turnSignal.asStateFlow()
    override val passengerSeatOccupied: StateFlow<Boolean?> = _seat.asStateFlow()
    override val doorsOpen: StateFlow<Set<Door>> = _doors.asStateFlow()
    override val location: Flow<GeoFix> = _location.asSharedFlow()
    override val navi: Flow<NaviEvent> = _navi.asSharedFlow()
    override val unavailable: StateFlow<Set<Signal>> = _unavailable.asStateFlow()

    /** Destinations handed to the "navigation", in order. */
    val destinationsSet = mutableListOf<Destination>()

    /** When set, [setDestination] fails with this error. */
    var failSetDestination: Throwable? = null

    private val _naviRemaining = MutableStateFlow<NaviRemaining?>(null)
    override val naviRemaining: StateFlow<NaviRemaining?> = _naviRemaining.asStateFlow()
    var naviPolls = 0
        private set

    override fun pollNavi() {
        naviPolls++
    }

    fun setNaviRemaining(remaining: NaviRemaining?) { _naviRemaining.value = remaining }

    fun setGear(gear: Gear?) { _gear.value = gear }
    fun setTurnSignal(signal: TurnSignal) { _turnSignal.value = signal }
    fun setPassengerSeatOccupied(occupied: Boolean?) { _seat.value = occupied }
    fun setDoorsOpen(doors: Set<Door>) { _doors.value = doors }
    fun setUnavailable(signals: Set<Signal>) { _unavailable.value = signals }
    suspend fun emitFix(fix: GeoFix) = _location.emit(fix)
    suspend fun arrive() = _navi.emit(NaviEvent.Arrived)

    override suspend fun setDestination(dest: Destination): Result<Unit> {
        failSetDestination?.let { return Result.failure(it) }
        destinationsSet += dest
        _navi.emit(NaviEvent.DestinationSet(dest))
        return Result.success(Unit)
    }
}
