package dev.irshadm.volta.core.domain

import kotlinx.coroutines.flow.StateFlow

/** The MG i-SMART account on this phone. */
sealed interface Account {
    data object SignedOut : Account

    /** [phone] is the 10-digit mobile number, without +91. */
    data class SignedIn(val phone: String) : Account
}

/** Why a sign-in didn't go through. Each case needs a different message and a different next step. */
sealed interface SignInError {
    /** MG said no (wrong password, unregistered number, daily limit). Never retried automatically. */
    data class Rejected(val message: String) : SignInError

    /**
     * MG's sign-in service turned the attempt away without judging the password ("service is not available"): an
     * MG outage, or a car on i-SMART 3.0 (Hector Tomahawk EV) that this sign-in doesn't serve. The password is kept.
     */
    data class ServiceUnavailable(val message: String) : SignInError

    /** Too many sign-ins this hour; the next one is allowed at [retryAtMillis]. */
    data class Throttled(val retryAtMillis: Long) : SignInError

    /** The account is a shared (secondary) one, which gets no battery data. */
    data object NotOwner : SignInError

    /** MG's servers couldn't be reached. */
    data object Offline : SignInError
    data class Other(val message: String) : SignInError
}

interface AccountRepository {
    val account: StateFlow<Account>

    /** MG's message when it rejected a saved password on a reconnect; the sign-in screen shows it once. */
    val rejection: StateFlow<String?>

    /** Signs in and keeps the login (encrypted) only when MG accepts it. */
    suspend fun signIn(phone: String, password: String): Result<Unit>

    fun signOut()
}

/** Thrown inside a failed [Result] from [AccountRepository.signIn]. */
class SignInException(val error: SignInError) : Exception(error.toString())
