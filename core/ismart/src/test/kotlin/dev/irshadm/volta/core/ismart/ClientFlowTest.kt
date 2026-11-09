package dev.irshadm.volta.core.ismart

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClientFlowTest {
    private val v: JsonObject = Json.parseToJsonElement(javaClass.getResource("/ismart_vectors.json")!!.readText()).jsonObject
    private fun s(k: String) = v[k]!!.jsonPrimitive.content
    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun client(store: SessionStore, creds: Credentials? = Credentials("9876543210", "secret-pass")) = MgIsmartClient(
        http = OkHttpClient(),
        credentials = { creds },
        sessions = store,
        tapLoginUrl = server.url("/login").toString(),
        tapStatusUrl = server.url("/status").toString(),
        gatewayBase = server.url("/gw").toString().trimEnd('/'),
        pollDelayMs = 1,
        commandDelayMs = 1,
    )

    private fun text(body: String) = MockResponse.Builder().body(body).build()

    private fun vinList(): MockResponse {
        val g = v["gateway"]!!.jsonObject
        return MockResponse.Builder().body(g["cipher"]!!.jsonPrimitive.content)
            .addHeader("APP-SEND-DATE", g["timestamp"]!!.jsonPrimitive.content)
            .addHeader("ORIGINAL-CONTENT-TYPE", "application/json").build()
    }

    private val session = LoginFrame.Session(s("uid"), s("token"))

    @Test
    fun readPollsUntilStatusThenFetchesCharge() = runTest {
        server.enqueue(vinList())
        server.enqueue(text(s("pendingResponse"))) // car waking up
        server.enqueue(text(s("statusResponse")))
        server.enqueue(text(s("chargeResponse")))
        val c = client(InMemorySessionStore(session))
        val reading = c.read()
        assertEquals(s("vin"), c.vin)
        assertNotNull(reading.charge)
        assertEquals(59.0, reading.charge!!.socPercent!!, 1e-9)
        assertTrue(reading.snapshot.locked)
        server.takeRequest() // vin list
        server.takeRequest() // first status poll
        val second = server.takeRequest()
        assertTrue(second.body!!.utf8().contains("0000000000")) // still a v2.1 frame
        assertTrue(second.headers["APP-SIGNATURE"]!!.isNotBlank())
    }

    @Test
    fun lockCommandVerifiesPinThenWaitsForCompletion() = runTest {
        server.enqueue(vinList())
        server.enqueue(text(s("pinOkResponse")))
        server.enqueue(text(s("pendingResponse").replaceFirst("1", "1"))) // pending (app id 511 is ignored for commands)
        server.enqueue(text(s("controlDoneResponse")))
        client(InMemorySessionStore(session)).command(RemoteCommand.Lock, s("pinHash"))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun repeatedLoginsTripTheSignedOutGuard() = runTest {
        val store = InMemorySessionStore(null)
        val c = client(store)
        // The server keeps answering garbage, so every read needs a fresh login attempt.
        repeat(20) { server.enqueue(text("0000")) }
        repeat(MgIsmartClient.MAX_LOGINS_PER_HOUR) { assertFailsWith<IsmartException> { c.read() } }
        assertFailsWith<SignedOutException> { c.read() }
    }

    @Test
    fun anUnreadableLoginAnswerIsNotRetried() = runTest {
        // MG received the login (it answered), so it may have counted it: a second POST would spend another attempt.
        server.enqueue(text("0000"))
        assertFailsWith<IsmartException> { client(InMemorySessionStore(null)).signIn() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aLoginThatNeverConnectedIsRetried() = runTest {
        val closed = MockWebServer().apply { start() }
        val deadUrl = closed.url("/login").toString()
        closed.close() // nothing listens there now: connection refused, the login never reached MG
        val c = MgIsmartClient(
            http = OkHttpClient(),
            credentials = { Credentials("9876543210", "secret-pass") },
            sessions = InMemorySessionStore(null),
            tapLoginUrl = deadUrl,
        )
        val e = assertFailsWith<IsmartException> { c.signIn() }
        assertTrue(e.message!!.startsWith("Login failed"))
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is java.net.ConnectException })
    }

    @Test
    fun noCredentialsMeansNotSignedIn() = runTest {
        val e = assertFailsWith<IsmartException> { client(InMemorySessionStore(null), creds = null).read() }
        assertTrue(e.message!!.contains("Not signed in"))
    }
}
