package dev.mapmirror

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Captures the screen with MediaProjection, crops a 240:280 window around the
 * navigation arrow, scales it to 240x280, JPEG-encodes it and pushes it to the
 * display. Only the newest frame is ever sent; stale frames are dropped.
 *
 * In virtual display mode, the navigation app runs on a private display owned by the display
 * service instead, and every frame of that display is sent without cropping.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "dev.mapmirror.START"
        const val ACTION_START_VIRTUAL = "dev.mapmirror.START_VIRTUAL"
        const val ACTION_STOP = "dev.mapmirror.STOP"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"
        const val EXTRA_W = "w"
        const val EXTRA_H = "h"
        const val EXTRA_DPI = "dpi"

        const val OUT_W = 240
        const val OUT_H = 280
        /** Must stay below MAX_FRAME in the firmware (48 KB). */
        const val MAX_FRAME_BYTES = 45_000

        /** Capture at full resolution: a picture-in-picture window is only about
         *  a third of the screen wide, so half resolution would be upscaled. */
        private const val CAPTURE_SCALE = 1f

        /** Twice the panel, so the map's text stays sharp after scaling down. */
        private const val VD_W = OUT_W * 2
        private const val VD_H = OUT_H * 2
        private const val PREVIEW_FRAME_MS = 66L

        private const val BLE_MAX_FPS = 5
        private const val BLE_MAX_QUALITY = 45

        // A frame must cross these to count as changing between dark and light. The gap
        // stops the filter flickering on frames in between.
        private const val DARK_BELOW = 110
        private const val LIGHT_ABOVE = 145

        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1

        val status = MutableStateFlow("Idle")
        val preview = MutableStateFlow<Bitmap?>(null)
        val running = MutableStateFlow(false)

        /** Set while MapMirror is on screen, so the preview only costs CPU when it's seen. */
        @Volatile var previewVisible = false

        /** True when the virtual display preview should show the colour filter too. */
        val previewFlipped = MutableStateFlow(false)

        /**
         * Swaps light and dark, then turns the hues back, so blue stays blue: the same
         * as inverting and rotating the hue by 180 degrees.
         */
        val flipFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.574f, -1.430f, -0.144f, 0f, 255f,
                    -0.426f, -0.430f, -0.144f, 0f, 255f,
                    -0.426f, -1.430f, 0.856f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )

        /** Set while streaming: sends the current picture again, for settings that change it. */
        @Volatile private var redraw: (() -> Unit)? = null

        fun redrawPanel() {
            redraw?.invoke()
        }

        @Volatile private var link: DisplayHostLink? = null
        private val input = Executors.newSingleThreadExecutor()

        /** True while touches on the preview can reach the virtual display. */
        val interactive get() = link != null

        /** Forwards a touch on a view showing the whole virtual display. */
        fun sendTouch(e: MotionEvent, viewW: Int, viewH: Int) {
            val l = link ?: return
            val index = e.actionIndex
            val count = minOf(e.pointerCount, DisplayHostMain.MAX_POINTERS)
            if (index >= count || viewW <= 0 || viewH <= 0) return
            val ids = IntArray(count) { e.getPointerId(it) }
            val xs = IntArray(count) { (e.getX(it) * VD_W / viewW).toInt().coerceIn(0, VD_W - 1) }
            val ys = IntArray(count) { (e.getY(it) * VD_H / viewH).toInt().coerceIn(0, VD_H - 1) }
            val action = e.actionMasked
            input.execute { sendQuietly { l.sendTouch(action, index, ids, xs, ys) } }
        }

        fun sendBack() {
            val l = link ?: return
            input.execute { sendQuietly { l.sendBack() } }
        }

        fun sendMapDpi(dpi: Int) {
            val l = link ?: return
            input.execute { sendQuietly { l.sendDpi(dpi) } }
        }

        /** A broken link is reported by the frame loop, which stops the service. */
        private inline fun sendQuietly(send: () -> Unit) {
            try { send() } catch (_: IOException) {}
        }
    }

    private class Bytes(size: Int) : ByteArrayOutputStream(size) {
        fun raw(): ByteArray = buf
    }

    private lateinit var settings: Settings
    private lateinit var captureThread: HandlerThread
    private lateinit var handler: Handler
    private val encoder = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var transport: Transport? = null
    private var captureDpi = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var virtualMode = false

    // Newest virtual display frame. Each is a new bitmap, as the preview may hold it.
    @Volatile private var latestFrame: Bitmap? = null
    @Volatile private var encodedFrame: Bitmap? = null
    private var frameLooksDark = false

    // Full captured frame; may be wider than the image because of row padding.
    private var src: Bitmap? = null
    private var srcW = 0
    private var srcH = 0
    private var padded: ByteBuffer? = null

    private val out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(out)
    // The frame turned upside down, for a display mounted that way. The preview stays upright.
    private val upsideDown = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
    private val upsideDownCanvas = Canvas(upsideDown).apply { rotate(180f, OUT_W / 2f, OUT_H / 2f) }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect(0, 0, OUT_W, OUT_H)
    private val pixels = IntArray(OUT_W * OUT_H)
    private val jpeg = Bytes(64 * 1024)

    private var nextAllowedAt = 0L
    private var pumpPending = false
    private var lastHash = 0
    private var lastSentAt = 0L
    private var statWindowStart = 0L
    private var statFrames = 0
    private var statBytes = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        captureThread = HandlerThread("capture").also { it.start() }
        handler = Handler(captureThread.looper)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Mirroring", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            DisplayConfirm.ACTION_YES -> DisplayConfirm.answer(this, true)
            DisplayConfirm.ACTION_NO -> DisplayConfirm.answer(this, false)
            ACTION_START -> {
                if (projection != null || virtualMode) return START_NOT_STICKY
                // Must be in the foreground *before* getMediaProjection() (Android 10+).
                startInForeground(virtual = false)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val w = intent.getIntExtra(EXTRA_W, 1080)
                val h = intent.getIntExtra(EXTRA_H, 2400)
                val dpi = intent.getIntExtra(EXTRA_DPI, 440)
                handler.post { startCapture(code, data, w, h, dpi) }
            }
            ACTION_START_VIRTUAL -> {
                if (projection != null || virtualMode) return START_NOT_STICKY
                virtualMode = true
                startInForeground(virtual = true)
                handler.post { startVirtual() }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(virtual: Boolean) {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .setContentTitle("MapMirror is streaming")
            .setContentText("Sending the map to your display")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        // Android 14+ only allows the media projection type after capture consent.
        val type = when {
            virtual && Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            !virtual && Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    // ---- capture --------------------------------------------------------

    private fun startCapture(code: Int, data: Intent, w: Int, h: Int, dpi: Int) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            setStatus("Capture failed: ${e.message}")
            null
        }
        if (mp == null) {
            stopSelf()
            return
        }
        projection = mp

        // Android 14+ requires a callback before createVirtualDisplay().
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!running.value) return // we stopped it ourselves
                setStatus("Capture stopped by system")
                stopSelf()
            }

            // Android 14+: size of the captured content changed (rotation, or a
            // single-app capture whose window size differs from the screen).
            override fun onCapturedContentResize(width: Int, height: Int) {
                resize(width, height)
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                if (!isVisible) setStatus("Captured app is hidden — display keeps last frame")
            }
        }, handler)

        captureDpi = max(1, (dpi * CAPTURE_SCALE).toInt())
        val (vw, vh) = scaled(w, h)
        val r = newReader(vw, vh)
        reader = r
        display = mp.createVirtualDisplay(
            "MapMirror", vw, vh, captureDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler,
        )

        startTransport()
        redraw = {
            if (!encoder.isShutdown && busy.compareAndSet(false, true)) {
                encoder.execute {
                    try { encodeAndSend() } catch (t: Throwable) { setStatus("Error: ${t.message}") } finally { busy.set(false) }
                }
            }
        }

        // Android stops drawing the screen once it turns off, so there would be
        // nothing to capture. Let it dim, but keep it on while mirroring.
        @Suppress("DEPRECATION")
        holdWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK)

        running.value = true
    }

    private fun startVirtual() {
        // The virtual display doesn't need the phone's screen, only the CPU awake.
        holdWakeLock(PowerManager.PARTIAL_WAKE_LOCK)
        running.value = true
        thread(name = "virtual-display", isDaemon = true) { runVirtual() }
    }

    /**
     * Takes one frame at a time. The preview uses every frame, and the panel takes
     * the newest one whenever it's free, so a slow link doesn't slow down touch.
     */
    private fun runVirtual() {
        try {
            val l = DisplayHostLink.open(this, settings, VD_W, VD_H, settings.mapDpi, ::setStatus)
            link = l
            if (!running.value) { l.close(); return } // stopped while connecting
            setStatus("Virtual display ready: ${l.status}")
            redraw = {
                encodedFrame = null
                sendLatestToPanel()
            }
            // Only now: joining the display's Wi-Fi turns off wireless debugging,
            // which starting the display service needs.
            startTransport()
            while (running.value) {
                val started = SystemClock.uptimeMillis()
                l.nextFrame()
                val row = Bitmap.createBitmap(l.rowPixels, l.height, Bitmap.Config.ARGB_8888)
                row.copyPixelsFromBuffer(ByteBuffer.wrap(l.pixels))
                val frame = if (l.width == l.rowPixels) row else Bitmap.createBitmap(row, 0, 0, l.width, l.height)
                val showing = previewVisible
                if (showing) preview.value = frame
                latestFrame = frame
                sendLatestToPanel()
                val interval = if (showing) PREVIEW_FRAME_MS else panelIntervalMs()
                val spare = interval - (SystemClock.uptimeMillis() - started)
                if (spare > 0) Thread.sleep(spare)
            }
        } catch (e: Exception) {
            if (running.value) {
                setStatus("Capture stopped: ${e.message ?: e}")
                stopSelf()
            }
        }
    }

    private fun panelIntervalMs(): Long {
        val fps = settings.fps.coerceIn(1, 30)
        return 1000L / (if (transport?.slow == true) minOf(fps, BLE_MAX_FPS) else fps)
    }

    /** Runs until the panel has the newest frame, so the last change is never lost. */
    private fun sendLatestToPanel() {
        if (encoder.isShutdown || !busy.compareAndSet(false, true)) return
        encoder.execute {
            try {
                while (running.value) {
                    val f = latestFrame
                    if (f == null || f === encodedFrame) break
                    encodedFrame = f
                    src = f
                    srcW = f.width
                    srcH = f.height
                    val started = SystemClock.uptimeMillis()
                    encodeAndSend()
                    val spare = panelIntervalMs() - (SystemClock.uptimeMillis() - started)
                    if (spare > 0) Thread.sleep(spare)
                }
            } catch (_: InterruptedException) {
                return@execute // shutting down
            } catch (t: Throwable) {
                setStatus("Error: ${t.message}")
            } finally {
                busy.set(false)
            }
            if (latestFrame !== encodedFrame) sendLatestToPanel()
        }
    }

    private fun startTransport() {
        transport = AutoTransport(this, settings, ::setStatus).also { it.start() }
    }

    private fun holdWakeLock(level: Int) {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(level, "mapmirror:stream")
            .also { it.acquire() }
    }

    private fun scaled(w: Int, h: Int): Pair<Int, Int> {
        val vw = max(OUT_W, (w * CAPTURE_SCALE).toInt()) and 1.inv()
        val vh = max(OUT_H, (h * CAPTURE_SCALE).toInt()) and 1.inv()
        return vw to vh
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also {
            it.setOnImageAvailableListener({ onFrameAvailable() }, handler)
        }

    private fun resize(width: Int, height: Int) {
        val d = display ?: return
        val (vw, vh) = scaled(width, height)
        val old = reader
        if (old != null && old.width == vw && old.height == vh) return
        val r = newReader(vw, vh)
        reader = r
        d.resize(vw, vh, captureDpi)
        d.surface = r.surface
        old?.close()
    }

    /** Runs on the capture thread. Throttles to the configured fps. */
    private fun onFrameAvailable() {
        if (pumpPending) return
        val wait = nextAllowedAt - SystemClock.uptimeMillis()
        if (wait > 0 || busy.get()) {
            pumpPending = true
            handler.postDelayed(pumpRunnable, max(wait, 8L))
        } else {
            pump()
        }
    }

    private val pumpRunnable = Runnable {
        pumpPending = false
        onFrameAvailable()
    }

    private fun pump() {
        val r = reader ?: return
        val img = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        try {
            val plane = img.planes[0]
            val bw = plane.rowStride / plane.pixelStride
            val bh = img.height
            var b = src
            if (b == null || b.width != bw || b.height != bh) {
                b?.recycle()
                b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                src = b
            }
            val buf = plane.buffer
            buf.rewind()
            val need = bw * bh * 4
            if (buf.remaining() >= need) {
                b.copyPixelsFromBuffer(buf)
            } else {
                // Some devices omit padding on the last row.
                val tmp = padded?.takeIf { it.capacity() >= need }
                    ?: ByteBuffer.allocateDirect(need).also { padded = it }
                tmp.clear()
                tmp.put(buf)
                tmp.position(0)
                b.copyPixelsFromBuffer(tmp)
            }
            srcW = img.width
            srcH = bh
        } finally {
            img.close()
        }

        if (encoder.isShutdown) return
        busy.set(true)
        nextAllowedAt = SystemClock.uptimeMillis() + panelIntervalMs()
        encoder.execute {
            try {
                encodeAndSend()
            } catch (t: Throwable) {
                setStatus("Error: ${t.message}")
            } finally {
                busy.set(false)
            }
        }
    }

    // ---- encode + send (encoder thread) ---------------------------------

    private fun encodeAndSend() {
        val b = src ?: return
        val w = srcW
        val h = srcH
        if (w <= 0 || h <= 0) return

        if (virtualMode) {
            srcRect.set(0, 0, w, h)
        } else {
            // Screen off or locked (e.g. the power button): send nothing, so the panel
            // keeps the last frame instead of showing black or the lock screen.
            val screenOff = !getSystemService(PowerManager::class.java).isInteractive
            if (screenOff || getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                setStatus("Phone screen off or locked — display keeps last frame")
                return
            }

            // Crop window with the panel's 240:280 aspect, positioned by the
            // horizontal and vertical "centre" settings.
            val aspect = OUT_W.toFloat() / OUT_H
            var cw = w * settings.zoomPct / 100f
            var ch = cw / aspect
            if (ch > h) {
                ch = h.toFloat()
                cw = ch * aspect
            }
            val cx = w * settings.centerXPct / 100f
            val cy = h * settings.centerPct / 100f
            val left = (cx - cw / 2).coerceIn(0f, w - cw)
            val top = (cy - ch / 2).coerceIn(0f, h - ch)
            srcRect.set(left.toInt(), top.toInt(), (left + cw).toInt(), (top + ch).toInt())
        }
        canvas.drawBitmap(b, srcRect, dstRect, paint)
        applyColourFilter(b)

        // In virtual mode the preview already shows the sharper full-size frame.
        if (!virtualMode) preview.value = out.copy(Bitmap.Config.ARGB_8888, false)

        // Skip unchanged frames, but resend every 2 s so a reconnected display fills in.
        out.getPixels(pixels, 0, OUT_W, 0, 0, OUT_W, OUT_H)
        val rotate = settings.rotate180
        val hash = pixels.contentHashCode() xor (if (rotate) 0x5a5a5a5a else 0) // resend when turned
        val now = SystemClock.uptimeMillis()
        if (hash == lastHash && now - lastSentAt < 2000) return

        val t = transport ?: return
        if (!t.isReady()) return

        val frame = if (rotate) upsideDown.also { upsideDownCanvas.drawBitmap(out, 0f, 0f, null) } else out
        var q = if (t.slow) minOf(settings.quality, BLE_MAX_QUALITY) else settings.quality
        do {
            jpeg.reset()
            frame.compress(Bitmap.CompressFormat.JPEG, q, jpeg)
            q -= 10
        } while (jpeg.size() > MAX_FRAME_BYTES && q >= 15)
        if (jpeg.size() > MAX_FRAME_BYTES) return

        if (t.send(jpeg.raw(), jpeg.size())) {
            lastHash = hash
            lastSentAt = now
            statFrames++
            statBytes += jpeg.size()
            if (now - statWindowStart >= 1000) {
                val secs = (now - statWindowStart) / 1000f
                if (statWindowStart != 0L) {
                    setStatus(
                        "Streaming over ${t.label}: %.1f fps, %d KB/frame".format(
                            statFrames / secs, statBytes / max(1, statFrames) / 1024,
                        )
                    )
                }
                statWindowStart = now
                statFrames = 0
                statBytes = 0
            }
        }
    }

    /** Day and Night only flip frames that show the opposite of what was asked for. */
    private fun applyColourFilter(b: Bitmap) {
        val mode = settings.mapColours
        var flip = false
        if (mode != Settings.COLOURS_NORMAL) {
            out.getPixels(pixels, 0, OUT_W, 0, 0, OUT_W, OUT_H)
            var total = 0L
            var count = 0
            var i = 0
            while (i < pixels.size) {
                val p = pixels[i]
                total += (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
                count++
                i += 7
            }
            val luma = total / count
            if (luma < DARK_BELOW) frameLooksDark = true else if (luma > LIGHT_ABOVE) frameLooksDark = false
            flip = frameLooksDark == (mode == Settings.COLOURS_DAY)
        }
        if (flip) {
            paint.colorFilter = flipFilter
            canvas.drawBitmap(b, srcRect, dstRect, paint)
            paint.colorFilter = null
        }
        previewFlipped.value = flip && virtualMode
    }

    private fun setStatus(msg: String) {
        Log.i("MapMirror", msg)
        status.value = msg
    }

    override fun onDestroy() {
        running.value = false
        redraw = null
        previewFlipped.value = false
        preview.value = null // a stale frame makes a stopped app look frozen
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        link?.close() // the display service then returns the app to the phone
        link = null
        transport?.close()
        transport = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        encoder.shutdownNow()
        captureThread.quitSafely()
        if (!status.value.startsWith("Capture")) setStatus("Stopped")
        super.onDestroy()
    }
}
