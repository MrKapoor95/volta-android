package dev.irshadm.volta.service.smart

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.irshadm.volta.QuickActionActivity
import dev.irshadm.volta.R
import dev.irshadm.volta.core.model.VehicleStatus
import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.LatLng
import dev.irshadm.volta.data.SavedPlace
import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.data.db.TripDao
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.data.trip.LocationProvider
import dev.irshadm.volta.service.Notifications
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Smart pre-cool. Signals that you're heading to the car:
 *  - leaving one of your places: its Wi-Fi drops for 60 s (fast, while the app process is alive), or the
 *    phone leaves a 150 m zone around it (reliable, handled by Play services even when the app is closed);
 *  - walking back to within ~400 m (≈ 5 min) of the parked car — only where the car is NOT at one of
 *    your places, so office coffee/lunch walks don't count, and not when [Walking] says you're in a vehicle or on a bike.
 * Then [DepartureWorker] decides (daytime, parked, AC off, outside air (weather) and estimated cabin above your learned threshold,
 * routine, cooldown, mutes) and at most posts ONE notification. Sending needs the sheet + fingerprint.
 */
object SmartPrecool {
    private const val PREFS = "precool"
    private const val CAR_FENCE = "car"
    private const val PLACE_PREFIX = "place:"
    const val COOLDOWN_MS = 2 * 60 * 60_000L

    fun hasPermission(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun fencesIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        7,
        Intent(context, SmartGeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Rebuilds all zones: one per saved place with Wi-Fi (exit) + the parked car (enter, if not at a place). */
    @SuppressLint("MissingPermission")
    fun arm(context: Context, enabled: Boolean, places: List<SavedPlace>, car: VehicleStatus?) {
        val client = LocationServices.getGeofencingClient(context)
        client.removeGeofences(fencesIntent(context))
        Walking.watch(context, enabled)
        if (!enabled || !hasPermission(context)) return
        val fences = ArrayList<Geofence>()
        places.filter { it.ssid != null }.forEach { p ->
            fences += Geofence.Builder().setRequestId(PLACE_PREFIX + p.name).setCircularRegion(p.lat, p.lng, 150f)
                .setExpirationDuration(Geofence.NEVER_EXPIRE).setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT).build()
        }
        val g = car?.gps?.value
        val parkedAtPlace = g != null && places.any { it.ssid != null && it.contains(g.latitude, g.longitude) }
        if (g != null && car.ignitionOn?.value != true && !parkedAtPlace) {
            fences += Geofence.Builder().setRequestId(CAR_FENCE).setCircularRegion(g.latitude, g.longitude, 400f)
                .setExpirationDuration(Geofence.NEVER_EXPIRE).setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER).build()
        }
        if (fences.isEmpty()) return
        client.addGeofences(GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build(), fencesIntent(context))
    }

    /** After each car reading: re-arm only when the car moved to a new spot (~100 m buckets). */
    suspend fun onCarReading(context: Context, v: VehicleStatus, settings: SettingsRepository) {
        val g = v.gps?.value ?: return
        if (v.ignitionOn?.value == true) return
        val spot = "%.3f,%.3f".format(g.latitude, g.longitude)
        if (prefs(context).getString("spot", null) == spot) return
        prefs(context).edit().putString("spot", spot).apply()
        arm(context, settings.smartPrecool.first().enabled, settings.savedPlaces.first(), v)
    }

    fun departing(context: Context, reason: String, place: String?, delayS: Long = 0) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "precool-departure",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DepartureWorker>().setInitialDelay(delayS, TimeUnit.SECONDS)
                .setInputData(workDataOf("reason" to reason, "place" to place)).build(),
        )
    }

    internal fun lastSuggestedAt(context: Context) = prefs(context).getLong("lastAt", 0)
    internal fun markSuggested(context: Context) = prefs(context).edit().putLong("lastAt", System.currentTimeMillis()).apply()

    // ---- Wi-Fi watching (fast path while the app process is alive) -----------------------------

    @Volatile private var currentSsid: String? = null

    @Volatile private var watching = false

    /** Current Wi-Fi name (needs location permission + location on), without quotes. */
    @Suppress("DEPRECATION")
    fun currentWifi(context: Context): String? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
            ?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }.getOrNull()

    fun watchWifi(context: Context, settings: SettingsRepository) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        currentSsid = currentWifi(app)
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    (caps.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" }?.let { currentSsid = it }
                }
                override fun onLost(network: Network) = lost(app, settings)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    currentSsid = currentWifi(app)
                }
                override fun onLost(network: Network) = lost(app, settings)
            }
        }
        runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback) }
    }

    private fun lost(context: Context, settings: SettingsRepository) {
        val ssid = currentSsid ?: return
        currentSsid = null
        // Which place was that? Decided in the worker after 60 s, so a Wi-Fi blip that reconnects is ignored.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "precool-departure",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DepartureWorker>().setInitialDelay(60, TimeUnit.SECONDS)
                .setInputData(workDataOf("reason" to "wifi", "ssid" to ssid)).build(),
        )
    }
}

class SmartGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        event.triggeringGeofences?.forEach { f ->
            when {
                f.requestId == "car" && event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER -> SmartPrecool.departing(context, "approach", null)

                f.requestId.startsWith("place:") && event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT ->
                    SmartPrecool.departing(context, "left", f.requestId.removePrefix("place:"))
            }
        }
    }
}

/** Geofences are cleared on reboot; re-create them. */
class SmartBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<RearmWorker>().build())
    }
}

/** "Not today here" (quiet at this place until tomorrow) or a plain "not now" on the notification. */
class PrecoolFeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("place")?.let { if (intent.getStringExtra("what") == "mute_today") Feedback.muteToday(context, it) else Feedback.dismissed(context, it) }
        NotificationManagerCompat.from(context).cancel(DepartureWorker.NOTIFICATION_ID)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmartDeps {
    fun smartIsmart(): IsmartRepository
    fun smartSettings(): SettingsRepository
    fun tripDao(): TripDao
    fun smartLocation(): LocationProvider
    fun smartWeather(): dev.irshadm.volta.data.weather.WeatherClient
}

class RearmWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val d = EntryPointAccessors.fromApplication(applicationContext, SmartDeps::class.java)
        SmartPrecool.arm(applicationContext, d.smartSettings().smartPrecool.first().enabled, d.smartSettings().savedPlaces.first(), d.smartIsmart().status.value)
        RoutinePrefetch.scheduleNext(applicationContext, d.tripDao())
        return Result.success()
    }
}

/** On hot days, read the car ~10 min before a usual departure so the suggestion is instant and current. */
object RoutinePrefetch {
    suspend fun scheduleNext(context: Context, trips: TripDao) {
        val next = Routine.nextToday(Routine.slots(trips.finished(300))) ?: return
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.timeInMillis +
            (next.minuteOfDay - 10) * 60_000L
        val delay = at - System.currentTimeMillis()
        if (delay <= 0) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "precool-prefetch",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PrefetchWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
        )
    }
}

class PrefetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val d = EntryPointAccessors.fromApplication(applicationContext, SmartDeps::class.java)
        if (d.smartSettings().smartPrecool.first().enabled && d.smartIsmart().link.value is LinkState.Connected) d.smartIsmart().refresh(includeCharge = false)
        RoutinePrefetch.scheduleNext(applicationContext, d.tripDao())
        return Result.success()
    }
}

class DepartureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val NOTIFICATION_ID = 4243
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val d = EntryPointAccessors.fromApplication(ctx, SmartDeps::class.java)
        val cfg = d.smartSettings().smartPrecool.first()
        if (!cfg.enabled) return Result.success()
        val reason = inputData.getString("reason") ?: return Result.success()
        val places = d.smartSettings().savedPlaces.first()

        // Which place are we leaving? (Wi-Fi: match the name; ignore if it reconnected within 60 s.)
        val place: SavedPlace? = when (reason) {
            "wifi" -> {
                val ssid = inputData.getString("ssid")
                if (ssid != null && SmartPrecool.currentWifi(ctx) == ssid) return Result.success()
                places.firstOrNull { it.ssid == ssid } ?: return Result.success()
            }

            "left" -> places.firstOrNull { it.name == inputData.getString("place") }

            else -> null
        }
        val placeKey = place?.name ?: "elsewhere"
        // Passing the car in a cab or on a bike isn't heading to it.
        if (reason == "approach" && Walking.now(ctx).let { it == Walking.Mode.Vehicle || it == Walking.Mode.Bike }) return Result.success()

        // Hard limits.
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour !in 7 until 19) return Result.success()
        if (System.currentTimeMillis() - SmartPrecool.lastSuggestedAt(ctx) < SmartPrecool.COOLDOWN_MS) return Result.success()
        val memory = Feedback.get(ctx, placeKey)
        if (memory.quiet()) return Result.success()
        // A cool is already running from Volta: nothing to suggest.
        if (Cooling.session(ctx) != null) return Result.success()

        val ismart = d.smartIsmart()
        if (ismart.link.value !is LinkState.Connected) return Result.success()
        val cached = ismart.status.value
        val fresh = cached?.soc?.value?.measuredAtMillis?.let { System.currentTimeMillis() - it < 10 * 60_000 } == true
        if (!fresh) ismart.refresh(includeCharge = false)
        val v = ismart.status.value ?: return Result.success()
        if (v.ignitionOn?.value == true || v.climateRunning?.value == true) return Result.success()
        // The car's own temperatures freeze when it's switched off: outside comes from the weather where the
        // car is, and the cabin is estimated from it, the sun and how long it's been parked.
        val weather = v.gps?.value?.let { d.smartWeather().now(it.latitude, it.longitude) }
        val temps = dev.irshadm.volta.data.weather.CabinModel.temps(v, weather)
        val inside = temps.cabinC ?: return Result.success()
        val outside = temps.outsideC ?: return Result.success()

        // The car must be near where you're leaving from (leaving office Wi-Fi while the car is at home → no).
        val car = v.gps?.value
        if (place != null && car != null && Geo.distanceM(LatLng(place.lat, place.lng), LatLng(car.latitude, car.longitude)) > 1_500) return Result.success()

        // Learned threshold; off-routine departures need it noticeably hotter.
        val usual = Routine.usual(Routine.slots(d.tripDao().finished(300)), place?.name)
        val threshold = cfg.aboveC + memory.offset + if (usual == null && reason != "approach") 3 else 0
        if (inside <= threshold || outside <= threshold) return Result.success()

        SmartPrecool.markSuggested(ctx)
        val capacity = d.smartSettings().vehicleProfile.first().usableCapacityKwh
        postSuggestion(ctx, inside, outside.roundToInt(), placeKey, usual, reason, capacity)
        return Result.success()
    }
}

/**
 * "Cool to 24° before you get in": the outcome as the title, the temperatures as a pair with the time, the
 * reason and the cost underneath, the car as the large icon, and the primary button carrying the minutes.
 * "Not today here" keeps this place quiet until tomorrow.
 */
private fun postSuggestion(ctx: Context, insideC: Double, outside: Int, place: String, slot: Routine.Slot?, reason: String, capacityKwh: Double) {
    val usual = slot?.label
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val inside = insideC.roundToInt()
    val minutes = Cooling.minutes(insideC)
    val cost = Cooling.batteryText(Cooling.batteryPercent(minutes, capacityKwh))
    val sheet = PendingIntent.getActivity(
        ctx,
        8,
        Intent(Intent.ACTION_VIEW, Uri.parse("volta://action/precool"), ctx, QuickActionActivity::class.java)
            .putExtra("suggestion_place", place).putExtra("suggestion_reason", reason)
            .putExtra("suggestion_token", Feedback.issueSuggestionToken(ctx))
            .putExtra("suggestion_usual", slot?.let { "%d:%02d".format(((it.minuteOfDay / 60 + 11) % 12) + 1, it.minuteOfDay % 60) })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notToday = PendingIntent.getBroadcast(
        ctx,
        9,
        Intent(ctx, PrecoolFeedbackReceiver::class.java).putExtra("place", place).putExtra("what", "mute_today"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val placeName = place.takeIf { it != "elsewhere" }
    val why = when (reason) {
        "approach" -> "You're about 5 minutes from the car."
        else -> if (usual != null) "Leaving${placeName?.let { " $it" } ?: ""}, like usual around now." else "Looks like you're heading to the car."
    }
    val line = "$inside° → ${Cooling.TARGET_C}° · about $minutes min · outside $outside°"
    NotificationManagerCompat.from(ctx).notify(
        DepartureWorker.NOTIFICATION_ID,
        NotificationCompat.Builder(ctx, Notifications.CHANNEL_PRECOOL)
            .setSmallIcon(R.drawable.ic_stat_precool)
            .setLargeIcon(Cooling.carIcon(ctx, Cooling.HOT))
            .setColor(0xFFD4AF7C.toInt())
            .setContentTitle("Cool to ${Cooling.TARGET_C}° before you get in")
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$line\n$why Uses $cost of the battery."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(15 * 60_000L)
            .setContentIntent(sheet)
            .addAction(0, "Start AC · $minutes min", sheet)
            .addAction(0, "Not today here", notToday)
            .build(),
    )
}
