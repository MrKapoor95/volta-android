// Ported to Kotlin from mg-ismart-india-client by John Lazarus (MIT licence):
// https://github.com/john-lazarus/mg-ismart-india-client. Thank you, John: this module would not exist without
// your work on the MG India protocol. The original licence is reproduced in NOTICE.md.

package dev.irshadm.volta.core.ismart

/**
 * MG India TAP messages (ported from mg-ismart-india-client@2b890f2, `tap.py`). Every
 * encoder/decoder here mirrors one ASN.1 type field-for-field; golden vectors produced by the
 * Python client pin the exact bytes (see `ismart_vectors.json`).
 */
object Tap {
    const val PROTOCOL_V21 = 33
    const val PROTOCOL_V11 = 17
    const val APP_PROTOCOL = 513
    const val RESERVED = 16
    const val STATUS_APP = "511"
    const val CONTROL_APP = "510"
    const val PIN_APP = "313"
    const val CHARGE_MESSAGE_ID = 8
    const val CHARGE_APP_LEN = 63
    private const val I31 = 2_147_483_647L

    // ---------------- dispatcher (v2.1) ----------------

    data class Dispatcher(
        val uid: String? = null,
        val token: String? = null,
        val applicationId: String,
        val vin: String? = null,
        val messageId: Int,
        val eventCreationTime: Long,
        val eventId: Long? = null,
        val ulCounter: Int? = null,
        val dlCounter: Int? = null,
        val ackCounter: Int? = null,
        val ackRequired: Boolean? = null,
        val appDataLength: Int? = null,
        val appDataEncoding: Int? = null, // 0 = perUnaligned
        val appProtocolVersion: Int? = null,
        val testFlag: Int? = null,
        val result: Int? = null,
        val errorMessage: ByteArray? = null,
    ) {
        /** The server's reason for a non-zero [result], as readable text, or null when it sent none. */
        fun errorText(): String? = errorMessage?.toString(Charsets.UTF_8)
            ?.filter { it == ' ' || !it.isISOControl() }?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
    }

    fun encodeDispatcher(d: Dispatcher): ByteArray {
        val w = BitWriter()
        listOf(
            d.uid, d.token, d.vin, d.eventId, d.ulCounter, d.dlCounter, d.ackCounter, d.ackRequired, d.appDataLength,
            d.appDataEncoding, d.appProtocolVersion, d.testFlag, d.result, d.errorMessage,
        ).forEach { w.bit(it != null) }
        d.uid?.let { w.ia5Fixed(it, 50) }
        d.token?.let { w.ia5Fixed(it, 40) }
        w.ia5Fixed(d.applicationId, 3)
        d.vin?.let { w.ia5Fixed(it, 17) }
        w.int(d.messageId.toLong(), 0, 255)
        w.int(d.eventCreationTime, 0, I31)
        d.eventId?.let { w.int(it, 0, I31) }
        d.ulCounter?.let { w.int(it.toLong(), 0, 65535) }
        d.dlCounter?.let { w.int(it.toLong(), 0, 65535) }
        d.ackCounter?.let { w.int(it.toLong(), 0, 65535) }
        d.ackRequired?.let { w.bit(it) }
        d.appDataLength?.let { w.int(it.toLong(), 0, 65535) }
        d.appDataEncoding?.let { w.int(it.toLong(), 0, 2) }
        d.appProtocolVersion?.let { w.int(it.toLong(), 0, 65535) }
        d.testFlag?.let { w.int(it.toLong(), 1, 3) }
        d.result?.let { w.int(it.toLong(), 0, 65535) }
        d.errorMessage?.let { w.octets(it, 1, 1024) }
        return w.toByteArray()
    }

    fun decodeDispatcher(bytes: ByteArray): Dispatcher {
        val r = BitReader(bytes)
        val p = BooleanArray(14) { r.bit() }
        val uid = if (p[0]) r.ia5Fixed(50) else null
        val token = if (p[1]) r.ia5Fixed(40) else null
        val app = r.ia5Fixed(3)
        val vin = if (p[2]) r.ia5Fixed(17) else null
        val msg = r.int(0, 255).toInt()
        val created = r.int(0, I31)
        return Dispatcher(
            uid, token, app, vin, msg, created,
            eventId = if (p[3]) r.int(0, I31) else null,
            ulCounter = if (p[4]) r.int(0, 65535).toInt() else null,
            dlCounter = if (p[5]) r.int(0, 65535).toInt() else null,
            ackCounter = if (p[6]) r.int(0, 65535).toInt() else null,
            ackRequired = if (p[7]) r.bit() else null,
            appDataLength = if (p[8]) r.int(0, 65535).toInt() else null,
            appDataEncoding = if (p[9]) r.int(0, 2).toInt() else null,
            appProtocolVersion = if (p[10]) r.int(0, 65535).toInt() else null,
            testFlag = if (p[11]) r.int(1, 3).toInt() else null,
            result = if (p[12]) r.int(0, 65535).toInt() else null,
            errorMessage = if (p[13]) r.octets(1, 1024) else null,
        )
    }

    // ---------------- requests ----------------

    private fun requestDispatcher(uid: String, token: String, vin: String, appId: String, app: ByteArray, eventId: Long, nowS: Long, messageId: Int = 1) = encodeDispatcher(
        Dispatcher(
            uid = uid, token = token, applicationId = appId, vin = vin, messageId = messageId, eventCreationTime = nowS,
            eventId = eventId, ulCounter = 0, dlCounter = 0, ackCounter = 0, ackRequired = false, appDataLength = app.size,
            appDataEncoding = 0, appProtocolVersion = APP_PROTOCOL, testFlag = 2, result = 0,
        ),
    )

    private fun frameV21(dispatcher: ByteArray, app: ByteArray): String {
        val dLen = dispatcher.size + 3
        if (dLen > 255) throw TapFormatException("TAP dispatcher too large")
        val payload = byteArrayOf(PROTOCOL_V21.toByte(), dLen.toByte(), 0) + ByteArray(RESERVED) + dispatcher + app
        return "1" + "%04X".format(payload.size + 3) + payload.hexUpper()
    }

    fun statusRequest(uid: String, token: String, vin: String, eventId: Long, nowS: Long): String {
        val app = BitWriter().int(2, 0, 255).toByteArray() // OTARVMVehicleStatusReq { vehStatusReqType 2 }
        return frameV21(requestDispatcher(uid, token, vin, STATUS_APP, app, eventId, nowS), app)
    }

    fun chargeRequest(uid: String, token: String, vin: String, eventId: Long, nowS: Long): String = frameV21(requestDispatcher(uid, token, vin, STATUS_APP, ByteArray(0), eventId, nowS, CHARGE_MESSAGE_ID), ByteArray(0))

    /** OTARVCReq: remote command [type] with (paramId, value) pairs. */
    fun controlRequest(uid: String, token: String, vin: String, eventId: Long, type: Int, params: List<Pair<Int, ByteArray>>, nowS: Long): String {
        val w = BitWriter().bit(params.isNotEmpty()).octetsFixed(byteArrayOf(type.toByte()))
        if (params.isNotEmpty()) {
            w.int(params.size.toLong(), 1, 10)
            params.forEach { (id, value) -> w.int(id.toLong(), 0, 65535).octets(value, 1, 255) }
        }
        val app = w.toByteArray()
        return frameV21(requestDispatcher(uid, token, vin, CONTROL_APP, app, eventId, nowS), app)
    }

    fun pinRequest(uid: String, token: String, vin: String, pinHash: String, nowS: Long): String {
        val app = BitWriter().ia5Fixed(pinHash, 32).toByteArray()
        val body = encodeDispatcherV11(
            DispatcherV11(
                uid = uid, token = token, applicationId = PIN_APP, vin = vin, eventCreationTime = nowS, messageId = 1,
                messageCounter = 1 to 0, simInfo = "1234567890987654321", iccId = "12345678901234567890",
                appDataLength = app.size.toLong(), appDataEncoding = 0, appProtocolVersion = APP_PROTOCOL, testFlag = 2,
            ),
        )
        val dLen = body.size + 4
        if (dLen > 255) throw TapFormatException("TAP PIN dispatcher too large")
        val payload = byteArrayOf(PROTOCOL_V11.toByte(), 0, dLen.toByte(), 0) + body + app
        return "%04X".format(payload.size * 2 + 5) + "1" + payload.hexUpper()
    }

    // ---------------- dispatcher (v1.1, login and PIN) ----------------

    data class DispatcherV11(
        val uid: String? = null,
        val token: String? = null,
        val applicationId: String,
        val vin: String? = null,
        val eventCreationTime: Long,
        val eventId: Long? = null,
        val messageId: Int,
        val messageCounter: Pair<Int, Int>? = null,
        val simInfo: String? = null,
        val iccId: String,
        val appDataLength: Long,
        val appDataEncoding: Int? = null,
        val appProtocolVersion: Int,
        val testFlag: Int? = null,
        val result: Int? = null,
        val errorMessage: ByteArray? = null,
    )

    fun encodeDispatcherV11(d: DispatcherV11): ByteArray {
        val w = BitWriter()
        // OPTIONAL bitmap: uid token vin eventID messageCounter ackRequired stateless crqm basicPosition
        // networkInfo simInfo hmiLanguage applicationDataEncoding testFlag result errorMessage
        listOf(
            d.uid != null, d.token != null, d.vin != null, d.eventId != null, d.messageCounter != null, false, false, false,
            false, false, d.simInfo != null, false, d.appDataEncoding != null, d.testFlag != null, d.result != null, d.errorMessage != null,
        )
            .forEach { w.bit(it) }
        d.uid?.let { w.ia5Fixed(it, 50) }
        d.token?.let { w.ia5Fixed(it, 40) }
        w.ia5Fixed(d.applicationId, 3)
        d.vin?.let { w.ia5Fixed(it, 17) }
        w.int(d.eventCreationTime, 0, 4_294_967_295L)
        d.eventId?.let { w.int(it, 0, 281_474_976_710_655L) }
        w.int(d.messageId.toLong(), 0, 255)
        d.messageCounter?.let { (up, down) -> w.int(up.toLong(), 0, 255).int(down.toLong(), 0, 255) }
        d.simInfo?.let { w.numericFixed(it, 19) }
        w.numericFixed(d.iccId, 20)
        w.int(d.appDataLength, 0, 4_294_967_295L)
        d.appDataEncoding?.let { w.int(it.toLong(), 0, 2) }
        w.int(d.appProtocolVersion.toLong(), 0, 65535)
        d.testFlag?.let { w.int(it.toLong(), 1, 3) }
        d.result?.let { w.int(it.toLong(), 0, 65535) }
        d.errorMessage?.let { w.octets(it, 1, 1024) }
        return w.toByteArray()
    }

    fun decodeDispatcherV11(bytes: ByteArray): DispatcherV11 {
        val r = BitReader(bytes)
        val p = BooleanArray(16) { r.bit() }
        val uid = if (p[0]) r.ia5Fixed(50) else null
        val token = if (p[1]) r.ia5Fixed(40) else null
        val app = r.ia5Fixed(3)
        val vin = if (p[2]) r.ia5Fixed(17) else null
        val created = r.int(0, 4_294_967_295L)
        val eventId = if (p[3]) r.int(0, 281_474_976_710_655L) else null
        val msg = r.int(0, 255).toInt()
        val counter = if (p[4]) r.int(0, 255).toInt() to r.int(0, 255).toInt() else null
        if (p[5]) r.bit()
        if (p[6]) r.bit()
        if (p[7]) r.bit()
        if (p[8]) {
            r.int(-90_000_000, 90_000_000)
            r.int(-180_000_000, 180_000_000)
        }
        if (p[9]) {
            repeat(4) { r.numericFixed(3) }
            r.int(0, 99)
        }
        val sim = if (p[10]) r.numericFixed(19) else null
        if (p[11]) r.int(0, 4)
        val icc = r.numericFixed(20)
        val len = r.int(0, 4_294_967_295L)
        val enc = if (p[12]) r.int(0, 2).toInt() else null
        val proto = r.int(0, 65535).toInt()
        val test = if (p[13]) r.int(1, 3).toInt() else null
        val result = if (p[14]) r.int(0, 65535).toInt() else null
        val err = if (p[15]) r.octets(1, 1024) else null
        return DispatcherV11(uid, token, app, vin, created, eventId, msg, counter, sim, icc, len, enc, proto, test, result, err)
    }

    // ---------------- responses ----------------

    data class V21Response(val dispatcher: Dispatcher, val app: ByteArray?)

    fun decodeV21(raw: String): V21Response {
        if (raw.length < 5 || raw[0] != '1') throw TapFormatException("unexpected TAP v2.1 response framing")
        val data = raw.substring(5).hexToBytes()
        if (data.size < 19) throw TapFormatException("short TAP v2.1 response")
        if (data[0].toInt() != PROTOCOL_V21) throw TapFormatException("unsupported TAP response protocol")
        val dLen = data[1].toInt() and 0xff
        val dEnd = RESERVED + dLen
        if (dLen < 3 || dEnd > data.size) throw TapFormatException("invalid TAP dispatcher length")
        val d = decodeDispatcher(data.copyOfRange(19, dEnd))
        if (d.appDataEncoding != null && d.appDataEncoding != 0) throw TapFormatException("unsupported TAP app encoding")
        if (d.appProtocolVersion != null && d.appProtocolVersion != APP_PROTOCOL) throw TapFormatException("unsupported TAP app protocol")
        if ((d.result ?: 0) !in setOf(0, 4, 6)) return V21Response(d, null)
        val appLen = d.appDataLength ?: 0
        if (appLen == 0) return V21Response(d, null)
        if (dEnd + appLen > data.size) throw TapFormatException("truncated TAP app data")
        return V21Response(d, data.copyOfRange(dEnd, dEnd + appLen))
    }

    data class DispatcherV11Response(val dispatcher: DispatcherV11)

    fun decodePinResponse(raw: String): DispatcherV11 {
        val payload = (if (raw.length >= 5 && raw[4] == '1') raw.substring(5) else raw).hexToBytes()
        if (payload.size < 4) throw TapFormatException("short TAP PIN header")
        val dLen = (payload[2].toInt() and 0xff) + ((payload[3].toInt() and 0xff) shl 8)
        if (payload[0].toInt() != PROTOCOL_V11 || dLen !in 5..payload.size) throw TapFormatException("invalid TAP PIN dispatcher header")
        val d = decodeDispatcherV11(payload.copyOfRange(4, dLen))
        if (d.applicationId != PIN_APP) throw TapFormatException("unexpected PIN application identity")
        return d
    }

    // ---------------- vehicle status payloads ----------------

    data class Position(
        val latitudeMicro: Long,
        val longitudeMicro: Long,
        val altitudeM: Int,
        val heading: Int,
        val speedTenths: Int,
        val hdop: Int,
        val satellites: Int,
        val timeSeconds: Long,
        val gpsStatus: Int,
    )

    private fun readPosition(r: BitReader) = Position(
        latitudeMicro = r.int(-90_000_000, 90_000_000),
        longitudeMicro = r.int(-180_000_000, 180_000_000),
        altitudeM = r.int(-100, 8900).toInt(),
        heading = r.int(0, 359).toInt(),
        speedTenths = r.int(-1000, 4500).toInt(),
        hdop = r.int(0, 1000).toInt(),
        satellites = r.int(0, 16).toInt(),
        timeSeconds = r.int(0, I31),
        gpsStatus = r.int(0, 3).toInt(),
    )

    /** RvsBasicStatus513 — raw protocol units. */
    data class BasicStatus(
        val driverDoor: Boolean,
        val passengerDoor: Boolean,
        val rearLeftDoor: Boolean,
        val rearRightDoor: Boolean,
        val boot: Boolean,
        val bonnet: Boolean,
        val locked: Boolean,
        val driverWindow: Boolean?,
        val passengerWindow: Boolean?,
        val rearLeftWindow: Boolean?,
        val rearRightWindow: Boolean?,
        val sunroof: Boolean?,
        val tyreFrontRight: Int?,
        val tyreFrontLeft: Int?,
        val tyreRearRight: Int?,
        val tyreRearLeft: Int?,
        val tyreMonitor: Int?,
        val sideLights: Boolean,
        val dippedBeam: Boolean,
        val mainBeam: Boolean,
        val alarm: Int?,
        val engineStatus: Int,
        val powerMode: Int,
        val lastKeySeen: Int,
        val journeyDistance: Int,
        val journeyId: Long,
        val interiorTemp: Int,
        val exteriorTemp: Int,
        val fuelLevelPrc: Int,
        val fuelRange: Int,
        val remoteClimate: Int,
        val seatHeatLeft: Int?,
        val seatHeatRight: Int?,
        val canBusActive: Boolean,
        val lastCanActivity: Long,
        val clusterFuelSegment: Int,
        val mileage: Long,
        val batteryVoltage: Int,
        val ext1: Long?,
        val ext2: Long?,
        val handBrake: Boolean,
    )

    private fun readBasic(r: BitReader): BasicStatus {
        val p = BooleanArray(15) { r.bit() }
        fun ob(i: Int) = if (p[i]) r.bit() else null
        fun oi(i: Int) = if (p[i]) r.int(0, 255).toInt() else null
        val dd = r.bit()
        val pd = r.bit()
        val rl = r.bit()
        val rr = r.bit()
        val bt = r.bit()
        val bn = r.bit()
        val lk = r.bit()
        val dw = ob(0)
        val pw = ob(1)
        val rlw = ob(2)
        val rrw = ob(3)
        val sr = ob(4)
        val tfr = oi(5)
        val tfl = oi(6)
        val trr = oi(7)
        val trl = oi(8)
        val tm = oi(9)
        val side = r.bit()
        val dip = r.bit()
        val main = r.bit()
        val alarm = oi(10)
        val engine = r.int(0, 255).toInt()
        val power = r.int(0, 255).toInt()
        val key = r.int(0, 65535).toInt()
        val jd = r.int(0, 65535).toInt()
        val jid = r.int(0, I31)
        val it = r.int(-128, 127).toInt()
        val et = r.int(-128, 127).toInt()
        val fuel = r.int(0, 255).toInt()
        val range = r.int(0, 65535).toInt()
        val climate = r.int(0, 255).toInt()
        val shl = oi(11)
        val shr = oi(12)
        val can = r.bit()
        val canT = r.int(0, I31)
        val seg = r.int(0, 255).toInt()
        val mileage = r.int(0, I31)
        val volt = r.int(0, 65535).toInt()
        val e1 = if (p[13]) r.int(0, I31) else null
        val e2 = if (p[14]) r.int(0, I31) else null
        val hb = r.bit()
        return BasicStatus(
            dd, pd, rl, rr, bt, bn, lk, dw, pw, rlw, rrw, sr, tfr, tfl, trr, trl, tm, side, dip, main, alarm,
            engine, power, key, jd, jid, it, et, fuel, range, climate, shl, shr, can, canT, seg, mileage, volt, e1, e2, hb,
        )
    }

    data class StatusFrame(val statusTime: Long, val position: Position, val basic: BasicStatus, val alerts: List<Pair<Int, Int>>)

    fun decodeStatusApp(app: ByteArray): StatusFrame {
        val r = BitReader(app)
        val hasExt = r.bit()
        val time = r.int(0, I31)
        val pos = readPosition(r)
        val basic = readBasic(r)
        val alerts = if (hasExt) List(r.int(0, 64).toInt()) { r.int(0, 255).toInt() to r.int(0, 255).toInt() } else emptyList()
        return StatusFrame(time, pos, basic, alerts)
    }

    data class ControlFrame(val reqType: Int, val reqStatus: Int, val failureType: Int?)

    fun decodeControlApp(app: ByteArray): ControlFrame {
        val r = BitReader(app)
        val hasFailure = r.bit()
        val type = r.octetsFixed(1)[0].toInt() and 0xff
        val sts = r.octetsFixed(1)[0].toInt() and 0xff
        val failure = if (hasFailure) r.int(0, 255).toInt() else null
        return ControlFrame(type, sts, failure)
    }

    /** RvsChargingStatus — raw protocol units (see [ChargeStatus] for scaled values). */
    data class ChargeFrame(
        val statusTime: Long,
        val realtimePower: Int,
        val powerLevelPrc: Int?,
        val charging: Boolean,
        val gunConnected: Boolean,
        val fuelRange: Int,
        val chargingType: Int,
        val timeLevelPrc: Int?,
        val startTime: Long?,
        val endTime: Long?,
        val current: Int,
        val voltage: Int,
        val pileId: String?,
        val pileSupplier: String?,
        val workingCurrent: Int?,
        val workingVoltage: Int?,
        val mileageSinceCharge: Int?,
        val powerUsageSinceCharge: Int?,
        val mileageOfDay: Int?,
        val powerUsageOfDay: Int?,
        val staticConsumption: Int?,
        val phase: Int?,
        val duration: Long?,
        val lastChargeEndingPower: Int?,
        val totalCapacity: Int?,
        val fotaLowestVoltage: Int?,
        val mileage: Long,
    )

    /**
     * The charging frame from any model, or null when [app] isn't one. The Windsor's frame is 63 bytes, but the
     * length moves with the optional fields (a charger ID string, extra counters), so it is checked by decoding:
     * it must decode without running out of data and carry a believable time and battery level.
     */
    fun decodeChargeAppOrNull(app: ByteArray): ChargeFrame? = app.takeIf { it.isNotEmpty() }
        ?.let { runCatching { decodeChargeApp(it) }.getOrNull() }
        ?.takeIf { it.statusTime >= MIN_PLAUSIBLE_TIME_S && (it.powerLevelPrc ?: 0) <= 1000 }

    /** 2020-01-01: a charging frame stamped earlier than this was not decoded right. */
    private const val MIN_PLAUSIBLE_TIME_S = 1_577_836_800L

    fun decodeChargeApp(app: ByteArray): ChargeFrame {
        val r = BitReader(app)
        val time = r.int(0, I31)
        readPosition(r)
        val p = BooleanArray(22) { r.bit() }
        fun o16(i: Int) = if (p[i]) r.int(0, 65535).toInt() else null
        fun o31(i: Int) = if (p[i]) r.int(0, I31) else null
        val rp = r.int(0, 65535).toInt()
        val level = o16(0)
        val st = r.bit()
        val gun = r.bit()
        val range = r.int(0, 65535).toInt()
        val type = r.int(0, 255).toInt()
        val tl = o16(1)
        val start = o31(2)
        val end = o31(3)
        val cur = r.int(0, 65535).toInt()
        val volt = r.int(0, 65535).toInt()
        val pile = if (p[4]) r.ia5(0, 64) else null
        val sup = if (p[5]) r.ia5(0, 64) else null
        val wc = o16(6)
        val wv = o16(7)
        val ms = o16(8)
        val pu = o16(9)
        val md = o16(10)
        val pd = o16(11)
        val sec = o16(12)
        val phase = if (p[13]) r.int(0, 255).toInt() else null
        val dur = o31(14)
        val lce = o16(15)
        val cap = o16(16)
        val fota = if (p[17]) r.int(0, 255).toInt() else null
        val mileage = r.int(0, I31)
        // extendedData1..4 follow; not used.
        return ChargeFrame(
            time, rp, level, st, gun, range, type, tl, start, end, cur, volt, pile, sup, wc, wv, ms, pu, md, pd, sec,
            phase, dur, lce, cap, fota, mileage,
        )
    }
}
