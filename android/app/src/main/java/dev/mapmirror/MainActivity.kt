package dev.mapmirror

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.app.AlertDialog
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private val applyMapDpi = Runnable { CaptureService.sendMapDpi(settings.mapDpi) }

    private val pairNotificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPairing() else toast("Pairing needs notifications, to enter the code")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode != RESULT_OK || data == null) {
                toast("Screen capture was not allowed")
                return@registerForActivityResult
            }
            val (w, h, dpi) = screenSize()
            val i = Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, res.resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                .putExtra(CaptureService.EXTRA_W, w)
                .putExtra(CaptureService.EXTRA_H, h)
                .putExtra(CaptureService.EXTRA_DPI, dpi)
            ContextCompat.startForegroundService(this, i)
            openApp()
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val bleDenied = result.any { (perm, ok) -> !ok && perm != Manifest.permission.POST_NOTIFICATIONS }
            if (bleDenied) {
                toast("Bluetooth permission is needed to reach the display")
            } else {
                beginSource()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        val source = findViewById<RadioGroup>(R.id.source)
        source.check(if (settings.source == Settings.VIRTUAL) R.id.radioVirtual else R.id.radioScreen)
        source.setOnCheckedChangeListener { _, id ->
            settings.source = if (id == R.id.radioVirtual) Settings.VIRTUAL else Settings.SCREEN
            applySourceLayout()
        }
        applySourceLayout()

        val colours = findViewById<RadioGroup>(R.id.mapColours)
        colours.check(
            when (settings.mapColours) {
                Settings.COLOURS_DAY -> R.id.coloursDay
                Settings.COLOURS_NIGHT -> R.id.coloursNight
                else -> R.id.coloursNormal
            }
        )
        colours.setOnCheckedChangeListener { _, id ->
            settings.mapColours = when (id) {
                R.id.coloursDay -> Settings.COLOURS_DAY
                R.id.coloursNight -> Settings.COLOURS_NIGHT
                else -> Settings.COLOURS_NORMAL
            }
            CaptureService.redrawPanel()
        }

        // Touches on the preview go to the app on the virtual display.
        findViewById<ImageView>(R.id.preview).setOnTouchListener { v, e ->
            if (!CaptureService.interactive) return@setOnTouchListener false
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent.requestDisallowInterceptTouchEvent(true)
            CaptureService.sendTouch(e, v.width, v.height)
            true
        }
        findViewById<Button>(R.id.back).setOnClickListener { CaptureService.sendBack() }

        val status = findViewById<TextView>(R.id.status)
        val preview = findViewById<ImageView>(R.id.preview)

        bindSeekBars()

        findViewById<CheckBox>(R.id.rotate180).apply {
            isChecked = settings.rotate180
            setOnCheckedChangeListener { _, on -> settings.rotate180 = on }
        }

        findViewById<Button>(R.id.start).setOnClickListener { start() }
        findViewById<Button>(R.id.stop).setOnClickListener {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        }
        findViewById<Button>(R.id.openApp).setOnClickListener { openApp() }
        findViewById<Button>(R.id.chooseApp).setOnClickListener { chooseApp {} }
        showChosenApp()
        findViewById<Button>(R.id.pair).setOnClickListener { pair() }
        findViewById<Button>(R.id.forgetDisplay).setOnClickListener { forgetDisplay() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CaptureService.status.collect { status.text = it } }
                launch { CaptureService.preview.collect { bmp -> preview.setImageBitmap(bmp) } }
                launch {
                    CaptureService.previewFlipped.collect { flip ->
                        preview.colorFilter = if (flip) CaptureService.flipFilter else null
                    }
                }
                launch { DisplayConfirm.code.collect { code -> showConfirm(code) } }
                launch {
                    CaptureService.running.collect { on ->
                        for (i in 0 until source.childCount) source.getChildAt(i).isEnabled = !on
                    }
                }
            }
        }
    }

    private var confirmDialog: AlertDialog? = null

    /** The display check, also offered in a notification for when another app is in front. */
    private fun showConfirm(code: String?) {
        confirmDialog?.dismiss()
        confirmDialog = null
        if (code == null) return
        confirmDialog = AlertDialog.Builder(this)
            .setTitle("Check the display")
            .setMessage(
                "Does the display show $code?\n\nOnly tap Yes if the codes match. " +
                    "Otherwise another device may be pretending to be your display."
            )
            .setCancelable(false)
            .setPositiveButton("Yes, it matches") { _, _ -> DisplayConfirm.answer(this, true) }
            .setNegativeButton("No") { _, _ -> DisplayConfirm.answer(this, false) }
            .show()
    }

    override fun onStart() {
        super.onStart()
        CaptureService.previewVisible = true
    }

    override fun onStop() {
        CaptureService.previewVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        confirmDialog?.dismiss() // the notification still asks
        confirmDialog = null
        super.onDestroy()
    }

    /** In virtual display mode the preview is also the touch screen, so make it big. */
    private fun applySourceLayout() {
        val preview = findViewById<ImageView>(R.id.preview)
        val virtual = settings.source == Settings.VIRTUAL
        val density = resources.displayMetrics.density
        val width = if (virtual) {
            resources.displayMetrics.widthPixels - (2 * 16 * density).toInt() // layout padding
        } else {
            (180 * density).toInt()
        }
        preview.layoutParams = preview.layoutParams.apply {
            this.width = width
            height = width * CaptureService.OUT_H / CaptureService.OUT_W
        }
        findViewById<Button>(R.id.back).visibility = if (virtual) View.VISIBLE else View.GONE
        // The whole virtual display is sent, so there's nothing to crop.
        val crop = intArrayOf(R.id.zoomLabel, R.id.zoom, R.id.centerLabel, R.id.center, R.id.centerXLabel, R.id.centerX)
        for (id in crop) findViewById<View>(id).visibility = if (virtual) View.GONE else View.VISIBLE
        val mapText = if (virtual) View.VISIBLE else View.GONE
        findViewById<View>(R.id.mapTextLabel).visibility = mapText
        findViewById<View>(R.id.mapText).visibility = mapText
    }

    private fun bindSeekBars() {
        bindSeek(R.id.mapText, R.id.mapTextLabel, DisplayHostMain.MIN_DPI, DisplayHostMain.MAX_DPI, settings.mapDpi, {
            settings.mapDpi = it
            // The app redraws its whole layout for each change, so wait for the slider to settle.
            window.decorView.removeCallbacks(applyMapDpi)
            window.decorView.postDelayed(applyMapDpi, 300)
        }) { "Map text size: ${it * 100 / 200}%" }
        bindSeek(R.id.fps, R.id.fpsLabel, 1, 20, settings.fps, { settings.fps = it }) {
            "Frame rate on Wi-Fi: $it fps (lower on BLE)"
        }
        bindSeek(R.id.quality, R.id.qualityLabel, 20, 90, settings.quality, { settings.quality = it }) {
            "JPEG quality on Wi-Fi: $it (lower on BLE)"
        }
        bindSeek(R.id.zoom, R.id.zoomLabel, 30, 100, settings.zoomPct, { settings.zoomPct = it }) {
            "Crop width: $it% of screen (smaller = more zoomed in)"
        }
        bindSeek(R.id.center, R.id.centerLabel, 15, 90, settings.centerPct, { settings.centerPct = it }) {
            "Crop centre: $it% from top"
        }
        bindSeek(R.id.centerX, R.id.centerXLabel, 15, 90, settings.centerXPct, { settings.centerXPct = it }) {
            "Crop centre: $it% from left"
        }
    }

    private fun bindSeek(
        seekId: Int, labelId: Int, min: Int, max: Int, value: Int,
        set: (Int) -> Unit, fmt: (Int) -> String,
    ) {
        val seek = findViewById<SeekBar>(seekId)
        val label = findViewById<TextView>(labelId)
        seek.max = max - min
        seek.progress = value.coerceIn(min, max) - min
        label.text = fmt(seek.progress + min)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                label.text = fmt(p + min)
                if (fromUser) set(p + min)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun start() {
        if (CaptureService.running.value) {
            toast("Already running")
            return
        }
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) beginSource() else permLauncher.launch(missing.toTypedArray())
    }

    private fun beginSource() {
        if (settings.source == Settings.VIRTUAL) requestVirtual() else requestProjection()
    }

    private fun requestVirtual() {
        when {
            Build.VERSION.SDK_INT < 33 -> toast("The virtual display needs Android 13 or newer")
            !settings.adbPaired -> toast("Tap \"Pair wireless debugging\" first")
            settings.navPackage.isEmpty() -> chooseApp { requestVirtual() }
            else -> ContextCompat.startForegroundService(
                this,
                Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START_VIRTUAL),
            )
        }
    }

    /** For a replaced display. The next Start pairs with whichever display it finds. */
    private fun forgetDisplay() {
        if (CaptureService.running.value) {
            toast("Stop mirroring first")
            return
        }
        val address = settings.displayAddress
        if (address.isEmpty()) {
            toast("No display is paired")
            return
        }
        settings.displayAddress = ""
        settings.displayKey = ""
        try {
            val adapter = getSystemService(BluetoothManager::class.java).adapter
            val device = adapter.getRemoteDevice(address)
            device.javaClass.getMethod("removeBond").invoke(device) // hidden, no public API
            toast("Forgotten. To pair again, restart the display, then tap Start within 2 minutes")
        } catch (e: Exception) {
            toast("Also remove MapMirror in Bluetooth settings")
        }
    }

    private fun pair() {
        if (Build.VERSION.SDK_INT < 33) {
            toast("The virtual display needs Android 13 or newer")
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) {
            startPairing()
        } else {
            pairNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startPairing() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, PairingService::class.java).setAction(PairingService.ACTION_START),
        )
        toast("Open Wireless debugging → Pair device with pairing code")
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // On Android 14+ the system dialog offers "A single app": pick the navigation
        // app to stream only it, even while other apps are open.
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun openApp() {
        if (settings.navPackage.isEmpty()) {
            chooseApp { openApp() }
            return
        }
        val i = packageManager.getLaunchIntentForPackage(settings.navPackage)
        if (i != null) startActivity(i) else toast("That app is no longer installed. Choose another")
    }

    /** Lists the phone's apps, for the person to pick their navigation app. */
    private fun chooseApp(then: () -> Unit) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .sortedBy { it.first.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("Choose your navigation app")
            .setItems(apps.map { it.first }.toTypedArray()) { _, i ->
                settings.navPackage = apps[i].second
                showChosenApp()
                then()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChosenApp() {
        val label = settings.navPackage.takeIf { it.isNotEmpty() }?.let {
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
        findViewById<Button>(R.id.chooseApp).text = "Navigation app: ${label ?: "not chosen"}"
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
