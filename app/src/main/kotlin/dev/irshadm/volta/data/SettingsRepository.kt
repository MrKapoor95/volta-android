package dev.irshadm.volta.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.irshadm.volta.core.model.CapacityLearning
import dev.irshadm.volta.core.model.ChargerPreferences
import dev.irshadm.volta.core.model.EnergySettings
import dev.irshadm.volta.core.model.NetworkPreference
import dev.irshadm.volta.core.model.Tariff
import dev.irshadm.volta.core.model.TripSettings
import dev.irshadm.volta.core.model.VehicleProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Persists vehicle profile, trip buffers and charger preferences. Missing keys fall back to model defaults. */
@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    /** Meal windows (minutes after midnight), the night start and the meal length; all adjustable in Settings. */
    val mealRules: Flow<dev.irshadm.volta.core.planner.MealRules> = store.data.map { p ->
        val d = dev.irshadm.volta.core.planner.MealRules()
        fun window(from: Preferences.Key<Int>, to: Preferences.Key<Int>, default: IntRange) = (p[from] ?: default.first)..(p[to] ?: default.last)
        dev.irshadm.volta.core.planner.MealRules(
            breakfast = window(BREAKFAST_FROM, BREAKFAST_TO, d.breakfast),
            lunch = window(LUNCH_FROM, LUNCH_TO, d.lunch),
            dinner = window(DINNER_FROM, DINNER_TO, d.dinner),
            nightFrom = p[NIGHT_FROM] ?: 21 * 60,
            mealMinutes = p[MEAL_MIN] ?: 35,
        )
    }
    suspend fun saveMealRules(r: dev.irshadm.volta.core.planner.MealRules) {
        store.edit {
            it[NIGHT_FROM] = r.nightFrom
            it[MEAL_MIN] = r.mealMinutes
            it[BREAKFAST_FROM] = r.breakfast.first
            it[BREAKFAST_TO] = r.breakfast.last
            it[LUNCH_FROM] = r.lunch.first
            it[LUNCH_TO] = r.lunch.last
            it[DINNER_FROM] = r.dinner.first
            it[DINNER_TO] = r.dinner.last
        }
    }

    /** Smart pre-cool: suggest AC when walking back to a hot car (never sends on its own). */
    val smartPrecool: Flow<SmartPrecool> = store.data.map { p -> SmartPrecool(p[PRECOOL_ON] ?: false, p[PRECOOL_TEMP] ?: 29) }
    suspend fun saveSmartPrecool(s: SmartPrecool) {
        store.edit {
            it[PRECOOL_ON] = s.enabled
            it[PRECOOL_TEMP] = s.aboveC
        }
    }

    /** Places the user named ("Apartment", "Office"), shown on the dashboard when the car is parked there. */
    val savedPlaces: Flow<List<SavedPlace>> = store.data.map { p ->
        p[SAVED_PLACES]?.let { runCatching { placesJson.decodeFromString<List<SavedPlace>>(it) }.getOrNull() }.orEmpty()
    }

    /** Adds/replaces a place with its Wi-Fi (smart pre-cool uses leaving it as "heading to the car"). */
    suspend fun savePlace(place: SavedPlace) {
        store.edit { p ->
            val list = p[SAVED_PLACES]?.let { runCatching { placesJson.decodeFromString<List<SavedPlace>>(it) }.getOrNull() }.orEmpty()
            p[SAVED_PLACES] = placesJson.encodeToString(list.filterNot { it.name.equals(place.name, true) || it.contains(place.lat, place.lng) } + place)
        }
    }

    suspend fun removePlace(name: String) {
        store.edit { p ->
            val list = p[SAVED_PLACES]?.let { runCatching { placesJson.decodeFromString<List<SavedPlace>>(it) }.getOrNull() }.orEmpty()
            p[SAVED_PLACES] = placesJson.encodeToString(list.filterNot { it.name == name })
        }
    }

    /** Adds or renames the place nearest [lat]/[lng] (within its radius). */
    suspend fun namePlace(name: String, lat: Double, lng: Double) {
        store.edit { p ->
            val list = p[SAVED_PLACES]?.let { runCatching { placesJson.decodeFromString<List<SavedPlace>>(it) }.getOrNull() }.orEmpty()
            val old = list.firstOrNull { it.contains(lat, lng) }
            val kept = list.filterNot { it.contains(lat, lng) }
            p[SAVED_PLACES] = placesJson.encodeToString(kept + SavedPlace(name.trim(), lat, lng, ssid = old?.ssid))
        }
    }

    val vehicleProfile: Flow<VehicleProfile> = store.data.map { p ->
        val d = VehicleProfile()
        d.copy(
            usableCapacityKwh = p[CAPACITY] ?: d.usableCapacityKwh,
            cityKmPerKwh = p[CITY_EFF] ?: d.cityKmPerKwh,
            highwayKmPerKwh = p[HIGHWAY_EFF] ?: d.highwayKmPerKwh,
            massKg = p[MASS] ?: d.massKg,
            driveEfficiency = p[DRIVE_EFF] ?: d.driveEfficiency,
            regenEfficiency = p[REGEN_EFF] ?: d.regenEfficiency,
        )
    }

    val tripSettings: Flow<TripSettings> = store.data.map { p ->
        val d = TripSettings()
        d.copy(
            minArrivalAtChargerPercent = p[MIN_CHARGER] ?: d.minArrivalAtChargerPercent,
            minArrivalAtDestinationPercent = p[MIN_DEST] ?: d.minArrivalAtDestinationPercent,
            maxChargeTargetPercent = p[MAX_TARGET] ?: d.maxChargeTargetPercent,
            maxDetourKm = p[MAX_DETOUR] ?: d.maxDetourKm,
            tripPollIntervalMinutes = p[POLL_MIN] ?: d.tripPollIntervalMinutes,
            replanDeviationPercent = p[REPLAN_DEV] ?: d.replanDeviationPercent,
        )
    }

    val chargerPreferences: Flow<ChargerPreferences> = store.data.map { p ->
        val d = ChargerPreferences()
        d.copy(
            networkPreference = p[NETWORK_MODE]?.let { runCatching { NetworkPreference.valueOf(it) }.getOrNull() }
                ?: d.networkPreference,
            preferredNetworks = p[NETWORKS] ?: d.preferredNetworks,
            avoidedNetworks = p[AVOID_NETWORKS] ?: d.avoidedNetworks,
        )
    }

    // ---- energy accounting ----

    val energySettings: Flow<EnergySettings> = store.data.map { p ->
        val d = EnergySettings()
        d.copy(
            homeLat = p[HOME_LAT], homeLng = p[HOME_LNG],
            homeRadiusM = p[HOME_RADIUS] ?: d.homeRadiusM,
            homeEfficiency = p[EFF_HOME] ?: d.homeEfficiency,
            publicAcRatePerKwh = p[RATE_AC] ?: d.publicAcRatePerKwh,
            dcRatePerKwh = p[RATE_DC] ?: d.dcRatePerKwh,
            householdUnitsPerCycle = p[HOUSEHOLD] ?: d.householdUnitsPerCycle,
            billCycleStartDay = p[CYCLE_DAY] ?: d.billCycleStartDay,
            billCycleStartMonth = p[CYCLE_MONTH] ?: d.billCycleStartMonth,
        )
    }

    suspend fun saveEnergy(e: EnergySettings) {
        store.edit { p ->
            val lat = e.homeLat
            val lng = e.homeLng
            if (lat != null && lng != null) {
                p[HOME_LAT] = lat
                p[HOME_LNG] = lng
            } else {
                p.remove(HOME_LAT)
                p.remove(HOME_LNG)
            }
            p[HOME_RADIUS] = e.homeRadiusM
            p[EFF_HOME] = e.homeEfficiency
            p[RATE_AC] = e.publicAcRatePerKwh
            p[RATE_DC] = e.dcRatePerKwh
            p[HOUSEHOLD] = e.householdUnitsPerCycle
            p[CYCLE_DAY] = e.billCycleStartDay
            p[CYCLE_MONTH] = e.billCycleStartMonth
        }
    }

    /** Tariff stored as simple text so the user can edit slab rates. */
    val tariff: Flow<Tariff> = store.data.map { p ->
        val d = Tariff()
        d.copy(
            telescopic = p[TARIFF_TELE]?.let { parsePairs(it) }?.takeIf { it.isNotEmpty() } ?: d.telescopic,
            nonTelescopic = p[TARIFF_FLAT]?.let { parsePairs(it) }?.takeIf { it.isNotEmpty() } ?: d.nonTelescopic,
            dutyPercent = p[TARIFF_DUTY] ?: d.dutyPercent,
            billingMonths = p[TARIFF_MONTHS] ?: d.billingMonths,
            verifiedOn = p[TARIFF_VERIFIED],
        )
    }

    suspend fun saveTariff(t: Tariff) {
        store.edit { p ->
            p[TARIFF_TELE] = t.telescopic.joinToString(";") { "${it.first}:${it.second}" }
            p[TARIFF_FLAT] = t.nonTelescopic.joinToString(";") { "${it.first}:${it.second}" }
            p[TARIFF_DUTY] = t.dutyPercent
            p[TARIFF_MONTHS] = t.billingMonths
            t.verifiedOn?.let { p[TARIFF_VERIFIED] = it }
        }
    }

    private fun parsePairs(s: String) = s.split(";").mapNotNull { part ->
        val (a, b) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
        (a.toIntOrNull() ?: return@mapNotNull null) to (b.toDoubleOrNull() ?: return@mapNotNull null)
    }

    /** Home parking is under a roof: the sun doesn't heat the cabin there. */
    val homeCovered: Flow<Boolean> = store.data.map { it[HOME_COVERED] ?: true }

    suspend fun setHomeCovered(on: Boolean) {
        store.edit { it[HOME_COVERED] = on }
    }

    /** When the usable capacity was last measured from the car (null until the car has been read enough). */
    val capacityLearnedAt: Flow<Long?> = store.data.map { it[CAPACITY_LEARNED_AT] }

    /**
     * Feeds one car reading (SoC and pack energy from the same charge frame) into [CapacityLearning]. Once enough
     * samples agree, the profile's usable capacity follows the car; returns the new capacity when it changed.
     */
    suspend fun learnCapacity(socPercent: Double, energyKwh: Double?, nowMillis: Long = System.currentTimeMillis()): Double? {
        val sample = CapacityLearning.sample(socPercent, energyKwh) ?: return null
        var changed: Double? = null
        store.edit { p ->
            val samples = CapacityLearning.append(p[CAPACITY_SAMPLES]?.split(',')?.mapNotNull { it.toDoubleOrNull() }.orEmpty(), sample)
            p[CAPACITY_SAMPLES] = samples.joinToString(",")
            val learned = CapacityLearning.learned(samples) ?: return@edit
            p[CAPACITY_LEARNED_AT] = nowMillis
            val current = p[CAPACITY] ?: VehicleProfile().usableCapacityKwh
            if (CapacityLearning.shouldReplace(current, learned) && VehicleProfile(usableCapacityKwh = learned).validate().isEmpty()) {
                p[CAPACITY] = learned
                changed = learned
            }
        }
        return changed
    }

    /** Another car was picked: forget the pack size learned from the last one, so this car's is measured afresh. */
    suspend fun forgetLearnedCapacity() {
        store.edit { p ->
            p.remove(CAPACITY)
            p.remove(CAPACITY_SAMPLES)
            p.remove(CAPACITY_LEARNED_AT)
        }
    }

    suspend fun save(profile: VehicleProfile) {
        require(profile.validate().isEmpty())
        store.edit { p ->
            p[CAPACITY] = profile.usableCapacityKwh
            p[CITY_EFF] = profile.cityKmPerKwh
            p[HIGHWAY_EFF] = profile.highwayKmPerKwh
            p[MASS] = profile.massKg
            p[DRIVE_EFF] = profile.driveEfficiency
            p[REGEN_EFF] = profile.regenEfficiency
        }
    }

    suspend fun save(settings: TripSettings) {
        require(settings.validate().isEmpty())
        store.edit { p ->
            p[MIN_CHARGER] = settings.minArrivalAtChargerPercent
            p[MIN_DEST] = settings.minArrivalAtDestinationPercent
            p[MAX_TARGET] = settings.maxChargeTargetPercent
            p[MAX_DETOUR] = settings.maxDetourKm
            p[POLL_MIN] = settings.tripPollIntervalMinutes
            p[REPLAN_DEV] = settings.replanDeviationPercent
        }
    }

    suspend fun save(prefs: ChargerPreferences) {
        store.edit { p ->
            p[NETWORK_MODE] = prefs.networkPreference.name
            p[NETWORKS] = prefs.preferredNetworks
            p[AVOID_NETWORKS] = prefs.avoidedNetworks
        }
    }

    private companion object {
        val CAPACITY = doublePreferencesKey("vehicle.capacity_kwh")
        val CAPACITY_SAMPLES = stringPreferencesKey("vehicle.capacity_samples")
        val CAPACITY_LEARNED_AT = longPreferencesKey("vehicle.capacity_learned_at")
        val CITY_EFF = doublePreferencesKey("vehicle.city_km_per_kwh")
        val HIGHWAY_EFF = doublePreferencesKey("vehicle.highway_km_per_kwh")
        val MASS = doublePreferencesKey("vehicle.mass_kg")
        val DRIVE_EFF = doublePreferencesKey("vehicle.drive_efficiency")
        val REGEN_EFF = doublePreferencesKey("vehicle.regen_efficiency")
        val MIN_CHARGER = intPreferencesKey("trip.min_arrival_charger_pct")
        val MIN_DEST = intPreferencesKey("trip.min_arrival_destination_pct")
        val MAX_TARGET = intPreferencesKey("trip.max_charge_target_pct")
        val MAX_DETOUR = intPreferencesKey("trip.max_detour_km")
        val POLL_MIN = intPreferencesKey("trip.poll_interval_min")
        val REPLAN_DEV = intPreferencesKey("trip.replan_deviation_pct")
        val NETWORK_MODE = stringPreferencesKey("chargers.network_mode")
        val NETWORKS = stringSetPreferencesKey("chargers.networks")
        val AVOID_NETWORKS = stringSetPreferencesKey("chargers.avoid_networks")
        val HOME_COVERED = booleanPreferencesKey("energy.home_covered")
        val HOME_LAT = doublePreferencesKey("energy.home_lat")
        val CYCLE_DAY = intPreferencesKey("energy.cycle_start_day")
        val CYCLE_MONTH = intPreferencesKey("energy.cycle_start_month")
        val HOME_LNG = doublePreferencesKey("energy.home_lng")
        val HOME_RADIUS = doublePreferencesKey("energy.home_radius_m")
        val EFF_HOME = doublePreferencesKey("energy.home_efficiency")
        val RATE_AC = doublePreferencesKey("energy.rate_ac")
        val RATE_DC = doublePreferencesKey("energy.rate_dc")
        val HOUSEHOLD = doublePreferencesKey("energy.household_units")
        val TARIFF_TELE = stringPreferencesKey("tariff.telescopic")
        val TARIFF_FLAT = stringPreferencesKey("tariff.flat")
        val TARIFF_DUTY = doublePreferencesKey("tariff.duty")
        val TARIFF_MONTHS = intPreferencesKey("tariff.months")
        val TARIFF_VERIFIED = stringPreferencesKey("tariff.verified")
        val SAVED_PLACES = stringPreferencesKey("places.saved")
        val PRECOOL_ON = booleanPreferencesKey("precool.enabled")
        val NIGHT_FROM = intPreferencesKey("meals.night_from_min")
        val MEAL_MIN = intPreferencesKey("meals.meal_minutes")
        val BREAKFAST_FROM = intPreferencesKey("meals.breakfast_from")
        val BREAKFAST_TO = intPreferencesKey("meals.breakfast_to")
        val LUNCH_FROM = intPreferencesKey("meals.lunch_from")
        val LUNCH_TO = intPreferencesKey("meals.lunch_to")
        val DINNER_FROM = intPreferencesKey("meals.dinner_from")
        val DINNER_TO = intPreferencesKey("meals.dinner_to")
        val PRECOOL_TEMP = intPreferencesKey("precool.above_c")
    }
}

private val placesJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

@kotlinx.serialization.Serializable
data class SavedPlace(val name: String, val lat: Double, val lng: Double, val radiusM: Double = 150.0, val ssid: String? = null) {
    fun contains(lat: Double, lng: Double): Boolean = dev.irshadm.volta.core.planner.Geo.distanceM(dev.irshadm.volta.core.planner.LatLng(this.lat, this.lng), dev.irshadm.volta.core.planner.LatLng(lat, lng)) <= radiusM
}

data class SmartPrecool(val enabled: Boolean = false, val aboveC: Int = 29)
