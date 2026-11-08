package dev.irshadm.volta.core.model

import kotlin.math.abs

/**
 * Learns the usable battery size from the car instead of assuming the Windsor 38's 37.1 kWh.
 *
 * The i-SMART charge frame reports the pack's energy in kWh next to its state of charge, so one reading gives
 * `energy / (soc / 100)` — exactly how 37.1 was measured on a real Windsor 38 (21.9 kWh at 59 %). A Windsor Pro reports about
 * 52 kWh the same way, which is what a fresh install got wrong: every range, kWh and % figure came from the 38's
 * pack until the owner found the stepper in Settings.
 *
 * Low readings are skipped (the rounding of a whole-percent SoC is too coarse below 20 %), and the median of the
 * last few samples is used so one bad frame can't move the profile. The profile is only rewritten when the learned
 * value differs from it by [CHANGE_KWH] or more, so the number in Settings doesn't jitter by a tenth each read.
 */
object CapacityLearning {
    const val MIN_SOC_PERCENT = 20.0
    const val MIN_SAMPLES = 3
    const val MAX_SAMPLES = 8
    const val CHANGE_KWH = 0.5

    /** One reading's estimate of usable capacity, or null when the reading can't support one. */
    fun sample(socPercent: Double, energyKwh: Double?): Double? {
        if (energyKwh == null || energyKwh < 1.0 || socPercent !in MIN_SOC_PERCENT..100.0) return null
        val kwh = energyKwh / (socPercent / 100.0)
        return kwh.takeIf { it in 10.0..120.0 }
    }

    /** [samples] with [sample] appended, keeping the newest [MAX_SAMPLES]. */
    fun append(samples: List<Double>, sample: Double): List<Double> = (samples + sample).takeLast(MAX_SAMPLES)

    /** The learned capacity (median, to 0.1 kWh) once there are enough samples; null before that. */
    fun learned(samples: List<Double>): Double? {
        if (samples.size < MIN_SAMPLES) return null
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
        return Math.round(median * 10) / 10.0
    }

    /** Whether [learned] is far enough from the profile's [current] capacity to replace it. */
    fun shouldReplace(current: Double, learned: Double): Boolean = abs(learned - current) >= CHANGE_KWH - 1e-9
}
