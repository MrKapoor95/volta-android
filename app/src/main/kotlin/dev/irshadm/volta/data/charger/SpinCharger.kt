package dev.irshadm.volta.data.charger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts and stops an Exicom Spin home charger over Bluetooth, the same way the Spin app does (worked out from
 * its code; Exicom publishes nothing):
 *  - Bluetooth Low Energy, no Android pairing: the charger's Microchip serial service, one characteristic used
 *    both to send commands and to receive replies;
 *  - after connecting (and a 512-byte MTU), `10AC520000000000` (board version) then `10AC320000000000` asks for
 *    the session password; the reply, often ASCII text, starts `10AC3200` and carries the password from character 10;
 *  - start = `10AC3C0101` + password, stop = `10AC3C0110` + password, hex text sent as bytes (confirmed from the
 *    Spin app's own log at the charger).
 * Volta connects only while the dashboard is open and the car is plugged in, and the start/stop button shows
 * only while connected. One connection at a time: if the Spin app holds it, Volta simply doesn't connect.
 */
@Singleton
class SpinCharger @Inject constructor(@ApplicationContext private val context: Context) {

    /** A device found in the one-time setup scan; [likely] when it looks like a Spin charger. */
    data class Found(val name: String, val address: String, val rssi: Int, val likely: Boolean = false)

    sealed interface Link {
        data object Off : Link
        data object Connecting : Link
        data object Ready : Link
        data class Sending(val start: Boolean) : Link
        data class Failed(val message: String) : Link
    }

    private val prefs = context.getSharedPreferences("spin_charger", Context.MODE_PRIVATE)
    private val _link = MutableStateFlow<Link>(Link.Off)
    val link: StateFlow<Link> = _link
    private val _status = MutableStateFlow<String?>(null)

    /** How the last connection attempt went, in words (shown under the charger in Settings). */
    val status: StateFlow<String?> = _status

    private fun note(msg: String) {
        _status.value = msg
        log(msg)
    }

    /**
     * Steps and frames to logcat (tag VoltaSpin) for troubleshooting, in debug builds only. The charger's session
     * password travels inside the frames, so it is masked even there: logcat is readable over adb and in bug reports.
     */
    private fun log(line: String) {
        if (!dev.irshadm.volta.BuildConfig.DEBUG) return
        val pw = password
        val safe = when {
            hideRx && line.startsWith("RX") -> "RX <password reply hidden>"
            pw.isNullOrEmpty() -> line
            else -> line.replace(pw, "<password>", ignoreCase = true)
        }
        android.util.Log.d("VoltaSpin", safe)
    }
    private val _saved = MutableStateFlow(savedCharger())

    /** The home charger chosen in setup: (name, address), or null. */
    val saved: StateFlow<Pair<String, String>?> = _saved

    private var gatt: BluetoothGatt? = null
    private var channel: BluetoothGattCharacteristic? = null
    private var password: String? = null

    /** True while the password request is out, so its reply never reaches logcat. */
    @Volatile private var hideRx = false
    private var reply: CompletableDeferred<String>? = null

    /** Reply pieces since the last command (a long reply can arrive in several notifications). */
    private val rx = StringBuilder()
    private var firstPiece: String? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var missingService = false
    private val mutex = Mutex()

    private val manager get() = context.getSystemService(BluetoothManager::class.java)

    fun hasPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all { granted(it) }
    } else {
        granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun savedCharger(): Pair<String, String>? {
        val a = prefs.getString("address", null) ?: return null
        return (prefs.getString("name", null) ?: "Spin charger") to a
    }

    fun save(found: Found) {
        prefs.edit().putString("name", found.name).putString("address", found.address).apply()
        _saved.value = found.name to found.address
    }

    fun forget() {
        disconnect()
        prefs.edit().clear().apply()
        _saved.value = null
    }

    /**
     * Setup: named Bluetooth devices nearby, likely chargers first, then strongest signal. The charger doesn't
     * advertise its service, and its Bluetooth name is its serial number (that's how the Spin app matches it),
     * so the list shows names and you pick the one matching the serial in the Spin app.
     */
    @SuppressLint("MissingPermission")
    suspend fun scan(seconds: Long = 10): List<Found> {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return emptyList()
        if (!hasPermission()) return emptyList()
        val found = LinkedHashMap<String, Found>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (name.isBlank()) return
                val advertisesSerial = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE } == true
                found[result.device.address] = Found(name, result.device.address, result.rssi, advertisesSerial || looksLikeCharger(name))
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        delay(seconds * 1000)
        runCatching { scanner.stopScan(callback) }
        return found.values.sortedWith(compareByDescending<Found> { it.likely }.thenByDescending { it.rssi }).take(12)
    }

    /** Connects to the saved charger and fetches the session password. Quietly gives up when it isn't reachable. */
    @SuppressLint("MissingPermission")
    suspend fun connect() = mutex.withLock {
        if (_link.value is Link.Ready || _link.value is Link.Sending) return@withLock
        val address = _saved.value?.second ?: return@withLock note("No home charger saved")
        val adapter = manager?.adapter ?: return@withLock note("This phone has no Bluetooth")
        if (!adapter.isEnabled) return@withLock note("Bluetooth is off")
        if (!hasPermission()) return@withLock note("Volta needs the Nearby devices permission")
        note("Connecting…")
        _link.value = Link.Connecting
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: run {
            _link.value = Link.Off
            return@withLock
        }
        closeGatt() // never two connections at once
        ready = CompletableDeferred()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(15_000) { ready!!.await() } == true
        if (!ok) {
            closeGatt()
            _link.value = Link.Off
            return@withLock note(if (missingService) "Connected, but this isn't a Spin charger (no serial service)" else "Charger didn't answer · out of range, or the Spin app has it")
        }
        // Session password: the charger answers the password request; the password starts at character 10.
        password = ""
        // Same order as the Spin app after connecting: board version first (answer only logged), then the password.
        send(BOARD_VERSION, prefix = false)
        hideRx = true
        val answer = try {
            send(PASSWORD_REQUEST, prefix = false)
        } finally {
            hideRx = false
        }
        // As the Spin app: the reply must start 10AC3200, and the password is everything from character 10.
        val pw = answer?.takeIf { it.startsWith("10AC3200") && it.length > 10 }?.substring(10)
        log("Password ${if (pw == null) "not found" else "received (${pw.length} chars)"}")
        if (pw == null) {
            closeGatt()
            _link.value = Link.Off
            return@withLock note(if (answer == null) "Connected, but no password reply" else "Password reply not recognised: ${answer.take(24)}")
        }
        password = pw
        _state.value = readState()
        _link.value = Link.Ready
        note("Connected · charger ${_state.value?.label?.lowercase() ?: "ready"}")
    }

    /**
     * Start (true) or stop (false) charging. Like the Spin app, the charger doesn't answer start/stop: the command
     * counts as sent once written (one retry if the write fails). A few seconds later the charger's own state is
     * read, so "on, but the car isn't taking power" (EV suspended) shows as such rather than as a failure.
     */
    suspend fun charge(start: Boolean): Boolean = mutex.withLock {
        if (_link.value !is Link.Ready) return@withLock false
        _link.value = Link.Sending(start)
        val what = if (start) "Start" else "Stop"
        var written = write(if (start) START else STOP, prefix = true)
        if (!written) {
            delay(1_000)
            written = write(if (start) START else STOP, prefix = true)
        }
        if (!written) {
            _link.value = Link.Failed("Couldn't send to the charger · try again")
            note("$what · the write failed twice")
            return@withLock false
        }
        delay(4_000)
        val state = readState()
        _state.value = state
        val ok = when {
            state == null -> true

            // no answer: let the car's next reading confirm
            start -> state == ChargerState.Charging || state == ChargerState.Preparing

            else -> state != ChargerState.Charging
        }
        _link.value = when {
            ok -> Link.Ready
            start && state == ChargerState.EvSuspended -> Link.Failed("Charger is on · the car isn't taking power")
            start && state == ChargerState.EvseSuspended -> Link.Failed("The charger paused itself")
            start && state == ChargerState.Fault -> Link.Failed("Charger reports a fault")
            start -> Link.Failed("Charger didn't start · check the plug")
            else -> Link.Failed("Charger is still charging")
        }
        note("$what sent · charger now: ${state?.label ?: "no answer"}")
        ok
    }

    /** Re-reads the charger's own state (cheap; used every 20 s while connected so the button follows reality). */
    suspend fun refreshState() {
        if (_link.value !is Link.Ready) return
        mutex.withLock { readState()?.let { _state.value = it } }
    }

    /** The charger's own state, as the Spin app reads it: reply to 10AC67…, one byte at characters 14–16. */
    enum class ChargerState(val label: String) {
        Available("Available"),
        PlugConnected("Plug connected"),
        Preparing("Preparing"),
        Charging("Charging"),
        Fault("Fault"),
        Unavailable("Unavailable"),
        EvseSuspended("Paused by the charger"),
        EvSuspended("On · the car isn't taking power"),
        ;

        companion object {
            fun of(code: Int) = entries.getOrNull(code - 1)
        }
    }

    private val _state = MutableStateFlow<ChargerState?>(null)
    val state: StateFlow<ChargerState?> = _state

    private suspend fun readState(): ChargerState? {
        val r = send(STATE, prefix = false) ?: return null
        val code = r.takeIf { it.startsWith("10AC67") && it.length >= 16 }?.substring(14, 16)?.toIntOrNull(16)
        log("Charger state ${code ?: "?"} = ${code?.let { ChargerState.of(it)?.label } ?: "unknown"}")
        return code?.let { ChargerState.of(it) }
    }

    /** Back to the button after a failure. */
    fun clearFailure() {
        if (_link.value is Link.Failed) _link.value = if (channel != null) Link.Ready else Link.Off
    }

    fun disconnect() {
        closeGatt()
        _link.value = Link.Off
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
        channel = null
        password = null
    }

    /** Writes a command (with the session password after it when [prefix]) and waits up to 5 s for the reply. */
    private suspend fun send(command: String, prefix: Boolean): String? {
        if (!write(command, prefix)) return null
        // The first piece completes the wait; give any further pieces 400 ms, then read the whole reply.
        withTimeoutOrNull(5_000) { reply!!.await() } ?: run {
            log("RX nothing within 5 s")
            return null
        }
        delay(400)
        // Like the Spin app, read one notification as one frame: the first piece, unless it was cut short.
        val (first, all) = synchronized(rx) { firstPiece.orEmpty() to rx.toString() }
        val whole = normalize(if (normalize(first).length >= 16) first else all)
        log("RX (frame) $whole")
        return whole
    }

    /** Writes a command (password after it when [prefix]) without waiting for an answer; true when Android accepted it. */
    @SuppressLint("MissingPermission")
    private fun write(command: String, prefix: Boolean): Boolean {
        val g = gatt ?: return false
        val ch = channel ?: return false
        // The session password goes after the command (the Spin app logs "final command 10AC3C01" + the password).
        val hex = command + (if (prefix) password.orEmpty() else "")
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        synchronized(rx) {
            rx.setLength(0)
            firstPiece = null
        }
        reply = CompletableDeferred()
        log("TX $hex")
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val written = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = type
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
        }
        if (!written) log("TX failed to write")
        return written
    }

    /**
     * One notification as the Spin app reads it: zero bytes and line breaks dropped; if everything left is a hex
     * character it's text (the charger often answers in ASCII), otherwise the bytes are turned into hex.
     */
    private fun decode(value: ByteArray): String {
        val kept = value.filter { it != 0.toByte() && it != 0x0A.toByte() && it != 0x0D.toByte() }
        val text = kept.all { it.toInt().toChar().let { c -> c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f' } }
        return if (kept.isNotEmpty() && text) String(kept.toByteArray(), Charsets.US_ASCII) else value.joinToString("") { "%02X".format(it) }
    }

    /** As the Spin app: no spaces, upper case, and anything before the first "10AC" dropped. */
    private fun normalize(s: String): String {
        val t = s.replace(Regex("\\s+"), "").uppercase()
        val i = t.indexOf("10AC")
        return if (i > 0) t.substring(i) else t
    }

    private fun received(g: BluetoothGatt, value: ByteArray) {
        if (g != gatt) return // a leftover connection: ignore its echoes
        val piece = decode(value)
        log("RX ${value.joinToString("") { "%02X".format(it) }}  ->  $piece")
        synchronized(rx) {
            rx.append(piece)
            if (firstPiece == null) firstPiece = piece
        }
        reply?.complete(piece)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runCatching { g.close() }
                } else {
                    runCatching {
                        g.disconnect()
                        g.close()
                    }
                }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Connected (status $status) · asking for a 512-byte MTU like the Spin app")
                    if (!g.requestMtu(512)) g.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Disconnected (status $status)")
                    ready?.complete(false)
                    if (gatt == g) {
                        channel = null
                        password = null
                        _link.value = Link.Off
                    }
                    runCatching { g.close() }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU $mtu (status $status)")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("Services: " + g.services.joinToString { s -> s.uuid.toString().take(8) + "[" + s.characteristics.joinToString { c -> c.uuid.toString().take(8) + "/p" + c.properties } + "]" })
            val ch = g.getService(SERVICE)?.getCharacteristic(CHANNEL)
            missingService = ch == null
            if (ch == null) {
                ready?.complete(false)
                return
            }
            channel = ch
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD)
            if (cccd == null) {
                ready?.complete(true)
                return
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("Notifications on (status $status)")
            ready?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            log("TX written (status $status)")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) = received(g, value)

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) received(g, ch.value)
        }
    }

    /** Spin / Exicom in the name, or the Spin serial pattern: 10–14 digits, underscore, 4 hex (e.g. 123456789012_ab12). */
    private fun looksLikeCharger(name: String) = Regex("spin|exicom", RegexOption.IGNORE_CASE).containsMatchIn(name) || Regex("^\\d{10,14}_[0-9a-fA-F]{4}$").matches(name.trim())

    private companion object {
        val SERVICE: UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
        val CHANNEL: UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val PASSWORD_REQUEST = "10AC320000000000"
        const val BOARD_VERSION = "10AC520000000000"
        const val STATE = "10AC670000000000"
        const val START = "10AC3C0101"
        const val STOP = "10AC3C0110"
    }
}
