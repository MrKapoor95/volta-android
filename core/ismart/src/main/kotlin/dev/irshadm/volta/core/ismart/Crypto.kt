// Ported to Kotlin from mg-ismart-india-client by John Lazarus (MIT licence):
// https://github.com/john-lazarus/mg-ismart-india-client. Thank you, John: this module would not exist without
// your work on the MG India protocol. The original licence is reproduced in NOTICE.md.

package dev.irshadm.volta.core.ismart

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Request signing and response decryption used by the MG India app (ported from `crypto.py`). */
object MgCrypto {
    fun md5Hex(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun hmacSha256Hex(key: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun tapSignature(body: String): String = hmacSha256Hex(md5Hex(body.substring(1, body.length / 2)), body)

    fun gatewaySignature(path: String, timestamp: String, contentType: String = "application/json"): String {
        val part1 = md5Hex(path)
        val part2 = md5Hex(part1 + timestamp + "1" + contentType)
        val key = md5Hex(part2 + timestamp)
        return hmacSha256Hex(key, path + timestamp + "1" + contentType)
    }

    fun decryptGatewayBody(cipherHex: String, timestamp: String, contentType: String): String {
        val key = md5Hex(timestamp + "1" + contentType).hexToBytes()
        val iv = md5Hex(timestamp).hexToBytes()
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(c.doFinal(cipherHex.trim().hexToBytes()))
    }

    /**
     * The hash MG's protocol expects: MD5 of the PIN plus "00". A 4-digit PIN has only 10,000 values, so this hash is
     * as good as the PIN itself: anyone holding it can find the PIN instantly. Store it only encrypted (SecureStore).
     */
    fun pinHash(pin: String): String {
        require(Regex("\\d{4}").matches(pin)) { "Control PIN must be exactly 4 digits" }
        return md5Hex(pin + "00").uppercase()
    }

    fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }.takeLast(10)
        require(digits.length == 10) { "Use the 10 digit India mobile number" }
        return digits
    }

    /** Stable per-install device id, padded to the 103 characters the app sends. */
    fun deviceId(phone: String, prefix: String): String {
        val seed = MessageDigest.getInstance("SHA-256").digest("mg-ismart-india:${normalizePhone(phone)}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return ("$prefix$seed" + "0".repeat(120)).take(103)
    }
}

/** Fixed-layout login frame (ported from `client.py` `_build_login_body` / `decode_login_response`). */
object LoginFrame {
    private const val DISPATCHER_TEMPLATE =
        "11005600882c60c183060c183060c183060c183060c183060c183060c183060c183060c183" +
            "060c183060c183060c183060c1ab06200000000020200468acf134468acf1342468acf134" +
            "2468acf1342000000000100a0"

    /** The app trims passwords to 16 characters; sending more fails as "incorrect password". */
    const val PASSWORD_MAX_LEN = 16

    fun build(phone: String, password: String, deviceId: String, nowS: Long): String {
        val dispatcher = DISPATCHER_TEMPLATE.hexToBytes()
        val app = BitWriter().bits(1, 1).ia5(password.take(PASSWORD_MAX_LEN), 6, 30).ia5(deviceId, 1, 200).toByteArray()
        FixedBits.set7bit(dispatcher, 48, MgCrypto.normalizePhone(phone).padStart(50, '0'))
        FixedBits.set(dispatcher, 419, 32, nowS)
        val n = dispatcher.size
        val len = (app.size * 2).toLong()
        for (i in 0 until 4) dispatcher[n - 7 + i] = (len ushr (8 * (3 - i))).toByte()
        dispatcher[n - 3] = 1
        dispatcher[n - 2] = 0
        dispatcher[n - 1] = 160.toByte()
        val raw = "1" + (dispatcher + app).hexUpper()
        return "%04X".format(raw.length + 4) + raw
    }

    data class Session(val uid: String, val token: String)

    sealed interface Result {
        data class Ok(val session: Session) : Result

        /** Definitive server verdict (bad password, unregistered, daily limit). Never retry automatically. */
        data class Rejected(val message: String) : Result
    }

    fun decode(raw: String): Result {
        if (raw.length < 5 || raw[4] != '1') throw TapFormatException("Unexpected TAP login response framing")
        val payload = raw.substring(5).hexToBytes()
        if (payload.size < 4) throw TapFormatException("TAP login response is too short")
        val dLen = (payload[2].toInt() and 0xff) + ((payload[3].toInt() and 0xff) shl 8)
        if (dLen !in 4..payload.size) throw TapFormatException("Invalid TAP login dispatcher length")
        runCatching { Tap.decodeDispatcherV11(payload.copyOfRange(4, dLen)) }.getOrNull()?.let { d ->
            val result = d.result ?: 0
            if (result != 0) {
                val msg = d.errorMessage?.let { String(it).trim() }?.takeIf { it.isNotEmpty() } ?: "result $result"
                return Result.Rejected(msg)
            }
        }
        val dispatcher = payload.copyOfRange(0, dLen)
        val app = payload.copyOfRange(dLen, payload.size)
        if (dispatcher.size < 50 || app.size * 8 < 6 + 40 * 7 * 2) {
            return Result.Rejected("Login rejected (check phone/password, or the account may not be allowed to use this app)")
        }
        val uid = FixedBits.read7bit(dispatcher, 300, 14).padStart(50, '0')
        val r = BitReader(app)
        r.bits(6)
        val token = r.ia5Fixed(40)
        val refresh = r.ia5Fixed(40)
        if (token != refresh) throw TapFormatException("Login token and refresh token differ")
        return Result.Ok(Session(uid, token))
    }
}
