package dev.irshadm.volta.data.ismart

import dev.irshadm.volta.core.domain.SignInError
import dev.irshadm.volta.core.ismart.IsmartException
import dev.irshadm.volta.core.ismart.LoginRejectedException
import dev.irshadm.volta.core.ismart.LoginThrottledException
import dev.irshadm.volta.core.ismart.LoginUnavailableException
import dev.irshadm.volta.core.ismart.NotOwnerException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class SignInErrorMappingTest {
    @Test fun `client exceptions map to what the sign-in screen shows`() {
        assertEquals(SignInError.Rejected("Wrong password"), signInErrorOf(LoginRejectedException("Wrong password")))
        assertEquals(
            SignInError.ServiceUnavailable("The service is not available, please try again later"),
            signInErrorOf(LoginUnavailableException("The service is not available, please try again later")),
        )
        assertEquals(SignInError.Throttled(42L), signInErrorOf(LoginThrottledException(42L)))
        assertEquals(SignInError.NotOwner, signInErrorOf(NotOwnerException()))
        assertEquals(SignInError.Offline, signInErrorOf(IOException("timeout")))
        assertEquals(SignInError.Offline, signInErrorOf(IsmartException("Login failed", IOException("dns"))))
        assertEquals(SignInError.Other("MG server error: HTTP 500"), signInErrorOf(IsmartException("MG server error: HTTP 500")))
        assertEquals(SignInError.Other("Use the 10 digit India mobile number"), signInErrorOf(IllegalArgumentException("Use the 10 digit India mobile number")))
    }
}
