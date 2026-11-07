package dev.mapmirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The first time the phone sees a display's key, the display and the phone both show a
 * code, and the person confirms they match. A look-alike display can't show the right
 * code on the real one. Asked in the app if it's open, and in a notification over other apps.
 */
object DisplayConfirm {
    const val ACTION_YES = "dev.mapmirror.CONFIRM_YES"
    const val ACTION_NO = "dev.mapmirror.CONFIRM_NO"
    private const val CHANNEL = "confirm"
    private const val NOTIF_ID = 3

    /** The code waiting for an answer, or null. */
    val code = MutableStateFlow<String?>(null)
    private var onAnswer: ((Boolean) -> Unit)? = null

    fun ask(ctx: Context, code: String, answer: (Boolean) -> Unit) {
        synchronized(this) { onAnswer = answer }
        this.code.value = code
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Display check", NotificationManager.IMPORTANCE_HIGH)
        )
        fun action(action: String, req: Int) = PendingIntent.getService(
            ctx, req, Intent(ctx, CaptureService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .setContentTitle("Does the display show $code?")
            .setContentText("Only tap Yes if the codes match")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(0, "Yes, it matches", action(ACTION_YES, 10))
            .addAction(0, "No", action(ACTION_NO, 11))
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (_: SecurityException) {} // notifications refused
    }

    fun answer(ctx: Context, ok: Boolean) {
        val a = synchronized(this) { onAnswer.also { onAnswer = null } }
        clear(ctx)
        a?.invoke(ok)
    }

    fun cancel(ctx: Context) {
        synchronized(this) { onAnswer = null }
        clear(ctx)
    }

    private fun clear(ctx: Context) {
        code.value = null
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}
