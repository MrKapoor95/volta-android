// Ported to Kotlin from mg-ismart-india-client by John Lazarus (MIT licence):
// https://github.com/john-lazarus/mg-ismart-india-client. Thank you, John: this module would not exist without
// your work on the MG India protocol. The original licence is reproduced in NOTICE.md.

package dev.irshadm.volta.core.ismart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

open class IsmartException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** What to show on screen; the [message] by default. */
    open val userMessage: String? get() = message
}

/** Server rejected the credentials (bad password, unregistered, daily limit). Never retried automatically. */
class LoginRejectedException(message: String) : IsmartException(message)

/** The session was taken (the MG app signed in) or expired; the saved login is still good. */
open class SignedOutException(message: String = "Signed out by another app") : IsmartException(message)

/** More than [MgIsmartClient.MAX_LOGINS_PER_HOUR] logins in an hour: the apps are taking the session from each other. Try again at [retryAtMillis]. */
class LoginThrottledException(val retryAtMillis: Long) : SignedOutException("Signed in too often this hour")

/** A command was sent but its outcome is unknown. Must not be replayed automatically. */
class CommandIndeterminateException(message: String) : IsmartException(message)

/** MG said the control PIN is wrong. Wrong tries count against MG's limit, so it is never retried. */
class WrongPinException(message: String) : IsmartException(message)

/**
 * MG's server answered a status request with a result code we don't know (a Windsor got 15016), with the reason it
 * gave when it sent one. A read failed, nothing was sent to the car.
 */
class ServerResultException(val label: String, val result: Int, val serverMessage: String?) : IsmartException("$label failed: ${serverMessage?.let { "$it (result $result)" } ?: "result $result"}") {
    override val userMessage: String
        get() = serverMessage?.let { "MG: $it (code $result)" }
            ?: "MG's server couldn't get the car's ${label.lowercase()} (code $result). Try again in a minute."
}

class NotOwnerException : IsmartException("This MG account is a shared (secondary) account; battery data needs the owner account")

data class Credentials(val phone: String, val password: String)

/** Persists the session so restarts and background work don't log in again. */
interface SessionStore {
    fun load(): LoginFrame.Session?
    fun save(session: LoginFrame.Session?)
}

class InMemorySessionStore(private var session: LoginFrame.Session? = null) : SessionStore {
    override fun load() = session
    override fun save(session: LoginFrame.Session?) {
        this.session = session
    }
}

/**
 * Kotlin port of `MgIndiaClient` from John Lazarus's mg-ismart-india-client (commit 2b890f2): one session, logins only when
 * the server says the session is invalid, a guard against login tug-of-war, polled status/charge
 * frames, PIN-verified commands.
 */
class MgIsmartClient(
    private val http: OkHttpClient,
    private val credentials: () -> Credentials?,
    private val sessions: SessionStore,
    deviceIdPrefix: String = "volta-android-",
    private val clock: () -> Long = System::currentTimeMillis,
    private val tapLoginUrl: String = "https://iov-tap.mgindia.co.in/TAP.Web/ota.mp",
    private val tapStatusUrl: String = "https://iov-tap.mgindia.co.in/TAP.Web/ota.mpv21",
    private val gatewayBase: String = "https://iov-gateway.mgindia.co.in/api.app/v1",
    private val pollDelayMs: Long = 1_500,
    private val commandDelayMs: Long = 2_000,
) {
    private val prefix = deviceIdPrefix
    private val json = Json { ignoreUnknownKeys = true }
    private val text = "text/plain".toMediaType()
    private val mutex = Mutex()
    private val recentLogins = ArrayDeque<Long>()
    var vin: String? = null
        private set

    private fun nowS() = clock() / 1000

    // ---------------- transport ----------------

    private suspend fun postTap(url: String, body: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).post(body.toRequestBody(text))
            .header("User-Agent", USER_AGENT).header("Accept", "*/*").header("Accept-Language", "en-US;q=1")
            .header("APP-SIGNATURE", MgCrypto.tapSignature(body)).header("SIGNATURE", "1")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IsmartException("MG server error: HTTP ${res.code}")
            res.body.string()
        }
    }

    // ---------------- session ----------------

    /** Logs in explicitly (first sign-in or "Reconnect"). Resets the tug-of-war guard. */
    suspend fun signIn(): LoginFrame.Session = mutex.withLock {
        recentLogins.clear()
        loginLocked()
    }

    fun signOut() {
        sessions.save(null)
        vin = null
    }

    private suspend fun loginLocked(): LoginFrame.Session {
        val creds = credentials() ?: throw IsmartException("Not signed in to MG i-SMART")
        val now = clock()
        while (recentLogins.isNotEmpty() && now - recentLogins.first() > 3_600_000) recentLogins.removeFirst()
        if (recentLogins.size >= MAX_LOGINS_PER_HOUR) throw LoginThrottledException(recentLogins.first() + 3_600_000)
        recentLogins.addLast(now)
        var last: Exception? = null
        repeat(3) { attempt ->
            val body = LoginFrame.build(creds.phone, creds.password, MgCrypto.deviceId(creds.phone, prefix), nowS())
            try {
                when (val r = LoginFrame.decode(postTap(tapLoginUrl, body))) {
                    is LoginFrame.Result.Ok -> {
                        sessions.save(r.session)
                        return r.session
                    }

                    is LoginFrame.Result.Rejected -> throw LoginRejectedException(r.message)
                }
            } catch (e: LoginRejectedException) {
                throw e // each POST counts against the daily quota
            } catch (e: Exception) {
                last = e
                if (attempt < 2) delay(1_500)
            }
        }
        throw IsmartException("Login failed: ${last?.message}", last)
    }

    private suspend fun session(): LoginFrame.Session = sessions.load() ?: loginLocked()

    /** When the login guard lets the next login through, or null if one is allowed now. */
    fun nextLoginAllowedAt(): Long? {
        val now = clock()
        val recent = recentLogins.filter { now - it <= 3_600_000 }
        return if (recent.size >= MAX_LOGINS_PER_HOUR) recent.first() + 3_600_000 else null
    }

    // ---------------- gateway (REST) ----------------

    private suspend fun gatewayGet(path: String, params: Map<String, String> = emptyMap(), retry: Boolean = true): JsonObject {
        val s = session()
        val url = (gatewayBase + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val signingPath = path + (url.encodedQuery?.let { "?$it" } ?: "")
        val ts = clock().toString()
        val ct = "application/json"
        val parsed = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).get()
                .header("User-Agent", USER_AGENT).header("Content-Type", ct).header("APP-CONTENT-ENCRYPTED", "1")
                .header("APP-LANGUAGE-TYPE", "en-us").header("APP-LOGIN-TOKEN", s.token).header("APP-USER-ID", s.uid)
                .header("APP-SEND-DATE", ts).header("APP-VERIFICATION-STRING", MgCrypto.gatewaySignature(signingPath, ts, ct))
                .header("ORIGINAL-CONTENT-TYPE", ct)
                .build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IsmartException("MG gateway error: HTTP ${res.code}")
                val sendDate = res.header("APP-SEND-DATE") ?: ""
                val origType = res.header("ORIGINAL-CONTENT-TYPE") ?: ct
                json.parseToJsonElement(MgCrypto.decryptGatewayBody(res.body.string(), sendDate, origType)).jsonObject
            }
        }
        val code = parsed["code"]?.jsonPrimitive?.intOrNull
        val message = parsed["message"]?.jsonPrimitive?.contentOrNull
        if (code == 7 || (code != null && code != 0 && sessionInvalid(message))) {
            if (!retry) throw IsmartException("MG session stays invalid after login")
            sessions.save(null)
            loginLocked()
            return gatewayGet(path, params, retry = false)
        }
        if (code != null && code != 0) throw IsmartException(message ?: "MG gateway error $code")
        return parsed
    }

    suspend fun vehicles(): List<Vehicle> = mutex.withLock {
        val data = gatewayGet("/vehicle/userVinList")
        fun list(e: JsonElement?): List<JsonObject> = when (e) {
            is JsonArray -> e.mapNotNull { it as? JsonObject }

            is JsonObject -> listOf("list", "vehicleList", "vehicles", "vinList", "result").firstNotNullOfOrNull { (e[it] as? JsonArray) }
                ?.mapNotNull { it as? JsonObject } ?: list(e["data"])

            else -> emptyList()
        }
        val vehicles = list(data).mapNotNull { o ->
            val vin = listOf("vin", "VIN", "vinNo", "vehicleVin").firstNotNullOfOrNull { o[it]?.jsonPrimitive?.contentOrNull } ?: return@mapNotNull null
            Vehicle(
                vin,
                o["series"]?.jsonPrimitive?.contentOrNull,
                o["modelName"]?.jsonPrimitive?.contentOrNull,
                o["colorName"]?.jsonPrimitive?.contentOrNull,
                o["isSubaccount"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        if (vin == null || vehicles.none { it.vin == vin }) vin = vehicles.firstOrNull()?.vin
        vehicles
    }

    // ---------------- status ----------------

    data class Reading(val snapshot: VehicleSnapshot, val charge: ChargeStatus?)

    /** Full status, then (optionally) the charging frame — each polled to completion, as the MG app does. */
    suspend fun read(includeCharge: Boolean = true): Reading {
        if (vin == null) vehicles()
        return mutex.withLock {
            val v = vin ?: throw IsmartException("No vehicle on this MG account")
            val status = poll("Status", v, unavailable = emptySet()) { s, uidTok, ev -> Tap.statusRequest(uidTok.uid, uidTok.token, s, ev, nowS()) }
                ?.let { Tap.decodeStatusApp(it) } ?: throw IsmartException("The car did not answer (it may be in a weak signal area)")
            val charge = if (includeCharge) {
                // Decoded by shape, not by the Windsor's 63 bytes: another model's frame may be longer (a charger ID,
                // extra fields), and dropping it leaves the battery to the status frame. A result code we don't know
                // on this request costs only the charging figures: the status has already arrived.
                try {
                    poll("Charge", v, unavailable = setOf(5), tolerateFraming = true) { s, uidTok, ev -> Tap.chargeRequest(uidTok.uid, uidTok.token, s, ev, nowS()) }
                        ?.let { Tap.decodeChargeAppOrNull(it) }?.let { ChargeStatus.from(it) }
                } catch (ignored: ServerResultException) {
                    null
                }
            } else {
                null
            }
            Reading(VehicleSnapshot.fromStatus(status), charge)
        }
    }

    /** Polls one request until its app payload arrives; null = not available. Re-logs in at most once. */
    private suspend fun poll(
        label: String,
        vin: String,
        unavailable: Set<Int>,
        tolerateFraming: Boolean = false,
        frame: (String, LoginFrame.Session, Long) -> String,
    ): ByteArray? {
        repeat(2) { loginAttempt ->
            var eventId = 0L
            for (attempt in 0 until STATUS_ATTEMPTS) {
                val s = session()
                val res = try {
                    Tap.decodeV21(postTap(tapStatusUrl, frame(vin, s, eventId)))
                } catch (e: TapFormatException) {
                    if (!tolerateFraming) throw IsmartException("$label response could not be read", e)
                    delay(pollDelayMs)
                    continue
                }
                val d = res.dispatcher
                if (d.vin != null && d.vin != vin) throw IsmartException("$label response is for a different vehicle")
                when (val result = d.result ?: 0) {
                    2 -> if (loginAttempt == 0) {
                        sessions.save(null)
                        loginLocked()
                        return@repeat
                    } else {
                        throw IsmartException("$label: session invalid")
                    }

                    in unavailable -> return null

                    0, 4, 6 -> Unit

                    else -> throw ServerResultException(label, result, d.errorText())
                }
                res.app?.let { return it }
                eventId = d.eventId ?: eventId
                if (attempt < STATUS_ATTEMPTS - 1) delay(pollDelayMs)
            }
            return null
        }
        return null
    }

    // ---------------- commands ----------------

    /** Checks [pinHash] with MG, the same check every command starts with, without sending anything to the car. */
    suspend fun checkPin(pinHash: String) {
        if (vin == null) vehicles()
        val v = vin ?: throw IsmartException("No vehicle on this MG account")
        mutex.withLock { verifyPin(v, pinHash) }
    }

    /** Sends [cmd] after PIN verification. Lock/unlock/windows that never report completion are verified from a fresh status. */
    suspend fun command(cmd: RemoteCommand, pinHash: String) {
        if (vin == null) vehicles()
        val v = vin ?: throw IsmartException("No vehicle on this MG account")
        val startedAt = nowS()
        try {
            mutex.withLock {
                verifyPin(v, pinHash)
                sendCommand(v, cmd)
            }
        } catch (e: CommandIndeterminateException) {
            if (e.message?.contains("did not complete") == true) {
                val check: ((VehicleSnapshot) -> Boolean)? = when (cmd) {
                    RemoteCommand.Lock, RemoteCommand.Unlock -> { s -> s.locked == (cmd == RemoteCommand.Lock) }

                    RemoteCommand.WindowsOpen, RemoteCommand.WindowsClose -> { s ->
                        val open = cmd == RemoteCommand.WindowsOpen
                        (s.driverWindowOpen ?: !open) == open && (s.passengerWindowOpen ?: !open) == open
                    }

                    else -> null
                }
                if (check != null) {
                    val snap = runCatching { read(includeCharge = false).snapshot }.getOrNull()
                    if (snap != null && snap.statusTime > startedAt && check(snap)) return
                }
            }
            throw e
        }
    }

    private suspend fun verifyPin(vin: String, pinHash: String) {
        repeat(2) { loginAttempt ->
            val s = session()
            val d = try {
                Tap.decodePinResponse(postTap(tapLoginUrl, Tap.pinRequest(s.uid, s.token, vin, pinHash, nowS())))
            } catch (e: TapFormatException) {
                throw IsmartException("PIN response could not be read", e)
            }
            if (d.vin != null && d.vin != vin) throw IsmartException("PIN response is for a different vehicle")
            when (d.result ?: 0) {
                0 -> return

                2 -> if (loginAttempt == 0) {
                    sessions.save(null)
                    loginLocked()
                } else {
                    throw IsmartException("PIN check: session invalid")
                }

                else -> throw WrongPinException("Wrong control PIN (result ${d.result})")
            }
        }
    }

    private suspend fun sendCommand(vin: String, cmd: RemoteCommand) {
        val s = session()
        val params = cmd.params.map { (id, value) -> id to byteArrayOf(value.toByte()) }
        var eventId = 0L
        for (attempt in 0 until COMMAND_ATTEMPTS) {
            val raw = try {
                postTap(tapStatusUrl, Tap.controlRequest(s.uid, s.token, vin, eventId, cmd.type, params, nowS()))
            } catch (e: Exception) {
                throw CommandIndeterminateException("${cmd.label}: completion is unknown (${e.message})")
            }
            val res = try {
                Tap.decodeV21(raw)
            } catch (e: TapFormatException) {
                throw CommandIndeterminateException("${cmd.label}: response could not be read")
            }
            val result = res.dispatcher.result ?: 0
            if (result !in setOf(0, 4, 6)) throw CommandIndeterminateException("${cmd.label}: completion unknown (result $result)")
            if (res.dispatcher.vin != null && res.dispatcher.vin != vin) throw CommandIndeterminateException("${cmd.label}: response for a different vehicle")
            res.app?.let { Tap.decodeControlApp(it) }?.let { c ->
                if (c.reqType != cmd.type) throw CommandIndeterminateException("${cmd.label}: response type mismatch")
                when (c.reqStatus) {
                    2 -> return
                    1 -> Unit
                    else -> throw IsmartException("${cmd.label} failed (status ${c.reqStatus}${c.failureType?.let { ", reason $it" } ?: ""})")
                }
            }
            eventId = res.dispatcher.eventId ?: throw CommandIndeterminateException("${cmd.label}: completion unknown (no polling cursor)")
            if (attempt < COMMAND_ATTEMPTS - 1) delay(commandDelayMs)
        }
        throw CommandIndeterminateException("${cmd.label} did not complete")
    }

    companion object {
        const val USER_AGENT = "CER_IKE_01/2.3.0 (iPad; iOS 26.3; Scale/2.00)"
        const val STATUS_ATTEMPTS = 10
        const val COMMAND_ATTEMPTS = 15
        const val MAX_LOGINS_PER_HOUR = 3
    }
}

/**
 * The gateway says the session is gone. Usually that's code 7, but it also answers with other codes and the message
 * "Login is invalid, please login again"; both mean the same thing and get one fresh login.
 */
internal fun sessionInvalid(message: String?): Boolean {
    val m = message?.lowercase() ?: return false
    return ("login" in m && "invalid" in m) || "login again" in m || ("token" in m && ("invalid" in m || "expired" in m))
}
