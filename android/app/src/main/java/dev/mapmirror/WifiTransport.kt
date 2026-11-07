package dev.mapmirror

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * TCP link to the display. Both sides prove they hold the display's key, then each
 * frame is encrypted and authenticated with a fresh session key. See main.cpp.
 *
 * Frame:  "MMF2" | u32 little-endian length | AES-128-GCM(JPEG) | tag(16)
 * Reply:  one byte 0x06 once the display has drawn the frame (flow control).
 *
 * Discovery: the display broadcasts "MAPMIRROR 1" on UDP 5006 every second;
 * we connect to whoever sent it. Falls back to 192.168.4.1 (display's own AP).
 */
class WifiTransport(
    private val ctx: Context,
    private val fixedHost: String,
    private val network: Network?,
    private val key: ByteArray,
    private val log: (String) -> Unit,
) : Transport {

    override val label = "Wi-Fi"

    @Volatile private var closed = false
    @Volatile private var sock: Socket? = null
    private var out: OutputStream? = null
    private var inp: InputStream? = null
    private val lock = Any()
    private val header = ByteArray(8)
    private var worker: Thread? = null
    private var sessionKey: SecretKeySpec? = null
    private var counter = 0L
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    override fun start() {
        worker = thread(name = "wifi-connect", isDaemon = true) {
            while (!closed) {
                if (sock == null) connectOnce()
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@thread }
            }
        }
    }

    override fun isReady() = sock != null

    private fun connectOnce() {
        val host = fixedHost.trim().ifEmpty {
            log("Wi-Fi: looking for display…")
            discover() ?: FALLBACK_HOST
        }
        if (closed) return
        log("Wi-Fi: connecting to $host…")
        try {
            val s = newSocket()
            s.connect(InetSocketAddress(host, PORT), 3000)
            s.tcpNoDelay = true
            s.soTimeout = 3000 // handshake and ack timeout
            val o = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
            val i = s.getInputStream()
            val sk = try {
                handshake(i, o)
            } catch (e: IOException) {
                s.close()
                throw e
            }
            synchronized(lock) {
                out = o
                inp = i
                sessionKey = sk
                counter = 0
                sock = s
            }
            log("Wi-Fi: connected to $host")
        } catch (e: IOException) {
            log("Wi-Fi: $host unreachable (${e.message})")
        }
    }

    /** Proves this phone holds the key, checks the display does too, and makes a session key. */
    private fun handshake(i: InputStream, o: OutputStream): SecretKeySpec {
        val din = DataInputStream(i)
        val hello = ByteArray(20)
        din.readFully(hello)
        if (String(hello, 0, 4, Charsets.US_ASCII) != "MMS2") throw IOException("not a MapMirror display")
        val nb = hello.copyOfRange(4, 20)
        val np = Crypto.nonce()
        o.write("MMC2".toByteArray(Charsets.US_ASCII))
        o.write(np)
        o.write(Crypto.hmac(key, "MMTC", nb, np))
        o.flush()
        val proof = ByteArray(32)
        din.readFully(proof)
        if (!Crypto.same(proof, Crypto.hmac(key, "MMTS", nb, np))) throw IOException("this isn't your display")
        return SecretKeySpec(Crypto.hmac(key, "MMTK", nb, np).copyOf(16), "AES")
    }

    /**
     * If the phone is connected to the display's own AP (no internet), Android keeps
     * mobile data as the default network, so an unbound socket would go out over
     * cellular. Binding to the Wi-Fi network avoids that. When the phone is the
     * hotspot there is no Wi-Fi client network and a plain socket is correct.
     */
    private fun newSocket(): Socket {
        network?.let { return it.socketFactory.createSocket() }
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifiNet = cm?.allNetworks?.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return wifiNet?.socketFactory?.createSocket() ?: Socket()
    }

    private fun discover(): String? {
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val mlock = wifi?.createMulticastLock("mapmirror")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        return try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(DISCOVERY_PORT))
                s.soTimeout = 3000
                val buf = ByteArray(64)
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                val msg = String(pkt.data, 0, pkt.length)
                if (msg.startsWith("MAPMIRROR")) pkt.address.hostAddress else null
            }
        } catch (e: IOException) {
            null
        } finally {
            mlock?.release()
        }
    }

    override fun send(data: ByteArray, len: Int): Boolean = synchronized(lock) {
        val o = out ?: return false
        val i = inp ?: return false
        val sk = sessionKey ?: return false
        try {
            header[0] = 'M'.code.toByte(); header[1] = 'M'.code.toByte()
            header[2] = 'F'.code.toByte(); header[3] = '2'.code.toByte()
            header[4] = len.toByte(); header[5] = (len shr 8).toByte()
            header[6] = (len shr 16).toByte(); header[7] = (len shr 24).toByte()
            val iv = ByteArray(12)
            for (b in 0 until 8) iv[4 + b] = (counter shr (56 - 8 * b)).toByte()
            counter++
            cipher.init(Cipher.ENCRYPT_MODE, sk, GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            val sealed = cipher.doFinal(data, 0, len) // ciphertext, then the 16-byte tag
            o.write(header)
            o.write(sealed)
            o.flush()
            val ack = i.read()
            if (ack != ACK) throw IOException("no ack ($ack)")
            true
        } catch (e: IOException) {
            log("Wi-Fi: link lost (${e.message}), reconnecting")
            dropLocked()
            false
        }
    }

    private fun dropLocked() {
        try { sock?.close() } catch (_: IOException) {}
        sock = null; out = null; inp = null; sessionKey = null
    }

    override fun close() {
        closed = true
        worker?.interrupt()
        synchronized(lock) { dropLocked() }
    }

    companion object {
        const val PORT = 5005
        const val DISCOVERY_PORT = 5006
        const val FALLBACK_HOST = "192.168.4.1"
        const val ACK = 0x06
    }
}
