package dev.mapmirror

import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * BLE connects first and gives the Wi-Fi details over the paired link, then frames
 * move to Wi-Fi once it's up, and back to BLE if it drops. Every frame is a complete
 * image, so a switch only costs the frame in flight.
 *
 * The display's Wi-Fi has no internet, so it's joined for this app only. The rest of
 * the phone stays on its normal network.
 */
class AutoTransport(
    private val ctx: Context,
    settings: Settings,
    private val log: (String) -> Unit,
) : Transport {

    private val ble = BleTransport(ctx, settings, log, ::onWifiInfo)
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var closed = false
    @Volatile private var wifi: WifiTransport? = null
    @Volatile private var onWifi = false
    private var wifiWindowEnd = 0L
    private var nextWifiWindow = 0L
    private var info: BleTransport.WifiInfo? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    override val label get() = if (onWifi) "Wi-Fi" else "BLE"
    override val slow get() = !onWifi

    override fun start() = ble.start()

    override fun isReady() = wifi?.isReady() == true || ble.isReady()

    /** If Wi-Fi fails mid-frame, BLE sends it, so a static map still gets its last frame. */
    override fun send(data: ByteArray, len: Int): Boolean {
        val w = wifi
        if (w != null && w.isReady()) {
            ble.setIdle(true)
            if (w.send(data, len)) {
                if (!onWifi) log("Switched to Wi-Fi")
                onWifi = true
                return true
            }
        }
        if (onWifi) log("Switched to BLE")
        onWifi = false
        // The display can't connect over Wi-Fi while BLE takes its radio time, so
        // now and then BLE pauses to give a waiting Wi-Fi connection a chance.
        if (w != null) {
            val now = SystemClock.uptimeMillis()
            if (now >= nextWifiWindow) {
                wifiWindowEnd = now + WIFI_WINDOW_MS
                nextWifiWindow = wifiWindowEnd + BLE_WINDOW_MS
            }
            if (now < wifiWindowEnd) {
                ble.setIdle(true)
                return false
            }
        }
        ble.setIdle(false)
        return ble.isReady() && ble.send(data, len)
    }

    /** Called on each BLE reconnect, so only act if the details are new. */
    private fun onWifiInfo(details: BleTransport.WifiInfo) {
        main.post {
            if (closed || details == info) return@post
            info = details
            dropWifi()
            if (Crypto.unhex(details.keyHex)?.size != 32) {
                log("Staying on BLE: no key for the display's Wi-Fi")
            } else if (details.password.isEmpty()) {
                useWifi(null, details) // the display joined a network the phone is on
            } else if (Build.VERSION.SDK_INT >= 29) {
                request(details)
            } else {
                log("Staying on BLE: joining Wi-Fi automatically needs Android 10")
            }
        }
    }

    private fun request(details: BleTransport.WifiInfo) {
        if (Build.VERSION.SDK_INT < 29) return
        // A fixed BSSID lets Android remember the approval instead of asking each time.
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(details.ssid)
            .setWpa2Passphrase(details.password)
            .apply { if (details.bssid.isNotEmpty()) setBssid(MacAddress.fromString(details.bssid)) }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { if (!closed) useWifi(network, details) }
            }

            // Android doesn't rejoin by itself, so ask again, as when the display restarts.
            override fun onLost(network: Network) {
                main.post {
                    if (closed) return@post
                    log("Wi-Fi lost, using BLE")
                    dropWifi()
                    main.postDelayed({ if (!closed && callback == null) request(details) }, WIFI_RELINK_MS)
                }
            }

            override fun onUnavailable() {
                main.post {
                    if (closed) return@post
                    log("Wi-Fi not available, staying on BLE")
                    unregister()
                    main.postDelayed({ if (!closed && callback == null) request(details) }, WIFI_RETRY_MS)
                }
            }
        }
        callback = cb
        cm.requestNetwork(request, cb)
    }

    private fun useWifi(network: Network?, details: BleTransport.WifiInfo) {
        stopWifiTransport()
        nextWifiWindow = 0L // start with a Wi-Fi window straight away
        val key = Crypto.unhex(details.keyHex) ?: return
        wifi = WifiTransport(ctx, details.ip, network, key, log).also { it.start() }
        ble.setIdle(true)
    }

    private fun stopWifiTransport() {
        wifi?.close()
        wifi = null
    }

    private fun unregister() {
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: IllegalArgumentException) {} }
        callback = null
    }

    private fun dropWifi() {
        stopWifiTransport()
        unregister()
    }

    override fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        dropWifi()
        ble.close()
    }

    private companion object {
        const val WIFI_RETRY_MS = 30_000L
        const val WIFI_RELINK_MS = 5_000L
        const val WIFI_WINDOW_MS = 8_000L
        const val BLE_WINDOW_MS = 30_000L
    }
}
