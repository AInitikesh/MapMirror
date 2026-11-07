package dev.mapmirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import io.github.muntashirakon.adb.android.AdbMdns

/**
 * One-time pairing with wireless debugging. Android closes its pairing dialog if you
 * leave it, so the code is typed into a notification, which opens on top of the dialog.
 */
class PairingService : Service() {

    companion object {
        const val ACTION_START = "dev.mapmirror.PAIR_START"
        private const val ACTION_CODE = "dev.mapmirror.PAIR_CODE"
        private const val ACTION_CANCEL = "dev.mapmirror.PAIR_CANCEL"
        private const val KEY_CODE = "code"
        private const val CHANNEL = "pairing"
        private const val NOTIF_ID = 2
        private const val TIMEOUT_MS = 5 * 60 * 1000L
    }

    private lateinit var workThread: HandlerThread
    private lateinit var work: Handler
    private var mdns: AdbMdns? = null
    @Volatile private var pairingPort = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        workThread = HandlerThread("pairing").also { it.start() }
        work = Handler(workThread.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_CANCEL -> stopSelf()
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.trim()
                if (!code.isNullOrEmpty()) work.post { pair(code) }
            }
        }
        return START_NOT_STICKY
    }

    private fun start() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(
            this, NOTIF_ID,
            notification("Waiting for the pairing dialog", "Wireless debugging → Pair device with pairing code"),
            type,
        )
        if (mdns == null) {
            mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port ->
                if (port > 0 && port != pairingPort) {
                    pairingPort = port
                    update(codeRequest("Pairing dialog found", "Enter the 6-digit code it shows"))
                }
            }.also { it.start() }
        }
        work.postDelayed({ stopSelf() }, TIMEOUT_MS)
    }

    private fun pair(code: String) {
        val port = pairingPort
        try {
            if (!AdbManager(this).pair(port, code)) throw IllegalStateException("the code wasn't accepted")
            Settings(this).adbPaired = true
            CaptureService.status.value = "Paired with wireless debugging"
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            update(notification("Paired", "MapMirror can now start its display service"))
            stopSelf()
        } catch (e: Exception) {
            update(codeRequest("Pairing failed", "${e.message}. Check the code and try again"))
        }
    }

    private fun update(n: android.app.Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun builder(title: String, text: String): NotificationCompat.Builder {
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, PairingService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .addAction(0, "Cancel", cancel)
    }

    private fun notification(title: String, text: String) = builder(title, text).build()

    private fun codeRequest(title: String, text: String): android.app.Notification {
        // RemoteInput needs a mutable intent so the system can add the typed text.
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val reply = PendingIntent.getService(
            this, 1, Intent(this, PairingService::class.java).setAction(ACTION_CODE),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
        val input = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build()
        val action = NotificationCompat.Action.Builder(0, "Enter code", reply).addRemoteInput(input).build()
        return builder(title, text).addAction(action).build()
    }

    override fun onDestroy() {
        mdns?.stop()
        mdns = null
        work.removeCallbacksAndMessages(null)
        workThread.quitSafely()
        super.onDestroy()
    }
}
