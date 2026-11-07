package dev.mapmirror

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * BLE link: one write-without-response characteristic, frames split into chunks.
 *
 * Chunk:  flags(u8) | frameId(u8) | [u32 LE total length, START chunk only] | payload
 *         flags bit0 = START, bit1 = END
 *
 * Requests MTU 517, 2M PHY and high connection priority. Expect roughly 3-6 fps
 * at JPEG quality ~40; Wi-Fi is much faster.
 *
 * The first connection pairs using a passkey shown on the panel, and Android and the
 * display both keep the bond. The phone then confirms the display's key once, by a code
 * shown on both, and checks it on every connection. After that, the phone only
 * connects to that display.
 *
 * Permissions are checked by MainActivity before the service starts.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val ctx: Context,
    private val settings: Settings,
    private val log: (String) -> Unit,
    private val onWifiInfo: (WifiInfo) -> Unit,
) : Transport {

    /** The display's Wi-Fi and key. An empty password means it joined an existing network. */
    data class WifiInfo(val ssid: String, val password: String, val bssid: String, val ip: String, val keyHex: String)

    /** A key check in progress. [known] is the key confirmed earlier, if any. */
    private class Check(val info: WifiInfo, val key: ByteArray, val known: ByteArray?, val nonce: ByteArray)

    override val label = "BLE"
    override val slow = true

    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var closed = false
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var frameChar: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    @Volatile private var scanning = false
    @Volatile private var idle = false
    @Volatile private var writeStatus = BluetoothGatt.GATT_SUCCESS
    @Volatile private var pending: Check? = null
    @Volatile private var refused = false
    private val writeDone = Semaphore(0)
    private var frameId = 0

    override fun start() = scan()

    override fun isReady() = frameChar != null

    // ---- scanning -------------------------------------------------------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning || closed) return
            stopScan()
            log("BLE: found ${result.device.address}, connecting…")
            gatt = result.device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("BLE: scan failed ($errorCode)")
            retryLater()
        }
    }

    private fun scan() {
        if (closed) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            log("BLE: Bluetooth is off")
            retryLater()
            return
        }
        scanning = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID))
            .apply { if (settings.displayAddress.isNotEmpty()) setDeviceAddress(settings.displayAddress) }
            .build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), scanSettings, scanCb)
        log("BLE: scanning for display…")
        main.postDelayed({
            if (scanning) {
                stopScan()
                retryLater()
            }
        }, 15_000)
    }

    private fun stopScan() {
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private fun retryLater() {
        if (!closed && !refused) main.postDelayed({ scan() }, 2_000)
    }

    // ---- GATT -----------------------------------------------------------

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                idle = false
                g.requestMtu(517)
            } else {
                frameChar = null
                pending = null
                DisplayConfirm.cancel(ctx)
                g.close()
                if (gatt === g) gatt = null
                writeStatus = BluetoothGatt.GATT_FAILURE
                writeDone.release()
                if (!closed && !refused) {
                    // The display drops a phone that isn't bonded outside its pairing window.
                    if (g.device.bondState != BluetoothDevice.BOND_BONDED) {
                        log("Display not accepting new phones. Restart it, then tap Start within 2 minutes")
                    } else {
                        log("BLE: disconnected ($status), rescanning")
                    }
                    retryLater()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            g.setPreferredPhy(
                BluetoothDevice.PHY_LE_2M_MASK,
                BluetoothDevice.PHY_LE_2M_MASK,
                BluetoothDevice.PHY_OPTION_NO_PREFERRED,
            )
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            val info = service?.getCharacteristic(WIFI_INFO_UUID)
            if (service?.getCharacteristic(FRAME_UUID) == null || service.getCharacteristic(AUTH_UUID) == null ||
                info == null) {
                log("BLE: display service not found. Update the display's firmware")
                g.disconnect()
                return
            }
            // This needs an encrypted link, so Android pairs here if it hasn't yet,
            // or encrypts with the existing bond. Frames are only sent after this.
            if (g.device.bondState != BluetoothDevice.BOND_BONDED) {
                log("BLE: enter the code shown on the display")
            }
            if (!g.readCharacteristic(info)) {
                log("BLE: couldn't start pairing")
                g.disconnect()
            }
        }

        @Deprecated("Used before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            onRead(g, c, c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            onRead(g, c, value, status)
        }

        private fun onRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            when (c.uuid) {
                WIFI_INFO_UUID -> onWifiInfoRead(g, value, status)
                AUTH_UUID -> onProofRead(g, value, status)
            }
        }

        private fun onWifiInfoRead(g: BluetoothGatt, value: ByteArray, status: Int) {
            // Refuse a display that gave the details without really pairing.
            if (status != BluetoothGatt.GATT_SUCCESS || g.device.bondState != BluetoothDevice.BOND_BONDED) {
                log("BLE: pairing failed ($status)")
                g.disconnect()
                return
            }
            val parts = String(value).split("\n")
            val key = parts.getOrNull(4)?.let { Crypto.unhex(it) }
            if (parts.size != 5 || key == null || key.size != 32) {
                log("BLE: the display's firmware is out of date. Flash the new firmware")
                g.disconnect()
                return
            }
            // Check the key on every connection. A look-alike display doesn't have it.
            val known = if (settings.displayAddress == g.device.address) Crypto.unhex(settings.displayKey) else null
            val check = Check(WifiInfo(parts[0], parts[1], parts[2], parts[3], parts[4]), key, known, Crypto.nonce())
            pending = check
            val auth = g.getService(SERVICE_UUID).getCharacteristic(AUTH_UUID)
            val msg = byteArrayOf(if (known == null) 1 else 0) + check.nonce
            if (!writeWithResponse(g, auth, msg)) {
                log("BLE: couldn't check the display's key")
                g.disconnect()
            }
        }

        private fun onProofRead(g: BluetoothGatt, value: ByteArray, status: Int) {
            val check = pending ?: return
            pending = null
            val key = check.known ?: check.key
            if (status != BluetoothGatt.GATT_SUCCESS || !Crypto.same(value, Crypto.hmac(key, "MMBP", check.nonce))) {
                log("BLE: this isn't the display you paired with. Use Forget paired display if you replaced it")
                g.disconnect()
                return
            }
            if (check.known != null) {
                ready(g, check.info.copy(keyHex = Crypto.hex(key))) // the key that passed the check
                return
            }
            val code = Crypto.confirmCode(check.key, check.nonce)
            log("BLE: check the code on the display: $code")
            DisplayConfirm.ask(ctx, code) { ok ->
                main.post {
                    if (closed || gatt !== g) return@post
                    if (ok) {
                        settings.displayAddress = g.device.address
                        settings.displayKey = Crypto.hex(check.key)
                        ready(g, check.info.copy(keyHex = Crypto.hex(key))) // the key that passed the check
                    } else {
                        refused = true
                        log("Display refused, as its code didn't match. Tap Stop")
                        g.disconnect()
                    }
                }
            }
        }

        private fun ready(g: BluetoothGatt, info: WifiInfo) {
            val c = g.getService(SERVICE_UUID).getCharacteristic(FRAME_UUID)
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            frameChar = c
            log("BLE: connected (MTU $mtu)")
            onWifiInfo(info)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (c.uuid == AUTH_UUID) {
                if (status != BluetoothGatt.GATT_SUCCESS || !g.readCharacteristic(c)) {
                    log("BLE: couldn't check the display's key ($status)")
                    g.disconnect()
                }
                return
            }
            writeStatus = status
            writeDone.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun writeWithResponse(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            c.value = value
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(c)
        }

    // ---- sending --------------------------------------------------------

    override fun send(data: ByteArray, len: Int): Boolean {
        val g = gatt ?: return false
        val c = frameChar ?: return false
        // ATT payload = MTU - 3, and the characteristic is capped at 512 bytes.
        val maxWrite = min(mtu - 3, 512)
        val id = (frameId++ and 0xFF).toByte()
        var off = 0
        var first = true
        while (first || off < len) {
            val hdr = if (first) 6 else 2
            val n = min(maxWrite - hdr, len - off)
            val pkt = ByteArray(hdr + n)
            var flags = 0
            if (first) flags = flags or 0x01
            if (off + n >= len) flags = flags or 0x02
            pkt[0] = flags.toByte()
            pkt[1] = id
            if (first) {
                pkt[2] = len.toByte(); pkt[3] = (len shr 8).toByte()
                pkt[4] = (len shr 16).toByte(); pkt[5] = (len shr 24).toByte()
            }
            System.arraycopy(data, off, pkt, hdr, n)
            if (!write(g, c, pkt)) return false
            off += n
            first = false
        }
        return true
    }

    /**
     * The display has one radio for BLE and Wi-Fi, and a fast BLE connection takes
     * nearly all its time. While idle, BLE stays connected but leaves room for Wi-Fi.
     */
    fun setIdle(idle: Boolean) {
        if (idle == this.idle) return
        val g = gatt ?: return
        val priority = if (idle) BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER else BluetoothGatt.CONNECTION_PRIORITY_HIGH
        if (g.requestConnectionPriority(priority)) this.idle = idle
    }

    @Suppress("DEPRECATION")
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, pkt: ByteArray): Boolean {
        writeDone.drainPermits()
        repeat(100) {
            if (frameChar == null) return false
            val queued = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                c.value = pkt
                g.writeCharacteristic(c)
            }
            if (queued) {
                return writeDone.tryAcquire(1, TimeUnit.SECONDS) &&
                    writeStatus == BluetoothGatt.GATT_SUCCESS
            }
            Thread.sleep(3) // stack busy, try again
        }
        return false
    }

    override fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        DisplayConfirm.cancel(ctx)
        stopScan()
        frameChar = null
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        writeDone.release()
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("4d4d0001-0000-4d61-7020-4d6972726f72")
        val FRAME_UUID: UUID = UUID.fromString("4d4d0002-0000-4d61-7020-4d6972726f72")
        val WIFI_INFO_UUID: UUID = UUID.fromString("4d4d0003-0000-4d61-7020-4d6972726f72")
        val AUTH_UUID: UUID = UUID.fromString("4d4d0004-0000-4d61-7020-4d6972726f72")
    }
}
