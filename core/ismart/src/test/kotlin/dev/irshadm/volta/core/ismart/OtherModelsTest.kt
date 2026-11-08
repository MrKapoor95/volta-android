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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reads from cars that aren't the Windsor the vectors were captured on, and server results we haven't seen. */
class OtherModelsTest {
    private val v: JsonObject = Json.parseToJsonElement(javaClass.getResource("/ismart_vectors.json")!!.readText()).jsonObject
    private fun s(k: String) = v[k]!!.jsonPrimitive.content
    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun client() = MgIsmartClient(
        http = OkHttpClient(),
        credentials = { Credentials("9876543210", "secret-pass") },
        sessions = InMemorySessionStore(LoginFrame.Session(s("uid"), s("token"))),
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

    /** A v2.1 status-app response with [result], an optional server message and app payload. */
    private fun response(result: Int, message: String? = null, app: ByteArray = ByteArray(0)): String {
        val d = Tap.encodeDispatcher(
            Tap.Dispatcher(
                applicationId = Tap.STATUS_APP, vin = s("vin"), messageId = 1, eventCreationTime = 1_790_000_000, eventId = 1,
                appDataLength = app.size, appDataEncoding = 0, appProtocolVersion = Tap.APP_PROTOCOL, result = result,
                errorMessage = message?.toByteArray(),
            ),
        )
        val payload = byteArrayOf(Tap.PROTOCOL_V21.toByte(), (d.size + 3).toByte(), 0) + ByteArray(Tap.RESERVED) + d + app
        return "1" + "%04X".format(payload.size + 3) + payload.hexUpper()
    }

    private fun windsorChargeApp() = Tap.decodeV21(s("chargeResponse")).app!!

    @Test
    fun aLongerChargeFrameStillDecodes() {
        val longer = windsorChargeApp() + ByteArray(12) { 0x55 }
        val f = assertNotNull(Tap.decodeChargeAppOrNull(longer))
        assertEquals(Tap.decodeChargeApp(windsorChargeApp()), f)
    }

    @Test
    fun junkIsNotAChargeFrame() {
        assertNull(Tap.decodeChargeAppOrNull(ByteArray(0)))
        assertNull(Tap.decodeChargeAppOrNull(ByteArray(8))) // runs out of data
        assertNull(Tap.decodeChargeAppOrNull(ByteArray(80))) // decodes, but stamped 1970
    }

    @Test
    fun readKeepsALongerChargeFrame() = runTest {
        server.enqueue(vinList())
        server.enqueue(text(s("statusResponse")))
        server.enqueue(text(response(0, app = windsorChargeApp() + ByteArray(5))))
        val reading = client().read()
        assertEquals(59.0, assertNotNull(reading.charge).socPercent!!, 1e-9)
    }

    @Test
    fun unknownResultOnTheChargeRequestKeepsTheStatus() = runTest {
        server.enqueue(vinList())
        server.enqueue(text(s("statusResponse")))
        server.enqueue(text(response(15016)))
        val reading = client().read()
        assertNull(reading.charge)
        assertTrue(reading.snapshot.locked)
    }

    @Test
    fun unknownResultCarriesMgsReason() = runTest {
        server.enqueue(vinList())
        server.enqueue(text(response(15016, "Vehicle is not online\u0000")))
        val e = assertFailsWith<ServerResultException> { client().read() }
        assertEquals(15016, e.result)
        assertEquals("Vehicle is not online", e.serverMessage)
        assertEquals("Status failed: Vehicle is not online (result 15016)", e.message)
        assertEquals("MG: Vehicle is not online (code 15016)", e.userMessage)
    }

    @Test
    fun unknownResultWithoutReason() = runTest {
        server.enqueue(vinList())
        server.enqueue(text(response(15016)))
        val e = assertFailsWith<ServerResultException> { client().read() }
        assertNull(e.serverMessage)
        assertEquals("Status failed: result 15016", e.message)
        assertEquals("MG's server couldn't get the car's status (code 15016). Try again in a minute.", e.userMessage)
    }
}
