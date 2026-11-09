package dev.irshadm.volta.data.ismart

import dev.irshadm.volta.core.domain.Account
import dev.irshadm.volta.core.domain.AccountRepository
import dev.irshadm.volta.core.domain.SignInError
import dev.irshadm.volta.core.domain.SignInException
import dev.irshadm.volta.core.ismart.IsmartException
import dev.irshadm.volta.core.ismart.LoginRejectedException
import dev.irshadm.volta.core.ismart.LoginThrottledException
import dev.irshadm.volta.core.ismart.LoginUnavailableException
import dev.irshadm.volta.core.ismart.NotOwnerException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The account as the first-run screens see it, on top of [IsmartRepository], so the whole app shares one MG client
 * and one session. A lost connection still counts as signed in: the connection-lost screen handles that.
 */
@Singleton
class IsmartAccount @Inject constructor(private val ismart: IsmartRepository) : AccountRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val account: StateFlow<Account> =
        ismart.link.map(::accountOf).stateIn(scope, SharingStarted.Eagerly, accountOf(ismart.link.value))

    override val rejection: StateFlow<String?> = ismart.rejection

    override suspend fun signIn(phone: String, password: String): Result<Unit> = ismart.signIn(phone, password).recoverCatching { e ->
        // IsmartRepository wraps the client's exception in a friendly message; the original is the cause.
        throw SignInException(signInErrorOf(e.cause ?: e))
    }

    override fun signOut() = ismart.signOut()

    private fun accountOf(link: LinkState): Account = when (link) {
        LinkState.SignedOut -> Account.SignedOut
        is LinkState.Connected -> Account.SignedIn(link.phone)
        is LinkState.NeedsReconnect -> Account.SignedIn(link.phone)
    }
}

/** Maps the client's exceptions to the cases the sign-in screen shows differently. */
internal fun signInErrorOf(e: Throwable): SignInError = when (e) {
    is LoginRejectedException -> SignInError.Rejected(e.message ?: "MG rejected the sign-in")
    is LoginUnavailableException -> SignInError.ServiceUnavailable(e.message ?: "MG's sign-in service is not available")
    is LoginThrottledException -> SignInError.Throttled(e.retryAtMillis)
    is NotOwnerException -> SignInError.NotOwner
    is IOException -> SignInError.Offline
    is IsmartException -> if (e.cause is IOException) SignInError.Offline else SignInError.Other(e.message ?: "MG i-SMART error")
    is IllegalArgumentException -> SignInError.Other(e.message ?: "Check the mobile number")
    else -> SignInError.Other(e.message ?: e.javaClass.simpleName)
}
