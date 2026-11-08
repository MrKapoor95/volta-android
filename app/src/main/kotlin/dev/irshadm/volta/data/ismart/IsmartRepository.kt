package dev.irshadm.volta.data.ismart

import dev.irshadm.volta.core.ismart.CommandIndeterminateException
import dev.irshadm.volta.core.ismart.Credentials
import dev.irshadm.volta.core.ismart.IsmartException
import dev.irshadm.volta.core.ismart.LoginRejectedException
import dev.irshadm.volta.core.ismart.LoginThrottledException
import dev.irshadm.volta.core.ismart.MgCrypto
import dev.irshadm.volta.core.ismart.MgIsmartClient
import dev.irshadm.volta.core.ismart.RemoteCommand
import dev.irshadm.volta.core.ismart.SignedOutException
import dev.irshadm.volta.core.model.BodyStatus
import dev.irshadm.volta.core.model.GpsFix
import dev.irshadm.volta.core.model.LightStatus
import dev.irshadm.volta.core.model.SocReading
import dev.irshadm.volta.core.model.SocSource
import dev.irshadm.volta.core.model.Timed
import dev.irshadm.volta.core.model.TyrePressures
import dev.irshadm.volta.core.model.VehicleStatus
import dev.irshadm.volta.core.model.WindowStatus
import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.data.SocRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LinkState {
    data object SignedOut : LinkState
    data class Connected(val phone: String, val refreshing: Boolean = false, val error: String? = null) : LinkState

    /** The session was taken by another login (MG app) or expired; the saved login is still good, so Reconnect is one tap. */
    data class NeedsReconnect(val phone: String, val error: String? = null) : LinkState
}

sealed interface CommandState {
    data object Idle : CommandState
    data class Running(val command: RemoteCommand) : CommandState
    data class Done(val command: RemoteCommand) : CommandState
    data class Failed(val command: RemoteCommand, val message: String, val uncertain: Boolean) : CommandState
}

/** The live MG i-SMART link: sign-in, status refresh, remote commands. */
@Singleton
class IsmartRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val client: MgIsmartClient,
    private val secure: SecureStore,
    private val socRepository: SocRepository,
    private val settings: SettingsRepository,
    private val charges: dev.irshadm.volta.data.energy.ChargeTracker,
    private val drives: dev.irshadm.volta.data.trip.DriveLogger,
) {
    private val _link = MutableStateFlow<LinkState>(secure.credentials?.let { LinkState.Connected(MgCrypto.normalizePhone(it.phone)) } ?: LinkState.SignedOut)
    val link: StateFlow<LinkState> = _link

    /** Last known status, persisted so Home shows the car immediately on a cold start. */
    private val cacheFile = java.io.File(context.filesDir, "last_status.json")
    private val _status = MutableStateFlow<VehicleStatus?>(
        if (_link.value is LinkState.SignedOut) {
            null
        } else {
            runCatching { cacheJson.decodeFromString<VehicleStatus>(cacheFile.readText()) }.getOrNull()
        },
    )
    val status: StateFlow<VehicleStatus?> = _status

    /** The signed-in car's model, saved at sign-in so the dashboard knows which picture to show. */
    private val carPrefs = context.getSharedPreferences(CAR_PREFS, android.content.Context.MODE_PRIVATE)
    private val _car = MutableStateFlow(CarIdentity.saved(context))
    val car: StateFlow<CarIdentity> = _car

    private val _command = MutableStateFlow<CommandState>(CommandState.Idle)
    val command: StateFlow<CommandState> = _command

    private val _pinSaved = MutableStateFlow(secure.pinHash != null)
    val pinSaved: StateFlow<Boolean> = _pinSaved
    val hasPin: Boolean get() = _pinSaved.value

    private val _pinStepDone = MutableStateFlow(secure.pinHash != null || secure.pinSkipped)
    val pinStepDone: StateFlow<Boolean> = _pinStepDone
    private val refreshMutex = Mutex()

    /** Work that must outlive the screen that started it (the first read after signing in). */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** MG rejected the saved password on a reconnect: the login was cleared and the sign-in screen shows this. */
    private val _rejection = MutableStateFlow<String?>(null)
    val rejection: StateFlow<String?> = _rejection

    /** "Keep showing saved readings" on the connection-lost screen; reset when the link changes state. */
    private val _reconnectDismissed = MutableStateFlow(false)
    val reconnectDismissed: StateFlow<Boolean> = _reconnectDismissed
    fun dismissReconnect() {
        _reconnectDismissed.value = true
    }

    /** True while [reconnect] is signing in; the link stays [LinkState.NeedsReconnect] until it succeeds. */
    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting

    /** When the login guard allows the next sign-in, or null if now (3 sign-ins an hour). */
    fun nextLoginAllowedAt(): Long? = client.nextLoginAllowedAt()

    /** The UI warns about MG's login limits before calling this. Wrong passwords are surfaced, never retried. */
    suspend fun signIn(phone: String, password: String): Result<Unit> = runCatching {
        val normalized = MgCrypto.normalizePhone(phone)
        secure.credentials = Credentials(normalized, password)
        try {
            client.signIn()
            val vehicles = client.vehicles()
            val car = CarIdentity.of(vehicles.firstOrNull { it.vin == client.vin } ?: vehicles.firstOrNull())
            carPrefs.edit().putString(CAR_NAME, car.name).apply()
            _car.value = car
        } catch (e: Exception) {
            secure.credentials = null
            secure.save(null)
            throw e
        }
        // Every suspending call goes before Connected: that closes the sign-in screen and cancels the scope this runs
        // in, so a call after it throws and the first read never starts (an empty dashboard until the app restarts).
        _rejection.value = null
        _reconnectDismissed.value = false
        // Refreshing from the start, so the dashboard shows the first read is on its way.
        _link.value = LinkState.Connected(normalized, refreshing = true)
        scope.launch { firstRead() }
        Unit
    }.recoverCatching { throw IllegalStateException(friendly(it), it) }

    /**
     * The read after signing in. A parked car can take longer to wake than one read polls for ("The car did not
     * answer"), and there's nothing to show until it does, so it keeps trying for about two minutes (each read polls
     * for 15 s or so) with the dashboard skeleton up, and only the last failure shows its error.
     * Stops if the link is lost: a retry would only sign in again.
     */
    private suspend fun firstRead() {
        repeat(FIRST_READ_TRIES) { i ->
            refresh(retrying = i < FIRST_READ_TRIES - 1)
            if (_status.value != null || link.value !is LinkState.Connected) return
            if (i < FIRST_READ_TRIES - 1) kotlinx.coroutines.delay(5_000)
        }
    }

    /** [keepReadings]: leave the last status in place (a rejected saved password should not blank the dashboard). */
    fun signOut(keepReadings: Boolean = false) {
        client.signOut()
        secure.clearLogin()
        _pinSaved.value = false
        _pinStepDone.value = false
        if (!keepReadings) {
            _status.value = null
            cacheFile.delete()
            _rejection.value = null
            carPrefs.edit().clear().apply()
            _car.value = CarIdentity.Unknown
        }
        _link.value = LinkState.SignedOut
        _reconnectDismissed.value = false
        dev.irshadm.volta.widget.VoltaWidget.updateAsync(context)
    }

    /**
     * Signs in again with the saved login. Stays in [LinkState.NeedsReconnect] when it can't (throttled, no
     * network), and only a rejected password clears the login and sends you to the sign-in screen.
     */
    suspend fun reconnect() {
        val phone = (link.value as? LinkState.NeedsReconnect)?.phone ?: return
        if (_reconnecting.value) return
        _reconnecting.value = true
        try {
            client.signIn()
            _link.value = LinkState.Connected(phone, refreshing = true)
            _reconnectDismissed.value = false
            scope.launch { refresh() } // outlives the connection-lost screen that Connected closes
        } catch (e: LoginRejectedException) {
            _rejection.value = e.message
            signOut(keepReadings = true)
        } catch (e: Exception) {
            _link.value = LinkState.NeedsReconnect(phone, friendly(e))
        } finally {
            _reconnecting.value = false
        }
    }

    fun setPin(pin: String) {
        secure.pinHash = MgCrypto.pinHash(pin)
        _pinSaved.value = true
        _pinStepDone.value = true
    }
    fun clearPin() {
        secure.pinHash = null
        _pinSaved.value = false
    }
    fun skipPinStep() {
        secure.pinSkipped = true
        _pinStepDone.value = true
    }

    /** Asks MG whether [pin] is right without sending anything to the car. Throws the client's exception if not. */
    suspend fun checkPin(pin: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { client.checkPin(MgCrypto.pinHash(pin)) }

    /** On-demand refresh. Readings are stored as a time series. */
    suspend fun refresh(includeCharge: Boolean = true, retrying: Boolean = false) = refreshMutex.withLock {
        val connected = link.value as? LinkState.Connected ?: return@withLock
        _link.value = connected.copy(refreshing = true, error = null)
        try {
            val r = client.read(includeCharge)
            val before = _status.value
            // A read without the charge frame (e.g. right after a command) keeps the last charging figures.
            val status = map(r, before).let { st ->
                if (r.charge != null || before == null) {
                    st
                } else {
                    st.copy(
                        pluggedIn = before.pluggedIn, charging = before.charging, chargePowerKw = before.chargePowerKw,
                        chargeMinutesRemaining = before.chargeMinutesRemaining, energySinceLastChargeKwh = before.energySinceLastChargeKwh,
                        distanceSinceLastChargeKm = before.distanceSinceLastChargeKm, chargingType = before.chargingType,
                        chargeStartedAtMillis = before.chargeStartedAtMillis, kmToday = before.kmToday, kwhToday = before.kwhToday,
                        soc = st.soc?.let { cur -> Timed(cur.value.copy(batteryEnergyKwh = cur.value.batteryEnergyKwh ?: before.soc?.value?.batteryEnergyKwh), cur.atMillis) },
                    )
                }
            }.let { st ->
                // No battery level in this read (see [map]): keep the last one rather than showing none.
                if (st.soc == null && before?.soc != null) st.copy(soc = before.soc) else st
            }.let { st ->
                // A parked time saved before a logged drive is stale.
                val lastDrive = runCatching { drives.lastDriveEnd() }.getOrNull()
                if (st.ignitionOn?.value == false && lastDrive != null && lastDrive > (st.ignitionChangedAtMillis ?: 0L)) st.copy(ignitionChangedAtMillis = lastDrive) else st
            }
            _status.value = status
            // Signed in before the model was saved (or MG didn't send one then): ask once more.
            if (_car.value == CarIdentity.Unknown) {
                runCatching {
                    val vehicles = client.vehicles()
                    val car = CarIdentity.of(vehicles.firstOrNull { it.vin == client.vin } ?: vehicles.firstOrNull())
                    if (car != CarIdentity.Unknown) {
                        carPrefs.edit().putString(CAR_NAME, car.name).apply()
                        _car.value = car
                    }
                }
            }
            runCatching { cacheFile.writeText(cacheJson.encodeToString(VehicleStatus.serializer(), status)) }
            val previous = socRepository.latest.first()
            runCatching { charges.onReading(status, previous?.takeIf { it.source == dev.irshadm.volta.core.model.SocSource.ISMART }) }
            // Only a level this read brought; one carried over from the last read is already stored.
            if (status.soc !== before?.soc) status.soc?.value?.let { socRepository.add(it) }
            // The pack size comes from the car, not the Windsor 38 default: SoC and energy from the same charge frame.
            r.charge?.let { c -> c.socPercent?.let { soc -> runCatching { settings.learnCapacity(soc, c.batteryEnergyKwh) } } }
            runCatching { drives.onReading(status, before) }
            runCatching { dev.irshadm.volta.service.smart.SmartPrecool.onCarReading(context, status, settings) }
            runCatching { dev.irshadm.volta.service.CarCheckWorker.evaluate(context, status) }
            runCatching { dev.irshadm.volta.widget.VoltaWidget.update(context, status) }
            _link.value = connected.copy(refreshing = false, error = null)
            _reconnectDismissed.value = false
        } catch (e: SignedOutException) {
            _link.value = LinkState.NeedsReconnect(connected.phone, if (e is LoginThrottledException) e.message else null)
        } catch (e: Exception) {
            // [retrying]: another try follows, so stay in the loading state and keep the error off the screen.
            _link.value = connected.copy(refreshing = retrying, error = if (retrying) null else friendly(e))
        }
    }

    suspend fun send(cmd: RemoteCommand) {
        val pin = secure.pinHash ?: run {
            _command.value = CommandState.Failed(cmd, "Set your control PIN in Settings first", false)
            return
        }
        _command.value = CommandState.Running(cmd)
        try {
            client.command(cmd, pin)
            _command.value = CommandState.Done(cmd)
            refresh(includeCharge = false)
        } catch (e: CommandIndeterminateException) {
            _command.value = CommandState.Failed(cmd, "${cmd.label} was sent but the car didn't confirm. Check the status before trying again.", true)
            refresh(includeCharge = false)
        } catch (e: SignedOutException) {
            (link.value as? LinkState.Connected)?.let { _link.value = LinkState.NeedsReconnect(it.phone) }
            _command.value = CommandState.Failed(cmd, "Car connection lost — reconnect first", false)
        } catch (e: Exception) {
            _command.value = CommandState.Failed(cmd, friendly(e), false)
        }
    }

    fun clearCommand() {
        _command.value = CommandState.Idle
    }

    private fun map(r: MgIsmartClient.Reading, previous: VehicleStatus?): VehicleStatus {
        val s = r.snapshot
        val c = r.charge
        val at = s.statusTime * 1000
        // The charging frame's level is the real one. Without it the status frame's fuel level stands in, which the
        // Windsor fills with the battery level but other models leave at 0: a ZS EV showed "Battery 0 %, not plugged
        // in". A car at 0 % couldn't answer at all, so 0 there means "not reported".
        val socPct = c?.socPercent ?: s.socPercent?.takeIf { it > 0 }?.toDouble()
        val soc = socPct?.let {
            SocReading(
                it.coerceIn(0.0, 100.0),
                SocSource.ISMART,
                (c?.statusTime ?: s.statusTime) * 1000,
                batteryEnergyKwh = c?.batteryEnergyKwh,
                carRangeKm = c?.rangeKm ?: s.rangeKm,
                odometerKm = s.odometerKm,
            )
        }
        val on = isDriving(s.powerMode, s.engineStatus, s.climateRunning, s.speedKmh, s.handBrakeOn)
        return VehicleStatus(
            soc = soc?.let { Timed(it, it.measuredAtMillis) },
            pluggedIn = c?.let { Timed(it.pluggedIn, it.statusTime * 1000) },
            charging = c?.let { Timed(it.charging, it.statusTime * 1000) },
            chargePowerKw = c?.let { Timed(it.powerKw, it.statusTime * 1000) },
            chargeMinutesRemaining = c?.minutesRemaining?.let { Timed(it, c.statusTime * 1000) },
            energySinceLastChargeKwh = c?.energySinceChargeKwh?.let { Timed(it, c.statusTime * 1000) },
            distanceSinceLastChargeKm = c?.distanceSinceChargeKm?.let { Timed(it, c.statusTime * 1000) },
            journeyId = Timed(s.journeyId, at),
            journeyDistanceRaw = Timed(s.journeyDistanceRaw, at),
            locked = Timed(s.locked, at),
            // India is right-hand drive: protocol driverDoor = front right.
            body = Timed(BodyStatus(s.driverDoorOpen, s.passengerDoorOpen, s.rearLeftDoorOpen, s.rearRightDoorOpen, s.bootOpen, s.bonnetOpen), at),
            lightsOn = Timed(s.lightsOn, at),
            climateRunning = Timed(s.climateRunning, at),
            ignitionOn = Timed(on, at),
            // The speed comes from the last GPS fix, which the car keeps after it's switched off — only trust it while on.
            speedKmh = s.speedKmh?.let { Timed(if (on) it else 0.0, at) },
            tyrePsi = s.tyres?.let { Timed(TyrePressures(it.frontLeft, it.frontRight, it.rearLeft, it.rearRight), at) },
            auxBatteryVolts = s.auxVolts?.let { Timed(it, at) },
            interiorTempC = s.interiorTempC?.let { Timed(it.toDouble(), at) },
            exteriorTempC = s.exteriorTempC?.let { Timed(it.toDouble(), at) },
            gps = if (s.latitude != null && s.longitude != null) Timed(GpsFix(s.latitude!!, s.longitude!!, s.altitudeM, s.speedKmh, s.headingDeg), (s.gpsTime ?: s.statusTime) * 1000) else null,
            windows = if (listOf(s.driverWindowOpen, s.passengerWindowOpen, s.rearLeftWindowOpen, s.rearRightWindowOpen).any { it != null }) {
                Timed(WindowStatus(s.driverWindowOpen == true, s.passengerWindowOpen == true, s.rearLeftWindowOpen == true, s.rearRightWindowOpen == true, s.sunroofOpen == true), at)
            } else {
                null
            },
            lights = Timed(LightStatus(s.sideLights, s.dippedBeam, s.mainBeam), at),
            handbrakeOn = s.handBrakeOn?.let { Timed(it, at) },
            warningCodes = Timed(s.activeAlerts, at),
            lockChangedAtMillis = previous?.let { p -> if (p.locked?.value == s.locked) p.lockChangedAtMillis else at },
            ignitionChangedAtMillis = previous?.let { p ->
                ignitionChangedAt(p.ignitionOn?.value, p.ignitionChangedAtMillis, p.soc?.value?.odometerKm, on, s.odometerKm, at, s.gpsTime?.let { it * 1000 })
            },
            chargingType = c?.let { Timed(it.chargingType, it.statusTime * 1000) },
            chargeStartedAtMillis = c?.takeIf { it.charging }?.startedAt?.let { it * 1000 },
            kmToday = c?.kmToday?.let { Timed(it, c.statusTime * 1000) },
            kwhToday = c?.kwhToday?.let { Timed(it, c.statusTime * 1000) },
        )
    }

    private fun friendly(e: Throwable): String = when (e) {
        is LoginRejectedException -> "MG rejected the sign-in: ${e.message}"
        is LoginThrottledException -> e.message ?: "Signed in too often this hour"
        is SignedOutException -> "Signed out by another app"
        is IsmartException -> e.userMessage ?: "MG i-SMART error"
        is IOException -> "No connection to MG servers"
        is IllegalArgumentException -> e.message ?: "Check the details"
        else -> e.message ?: e.javaClass.simpleName
    }
}

/**
 * When the car last switched on or off. Normally that's the read where the state flipped, but a whole drive can
 * fall between two reads (no Android Auto, phone asleep): both reads say parked and the time would stay at the
 * morning's. It once showed "Parked since 9:56" after a day of driving. So if the odometer moved while
 * parked both times, the car was driven since — it parked at its last GPS fix (kept from the switch-off), or at
 * this read when there's no usable fix.
 */
internal fun ignitionChangedAt(prevOn: Boolean?, prevAt: Long?, prevOdoKm: Double?, on: Boolean, odoKm: Double?, at: Long, gpsAt: Long?): Long? {
    if (prevOn != on) return at
    val droveUnseen = !on && prevOdoKm != null && odoKm != null && odoKm - prevOdoKm >= 0.1
    if (!droveUnseen) return prevAt
    return gpsAt?.takeIf { it <= at && (prevAt == null || it > prevAt) } ?: at
}

private val cacheJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/**
 * Whether the car is being driven, from one status read. Power mode alone isn't enough: the car powers up for a
 * remote AC run and sits that way while parked. Standing still (under 3 km/h) with the remote AC running is a parked
 * car being cooled. The park-brake reading can't decide it: on 31 Oct 2026 the Windsor reported the brake off while
 * parked and locked with the AC on (the bug that showed "Driving" and blocked Lock). A brake reported on while
 * standing still still counts as parked. Moving, or powered with neither, is driving.
 */
internal fun isDriving(powerMode: Int, engineStatus: Int, climateRunning: Boolean, speedKmh: Double?, handBrakeOn: Boolean?): Boolean {
    if (powerMode == 0 && engineStatus == 0) return false
    val still = (speedKmh ?: 0.0) < 3.0
    if (still && climateRunning) return false
    if (still && handBrakeOn == true) return false
    return true
}

private const val FIRST_READ_TRIES = 6
