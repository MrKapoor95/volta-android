package dev.irshadm.volta.core.ismart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which car an account's reads go to, and which MG sign-in answers aren't a wrong password. */
class AccountChoiceTest {
    private fun car(vin: String, shared: Boolean = false) = Vehicle(vin, null, null, null, shared)

    @Test fun `the car picked in Settings wins while it's on the account`() {
        val cars = listOf(car("WINDSOR"), car("TOMAHAWK"))
        assertEquals("TOMAHAWK", MgIsmartClient.pickVin(cars, preferred = "TOMAHAWK", current = "WINDSOR"))
        assertEquals("WINDSOR", MgIsmartClient.pickVin(cars, preferred = "SOLD", current = "WINDSOR"))
    }

    @Test fun `without a choice the first owned car is used, not a shared one`() {
        assertEquals("OWNED", MgIsmartClient.pickVin(listOf(car("SHARED", shared = true), car("OWNED")), null, null))
        assertEquals("SHARED", MgIsmartClient.pickVin(listOf(car("SHARED", shared = true)), null, null))
        assertNull(MgIsmartClient.pickVin(emptyList(), "ANY", "ANY"))
    }

    @Test fun `an unavailable service is not a wrong password`() {
        assertTrue(LoginUnavailableException.matches("The service is not available, please try again later"))
        assertTrue(LoginUnavailableException.matches("Service temporarily unavailable"))
        assertFalse(LoginUnavailableException.matches("Incorrect mobile number or password."))
        assertFalse(LoginUnavailableException.matches(null))
    }
}
