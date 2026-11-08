package dev.irshadm.volta.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapacityLearningTest {
    @Test
    fun oneReadingReproducesTheM0Measurement() {
        // 21.9 kWh at 59 % is how the Windsor 38's 37.1 kWh was measured.
        assertEquals(37.1, CapacityLearning.sample(59.0, 21.9)!!, 0.05)
    }

    @Test
    fun aWindsorProIsLearnedFromItsOwnPack() {
        // The report: a full Windsor Pro showed the 38's 241 km. Its charge frame says ~52 kWh at 100 %.
        val samples = listOf(CapacityLearning.sample(100.0, 52.4)!!, CapacityLearning.sample(97.0, 50.8)!!, CapacityLearning.sample(88.0, 46.1)!!)
        val learned = CapacityLearning.learned(samples)!!
        assertEquals(52.4, learned, 0.05)
        assertTrue(CapacityLearning.shouldReplace(VehicleProfile().usableCapacityKwh, learned))
        assertEquals(52.4 * 7.0, VehicleProfile(usableCapacityKwh = learned).cityRangeKm(100.0), 1e-9)
    }

    @Test
    fun lowOrMissingReadingsGiveNoSample() {
        assertNull(CapacityLearning.sample(12.0, 4.4)) // whole-percent rounding is too coarse this low
        assertNull(CapacityLearning.sample(59.0, null))
        assertNull(CapacityLearning.sample(59.0, 0.0))
        assertNull(CapacityLearning.sample(50.0, 90.0)) // 180 kWh is not a car
    }

    @Test
    fun nothingIsLearnedFromTooFewSamples() {
        assertNull(CapacityLearning.learned(listOf(37.1, 37.0)))
    }

    @Test
    fun oneBadFrameCannotMoveTheMedian() {
        val samples = listOf(37.1, 37.0, 37.2, 55.0)
        assertEquals(37.2, CapacityLearning.learned(samples)!!, 0.01) // median of 37.1 and 37.2, to 0.1 kWh; the 55 is ignored
        assertFalse(CapacityLearning.shouldReplace(37.1, 37.2))
    }

    @Test
    fun onlyTheNewestSamplesAreKept() {
        var s = emptyList<Double>()
        repeat(12) { s = CapacityLearning.append(s, it.toDouble()) }
        assertEquals(CapacityLearning.MAX_SAMPLES, s.size)
        assertEquals(11.0, s.last())
        assertEquals(4.0, s.first())
    }

    @Test
    fun smallDriftDoesNotRewriteTheProfile() {
        assertFalse(CapacityLearning.shouldReplace(37.1, 37.4))
        assertTrue(CapacityLearning.shouldReplace(37.1, 37.6))
    }
}
