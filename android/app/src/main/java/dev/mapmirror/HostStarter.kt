package dev.mapmirror

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Starts the display service in the background while the phone is on Wi-Fi, so it's
 * already running at the motorcycle, where there's no Wi-Fi to start it. It runs until
 * the phone restarts, and after a restart this starts it again the next time the
 * phone is on Wi-Fi. It needs WRITE_SECURE_SETTINGS, to turn Wireless debugging on
 * for a moment, since nobody is there to turn it on by hand.
 */
class HostStarter : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "host-starter") {
            startIfNeeded(this) { Log.i(TAG, it) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val TAG = "MapMirror"
        private const val JOB_ID = 50507
        private const val PERIOD_MS = 15 * 60 * 1000L // the shortest Android allows
        private val busy = AtomicBoolean(false)

        fun canRunUnattended(ctx: Context, s: Settings = Settings(ctx)) =
            s.source == Settings.VIRTUAL && s.adbPaired && DisplayHostLink.canTurnOnWirelessDebugging(ctx)

        /** Called when the app opens and when its settings change. */
        fun schedule(ctx: Context) {
            val jobs = ctx.getSystemService(JobScheduler::class.java)
            if (!canRunUnattended(ctx)) {
                jobs.cancel(JOB_ID)
                return
            }
            if (jobs.getPendingJob(JOB_ID) != null) return
            // Persisted, so it carries on after a restart. That's when it's needed.
            jobs.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, HostStarter::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build()
            )
        }

        /** Starts it now, if it can, without waiting for the job. */
        fun startSoon(ctx: Context, log: (String) -> Unit) {
            val app = ctx.applicationContext
            thread(name = "host-starter") { startIfNeeded(app, log) }
        }

        /** Returns quietly unless the service was started. Mirroring reports its own errors. */
        private fun startIfNeeded(ctx: Context, log: (String) -> Unit) {
            val s = Settings(ctx)
            if (!canRunUnattended(ctx, s) || CaptureService.running.value) return
            if (!DisplayHostLink.onWifi(ctx) || DisplayHostLink.isRunning()) return
            if (!busy.compareAndSet(false, true)) return
            try {
                DisplayHostLink.startService(ctx, s) {}
                log("Display service started. It now works without Wi-Fi until the phone restarts")
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the display service: ${e.message ?: e}")
            } finally {
                busy.set(false)
            }
        }
    }
}
