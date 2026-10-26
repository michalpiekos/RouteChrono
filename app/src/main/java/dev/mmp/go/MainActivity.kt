package dev.mmp.go

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.os.*
import android.provider.Settings
import android.widget.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.mmp.core.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.maplibre.android.annotations.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var pairs = emptyList<PairRow>()
    private var journeys = emptyList<JourneyRow>()
    private var trace = emptyList<Fix>()
    private var recordingTraces = emptyList<List<Fix>>()
    private val tabViews = mutableListOf<TextView>()
    private var tab = "Record"
    private var editing = false
    private var draftStart: Point? = null
    private var draftEnd: Point? = null
    private var draftEnd2: Point? = null
    private var draftName = ""
    private var pointToSet = 0
    private var selectedPair: String? = null
    private var selectedJourney: String? = null
    private var pendingPermission: (() -> Unit)? = null
    private var pendingNotification: (() -> Unit)? = null
    private var exiting = false
    private var locating: CancellationTokenSource? = null
    private var locatedPosition: Location? = null
    private var renderedLocation: Location? = null
    private var pendingLocateCenter = false
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var recordInfo: TextView? = null
    private var autoSwitch: ToggleButton? = null
    private lateinit var chrome: LinearLayout
    private var displayedActivity: RecordingAppearanceState? = null
    private var commandPending = false
    private var generation = 0
    private var renderRequested = true
    private var resumed = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh =
        object : Runnable {
            override fun run() {
                if (resumed) {
                    load(false)
                    handler.postDelayed(this, 2000)
                }
            }
        }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = Store.get(this)
        state?.let {
            locatedPosition = it.getParcelable("locatedPosition")
            pendingLocateCenter = it.getBoolean("pendingLocateCenter")
            tab = it.getString("tab") ?: "Record"
            editing = it.getBoolean("editing")
            draftName = it.getString("name") ?: ""
            pointToSet = it.getInt("pointToSet")
            if (it.containsKey("slat"))
                draftStart = Point(it.getDouble("slat"), it.getDouble("slon"))
            if (it.containsKey("elat")) draftEnd = Point(it.getDouble("elat"), it.getDouble("elon"))
            if (it.containsKey("e2lat"))
                draftEnd2 = Point(it.getDouble("e2lat"), it.getDouble("e2lon"))
            selectedPair = it.getString("pair")
            selectedJourney = it.getString("journey")
        }
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(AppAppearance.background)
            }
        chrome = root
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        val heading = row(root, compact = true)
        label(heading, getString(R.string.app_name), 22f).apply {
            setTextColor(AppAppearance.mint)
            layoutParams = LinearLayout.LayoutParams(0, -2, 2f)
        }
        button(heading, "Exit", destructive = true) {
            exiting = true
            pendingPermission = null
            pendingNotification = null
            locating?.cancel()
            Tracking.exit(this)
        }
        label(root, getString(R.string.brand_name), 12f)
        status = label(root, "Loading…", 13f)
        val tabs = row(root, compact = true)
        tabs.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(
                    1, 3, false, AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE,
                )
            }
        }
        listOf("Record", "Pairs", "Results").forEachIndexed { index, name ->
            val item = TextView(this).apply {
                text = name
                textSize = 16f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                minHeight = dp(48)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.app.ActionBar\$Tab"
                        info.isSelected = name == tab
                        info.collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, index, 1, false, name == tab)
                    }
                }
                setOnClickListener {
                    if (tab != name) {
                        tab = name
                        selectedJourney = null
                        selectedPair = null
                        trace = emptyList()
                        recordingTraces = emptyList()
                        updateTabs()
                        load(true)
                    }
                }
            }
            tabs.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
            tabViews += item
        }
        updateTabs()
        mapView = object : MapView(this) {
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                parent.requestDisallowInterceptTouchEvent(
                    event.actionMasked != android.view.MotionEvent.ACTION_UP &&
                        event.actionMasked != android.view.MotionEvent.ACTION_CANCEL,
                )
                return super.dispatchTouchEvent(event)
            }
        }
        mapView.onCreate(state)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        val mapHeight = if (resources.configuration.screenHeightDp < 500) 170 else 230
        content.addView(mapView, LinearLayout.LayoutParams(-1, dp(mapHeight)))
        mapView.getMapAsync { m ->
            map = m
            m.setStyle(Style.Builder().fromJson(MAP_STYLE)) {
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(52.0, 19.0), 5.0))
                drawMap(true)
                centerLocatedPosition()
            }
            m.addOnMapClickListener { p ->
                if (locatedPosition != null &&
                    m.queryRenderedFeatures(m.projection.toScreenLocation(p), LOCATION_LAYER).isNotEmpty()
                ) {
                    toast("My location • last located ${date(locatedPosition!!.time)}")
                    true
                } else if (tab == "Pairs" && editing) {
                    val tapped = Point(p.latitude, p.longitude)
                    when (pointToSet) {
                        0 -> {
                            draftStart = tapped
                            pointToSet = 1
                        }
                        1 -> draftEnd = tapped
                        else -> draftEnd2 = tapped
                    }
                    render()
                    drawMap(false)
                    true
                } else false
            }
        }
        body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(24))
            }
        content.addView(body)
        load(true)
    }

    private fun updateTabs() {
        tabViews.forEach { view ->
            val selected = view.text == tab
            view.isSelected = selected
            view.setTextColor(if (selected) AppAppearance.mint else AppAppearance.muted)
            view.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            val background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (selected) Color.rgb(27, 64, 52) else Color.TRANSPARENT)
            }
            val underline = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (selected) AppAppearance.mint else Color.rgb(44, 63, 58))
            }
            view.background = android.graphics.drawable.LayerDrawable(arrayOf(background, underline)).apply {
                setLayerHeight(1, dp(if (selected) 3 else 1))
                setLayerGravity(1, Gravity.BOTTOM)
            }
        }
    }

    private fun label(parent: LinearLayout, value: String, size: Float = 16f): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(if (size < 16f) AppAppearance.muted else AppAppearance.text)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            parent.addView(this)
        }

    private fun row(parent: LinearLayout, compact: Boolean = false) =
        LinearLayout(this).apply {
            orientation = if (!compact && resources.configuration.fontScale >= 1.3f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            parent.addView(this)
        }

    private fun button(parent: LinearLayout, text: String, primary: Boolean = false, destructive: Boolean = false, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            AppAppearance.button(this, primary, destructive)
            setOnClickListener { action() }
            parent.addView(
                this,
                if (parent.orientation == LinearLayout.HORIZONTAL)
                    LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) }
                else LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) },
            )
        }

    private fun load(full: Boolean) {
        renderRequested = renderRequested || full
        val token = ++generation
        val page = tab
        val detail = selectedJourney
        store.reader.execute {
            val ps = store.dao.pairs()
            val js = store.dao.journeys()
            val id =
                detail
                    ?: if (page == "Record") js.firstOrNull { it.status == "recording" }?.id
                    else null
            val live = if (page == "Record") js.filter { it.status == "recording" }
                .map { store.dao.points(it.id).map(TrackRow::fix) } else emptyList()
            val fs = if (page == "Record") live.firstOrNull() ?: emptyList()
                else id?.let { store.dao.points(it).map(TrackRow::fix) } ?: emptyList()
            runOnUiThread {
                if (isDestroyed || token != generation) return@runOnUiThread
                val changed = pairs != ps || journeys != js
                val firstTrace = trace.isEmpty() && fs.isNotEmpty()
                pairs = ps
                journeys = js
                if (page == "Results") {
                    if (selectedJourney != null && js.none { it.id == selectedJourney })
                        selectedJourney = null
                    val emptyGroup = js.none {
                        it.status != "recording" &&
                            (it.pairId == selectedPair || selectedPair == "unpaired" && it.pairId == null)
                    }
                    if (emptyGroup && (selectedPair == "unpaired" || ps.any { it.id == selectedPair && it.archived }))
                        selectedPair = null
                }
                trace = fs
                recordingTraces = live
                if (renderRequested || changed && tab != "Record" && !editing) {
                    renderRequested = false
                    render()
                    drawMap(true)
                } else {
                    updateStatus()
                    if (tab == "Record") drawMap(firstTrace)
                }
            }
        }
    }

    private fun render() {
        body.removeAllViews()
        startButton = null
        stopButton = null
        autoSwitch = null
        recordInfo = null
        when (tab) {
            "Record" -> recordPage()
            "Pairs" -> pairsPage()
            else -> resultsPage()
        }
        updateStatus()
    }

    private fun updateStatus() {
        val activity = recordingAppearanceState(Tracking.running, Tracking.enabled(this), Tracking.manual,
            journeys.any { it.status == "recording" })
        val waiting = Tracking.running &&
            (Tracking.lastFixAt == 0L || SystemClock.elapsedRealtime() - Tracking.lastFixAt > 15000)
        val mode = when (activity) {
            RecordingAppearanceState.PAUSED -> "Paused"
            RecordingAppearanceState.STOPPED -> "Automatic enabled • reopen to resume tracking"
            RecordingAppearanceState.MONITORING -> "Monitoring endpoints"
            RecordingAppearanceState.RECORDING -> "Recording journey"
        }
        status.text = if (waiting) "$mode • waiting for precise GPS" else mode
        status.setTextColor(if (Tracking.running) AppAppearance.mint else AppAppearance.muted)
        if (activity != displayedActivity) {
            displayedActivity = activity
            AppAppearance.glow(chrome, activity)
        }
        startButton?.isEnabled = !Tracking.manual && !commandPending
        stopButton?.isEnabled = Tracking.manual && !commandPending
        autoSwitch?.setOnCheckedChangeListener(null)
        autoSwitch?.isChecked = Tracking.enabled(this)
        autoSwitch?.setOnCheckedChangeListener { _, enabled ->
            toggleAutomatic(enabled)
            updateStatus()
        }
        val j = journeys.firstOrNull { it.status == "recording" && it.source == "manual" }
        recordInfo?.text =
            if (j != null)
                "${duration(SystemClock.elapsedRealtime()-j.startMono)}  •  ${trace.size} GPS points\nStart and Stop save these endpoints for future journeys."
            else
                Tracking.prefs(this)
                    .getString(
                        "last_result",
                        "Record a journey once. Future journeys between its endpoints are saved automatically, in both directions.",
                    )
    }

    private fun recordPage() {
        autoSwitch = ToggleButton(this).apply {
            textOn = "Automatic recording  •  ON"
            textOff = "Automatic recording  •  OFF"
            AppAppearance.button(this)
            minHeight = dp(76)
            minimumHeight = dp(76)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = isChecked
                }
            }
            body.addView(this, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(4), dp(8), dp(4), dp(12))
            })
        }
        val controls = row(body)
        startButton =
            button(controls, "Start", primary = true) {
                withTracking {
                    commandPending = true
                    updateStatus()
                    Tracking.command(this, "start", explicit = true)
                    handler.postDelayed(
                        {
                            commandPending = false
                            load(true)
                        },
                        800,
                    )
                }
            }
        stopButton =
            button(controls, "Stop") {
                commandPending = true
                updateStatus()
                Tracking.command(this, "stop")
                handler.postDelayed(
                    {
                        commandPending = false
                        load(true)
                    },
                    800,
                )
            }
        recordInfo = label(body, "", 16f)
        label(
            body,
            "${pairs.count { !it.archived }} saved pairs • either direction • any route",
            14f,
        )
        label(
            body,
            "Monitoring uses GPS while enabled, including with the screen off. Pause it when you do not want trips recorded.",
            13f,
        )
        button(body, "My location") { withLocation { locate() } }
    }

    private fun toggleAutomatic(enabled: Boolean) {
        if (enabled) {
            if (pairs.none { !it.archived }) {
                toast("Record a journey or add a pair first")
                updateStatus()
                return
            }
            withTracking {
                Tracking.command(this, "enable", explicit = true)
                updateStatus()
            }
        } else {
            Tracking.automatic(this, Tracking.generation(this), false)
            if (Tracking.running) Tracking.command(this, "pause")
            updateStatus()
        }
    }

    private fun pairsPage() {
        if (editing) {
            label(
                body,
                when (pointToSet) {
                    0 -> "Tap the map for the start point"
                    1 -> "Tap the map for the end point"
                    else -> "Tap the map for the optional second end point"
                },
                19f,
            )
            val input =
                EditText(this).apply {
                    hint = "Pair name"
                    setSingleLine()
                    setText(draftName)
                }
            input.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {
                        draftName = s.toString()
                    }

                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
            )
            body.addView(input)
            val choose = row(body)
            button(choose, "Set start${if(draftStart!=null) " ✓" else ""}") {
                pointToSet = 0
                render()
            }
            button(choose, "Set end${if(draftEnd!=null) " ✓" else ""}") {
                pointToSet = 1
                render()
            }
            val second = row(body)
            button(second, "Set end 2${if(draftEnd2!=null) " ✓" else " (optional)"}") {
                pointToSet = 2
                render()
            }
            if (draftEnd2 != null)
                button(second, "Clear end 2") {
                    draftEnd2 = null
                    if (pointToSet == 2) pointToSet = 1
                    render()
                    drawMap(false)
                }
            label(
                body,
                if (draftEnd2 == null)
                    "Endpoints must be over 150 m apart. Both directions are recorded automatically."
                else
                    "Trips from the start finish at the first end reached; trips from either end finish at the start. Travel between the two ends is not measured. All points must be over 150 m apart.",
                13f,
            )
            val actions = row(body)
            button(actions, "Save pair", primary = true) {
                val a = draftStart
                val b = draftEnd
                if (a == null || b == null) {
                    toast("Tap both endpoints on the map")
                    return@button
                }
                val pair =
                    EndpointPair(
                        newId(),
                        draftName.trim().ifEmpty { "Journey ${pairs.size+1}" },
                        a,
                        b,
                        draftEnd2,
                    )
                if (!pair.valid()) {
                    toast("Choose endpoints over 150 m apart from each other")
                    return@button
                }
                withTracking {
                    work { store.dao.pair(PairRow.from(pair)) } then
                        {
                            editing = false
                            draftStart = null
                            draftEnd = null
                            draftEnd2 = null
                            draftName = ""
                            Tracking.command(this, "enable", explicit = true)
                            load(true)
                        }
                }
            }
            button(actions, "Cancel") {
                editing = false
                draftStart = null
                draftEnd = null
                draftEnd2 = null
                draftName = ""
                render()
                drawMap(true)
            }
            button(body, "My location") { withLocation { locate() } }
        } else {
            button(body, "Add pair on map", primary = true) {
                editing = true
                pointToSet = 0
                render()
                drawMap(false)
            }
            if (pairs.none { !it.archived })
                label(
                    body,
                    "Add two endpoints on the map, or use Start and Stop to learn them from a journey.",
                )
            pairs
                .filter { !it.archived }
                .forEach { p ->
                    button(body, if (p.spec().end2 != null) "${p.name} · 2 ends" else p.name) {
                        selectedPair = p.id
                        drawMap(true)
                    }
                    val actions = row(body)
                    button(actions, "Rename") {
                        val input =
                            EditText(this).apply {
                                setSingleLine()
                                setText(p.name)
                            }
                        AlertDialog.Builder(this)
                            .setTitle("Pair name")
                            .setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                val name = input.text.toString().trim()
                                if (name.isNotEmpty())
                                    work { store.dao.rename(p.id, name) } then { load(true) }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    button(actions, "Remove", destructive = true) {
                        AlertDialog.Builder(this)
                            .setMessage(
                                "Stop monitoring ${p.name}? Recorded journeys will remain in Results."
                            )
                            .setPositiveButton("Remove") { _, _ ->
                                work {
                                    store.dao.archive(p.id)
                                    if (store.dao.pairs().none { !it.archived })
                                        Tracking.prefs(this)
                                            .edit()
                                            .putBoolean("automatic", false)
                                            .apply()
                                } then
                                    {
                                        if (Tracking.running) Tracking.command(this, "resume")
                                        selectedPair = null
                                        load(true)
                                    }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
        }
    }

    private fun resultsPage() {
        val detail = journeys.firstOrNull { it.id == selectedJourney }
        if (detail != null) {
            button(body, "Back to journeys") {
                selectedJourney = null
                load(true)
            }
            label(
                body,
                pairs.firstOrNull { it.id == detail.pairId }?.name ?: "Recorded journey",
                21f,
            )
            label(body, "${direction(detail)} • ${date(detail.started)}", 14f)
            val meters = routeDistance(trace)
            label(
                body,
                "${duration(detail.durationMs)}  •  ${format(meters/1000)} km\n${if(detail.durationMs>0) format(meters/detail.durationMs*3600) else "—"} km/h average",
                19f,
            )
            label(
                body,
                "${detail.source.replaceFirstChar(Char::uppercase)} • ${detail.status}",
                14f,
            )
            if (trace.isEmpty()) label(body, "No usable GPS points were received.")
            else
                label(
                    body,
                    "Recorded GPS path. Distance is an estimate; gaps are not connected.",
                    13f,
                )
            if (detail.status != "recording")
                button(body, "Delete recording", destructive = true) { confirmDeleteRecording(detail) }
            return
        }
        if (selectedPair == null) {
            label(body, "Journeys by endpoints", 21f)
            pairs
                .filter { !it.archived || journeys.any { j -> j.pairId == it.id } }
                .forEach { p ->
                    val count = journeys.count { it.pairId == p.id && it.status != "recording" }
                    button(body, "${p.name}${if(p.archived) " (removed)" else ""} · $count") {
                        selectedPair = p.id
                        load(true)
                    }
                }
            if (journeys.any { it.pairId == null && it.status != "recording" })
                button(body, "Other recordings") {
                    selectedPair = "unpaired"
                    load(true)
                }
            if (pairs.none { !it.archived } && journeys.isEmpty())
                label(body, "Your recorded routes will appear here.")
            return
        }
        button(body, "All pairs") {
            selectedPair = null
            load(true)
        }
        label(body, pairs.firstOrNull { it.id == selectedPair }?.name ?: "Other recordings", 21f)
        val records =
            journeys.filter {
                (it.pairId == selectedPair || (selectedPair == "unpaired" && it.pairId == null)) &&
                    it.status != "recording"
            }
        val groups =
            if (pairs.firstOrNull { it.id == selectedPair }?.spec()?.end2 == null)
                listOf("Start → End", "End → Start")
            else
                listOf("Start → End 1", "End 1 → Start", "Start → End 2", "End 2 → Start", NO_END)
        for (heading in groups) {
            val group = records.filter { direction(it) == heading }
            if (group.isEmpty()) continue
            label(body, heading, 18f)
            val summary = summarize(group.filter { it.status == "complete" }.map { it.durationMs })
            if (summary != null)
                label(
                    body,
                    "${summary.count} completed • fastest ${duration(summary.fastest)}\nAverage ${duration(summary.average)} • slowest ${duration(summary.slowest)}",
                    14f,
                )
            group.forEach { j ->
                button(
                    body,
                    "${date(j.started)}\n${duration(j.durationMs)} · ${j.source} · ${j.status}",
                ) {
                    selectedJourney = j.id
                    load(true)
                }
            }
        }
        if (records.isEmpty())
            label(
                body,
                "No journeys recorded yet. Travel between these endpoints in either direction while automatic recording is enabled.",
            )
    }

    private fun confirmDeleteRecording(recording: JourneyRow) {
        val name = pairs.firstOrNull { it.id == recording.pairId }?.name ?: "Recorded journey"
        AlertDialog.Builder(this)
            .setTitle("Delete recording?")
            .setMessage(
                "$name\n${direction(recording)} • ${date(recording.started)}\n\n" +
                    "Permanently delete this recording and its GPS path? " +
                    "Saved endpoint pairs and other recordings will remain. This cannot be undone."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                work("Could not delete recording") { store.deleteRecording(recording.id) } then {
                    if (selectedJourney == recording.id) {
                        selectedJourney = null
                        trace = emptyList()
                    }
                    load(true)
                }
            }
            .show()
    }

    private fun drawMap(fit: Boolean) {
        val m = map ?: return
        drawLocatedPosition()
        if (m.style == null || mapView.width <= dp(96) || mapView.height <= dp(96)) return
        m.clear()
        val bounds = mutableListOf<LatLng>()
        fun marker(p: Point, title: String) {
            val ll = LatLng(p.lat, p.lon)
            bounds += ll
            m.addMarker(MarkerOptions().position(ll).title(title))
        }
        fun pairMarkers(p: EndpointPair) {
            marker(p.start, "${p.name} · Start")
            marker(p.end, "${p.name} · ${if (p.end2 == null) "End" else "End 1"}")
            p.end2?.let { marker(it, "${p.name} · End 2") }
        }
        val livePair = if (tab == "Record") recordingPair(
            pairs.filter { !it.archived }.map { it.spec() },
            journeys.filter { it.status == "recording" && it.source == "automatic" }.mapNotNull { it.pairId },
        ) else null
        if (tab == "Pairs" && editing) {
            draftStart?.let { marker(it, "Start") }
            draftEnd?.let { marker(it, if (draftEnd2 == null) "End" else "End 1") }
            draftEnd2?.let { marker(it, "End 2") }
        } else if (selectedJourney != null || tab == "Record" && recordingTraces.any { it.isNotEmpty() }) {
            val traces = if (tab == "Record") recordingTraces else listOf(trace)
            traces.flatMap(::segments).forEach { segment ->
                val pts = segment.map { LatLng(it.point.lat, it.point.lon) }
                bounds += pts
                if (pts.size > 1)
                    m.addPolyline(
                        PolylineOptions().addAll(pts).color(AppAppearance.mint).width(5f)
                    )
            }
            traces.forEach { points ->
                points.firstOrNull()?.let { marker(it.point, "Recorded start") }
                points.lastOrNull()?.let { marker(it.point, if (tab == "Record") "Current position" else "Recorded end") }
            }
            livePair?.let(::pairMarkers)
        } else {
            pairs
                .filter { if (selectedPair != null) it.id == selectedPair else !it.archived }
                .forEach { pairMarkers(it.spec()) }
        }
        if (tab == "Record" && recordingTraces.any { it.isNotEmpty() }) {
            val required = recordingTraces.flatMap { listOfNotNull(it.firstOrNull()?.point, it.lastOrNull()?.point) }
                .plus(livePair?.points ?: emptyList())
                .map { LatLng(it.lat, it.lon) }
            if (livePair != null) {
                if (fit || required.any { !visibleOnRecordingMap(m, it) }) fitRecordingMap(m, required)
            } else if (fit) {
                if (bounds.size == 1 || bounds.all { it == bounds.first() })
                    m.moveCamera(CameraUpdateFactory.newLatLngZoom(bounds.first(), 16.0))
                else fitRecordingMap(m, required)
            } else if (required.any { !visibleOnRecordingMap(m, it) }) {
                var zoom = m.cameraPosition.zoom
                do {
                    zoom = (zoom - 1.0).coerceAtLeast(m.minZoomLevel)
                    m.moveCamera(CameraUpdateFactory.zoomTo(zoom))
                    if (required.any { !visibleOnRecordingMap(m, it) }) {
                        val center = LatLngBounds.Builder().includes(required).build().center
                        m.moveCamera(CameraUpdateFactory.newLatLngZoom(center, zoom))
                    }
                } while (zoom > m.minZoomLevel && required.any { !visibleOnRecordingMap(m, it) })
            }
            return
        }
        if (fit && bounds.isNotEmpty()) {
            if (
                bounds.all {
                    distance(
                        Point(bounds.first().latitude, bounds.first().longitude),
                        Point(it.latitude, it.longitude),
                    ) < 50
                }
            )
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(bounds.first(), 16.0))
            else
                m.moveCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds.Builder().includes(bounds).build(),
                        dp(36),
                    )
                )
        }
    }

    private fun visibleOnRecordingMap(m: MapLibreMap, position: LatLng): Boolean {
        val pixel = m.projection.toScreenLocation(position)
        return insideRecordingViewport(pixel.x.toDouble(), pixel.y.toDouble(), mapView.width, mapView.height, dp(36))
    }

    private fun fitRecordingMap(m: MapLibreMap, positions: List<LatLng>) {
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(positions).build(), dp(48)))
    }

    private class Work(private val launch: ((() -> Unit) -> Unit)) {
        infix fun then(done: () -> Unit) {
            launch(done)
        }
    }

    private fun work(errorMessage: String = "Could not save", action: () -> Unit) = Work { done ->
        val token = Tracking.generation(this)
        store.executor.execute {
            try {
                action()
                runOnUiThread {
                    if (
                        !isDestroyed &&
                            !isFinishing &&
                            !exiting &&
                            token == Tracking.generation(this)
                    )
                        done()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("$errorMessage: ${e.message}") }
            }
        }
    }

    private fun withLocation(action: () -> Unit) {
        if (!Tracking.precise(this)) {
            pendingPermission = action
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                10,
            )
        } else action()
    }

    private fun withTracking(action: () -> Unit) {
        val token = Tracking.generation(this)
        withLocation {
            if (!exiting && !isFinishing && token == Tracking.generation(this))
                ensureNotification {
                    if (!exiting && !isFinishing && token == Tracking.generation(this)) action()
                }
        }
    }

    private fun ensureNotification(action: () -> Unit) {
        TrackingNotification.createChannel(this)
        if (TrackingNotification.allowed(this)) {
            action()
            return
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotification = action
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        } else notificationSettings()
    }

    private fun notificationSettings() {
        if (isFinishing || exiting) return
        AlertDialog.Builder(this)
            .setTitle("Notifications are required for tracking")
            .setMessage(
                getString(R.string.notifications_required, getString(R.string.app_name))
            )
            .setPositiveButton("Settings") { _, _ ->
                val manager = getSystemService(NotificationManager::class.java)
                val channelBlocked =
                    manager.getNotificationChannel(TrackingNotification.CHANNEL)?.importance ==
                        NotificationManager.IMPORTANCE_NONE
                val intent =
                    Intent(
                            if (channelBlocked) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                            else Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        )
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                if (channelBlocked)
                    intent.putExtra(Settings.EXTRA_CHANNEL_ID, TrackingNotification.CHANNEL)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
        updateStatus()
    }

    override fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (exiting || isFinishing) {
            pendingPermission = null
            pendingNotification = null
            return
        }
        if (code == 11) {
            val action = pendingNotification
            pendingNotification = null
            if (TrackingNotification.allowed(this)) action?.invoke() else notificationSettings()
        }
        if (code == 10) {
            val action = pendingPermission
            pendingPermission = null
            if (Tracking.precise(this)) action?.invoke()
            else {
                toast("Precise location is needed to record endpoints")
                updateStatus()
            }
        }
    }

    private fun drawLocatedPosition() {
        val position = locatedPosition ?: return
        val style = map?.style ?: return
        val json = """{"type":"Feature","geometry":{"type":"Point","coordinates":[${position.longitude},${position.latitude}]},"properties":{}}"""
        val source = style.getSourceAs<GeoJsonSource>(LOCATION_SOURCE)
        if (source == null) style.addSource(GeoJsonSource(LOCATION_SOURCE, json))
        else if (renderedLocation !== position) source.setGeoJson(json)
        renderedLocation = position
        if (style.getLayer(LOCATION_LAYER) == null) {
            style.addLayer(CircleLayer(LOCATION_LAYER, LOCATION_SOURCE).withProperties(
                circleRadius(8f),
                circleColor("#4DA3FF"),
                circleStrokeWidth(3f),
                circleStrokeColor("#FFFFFF"),
            ))
        }
    }

    private fun centerLocatedPosition() {
        val position = locatedPosition ?: return
        val m = map ?: return
        if (!pendingLocateCenter || m.style == null) return
        pendingLocateCenter = false
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(position.latitude, position.longitude), 16.0))
    }

    private fun currentLocationRequest(token: CancellationTokenSource) =
        locating === token && !token.token.isCancellationRequested && !isDestroyed && !isFinishing && !exiting

    private fun completeLocation(token: CancellationTokenSource, position: Location?) {
        if (!currentLocationRequest(token)) return
        locating = null
        if (position == null || !position.latitude.isFinite() || !position.longitude.isFinite() ||
            position.latitude !in -90.0..90.0 || position.longitude !in -180.0..180.0
        ) {
            toast("Waiting for GPS. Try again outdoors.")
            return
        }
        locatedPosition = Location(position)
        pendingLocateCenter = true
        drawLocatedPosition()
        centerLocatedPosition()
    }

    private fun failLocation(token: CancellationTokenSource) {
        if (!currentLocationRequest(token)) return
        locating = null
        toast("Could not get location")
    }

    @SuppressLint("MissingPermission")
    private fun locate() {
        locating?.cancel()
        locating = null
        if (!Tracking.play(this)) {
            toast("Pan the map to your location")
            return
        }
        val token = CancellationTokenSource()
        locating = token
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
                .addOnSuccessListener { completeLocation(token, it) }
                .addOnFailureListener { failLocation(token) }
        } catch (_: RuntimeException) {
            failLocation(token)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun format(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

    private fun date(ms: Long) =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

    private fun direction(j: JourneyRow): String {
        val end =
            if (pairs.firstOrNull { it.id == j.pairId }?.spec()?.end2 == null) "End"
            else j.endpoint?.let { "End $it" }
        return when {
            j.direction == "return" -> "${end ?: "End"} → Start"
            end == null -> NO_END
            else -> "Start → $end"
        }
    }

    private fun duration(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putParcelable("locatedPosition", locatedPosition)
        out.putBoolean("pendingLocateCenter", pendingLocateCenter)
        out.putString("tab", tab)
        out.putBoolean("editing", editing)
        out.putString("name", draftName)
        out.putInt("pointToSet", pointToSet)
        draftStart?.let {
            out.putDouble("slat", it.lat)
            out.putDouble("slon", it.lon)
        }
        draftEnd?.let {
            out.putDouble("elat", it.lat)
            out.putDouble("elon", it.lon)
        }
        draftEnd2?.let {
            out.putDouble("e2lat", it.lat)
            out.putDouble("e2lon", it.lon)
        }
        out.putString("pair", selectedPair)
        out.putString("journey", selectedJourney)
        mapView.onSaveInstanceState(out)
        super.onSaveInstanceState(out)
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        resumed = true
        if (Tracking.enabled(this) && !TrackingNotification.allowed(this)) {
            Tracking.exit(this, closeApp = false)
            notificationSettings()
        } else if (Tracking.enabled(this) && Tracking.precise(this) && !Tracking.running)
            Tracking.command(this, "resume")
        handler.post(refresh)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(refresh)
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        locating?.cancel()
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    companion object {
        private const val NO_END = "Start → no end reached"
        private const val LOCATION_SOURCE = "my-location-source"
        private const val LOCATION_LAYER = "my-location-dot"
        private const val MAP_STYLE =
            """{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,"attribution":"© <a href='https://www.openstreetmap.org/copyright'>OpenStreetMap contributors</a>"}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#0a1114"}},{"id":"roads","type":"raster","source":"osm","paint":{"raster-saturation":-0.85,"raster-brightness-min":0.72,"raster-brightness-max":0.08}}]}"""
    }
}
