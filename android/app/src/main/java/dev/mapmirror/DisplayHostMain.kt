package dev.mapmirror

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * The display service. Runs as the shell user in its own process, started once
 * through wireless debugging (see DisplayHostLink), and survives until a reboot.
 *
 * It owns a virtual display, moves the chosen navigation app onto it and streams its frames to the app
 * over loopback TCP. A client must present the token passed at launch, and can only
 * receive frames and send touches and Back to this display. When the client goes
 * away, the display is removed and Android returns the navigation app to the phone.
 *
 * Client: token | u32 version, width, height, dpi | UTF package | then messages:
 *         MSG_FRAME (wants one frame) | MSG_BACK | MSG_DPI | u16 dpi
 *         MSG_TOUCH | u8 action, actionIndex, count | count * (u8 id | u16 x, y)
 * Server: UTF status | per frame: u32 rowPixels, height, width | RGBA pixels
 */
object DisplayHostMain {
    const val VERSION = 5
    const val PORT = 50507
    const val MSG_FRAME = 1
    const val MSG_TOUCH = 2
    const val MSG_BACK = 3
    const val MSG_DPI = 4
    const val MIN_DPI = 160
    const val MAX_DPI = 400
    const val MAX_POINTERS = 2
    const val PROCESS_NAME = "mapmirror_host"
    const val TOKEN_CHARS = 64

    private const val SHELL_PACKAGE = "com.android.shell"
    private const val SHELL_UID = 2000
    private const val MAX_SIDE = 2048
    private const val INJECT_ASYNC = 0 // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
    // Checked before use in a shell command, so the client can't inject one.
    private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
    private val COMPONENT = Regex("""[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+""")

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*; the last four are hidden from the SDK.
    private const val FLAG_PUBLIC = 1 shl 0
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val FLAG_OWN_FOCUS = 1 shl 14

    /** The display manager checks the caller's package against its uid (shell). */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
    }

    /** Injects input into one display only; the client can't choose another. */
    private class Injector(shell: Context, private val displayId: Int) {
        private val manager = shell.getSystemService(Context.INPUT_SERVICE)
        private val inject = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
        private val setDisplay = InputEvent::class.java.getMethod("setDisplayId", Int::class.java)
        private var downTime = 0L

        fun touch(inp: DataInputStream) {
            val action = inp.readUnsignedByte()
            val actionIndex = inp.readUnsignedByte()
            val count = inp.readUnsignedByte()
            if (count !in 1..MAX_POINTERS || actionIndex >= count) throw IOException("Bad touch")
            val props = Array(count) { MotionEvent.PointerProperties() }
            val coords = Array(count) { MotionEvent.PointerCoords() }
            for (i in 0 until count) {
                props[i].id = inp.readUnsignedByte()
                props[i].toolType = MotionEvent.TOOL_TYPE_FINGER
                coords[i].x = inp.readUnsignedShort().toFloat()
                coords[i].y = inp.readUnsignedShort().toFloat()
                coords[i].pressure = 1f
                coords[i].size = 1f
            }
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val composed = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            send(
                MotionEvent.obtain(
                    downTime, now, composed, count, props, coords, 0, 0, 1f, 1f, 0, 0,
                    InputDevice.SOURCE_TOUCHSCREEN, 0,
                )
            )
        }

        fun back() {
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val now = SystemClock.uptimeMillis()
                send(
                    KeyEvent(
                        now, now, action, KeyEvent.KEYCODE_BACK, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
                    )
                )
            }
        }

        private fun send(e: InputEvent) {
            setDisplay.invoke(e, displayId)
            inject.invoke(manager, e, INJECT_ASYNC)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args[0].toByteArray()
        Looper.prepareMainLooper()
        val shell = ShellContext(systemContext())
        val server = ServerSocket(PORT, 1, InetAddress.getLoopbackAddress())
        val handler = Handler(Looper.getMainLooper())
        thread(name = "accept") {
            while (true) {
                try {
                    server.accept().use { session(it, token, shell, handler) }
                } catch (e: Exception) {
                    println("Session ended: $e")
                }
            }
        }
        Looper.loop()
    }

    @SuppressLint("PrivateApi")
    private fun systemContext(): Context {
        val cls = Class.forName("android.app.ActivityThread")
        val thread = cls.getMethod("systemMain").invoke(null)
        return cls.getMethod("getSystemContext").invoke(thread) as Context
    }

    private fun session(socket: Socket, token: ByteArray, shell: Context, handler: Handler) {
        socket.tcpNoDelay = true
        socket.soTimeout = 3000
        val inp = DataInputStream(socket.getInputStream())
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))

        val presented = ByteArray(TOKEN_CHARS)
        inp.readFully(presented)
        if (!MessageDigest.isEqual(presented, token)) return
        if (inp.readInt() != VERSION) return
        val w = inp.readInt()
        val h = inp.readInt()
        val dpi = inp.readInt()
        val app = inp.readUTF()
        if (w !in 1..MAX_SIDE || h !in 1..MAX_SIDE || dpi !in MIN_DPI..MAX_DPI || !PACKAGE.matches(app)) return
        socket.soTimeout = 0

        val lock = Object()
        var credits = 0
        var frameReady = false
        var closed = false

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({
            synchronized(lock) { frameReady = true; lock.notifyAll() }
        }, handler)

        // getSystemService() would bind to the wrong package, so build one directly.
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        val display = ctor.newInstance(shell)
            .createVirtualDisplay("MapMirror", w, h, dpi, reader.surface, flags())
            ?: throw IOException("Virtual display was refused")
        val awake = keepAwake(shell, display.display.displayId)
        try {
            out.writeUTF(moveApp(app, display.display.displayId))
            out.flush()

            val injector = Injector(shell, display.display.displayId)

            // A separate thread, so a client that leaves is noticed while the map is static.
            thread(name = "messages", isDaemon = true) {
                try {
                    while (true) {
                        when (inp.readUnsignedByte()) {
                            MSG_FRAME -> synchronized(lock) { credits++; lock.notifyAll() }
                            MSG_TOUCH -> injector.touch(inp)
                            MSG_BACK -> injector.back()
                            MSG_DPI -> {
                                val newDpi = inp.readUnsignedShort()
                                if (newDpi !in MIN_DPI..MAX_DPI) throw IOException("Bad dpi")
                                display.resize(w, h, newDpi)
                            }
                            else -> throw IOException("Bad message")
                        }
                    }
                } catch (e: Exception) {
                    println("Client left: $e")
                }
                synchronized(lock) { closed = true; lock.notifyAll() }
            }

            var pixels = ByteArray(0)
            while (true) {
                synchronized(lock) {
                    while (!closed && !(credits > 0 && frameReady)) lock.wait()
                    frameReady = false
                }
                if (synchronized(lock) { closed }) break
                val img = reader.acquireLatestImage() ?: continue
                try {
                    val plane = img.planes[0]
                    val rowPixels = plane.rowStride / plane.pixelStride
                    val size = rowPixels * img.height * 4
                    if (pixels.size != size) pixels = ByteArray(size)
                    val buf = plane.buffer
                    buf.rewind()
                    // Some devices omit padding on the last row.
                    buf.get(pixels, 0, minOf(buf.remaining(), size))
                    out.writeInt(rowPixels)
                    out.writeInt(img.height)
                    out.writeInt(img.width)
                    out.write(pixels, 0, size)
                    out.flush()
                } finally {
                    img.close()
                }
                synchronized(lock) { credits-- }
            }
        } catch (e: EOFException) {
            println("Client left: $e")
        } finally {
            awake.takeIf { it.isHeld }?.release()
            // Without DESTROY_CONTENT_ON_REMOVAL, Android returns the app to the phone here.
            display.release()
            reader.close()
        }
    }

    /**
     * A display in its own group has its own screen timeout. Without this, Android
     * puts it to sleep after the timeout and stops the app, freezing the picture.
     */
    @Suppress("DEPRECATION")
    private fun keepAwake(shell: Context, displayId: Int): PowerManager.WakeLock {
        val power = shell.getSystemService(Context.POWER_SERVICE) as PowerManager
        val perDisplay = PowerManager::class.java.getMethod(
            "newWakeLock", Int::class.java, String::class.java, Int::class.java,
        )
        val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
        val lock = perDisplay.invoke(power, flags, "mapmirror:display", displayId) as PowerManager.WakeLock
        lock.acquire()
        println("Holding wake lock for display $displayId")
        return lock
    }

    /** Moves the app's existing task, keeping any navigation, or starts the app there. */
    private fun moveApp(app: String, displayId: Int): String {
        val taskLine = Regex("""taskId=(\d+): ${Regex.escape(app)}/""")
        val task = taskLine.find(run("am stack list"))?.groupValues?.get(1)
        if (task != null) {
            run("am display move-stack $task $displayId")
            return "moved the app"
        }
        val launcher = run(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER $app"
        ).lines().map { it.trim() }.lastOrNull { COMPONENT.matches(it) }
            ?: return "app not found"
        run("am start --display $displayId -n $launcher")
        return "started the app"
    }

    private fun run(cmd: String): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    private fun flags(): Int {
        var f = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY
        if (Build.VERSION.SDK_INT >= 33) {
            f = f or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
        }
        if (Build.VERSION.SDK_INT >= 34) f = f or FLAG_OWN_FOCUS
        return f
    }
}
