package dev.mapmirror

import android.content.Context

/**
 * Settings live in SharedPreferences. The capture service re-reads them on every
 * frame, so the sliders (fps, quality, zoom, centre) apply live while streaming.
 */
class Settings(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("mapmirror", Context.MODE_PRIVATE)

    /** The paired display. Empty until first paired; then no other display is used. */
    var displayAddress: String
        get() = p.getString("displayAddress", "") ?: ""
        set(v) = p.edit().putString("displayAddress", v).apply()

    /** The paired display's key, in hex, once the person confirmed its code. */
    var displayKey: String
        get() = p.getString("displayKey", "") ?: ""
        set(v) = p.edit().putString("displayKey", v).commit().let {}

    /** Turns the picture upside down, for a display mounted upside down. */
    var rotate180: Boolean
        get() = p.getBoolean("rotate180", false)
        set(v) = p.edit().putBoolean("rotate180", v).apply()

    /** Frame rate on Wi-Fi. BLE is capped lower by CaptureService. */
    var fps: Int
        get() = p.getInt("fpsWifi", 10)
        set(v) = p.edit().putInt("fpsWifi", v).apply()

    /** JPEG quality on Wi-Fi. BLE is capped lower by CaptureService. */
    var quality: Int
        get() = p.getInt("qualityWifi", 60)
        set(v) = p.edit().putInt("qualityWifi", v).apply()

    /** Width of the crop window as a percentage of the phone screen width. */
    var zoomPct: Int
        get() = p.getInt("zoom", 70)
        set(v) = p.edit().putInt("zoom", v).apply()

    /** Vertical centre of the crop window, percent from the top. In navigation
     *  mode the blue arrow sits in the lower part of the screen. */
    var centerPct: Int
        get() = p.getInt("center", 62)
        set(v) = p.edit().putInt("center", v).apply()

    /** Horizontal centre of the crop window, percent from the left. Move it off
     *  50 to aim at a window beside the middle, such as a picture-in-picture window. */
    var centerXPct: Int
        get() = p.getInt("centerX", 50)
        set(v) = p.edit().putInt("centerX", v).apply()

    /** Density of the virtual display. Higher makes the app's text and buttons larger. */
    var mapDpi: Int
        get() = p.getInt("mapDpi", 280)
        set(v) = p.edit().putInt("mapDpi", v).apply()

    /** Normal, or a filter that keeps the map looking like day or night. */
    var mapColours: String
        get() = p.getString("mapColours", COLOURS_NORMAL) ?: COLOURS_NORMAL
        set(v) = p.edit().putString("mapColours", v).apply()

    /** Package of the navigation app to open and to run on the virtual display. */
    var navPackage: String
        get() = p.getString("navPackage", "") ?: ""
        set(v) = p.edit().putString("navPackage", v).apply()

    /** Where frames come from: screen capture, or a virtual display. */
    var source: String
        get() = p.getString("source", SCREEN) ?: SCREEN
        set(v) = p.edit().putString("source", v).apply()

    /** True once this app's key has been paired with wireless debugging. */
    var adbPaired: Boolean
        get() = p.getBoolean("adbPaired", false)
        set(v) = p.edit().putBoolean("adbPaired", v).apply()

    /** Shared secret that the running display service expects from this app. */
    var hostToken: String
        get() = p.getString("hostToken", "") ?: ""
        set(v) = p.edit().putString("hostToken", v).commit().let {}

    companion object {
        const val COLOURS_NORMAL = "normal"
        const val COLOURS_DAY = "day"
        const val COLOURS_NIGHT = "night"
        const val SCREEN = "screen"
        const val VIRTUAL = "virtual"
    }
}
