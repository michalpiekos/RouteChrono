package dev.mmp.go

import android.Manifest
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

object Tracking {
    fun prefs(c: Context) = c.getSharedPreferences("journey_tracking", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("automatic", false)

    fun generation(c: Context) = prefs(c).getLong("generation", 0)

    fun current(c: Context, expected: Long) =
        !prefs(c).getBoolean("exited", false) && generation(c) == expected

    fun precise(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun play(c: Context) =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c) ==
            ConnectionResult.SUCCESS

    @Volatile var running = false
    @Volatile var manual = false
    @Volatile var lastFixAt = 0L
    @Volatile var message = "Ready"
    @Volatile
    var exiting = false
        private set

    @Synchronized
    fun automatic(c: Context, generation: Long, enabled: Boolean): Boolean {
        if (!current(c, generation)) return false
        prefs(c).edit().putBoolean("automatic", enabled).commit()
        return true
    }

    @Synchronized
    fun command(c: Context, action: String, explicit: Boolean = false) {
        if (exiting) return
        if (action == "start" || action == "enable" || action == "resume") {
            if (!TrackingNotification.allowed(c) || !precise(c)) {
                message = "Enable location and notifications before recording"
                return
            }
        }
        if (explicit) prefs(c).edit().putBoolean("exited", false).commit()
        val token = generation(c)
        if (!current(c, token)) return
        try {
            ContextCompat.startForegroundService(
                c,
                Intent(c, CaptureService::class.java)
                    .setAction(action)
                    .putExtra("generation", token),
            )
        } catch (e: RuntimeException) {
            message = "Open the app to resume recording: ${e.javaClass.simpleName}"
        }
    }

    @Synchronized
    internal fun disable(c: Context) {
        prefs(c)
            .edit()
            .putBoolean("automatic", false)
            .putBoolean("exited", true)
            .putLong("generation", generation(c) + 1)
            .commit()
    }

    fun exit(c: Context, closeApp: Boolean = true, done: () -> Unit = {}) {
        val context = c.applicationContext
        synchronized(this) {
            disable(context)
            exiting = true
        }
        val main = Handler(Looper.getMainLooper())
        main.post {
            val finish = {
                running = false
                manual = false
                message = "Tracking stopped"
                if (closeApp)
                    context.getSystemService(ActivityManager::class.java).appTasks.forEach {
                        it.finishAndRemoveTask()
                    }
                exiting = false
                done()
            }
            val service = CaptureService.active
            if (service != null) service.shutdown(finish)
            else {
                context.stopService(Intent(context, CaptureService::class.java))
                val store = Store.get(context)
                store.executor.execute {
                    runCatching { store.recover() }
                        .onFailure {
                            android.util.Log.e(
                                "MmpGo",
                                "Partial recording retained for recovery",
                                it,
                            )
                        }
                    main.post { finish() }
                }
            }
        }
    }
}

class ExitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Tracking.exit(context) { pending.finish() }
    }
}
