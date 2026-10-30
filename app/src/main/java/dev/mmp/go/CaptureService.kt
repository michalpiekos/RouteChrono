package dev.mmp.go

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import com.google.android.gms.location.*
import dev.mmp.core.*

class CaptureService : Service() {
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val manager by lazy { getSystemService(LocationManager::class.java) }
    private var tracker = EndpointTracker(emptyList())
    private var pairSpecs = emptyList<EndpointPair>()
    private lateinit var cooldownStore: CooldownStore
    private var manualId: String? = null
    private var lastMono = -1L
    private var interval = 0L
    @Volatile private var closed = false
    private var token = 0L
    private var startedAt = 0L
    @Volatile private var modeTitle = "Starting"
    @Volatile private var modeDetail = "Preparing GPS"
    private var lastNotification = ""
    private val shutdownCallbacks = mutableListOf<() -> Unit>()
    private var shutdownComplete = false
    private val watchdog =
        object : Runnable {
            override fun run() {
                if (closed) return
                if (
                    !Tracking.current(this@CaptureService, token) ||
                        !TrackingNotification.allowed(this@CaptureService) ||
                        (SystemClock.elapsedRealtime() - startedAt > 3000 &&
                            !TrackingNotification.visible(this@CaptureService))
                ) {
                    Tracking.exit(this@CaptureService, closeApp = false)
                    return
                }
                publishStatus()
                main.postDelayed(this, 1000)
            }
        }
    private val callback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach(::receive)
            }
        }
    private val native =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                receive(location)
            }

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {
                Tracking.message = "Location is off — waiting for GPS"
            }

            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

    override fun onCreate() {
        super.onCreate()
        store = Store.get(this)
        cooldownStore = CooldownStore(this)
        tracker = EndpointTracker(emptyList(), cooldownStore.load())
        Tracking.lastFixAt = 0L
        token = Tracking.generation(this)
        active = this
        TrackingNotification.createChannel(this)
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "resume"
        val requestToken = intent?.getLongExtra("generation", -1) ?: token
        if (
            closed ||
                requestToken != token ||
                !Tracking.current(this, token) ||
                (intent == null && !Tracking.enabled(this))
        ) {
            if (!Tracking.running) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!TrackingNotification.allowed(this) || !Tracking.precise(this)) {
            Tracking.exit(this, closeApp = false)
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                TrackingNotification.ID,
                TrackingNotification.build(
                    this,
                    "Starting",
                    "Preparing GPS • Exit stops all tracking",
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (_: RuntimeException) {
            Tracking.exit(this, closeApp = false)
            return START_NOT_STICKY
        }
        lastNotification = ""
        Tracking.running = true
        startedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 1000)
        val wall = System.currentTimeMillis()
        val mono = SystemClock.elapsedRealtime()
        store.executor.execute {
            if (closed || !Tracking.current(this, token)) return@execute
            when (action) {
                "start" ->
                    if (manualId == null) {
                        interruptAutomatic()
                        val id = newId()
                        store.dao.journey(JourneyRow(id, null, "outward", "manual", wall, mono))
                        manualId = id
                        Tracking.manual = true
                    }
                "enable" -> Tracking.automatic(this, token, true)
                "stop" -> finishManual(wall, mono)
                "pause" -> {
                    Tracking.automatic(this, token, false)
                    interruptAutomatic()
                }
            }
            reloadPairs()
            updateMode()
        }
        return START_STICKY
    }

    private fun publishStatus() {
        if (closed || !Tracking.current(this, token)) return
        if (!TrackingNotification.allowed(this)) {
            Tracking.exit(this, closeApp = false)
            return
        }
        val waiting =
            Tracking.lastFixAt == 0L || SystemClock.elapsedRealtime() - Tracking.lastFixAt > 15000
        val detail = if (waiting) "Waiting for GPS • $modeDetail" else modeDetail
        Tracking.message = "$modeTitle • $detail"
        val text = "$modeTitle|$detail"
        if (text != lastNotification) {
            lastNotification = text
            getSystemService(NotificationManager::class.java)
                .notify(
                    TrackingNotification.ID,
                    TrackingNotification.build(this, modeTitle, detail),
                )
        }
    }

    private fun interruptAutomatic() {
        store.db.runInTransaction {
            store.dao
                .journeys()
                .filter { it.status == "recording" && it.source == "automatic" }
                .forEach { store.discard(it.id) }
        }
        tracker = EndpointTracker(pairSpecs, tracker.cooldowns)
    }

    private fun reloadPairs() {
        val next = store.dao.pairs().filter { !it.archived }.map { it.spec() }
        if (next != pairSpecs) {
            interruptAutomatic()
            pairSpecs = next
            tracker = EndpointTracker(next, tracker.cooldowns)
        }
    }

    private fun finishManual(wall: Long, mono: Long) {
        val id = manualId ?: return
        val result = store.finishManual(id, wall, mono)
        val learned = result?.learned == true
        manualId = null
        Tracking.manual = false
        if (learned) Tracking.automatic(this, token, true)
        Tracking.message =
            when {
                result?.saved != true && learned ->
                    "Recording incomplete · not saved. Its endpoints are now monitored in both directions."
                result?.saved != true -> "Recording incomplete · not saved."
                learned -> "Journey saved. Its endpoints are now monitored in both directions."
                else ->
                    "Journey saved without a new pair. Endpoints need fresh GPS and must be over 150 m apart."
            }
        Tracking.prefs(this).edit().putString("last_result", Tracking.message).apply()
        tracker = EndpointTracker(pairSpecs, tracker.cooldowns)
    }

    private fun announceAbandoned(event: TrackingEvent.Abandoned) {
        val detail = TrackingNotification.abandonedMessage(event.pair.name)
        Tracking.prefs(this).edit { putString("last_result", detail) }
        TrackingNotification.abandoned(this, event.pair.id, detail)
    }

    private fun receive(location: Location) {
        val now = SystemClock.elapsedRealtime()
        val f =
            Fix(
                Point(location.latitude, location.longitude),
                location.time,
                location.elapsedRealtimeNanos / 1000000,
                location.accuracy.toDouble(),
            )
        if (!location.hasAccuracy() || !usable(f) || now - f.monoMs !in 0..15000L) return
        store.executor.execute {
            if (closed || !Tracking.current(this, token) || f.monoMs <= lastMono) return@execute
            lastMono = f.monoMs
            Tracking.lastFixAt = f.monoMs
            manualId?.let { id ->
                val j = store.dao.journey(id)!!
                if (f.monoMs >= j.startMono) store.dao.point(TrackRow.from(id, f))
            }
            if (manualId == null && Tracking.enabled(this)) {
                val before = tracker.cooldowns
                val events = tracker.feed(f)
                if (before != tracker.cooldowns) cooldownStore.save(tracker.cooldowns)
                store.accept(events)
                events.filterIsInstance<TrackingEvent.Abandoned>().forEach(::announceAbandoned)
            }
            updateMode()
        }
    }

    private fun updateMode() {
        if (closed || !Tracking.current(this, token)) return
        val automatic = Tracking.enabled(this) && pairSpecs.isNotEmpty()
        if (manualId == null && !automatic) {
            main.post { shutdown {} }
            return
        }
        modeTitle =
            if (manualId != null || tracker.activeCount > 0) "Recording journey"
            else "Monitoring in background"
        modeDetail =
            when {
                manualId != null ->
                    "Manual recording • Stop saves endpoints; Exit ends all tracking"
                tracker.activeCount > 0 ->
                    "${tracker.activeCount} automatic journey(s) • Exit stops all tracking"
                else ->
                    "Watching ${pairSpecs.size} pair(s), both directions • Exit stops all tracking"
            }
        val next = if (manualId != null || tracker.activeCount > 0) 1000L else 5000L
        main.post {
            if (!closed && Tracking.current(this, token)) {
                publishStatus()
                if (interval != next) {
                    interval = next
                    request(next)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun request(ms: Long) {
        removeUpdates()
        try {
            if (Tracking.play(this)) {
                val req =
                    LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ms)
                        .setMinUpdateIntervalMillis(ms / 2)
                        .setMaxUpdateDelayMillis(0)
                        .setMaxUpdateAgeMillis(0)
                        .build()
                fused
                    .requestLocationUpdates(req, callback, Looper.getMainLooper())
                    .addOnFailureListener { fail() }
            } else
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    ms,
                    0f,
                    native,
                    Looper.getMainLooper(),
                )
        } catch (_: RuntimeException) {
            fail()
        }
    }

    private fun fail() {
        if (closed) return
        Tracking.message = "GPS request failed. Check location permission and reopen the app."
        stopSelf()
    }

    private fun removeUpdates() {
        try {
            if (Tracking.play(this)) fused.removeLocationUpdates(callback)
        } catch (_: RuntimeException) {}
        try {
            manager.removeUpdates(native)
        } catch (_: RuntimeException) {}
    }

    internal fun shutdown(done: () -> Unit) {
        if (shutdownComplete) {
            done()
            return
        }
        shutdownCallbacks += done
        if (closed) return
        closed = true
        main.removeCallbacks(watchdog)
        removeUpdates()
        store.executor.execute {
            runCatching { store.discardIncomplete() }
                .onFailure {
                    android.util.Log.e("MmpGo", "Incomplete recordings left for the next cleanup", it)
                }
            main.post {
                Tracking.running = false
                Tracking.manual = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                shutdownComplete = true
                val callbacks = shutdownCallbacks.toList()
                shutdownCallbacks.clear()
                callbacks.forEach { it() }
            }
        }
    }

    override fun onDestroy() {
        closed = true
        main.removeCallbacks(watchdog)
        Tracking.running = false
        Tracking.manual = false
        if (active === this) active = null
        removeUpdates()
        store.executor.execute { store.discardIncomplete() }
        super.onDestroy()
    }

    companion object {
        internal var active: CaptureService? = null
            private set
    }
}
