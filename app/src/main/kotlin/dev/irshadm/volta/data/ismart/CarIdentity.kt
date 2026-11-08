package dev.irshadm.volta.data.ismart

import android.content.Context
import dev.irshadm.volta.core.ismart.Vehicle

internal const val CAR_PREFS = "car"
internal const val CAR_NAME = "car.name"

/**
 * The models Volta has pictures of. MG's model name can carry the trim ("COMET EV EXCLUSIVE FC") and its series can be
 * a code ("E230"), so [pattern] is tried against both. The ZS Astor is a petrol car, not the ZS EV.
 *
 * MG can also send the trim alone, with no model word (a Windsor Pro came through as "Essence"). The trims that only
 * one model sells name it: FC and Play / Plush / Pace are Comets, Excite Pro and Exclusive Plus are ZS EVs, Exclusive Pro
 * and Essence Pro are Windsor Pros. Executive, Excite and Exclusive are sold on more than one model, so they stay
 * MG's own name.
 */
enum class CarModel(val label: String?, private val pattern: Regex?) {
    Windsor("Windsor", Regex("windsor|\\bEQ100\\b", RegexOption.IGNORE_CASE)),
    Comet("Comet EV", Regex("comet|\\bEC32\\b|\\bE230\\b|\\b(?:excite|exclusive)\\s*fc\\b|^\\s*(?:play|plush|pace)\\b", RegexOption.IGNORE_CASE)),
    ZsEv("ZS EV", Regex("^(?!.*astor).*(?:\\bzs\\b|\\bexcite\\s*pro\\b|\\bexclusive\\s*(?:plus|\\+))", RegexOption.IGNORE_CASE)),
    M9("M9", Regex("\\bm9\\b", RegexOption.IGNORE_CASE)),
    Cyberster("Cyberster", Regex("cyberster", RegexOption.IGNORE_CASE)),
    Tomahawk("Hector Tomahawk EV", Regex("tomahawk", RegexOption.IGNORE_CASE)),
    Other(null, null),
    ;

    companion object {
        fun of(text: String): CarModel = entries.firstOrNull { it.pattern?.containsMatchIn(text) == true }
            ?: Windsor.takeIf { WINDSOR_TRIM.containsMatchIn(text) } ?: Other

        /**
         * A name that is only a Windsor trim: Exclusive Pro, Essence Pro, or Essence (the report; the ZS EV's Essence
         * comes with "ZS" in the name). Tried after every model, so "ZS EV ESSENCE" stays a ZS EV.
         */
        private val WINDSOR_TRIM = Regex("^\\s*(?:(?:exclusive|essence)\\s*pro|essence)\\b", RegexOption.IGNORE_CASE)
    }
}

/**
 * Which MG the signed-in account drives, from the vehicle list MG returns at sign-in. Volta works with any MG EV on
 * i-SMART; the picture follows the [model], and the Windsor also has a 360° view.
 */
data class CarIdentity(val name: String) {
    val model: CarModel get() = CarModel.of(name)

    companion object {
        /** Before the first sign-in, or when MG sent no model. */
        val Unknown = CarIdentity("Your MG")

        /**
         * The car saved at sign-in, for places that run outside the app's screens: widgets and notifications. Named
         * again from what was saved, so a name saved before Volta knew the model ("Essence Pro") is fixed too.
         */
        fun saved(context: Context): CarIdentity = context.getSharedPreferences(CAR_PREFS, Context.MODE_PRIVATE).getString(CAR_NAME, null)
            ?.let(::fromSaved) ?: Unknown

        internal fun fromSaved(name: String): CarIdentity = named(name, name)

        /** "COMET EV EXCLUSIVE FC" → "Comet EV". A model Volta doesn't know keeps MG's name, without the brand. */
        fun of(vehicle: Vehicle?): CarIdentity {
            val raw = listOfNotNull(vehicle?.model, vehicle?.series).firstOrNull { it.isNotBlank() } ?: return Unknown
            return named("${vehicle?.model.orEmpty()} ${vehicle?.series.orEmpty()}", raw)
        }

        private fun named(text: String, raw: String): CarIdentity {
            val model = CarModel.of(text)
            val name = when {
                model == CarModel.Windsor && PRO.containsMatchIn(text) -> "Windsor Pro"
                model.label != null -> model.label
                else -> raw.trim().removePrefix("MG ").removePrefix("mg ").trim()
            }
            return if (name.isBlank()) Unknown else CarIdentity(name)
        }

        private val PRO = Regex("\\bpro\\b", RegexOption.IGNORE_CASE)
    }
}
