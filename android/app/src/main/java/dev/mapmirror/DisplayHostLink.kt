package dev.mapmirror

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.Settings.Global as GlobalSettings
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's connection to the display service (see DisplayHostMain). If the service
 * isn't running, as after a reboot, it is started through wireless debugging. That
 * is the only thing the adb connection is used for, and it's closed straight after.
 */
class DisplayHostLink private constructor(
    private val socket: Socket,
    private val inp: DataInputStream,
    private val out: DataOutputStream,
    val status: String,
) : Closeable {

    var rowPixels = 0
        private set
    var height = 0
        private set
    var width = 0
        private set
    var pixels = ByteArray(0)
        private set

    /** Asks for the next new frame and waits for it. Blocks while the map is static. */
    fun nextFrame() {
        synchronized(out) {
            out.write(DisplayHostMain.MSG_FRAME)
            out.flush()
        }
        rowPixels = inp.readInt()
        height = inp.readInt()
        width = inp.readInt()
        val size = rowPixels * height * 4
        if (pixels.size != size) pixels = ByteArray(size)
        inp.readFully(pixels)
    }

    /** Coordinates are in display pixels. Pointers past MAX_POINTERS are left out. */
    fun sendTouch(action: Int, actionIndex: Int, ids: IntArray, xs: IntArray, ys: IntArray) {
        synchronized(out) {
            out.write(DisplayHostMain.MSG_TOUCH)
            out.write(action)
            out.write(actionIndex)
            out.write(ids.size)
            for (i in ids.indices) {
                out.write(ids[i])
                out.writeShort(xs[i])
                out.writeShort(ys[i])
            }
            out.flush()
        }
    }

    fun sendBack() {
        synchronized(out) {
            out.write(DisplayHostMain.MSG_BACK)
            out.flush()
        }
    }

    /** A higher density makes the app draw its text and buttons larger. */
    fun sendDpi(dpi: Int) {
        synchronized(out) {
            out.write(DisplayHostMain.MSG_DPI)
            out.writeShort(dpi)
            out.flush()
        }
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        private const val START_WAIT_MS = 8000L
        private const val ADB_TIMEOUT_MS = 20_000L // finding the port over mDNS can be slow
        private const val LAUNCHED = "mapmirror_launched"
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled" // hidden from the SDK
        private const val ADB_WIFI_START_MS = 3000L
        private const val ALLOW_WAIT_MS = 60_000L // for the person to tap Allow, the first time
        private const val JOIN_WAIT_MS = 30_000L

        fun open(ctx: Context, s: Settings, w: Int, h: Int, dpi: Int, log: (String) -> Unit): DisplayHostLink {
            val app = s.navPackage
            if (app.isEmpty()) throw IOException("choose a navigation app first")
            tryOpen(s.hostToken, w, h, dpi, app)?.let { return it }
            if (!s.adbPaired) throw IOException("pair wireless debugging first")
            // Wireless debugging only runs on Wi-Fi, not mobile data. With none around, as at
            // the motorcycle, join the display's own Wi-Fi for long enough to start the service.
            val joined = if (!onWifi(ctx)) joinDisplayWifi(ctx, s, log) else null
            try {
                if (!onWifi(ctx)) {
                    throw IOException(
                        if (s.displayWifiSsid.isEmpty()) {
                            "the display service needs Wi-Fi to start, as after the phone restarts. Join " +
                                "any Wi-Fi and start again. After one mirroring session, the display's own Wi-Fi works too"
                        } else {
                            "couldn't join ${s.displayWifiSsid} to start the display service. Check the display " +
                                "is on and the phone's Wi-Fi is on, then start again"
                        }
                    )
                }
                log("Starting display service…")
                startService(ctx, s, log)
            } finally {
                // The transport joins it again, for frames.
                joined?.let { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
            }
            val deadline = System.currentTimeMillis() + START_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                tryOpen(s.hostToken, w, h, dpi, app)?.let { return it }
                Thread.sleep(250)
            }
            throw IOException("the display service didn't start")
        }

        /**
         * Joins the display's Wi-Fi, as the transport does, and returns the request to release,
         * or null if it can't. On a phone with one Wi-Fi radio, this is the phone's own Wi-Fi
         * connection while it lasts, which Wireless debugging accepts. It has no internet, so
         * the phone keeps using mobile data for that.
         */
        private fun joinDisplayWifi(
            ctx: Context, s: Settings, log: (String) -> Unit,
        ): ConnectivityManager.NetworkCallback? {
            if (s.displayWifiSsid.isEmpty() || Build.VERSION.SDK_INT < 29) return null
            log("No Wi-Fi: joining ${s.displayWifiSsid} to start the display service…")
            // The same BSSID as the transport, so Android remembers the approval.
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(s.displayWifiSsid)
                .setWpa2Passphrase(s.displayWifiPass)
                .apply { if (s.displayWifiBssid.isNotEmpty()) setBssid(MacAddress.fromString(s.displayWifiBssid)) }
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
            val up = CountDownLatch(1)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = up.countDown()
            }
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.requestNetwork(request, callback)
            if (up.await(JOIN_WAIT_MS, TimeUnit.MILLISECONDS)) return callback
            cm.unregisterNetworkCallback(callback)
            return null
        }

        /** Starts the display service, with a new token. Needs the phone on Wi-Fi. */
        fun startService(ctx: Context, s: Settings, log: (String) -> Unit) {
            s.hostToken = newToken()
            launch(ctx, s.hostToken, log)
        }

        /** True if the display service is listening. It doesn't open a session. */
        fun isRunning(): Boolean = try {
            Socket().use {
                it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), DisplayHostMain.PORT), 500)
            }
            true
        } catch (_: IOException) {
            false
        }

        /**
         * True if the phone has joined a Wi-Fi network itself, which is what Wireless
         * debugging needs. That includes the display's own Wi-Fi once it's saved: it has
         * no internet, so the phone keeps mobile data as its default network.
         */
        @Suppress("DEPRECATION") // allNetworks: a callback is overkill for a one-off check
        fun onWifi(ctx: Context): Boolean {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            return cm.allNetworks.any {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }

        fun canTurnOnWirelessDebugging(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        /** Returns null if the service isn't running or doesn't accept the token. */
        private fun tryOpen(token: String, w: Int, h: Int, dpi: Int, app: String): DisplayHostLink? {
            if (token.length != DisplayHostMain.TOKEN_CHARS) return null
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), DisplayHostMain.PORT), 1000)
                socket.tcpNoDelay = true
                socket.soTimeout = 15_000 // moving the app can take a few seconds
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
                out.write(token.toByteArray())
                out.writeInt(DisplayHostMain.VERSION)
                out.writeInt(w)
                out.writeInt(h)
                out.writeInt(dpi)
                out.writeUTF(app)
                out.flush()
                val status = inp.readUTF()
                socket.soTimeout = 0
                return DisplayHostLink(socket, inp, out, status)
            } catch (_: IOException) {
                try { socket.close() } catch (_: IOException) {}
                return null
            }
        }

        /**
         * Returns true if this turned it on. Needs WRITE_SECURE_SETTINGS, which can only
         * be granted from a computer: adb shell pm grant dev.mapmirror
         * android.permission.WRITE_SECURE_SETTINGS. Without it, the user turns it on.
         */
        private fun turnOnWirelessDebugging(ctx: Context, log: (String) -> Unit): Boolean {
            val resolver = ctx.contentResolver
            if (GlobalSettings.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1) return false
            if (!canTurnOnWirelessDebugging(ctx)) return false
            log("Turning on Wireless debugging…")
            GlobalSettings.putInt(resolver, ADB_WIFI_ENABLED, 1)
            Thread.sleep(ADB_WIFI_START_MS)
            if (GlobalSettings.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1) return true
            // On a network it doesn't trust yet, Android switches it straight back off and asks
            // "Allow wireless debugging on this network?". Allowing turns it on.
            log("Tick \"Always allow on this network\" and tap Allow, so it works by itself next time")
            val until = System.currentTimeMillis() + ALLOW_WAIT_MS
            while (System.currentTimeMillis() < until) {
                Thread.sleep(500)
                if (GlobalSettings.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1) return true
            }
            throw IOException("Wireless debugging wasn't allowed on this Wi-Fi, so the display service can't start")
        }

        private fun launch(ctx: Context, token: String, log: (String) -> Unit) {
            val apk = ctx.applicationInfo.sourceDir
            // pkill replaces a service from an older version of the app. setsid lets
            // the service outlive the adb connection, which is closed next; closing
            // it kills the shell's whole session, so nohup alone isn't enough.
            val cmd = "pkill -f '^${DisplayHostMain.PROCESS_NAME}' && sleep 1; " +
                "CLASSPATH='$apk' setsid nohup app_process /system/bin " +
                "--nice-name=${DisplayHostMain.PROCESS_NAME} ${DisplayHostMain::class.java.name} $token " +
                ">/data/local/tmp/mapmirror_host.log 2>&1 </dev/null & echo $LAUNCHED"
            val turnedOn = turnOnWirelessDebugging(ctx, log)
            val adb = AdbManager(ctx)
            try {
                val connected = try {
                    adb.connectTls(ctx, ADB_TIMEOUT_MS)
                } catch (e: Exception) {
                    // Android forgets a pairing after 7 days unused, unless "Disable adb
                    // authorization timeout" is on in Developer options.
                    if (e.message?.contains("pairing", ignoreCase = true) == true) {
                        Settings(ctx).adbPaired = false
                        HostStarter.schedule(ctx)
                        throw IOException("Android has forgotten the pairing. Tap \"Pair wireless debugging\" again")
                    }
                    throw IOException("turn on Wireless debugging, on Wi-Fi, to start it (${e.message ?: e})")
                }
                if (!connected) throw IOException("turn on Wireless debugging, on Wi-Fi, to start it")
                // What `adb shell` uses. Under exec: the service dies at once, and under
                // the plain shell: service, with its terminal, it sometimes never starts.
                val stream = adb.openStream("shell,v2,raw:$cmd")
                val output = StringBuilder()
                val buf = ByteArray(256)
                val deadline = System.currentTimeMillis() + ADB_TIMEOUT_MS
                // Disconnecting too early would stop the shell before it runs the command.
                try {
                    while (LAUNCHED !in output && !stream.isClosed && System.currentTimeMillis() < deadline) {
                        val n = stream.read(buf, 0, buf.size)
                        if (n > 0) output.append(String(buf, 0, n)) else Thread.sleep(50)
                    }
                } catch (_: IOException) {}
                // It would also kill a service that hasn't finished detaching yet.
                waitUntilListening()
            } finally {
                // Not close(): it also tries to destroy the key, which throws for RSA keys.
                try { adb.disconnect() } catch (_: IOException) {}
                // Only needed for this moment, so don't leave it open on the network.
                if (turnedOn) GlobalSettings.putInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0)
            }
        }

        private fun waitUntilListening() {
            val deadline = System.currentTimeMillis() + START_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (isRunning()) return
                Thread.sleep(100)
            }
        }

        private fun newToken(): String {
            val bytes = ByteArray(DisplayHostMain.TOKEN_CHARS / 2)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
