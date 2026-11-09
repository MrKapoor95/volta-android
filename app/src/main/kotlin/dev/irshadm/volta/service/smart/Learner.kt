package dev.irshadm.volta.service.smart

import android.content.Context
import dev.irshadm.volta.data.db.TripEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar
import kotlin.math.abs

/**
 * What smart pre-cool has learned from your answers, per place: a temperature offset (raised when
 * you say "Not now", lowered when you accept) and a mute after two "Not now"s in a row there.
 */
object Feedback {
    @Serializable
    data class PlaceMemory(
        val offset: Int = 0,
        val dismissesInARow: Int = 0,
        val muted: Boolean = false,
        val accepted: Int = 0,
        val dismissed: Int = 0,
        /** "Not today here": no suggestions at this place until this time. */
        val mutedUntil: Long = 0,
    ) {
        fun quiet(now: Long = System.currentTimeMillis()) = muted || mutedUntil > now
    }

    private const val PREFS = "precool_learning"
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(place: String) = "place:" + place.lowercase()

    private const val SUGGESTION_TOKEN = "suggestion_token"

    /**
     * A fresh random token for the suggestion notification being posted. The pre-cool sheet is exported (shortcuts
     * and Routines open it), so it only learns from a place that arrives with the latest token: another app can't
     * open it with a made-up place and skew what Volta learns.
     */
    fun issueSuggestionToken(context: Context): String = java.util.UUID.randomUUID().toString().also {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SUGGESTION_TOKEN, it).apply()
    }

    fun isSuggestionToken(context: Context, token: String?): Boolean =
        token != null && token == context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SUGGESTION_TOKEN, null)

    fun get(context: Context, place: String): PlaceMemory = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(place), null)
        ?.let { runCatching { json.decodeFromString<PlaceMemory>(it) }.getOrNull() } ?: PlaceMemory()

    private fun put(context: Context, place: String, m: PlaceMemory) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(place), json.encodeToString(PlaceMemory.serializer(), m)).apply()

    fun all(context: Context): Map<String, PlaceMemory> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.filterKeys { it.startsWith("place:") }
        .mapNotNull { (k, v) -> (v as? String)?.let { runCatching { k.removePrefix("place:") to json.decodeFromString<PlaceMemory>(it) }.getOrNull() } }.toMap()

    fun accepted(context: Context, place: String) {
        val m = get(context, place)
        put(context, place, m.copy(offset = (m.offset - 1).coerceAtLeast(-2), dismissesInARow = 0, accepted = m.accepted + 1))
    }

    fun dismissed(context: Context, place: String) {
        val m = get(context, place)
        val row = m.dismissesInARow + 1
        put(context, place, m.copy(offset = (m.offset + 1).coerceAtMost(4), dismissesInARow = row, muted = m.muted || row >= 2, dismissed = m.dismissed + 1))
    }

    fun unmute(context: Context, place: String) = put(context, place, get(context, place).copy(muted = false, dismissesInARow = 0, mutedUntil = 0))

    /** "Not today here": quiet at this place until 4 am tomorrow; counts as one dismissal for the threshold. */
    fun muteToday(context: Context, place: String) {
        val m = get(context, place)
        val until = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 4)
            set(java.util.Calendar.MINUTE, 0)
        }.timeInMillis
        put(context, place, m.copy(offset = (m.offset + 1).coerceAtMost(4), dismissed = m.dismissed + 1, mutedUntil = until))
    }
}

/**
 * Your usual departures, learned from logged drives: the start place and time of day (weekday vs
 * weekend) of every drive in the last 4 weeks, grouped into ~40-minute slots.
 */
object Routine {
    data class Slot(val place: String, val weekend: Boolean, val minuteOfDay: Int, val count: Int) {
        val label: String get() = "%s ~%d:%02d %s from %s".format(
            if (weekend) "Weekends" else "Weekdays",
            ((minuteOfDay / 60 + 11) % 12) + 1,
            minuteOfDay % 60,
            if (minuteOfDay < 720) "AM" else "PM",
            place,
        )
    }

    private const val WINDOW_MIN = 40
    private const val MIN_COUNT = 2

    private fun startPlace(t: TripEntity): String? = t.destination?.takeIf { " → " in it }?.substringBefore(" → ")?.takeIf { it.isNotBlank() }

    private fun minuteOfDay(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    private fun isWeekend(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }

    fun slots(trips: List<TripEntity>, now: Long = System.currentTimeMillis()): List<Slot> {
        val recent = trips.filter { now - it.startedAt < 28L * 86_400_000 }.mapNotNull { t -> startPlace(t)?.let { Triple(it, isWeekend(t.startedAt), minuteOfDay(t.startedAt)) } }
        val out = ArrayList<Slot>()
        recent.groupBy { it.first.lowercase() to it.second }.forEach { (_, list) ->
            // Greedy clustering of departure times within the window.
            val times = list.map { it.third }.sorted().toMutableList()
            while (times.isNotEmpty()) {
                val seed = times.first()
                val group = times.filter { it - seed <= WINDOW_MIN }
                if (group.size >= MIN_COUNT) out += Slot(list.first().first, list.first().second, group.sorted()[group.size / 2], group.size)
                times.removeAll(group.toSet())
            }
        }
        return out.sortedWith(compareBy({ it.weekend }, { it.minuteOfDay }))
    }

    /** The usual slot matching leaving [place] at [now], if any. */
    fun usual(slots: List<Slot>, place: String?, now: Long = System.currentTimeMillis()): Slot? {
        val m = minuteOfDay(now)
        val w = isWeekend(now)
        return slots.firstOrNull { s -> s.weekend == w && abs(s.minuteOfDay - m) <= WINDOW_MIN && (place == null || s.place.equals(place, true)) }
    }

    /** Next usual departure today after [now] (for an early car read on hot days). */
    fun nextToday(slots: List<Slot>, now: Long = System.currentTimeMillis()): Slot? {
        val m = minuteOfDay(now)
        val w = isWeekend(now)
        return slots.filter { it.weekend == w && it.minuteOfDay > m + 5 }.minByOrNull { it.minuteOfDay }
    }
}
