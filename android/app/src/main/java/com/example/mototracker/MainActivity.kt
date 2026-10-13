package com.example.mototracker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateDraw
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The app's colours, the same ones the Mini's monitor uses.
private val BG = Color(0xFF0F1115)
private val PANEL = Color(0xFF171A20)
private val PANEL2 = Color(0xFF1C2027)
private val LINE = Color(0xFF2A2F37)
private val INK = Color(0xFFE8EAED)
private val DIM = Color(0xFFA3ACB6)
private val FAINT = Color(0xFF6B7480)
private val GO = Color(0xFF3DDC84)
private val WARN = Color(0xFFFFB020)
private val BAD = Color(0xFFFF4D4F)
private val ALARM = Color(0xFFA1121F)
/** A signal loss: true yellow, apart from the amber of "stopped", as on the Macs. */
private val SIG = Color(0xFFFFD60A)

/**
 * Everything was too small to read at a glance (Jack, 2026-10-12). One number scales
 * every piece of text in the app, on top of whatever size the phone itself is set to.
 */
private const val FONT_BOOST = 1.3f

/** The collapsed drawer: tall enough to hit with a gloved thumb. */
private val DRAWER_BAR = 52.dp

/** The tallest the rider / parked-bike picture grows. */
private val SCENE_MAX = 190.dp

/**
 * Tether (formerly Moto Tracker).
 *
 * One app, and the relay decides which face it shows: home when nobody has a trip
 * open, the rider's own trip, the other rider's Observer screen, or Hybrid when both
 * are out at once (Jack, 2026-09-24) — your own speed on top, theirs on a map below.
 * Observer and Hybrid keep this phone's trip controls in a drawer at the bottom, so
 * the map gets the room. The other rider's alarm reaches this phone whichever it is.
 */
class MainActivity : ComponentActivity() {

    private var resumeCount by mutableIntStateOf(0)

    /**
     * Settings never outlives the screen: it closes whenever the app leaves the
     * foreground, so a phone pulled out of a pocket opens on the real screen
     * (2026-09-18, when a pocket pressed buttons in the old diagnostics panel).
     */
    private var showSettings by mutableStateOf(false)

    /** The map for choosing home, over Settings. */
    private var pickingHome by mutableStateOf(false)

    /** Set while the home location is waiting on the "allow all the time" answer. */
    private var homeWatchAsked = false

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    override fun onStop() {
        super.onStop()
        showSettings = false
        pickingHome = false
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        RideLog.write(this, "PERM", granted.entries.joinToString {
            "${it.key.substringAfterLast('.')}=${it.value}"
        })
    }

    /**
     * Watching for the phone leaving home with the app closed needs location "all the
     * time", which Android only grants from a separate prompt after the first one.
     */
    private val backgroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        RideLog.write(this, "PERM", "background location=$it")
        Home.register(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askForWhatWeNeed()
        setContent { Themed { App() } }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, density.fontScale * FONT_BOOST)
        ) {
            MaterialTheme(colorScheme = darkColorScheme(background = BG, surface = PANEL), content = content)
        }
    }

    private fun hasBackgroundLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askForBackgroundLocation() {
        if (hasBackgroundLocation() || homeWatchAsked) return
        homeWatchAsked = true
        backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun askForWhatWeNeed() {
        val wanted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    private fun openMaps(lat: Double, lon: Double) {
        val url = mapsUrl(lat, lon)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun copyMaps(lat: Double, lon: Double) {
        val clip = getSystemService(ClipboardManager::class.java)
        clip.setPrimaryClip(ClipData.newPlainText("position", mapsUrl(lat, lon)))
    }

    private fun call(rider: String) {
        // A number is not something this app knows; hand the name to the dialer and
        // let the phone's own contacts do the rest.
        runCatching {
            startActivity(Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:") })
        }
    }

    // ---------------------------------------------------------------- the app

    @Composable
    private fun App() {
        val ctx = this
        val scope = rememberCoroutineScope()
        var paired by remember { mutableStateOf(DeviceKey.current != null) }
        var snap by remember { mutableStateOf(Relay.snapshot) }
        var tripState by remember { mutableStateOf(Trip.state(ctx)) }
        var reach by remember { mutableStateOf<Long?>(null) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("") }
        var weather by remember { mutableStateOf(Weather.current()) }
        var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var logFailed by remember { mutableStateOf(Uploader.logWaiting(ctx)) }
        var helpAsk by remember { mutableStateOf(false) }

        LaunchedEffect(resumeCount) {
            paired = DeviceKey.current != null
            tripState = Trip.state(ctx)
        }
        // The screen polls fast while it IS the screen; the listener service does the
        // slow listening behind it (Jack, 2026-09-27). Coming back to the foreground
        // asks the relay straight away — is anyone riding? — and the answer decides
        // whether this is the home screen or Observer mode.
        LaunchedEffect(Unit) {
            var lastLogTry = 0L
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    if (DeviceKey.current != null) {
                        snap = withContext(Dispatchers.IO) { Relay.refresh(ctx) } ?: snap
                        // A log that could not be sent when the trip ended goes now, and
                        // then once a minute; the home screen says so while it waits.
                        if (snap != null && Uploader.logWaiting(ctx) &&
                            System.currentTimeMillis() - lastLogTry > 60_000L) {
                            lastLogTry = System.currentTimeMillis()
                            withContext(Dispatchers.IO) { runCatching { Uploader.uploadRideLog(ctx) } }
                        }
                        logFailed = Uploader.logWaiting(ctx)
                        tripState = Trip.state(ctx)
                        val fresh = snap
                        val theirTrip = fresh?.other(ctx)?.takeIf { it.tripId != null }
                        // Listening outlives this screen, so it belongs to a service.
                        // Started here too, so pairing is all it takes to begin.
                        ObserverService.start(ctx)
                        if (tripState == Trip.State.IDLE && theirTrip != null) {
                            ObserverService.watching = theirTrip.name
                        } else if (tripState == Trip.State.IDLE && fresh != null) {
                            withContext(Dispatchers.IO) {
                                runCatching { IncidentWatch.decide(ctx, fresh) }
                            }
                        }
                    }
                    delay(1500)
                }
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                reach = withContext(Dispatchers.IO) { Uploader.probe().ms }
                delay(5000)
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                tick = System.currentTimeMillis()
                delay(1000)
            }
        }
        // Weather follows whoever is being watched.
        val watching = snap?.other(ctx)
        LaunchedEffect(watching?.lat, watching?.lon) {
            val lat = watching?.lat
            val lon = watching?.lon
            if (lat != null && lon != null) {
                weather = withContext(Dispatchers.IO) { Weather.fetch(ctx, lat, lon) }
            }
        }

        fun act(what: () -> Boolean, said: String, failed: String = "") {
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { what() }
                status = if (ok) said.replaceFirstChar { it.uppercase() } else failed
                tripState = Trip.state(ctx)
                snap = withContext(Dispatchers.IO) { Relay.refresh(ctx) } ?: snap
                busy = false
            }
        }

        Surface(Modifier.fillMaxSize(), color = BG) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) {
                Header(reach, snap)
                val me = snap?.me(ctx)
                val other = snap?.other(ctx)
                val mine = me?.incident
                val theirs = other?.incident

                // On every screen, under the relay light. One press only asks; the
                // dialog is what sends, so a pocket cannot raise a false alarm.
                // The rider's own open incident has its own, final, I-need-help.
                if (helpAsk) {
                    HelpDialog(other?.name ?: if (Identity.get(ctx) == "jack") "Dana" else "Jack",
                        onCancel = { helpAsk = false }) {
                        helpAsk = false
                        act({ Relay.needHelp(ctx) }, "Help requested — they have been alerted",
                            "Help NOT sent — the relay did not answer. Call them.")
                    }
                }

                // Like Waze: while anyone is out, the screen stays on for as long as
                // this app is in front (Jack, 2026-09-27). The home screen with nobody
                // out times out as normal, so a phone left open on a table sleeps.
                val live = tripState != Trip.State.IDLE || other?.tripId != null ||
                    mine != null || theirs != null
                DisposableEffect(live) {
                    if (live) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { }
                }

                // The screens get the space between the header and the footer, and
                // place their own buttons at the bottom of it.
                Column(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    showSettings && pickingHome -> HomePicker()

                    showSettings -> SettingsScreen(paired, tripState,
                        onPaired = { paired = DeviceKey.current != null },
                        onLogSent = { logFailed = Uploader.logWaiting(ctx) })

                    !paired -> PairScreen { paired = DeviceKey.current != null }

                    // The other rider needs help. This is the only screen that shouts.
                    theirs != null && theirs.state == "sos" ->
                        AlarmScreen(other!!, theirs, tick, busy, ::act)

                    mine != null && mine.state == "sos" && !mine.riderClosed ->
                        MyIncidentScreen(mine, other, busy, ::act)

                    // Both out at once: Hybrid. It follows the relay, so either rider
                    // opening a trip flips both phones into it (Jack, 2026-09-24).
                    tripState != Trip.State.IDLE && other?.tripId != null ->
                        WithDrawer(tripState, busy, ::act, status) {
                            HybridScreen(me, other, theirs, weather, tripState, tick)
                        }

                    tripState == Trip.State.RIDING -> RidingScreen(me, tick, busy, ::act, status)
                    tripState == Trip.State.OFF_BIKE -> OffBikeScreen(tick, busy, ::act, status)

                    // Not riding, and the other one is: this phone is the Observer.
                    other?.tripId != null ->
                        WithDrawer(tripState, busy, ::act, status) {
                            ObserverScreen(other, theirs, weather, tick)
                        }

                    else -> HomeScreen(snap, busy, ::act, status, logFailed)
                }
                }

                Footer(showSettings, onHelp = if (paired && mine == null) ({ helpAsk = true }) else null) { showSettings = !showSettings }
            }
        }
    }

    // ---------------------------------------------------------------- pieces

    @Composable
    private fun Header(reach: Long?, snap: Relay.Snapshot?) {
        val ctx = this        // inside Row {} `this` is the RowScope, not the activity
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("TETHER", color = DIM, fontSize = 13.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (reach != null) GO else BAD)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                if (reach != null) "Relay OK" else "No relay",
                color = if (reach != null) DIM else BAD, fontSize = 12.sp
            )
        }
    }

    @Composable
    private fun Pill(text: String, colour: Color) {
        Row(
            Modifier.clip(RoundedCornerShape(999.dp))
                .background(colour.copy(alpha = 0.15f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
            Spacer(Modifier.width(8.dp))
            Text(text, color = colour, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp)
        }
    }

    @Composable
    private fun Card(modifier: Modifier = Modifier.fillMaxWidth(), pad: androidx.compose.ui.unit.Dp = 16.dp,
                     content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier.clip(RoundedCornerShape(14.dp)).background(PANEL).padding(pad),
            content = content
        )
    }

    @Composable
    private fun KeyValue(key: String, value: String, valueColour: Color = INK) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(key, color = DIM, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(value, color = valueColour, fontSize = 14.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun BigButton(
        label: String, colour: Color, enabled: Boolean = true,
        text: Color = Color(0xFF07130C), onClick: () -> Unit,
    ) {
        Button(
            onClick, Modifier.fillMaxWidth().height(62.dp),
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colour, contentColor = text)
        ) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
    }

    @Composable
    private fun QuietButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
        OutlinedButton(
            onClick, Modifier.fillMaxWidth().height(56.dp),
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = INK)
        ) { Text(label, fontSize = 16.sp) }
    }

    /** Coordinates that open in Maps, with a copy button beside them. */
    @Composable
    private fun Coordinates(lat: Double, lon: Double, onLight: Boolean = false) {
        var copied by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ openMaps(lat, lon) }, contentPadding = PaddingValues(0.dp)) {
                Text(
                    String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon),
                    color = if (onLight) Color.White else Color(0xFF4DA3FF),
                    fontSize = 15.sp, fontFamily = FontFamily.Monospace
                )
            }
            TextButton({ copyMaps(lat, lon); copied = true }, contentPadding = PaddingValues(6.dp, 0.dp)) {
                Text(if (copied) "✓" else "⧉",
                    color = if (onLight) Color.White else DIM, fontSize = 16.sp)
            }
        }
    }

    // ---------------------------------------------------------------- rider screens

    @Composable
    private fun ColumnScope.HomeScreen(
        snap: Relay.Snapshot?, busy: Boolean,
        act: (() -> Boolean, String) -> Unit, status: String, logFailed: Boolean,
    ) {
        val ctx = this@MainActivity
        val previous = remember(snap) { LastTrip.remember(ctx, snap?.me(ctx)?.previousTrip) }
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(snap?.me(ctx)?.name ?: Identity.get(ctx).replaceFirstChar { it.uppercase() },
                    color = INK, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("  Not riding", color = FAINT, fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 5.dp))
            }
            Spacer(Modifier.height(20.dp))
            if (previous != null) {
                Card {
                    KeyValue("Last trip", previous.day)
                    KeyValue("Started", previous.started)
                    KeyValue("Ended", previous.ended)
                    KeyValue("Duration", previous.duration)
                    KeyValue("Max speed", previous.maxSpeed)
                    KeyValue("Average speed", previous.avgSpeed)
                    KeyValue("Peak G", previous.peakG)
                    KeyValue("Rotational force", previous.peakRot)
                }
            } else {
                Text("No trip recorded yet.", color = FAINT, fontSize = 14.sp)
            }
        }
        // Start trip sits at the bottom, under the thumb (Jack, 2026-09-16).
        Spacer(Modifier.weight(1f))
        if (status.isNotEmpty()) {
            Text(status, color = DIM, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (logFailed) {
            Text("Log upload failed. Tap settings to try again.",
                color = WARN, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        BigButton("Start trip", GO, enabled = !busy) {
            act({ Trip.startTrip(ctx) }, "")
        }
        Spacer(Modifier.height(28.dp))
    }

    /**
     * The rider or the parked bike: between the figures above and the buttons below,
     * centred in whatever is left, and never taller than [SCENE_MAX] — too small to
     * see on one build, a whole screen on the next (Jack, 2026-10-12).
     */
    @Composable
    private fun ColumnScope.SceneBox(scene: @Composable (Modifier) -> Unit) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), contentAlignment = Alignment.BottomCenter) {
            scene(Modifier.fillMaxWidth().heightIn(max = SCENE_MAX).fillMaxHeight())
        }
    }

    @Composable
    private fun ColumnScope.RidingScreen(
        me: Relay.Rider?, tick: Long, busy: Boolean,
        act: (() -> Boolean, String) -> Unit, status: String,
    ) {
        val ctx = this@MainActivity
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Pill("RIDING", GO)
            Spacer(Modifier.width(16.dp))
            Text("Speed ", color = DIM, fontSize = 16.sp)
            Text(
                ((me?.speed ?: 0.0).takeIf { !it.isNaN() } ?: 0.0).toInt().toString(),
                color = INK, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            Text(" km/h", color = DIM, fontSize = 16.sp)
        }
        Spacer(Modifier.height(14.dp))
        Card {
            KeyValue("Ride time", elapsed(Trip.startedAt(ctx), tick))
            KeyValue("Last packet",
                me?.lastSeenS?.takeIf { !it.isNaN() }?.let { "${it.toInt()} s ago" } ?: "—",
                if ((me?.lastSeenS ?: 0.0) > 20) WARN else INK)
            KeyValue("G-force", Live.g.takeIf { !it.isNaN() }
                ?.let { String.format(java.util.Locale.US, "%.2f g", it) } ?: "—")
            KeyValue("Rotation", Live.rot.takeIf { !it.isNaN() }
                ?.let { String.format(java.util.Locale.US, "%.1f r/s", it) } ?: "—")
            KeyValue("Peak G", String.format(java.util.Locale.US, "%.2f g", Live.peakG))
            KeyValue("Peak rotation", String.format(java.util.Locale.US, "%.1f r/s", Live.peakRot))
        }
        // The road runs at the speed actually being ridden.
        SceneBox { m -> StateScene(riding = true, speedKmh = me?.speed?.takeIf { !it.isNaN() } ?: 0.0, modifier = m) }
        if (status.isNotEmpty()) {
            Text(status, color = DIM, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        BigButton("Pause ride", WARN, enabled = !busy, text = Color(0xFF1A1206)) {
            act({ Trip.pauseRide(ctx) }, "")
        }
        Spacer(Modifier.height(10.dp))
        QuietButton("End trip", enabled = !busy) {
            act({ Trip.endTrip(ctx) }, "")
        }
        Spacer(Modifier.height(28.dp))
    }

    @Composable
    private fun ColumnScope.OffBikeScreen(
        tick: Long, busy: Boolean, act: (() -> Boolean, String) -> Unit, status: String,
    ) {
        val ctx = this@MainActivity
        // The same screen serves the start of a trip and a stop in the middle of
        // one; only the words change (Jack, 2026-09-20).
        val ridden = Trip.hasRidden(ctx)
        Pill("OFF BIKE", Color(0xFF4DA3FF))
        Spacer(Modifier.height(14.dp))
        Card {
            KeyValue("Trip continues since", clockTime(Trip.startedAt(ctx)))
            KeyValue("Location", "Sharing", GO)
            KeyValue("Sensors", "Off", DIM)
            KeyValue("Crash detection", "Off", DIM)
            KeyValue(if (ridden) "Off bike for" else "Trip open for",
                elapsed(Trip.offBikeSince(ctx), tick))
            if (Trip.autoResume(ctx)) KeyValue("Auto-resume", "On", GO)
            else KeyValue("Auto-resume", "Off", BAD)
        }
        SceneBox { m -> StateScene(riding = false, speedKmh = 0.0, modifier = m) }
        if (status.isNotEmpty()) {
            Text(status, color = DIM, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        BigButton(if (ridden) "Resume ride" else "Start ride", GO, enabled = !busy) {
            act({ Trip.resumeRide(ctx) }, "")
        }
        Spacer(Modifier.height(10.dp))
        QuietButton("End trip", enabled = !busy) {
            act({ Trip.endTrip(ctx) }, "")
        }
        Spacer(Modifier.height(28.dp))
    }

    /** The rider's own open incident: what was seen, and the three ways out of it. */
    @Composable
    private fun ColumnScope.MyIncidentScreen(
        inc: Relay.Incident, other: Relay.Rider?, busy: Boolean,
        act: (() -> Boolean, String) -> Unit,
    ) {
        val ctx = this@MainActivity
        val watcher = other?.name ?: "Your Observer"
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(6.dp))
            Pill("INCIDENT OPEN", BAD)
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2A1015)).padding(16.dp)
            ) {
                KeyValue("Raised", timeOf(inc.raisedAt))
                if (!inc.g.isNaN() && inc.g > 0)
                    KeyValue("Peak", String.format(java.util.Locale.US, "%.1f g", inc.g))
                if (!inc.rot.isNaN() && inc.rot > 0)
                    KeyValue("Rotation", String.format(java.util.Locale.US, "%.1f rad/s", inc.rot))
                if (!inc.speedBefore.isNaN() && inc.speedBefore > 0)
                    KeyValue("Speed", String.format(java.util.Locale.US, "%.0f → %.0f km/h",
                        inc.speedBefore, if (inc.speedAfter.isNaN()) 0.0 else inc.speedAfter))
                Spacer(Modifier.height(8.dp))
                Text(
                    if (inc.silenced) "$watcher was alerted and has silenced it."
                    else "$watcher was alerted at ${timeOf(inc.raisedAt)}.",
                    color = Color(0xFFE0A9AE), fontSize = 13.sp
                )
            }
            if (inc.lat != null && inc.lon != null) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Position", color = DIM, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Coordinates(inc.lat, inc.lon)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        BigButton("I'm OK", GO, enabled = !busy) {
            act({ Relay.resolve(ctx, inc.id, "ok") }, "told them you are OK")
        }
        Spacer(Modifier.height(10.dp))
        QuietButton("False alarm", enabled = !busy) {
            act({ Relay.resolve(ctx, inc.id, "false_alarm") }, "marked a false alarm")
        }
        Spacer(Modifier.height(10.dp))
        // No window, no waiting, no automatic retraction: this one wins outright.
        Button(
            { act({ Relay.escalate(ctx, inc.id) }, "help is being called for") },
            Modifier.fillMaxWidth().height(62.dp), enabled = !busy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BAD, contentColor = Color.White)
        ) { Text("I need help", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
    }

    // ---------------------------------------------------------------- observer screens

    @Composable
    private fun ObserverScreen(
        other: Relay.Rider, theirs: Relay.Incident?, weather: Weather.Now?, tick: Long,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(other.name, color = INK, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                StatePill(other.state)
            }
            Spacer(Modifier.height(12.dp))
            PendingBanner(theirs)
            Spacer(Modifier.height(2.dp))
            WatchedMap(other, theirs, weather, 280.dp, tick)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    ((other.speed.takeIf { !it.isNaN() }) ?: 0.0).toInt().toString(),
                    color = INK, fontSize = 58.sp, fontWeight = FontWeight.Bold
                )
                Text(" KM/H", color = DIM, fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 12.dp))
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Card {
                KeyValue("Last packet",
                    other.lastSeenS.takeIf { !it.isNaN() }?.let { "${it.toInt()} s ago" } ?: "—",
                    if (other.lastSeenS > 20) WARN else INK)
                other.battery?.let { KeyValue("Battery", "$it%") }
                weather?.let { KeyValue("Weather", it.label + (it.tempC?.let { t -> "  ${t.toInt()}°C" } ?: "")) }
                if (other.lat != null && other.lon != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Position", color = DIM, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Coordinates(other.lat, other.lon)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }

    /**
     * The other rider on a map, with the weather where they are sitting on it. [tick]
     * is the second it is drawn for, so a signal loss counts up in real time.
     */
    @Composable
    private fun WatchedMap(
        other: Relay.Rider, theirs: Relay.Incident?, weather: Weather.Now?,
        height: androidx.compose.ui.unit.Dp, tick: Long,
    ) {
        // Unheard for a minute: "Signal lost for m:ss" over the last known position.
        // Past two: a bar across the top of the map as well (Jack, 2026-09-29).
        val lost = other.signal
        val lostFor = lost?.lostFor(tick + (Relay.relayNow() - System.currentTimeMillis()))
        // Light from sunrise, dark from sunset, worked out for the date and where the
        // rider is (Jack, 2026-09-29) — re-asked every second, so it turns on time.
        val light = Sun.isUp(tick, other.lat ?: HOME_LAT, other.lon ?: HOME_LON)
        Box {
            RiderMap(
                lat = other.lat, lon = other.lon,
                speedKmh = other.speed.takeIf { !it.isNaN() } ?: 0.0,
                stoppedLabel = if (other.state == "offbike") "Off bike" else "Stopped",
                alarm = theirs != null && theirs.state == "sos",
                height = height,
                signalLostFor = lostFor,
                signalAlert = lost?.alert == true,
                light = light,
            )
            // Black, yellow text, across the top of the map, as on the Macs. Drawn here
            // rather than in the map page, so it shows even if the map could not load.
            if (lost?.alert == true) {
                Text(
                    "Signal lost for $lostFor. Awaiting acquisition of signal.",
                    color = SIG, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                        .background(Color.Black)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
            // The weather sits on the map, as it does on the Mini: one glyph for
            // what it is doing outside, the wind underneath (Jack, 2026-09-16).
            weather?.let {
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xE6171A20)).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(it.icon, fontSize = 26.sp)
                    Text(it.wind, color = DIM, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    /** The other rider's candidate: nothing raised yet, but worth knowing about. */
    @Composable
    private fun PendingBanner(theirs: Relay.Incident?) {
        if (theirs == null || theirs.state != "pending") return
        Column(
            Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp))
                .background(WARN.copy(alpha = 0.12f)).padding(14.dp)
        ) {
            Text("Possible crash — checking", color = WARN, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Nothing has been raised. It clears itself if the ride carries on.",
                color = DIM, fontSize = 13.sp)
        }
    }

    @Composable
    private fun StatePill(state: String) = when (state) {
        "riding" -> Pill("RIDING", GO)
        "offbike" -> Pill("OFF BIKE", Color(0xFF4DA3FF))
        else -> Pill("ASLEEP", FAINT)
    }

    /**
     * Hybrid: both riders out at once (Jack, 2026-09-24). This rider's own speed on
     * top, where the riding screen has it; the other rider's map and speed below, in
     * the space the riding cartoon would otherwise take. The trip controls are in
     * the drawer, not here.
     */
    @Composable
    private fun ColumnScope.HybridScreen(
        me: Relay.Rider?, other: Relay.Rider, theirs: Relay.Incident?, weather: Weather.Now?,
        tripState: Trip.State, tick: Long,
    ) {
        val ctx = this@MainActivity
        // Mine.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (tripState == Trip.State.RIDING) Pill("RIDING", GO)
            else Pill("OFF BIKE", Color(0xFF4DA3FF))
            Spacer(Modifier.weight(1f))
            Text(elapsed(Trip.startedAt(ctx), tick), color = DIM, fontSize = 14.sp,
                fontFamily = FontFamily.Monospace)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                ((me?.speed ?: 0.0).takeIf { !it.isNaN() } ?: 0.0).toInt().toString(),
                color = INK, fontSize = 64.sp, fontWeight = FontWeight.Bold
            )
            Text(" KM/H", color = DIM, fontSize = 14.sp, modifier = Modifier.padding(bottom = 14.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 12.dp)) {
                Text(me?.lastSeenS?.takeIf { !it.isNaN() }?.let { "Sent ${it.toInt()} s ago" } ?: "Not sent yet",
                    color = if ((me?.lastSeenS ?: 0.0) > 20) WARN else FAINT, fontSize = 12.sp)
                me?.battery?.let { Text("Battery $it%", color = FAINT, fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(LINE))
        Spacer(Modifier.height(10.dp))

        // Theirs.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(other.name, color = INK, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            StatePill(other.state)
            Spacer(Modifier.weight(1f))
            Text(((other.speed.takeIf { !it.isNaN() }) ?: 0.0).toInt().toString(),
                color = INK, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(" KM/H", color = DIM, fontSize = 12.sp)
        }
        Text(
            other.lastSeenS.takeIf { !it.isNaN() }?.let { "Last packet ${it.toInt()} s ago" } ?: "No packet yet",
            color = if (other.lastSeenS > 20) WARN else FAINT, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        PendingBanner(theirs)
        // The map takes whatever is left. Its height is fixed when it starts, so it
        // is measured once here, and the drawer slides over it rather than moving it.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(bottom = 8.dp)) {
            val h = maxHeight
            if (h > 80.dp) key(h) { WatchedMap(other, theirs, weather, h, tick) }
        }
    }

    /**
     * Observer and Hybrid put this phone's trip controls in a drawer (Jack,
     * 2026-09-24): a slim bar at the bottom with a chevron, which slides the buttons
     * up over the screen when tapped. The screen above keeps its size either way.
     */
    @Composable
    private fun ColumnScope.WithDrawer(
        tripState: Trip.State, busy: Boolean, act: (() -> Boolean, String) -> Unit, status: String,
        startOpen: Boolean = false,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().padding(bottom = DRAWER_BAR)) { content() }
            TripDrawer(tripState, busy, act, status, startOpen, Modifier.align(Alignment.BottomCenter))
        }
    }

    @Composable
    private fun TripDrawer(
        tripState: Trip.State, busy: Boolean, act: (() -> Boolean, String) -> Unit, status: String,
        startOpen: Boolean, modifier: Modifier,
    ) {
        val ctx = this
        // Closes itself once the trip changes state, so the buttons never outlive
        // the state they were drawn for.
        var open by remember(tripState) { mutableStateOf(startOpen) }
        val then: (() -> Boolean, String) -> Unit = { what, said -> open = false; act(what, said) }
        Column(
            modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(PANEL2)
        ) {
            Row(
                Modifier.fillMaxWidth().height(DRAWER_BAR).clickable { open = !open }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when (tripState) {
                        Trip.State.IDLE -> "Your trip · Not started"
                        Trip.State.OFF_BIKE -> "You · Off bike"
                        Trip.State.RIDING -> "You · Riding"
                    },
                    color = INK, fontSize = 14.sp
                )
                if (status.isNotEmpty()) {
                    Text("  ·  $status", color = FAINT, fontSize = 12.sp, maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false))
                }
                Spacer(Modifier.weight(1f))
                // One glyph, turned over when open, so it sits in the same place both ways.
                Text("⌃", color = INK, fontSize = 24.sp,
                    modifier = Modifier.rotate(if (open) 180f else 0f))
            }
            AnimatedVisibility(open, enter = expandVertically(), exit = shrinkVertically()) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    when (tripState) {
                        Trip.State.IDLE -> {
                            BigButton("Start trip", GO, enabled = !busy) {
                                then({ Trip.startTrip(ctx) }, "")
                            }
                        }
                        Trip.State.OFF_BIKE -> {
                            BigButton(if (Trip.hasRidden(ctx)) "Resume ride" else "Start ride", GO,
                                enabled = !busy) { then({ Trip.resumeRide(ctx) }, "") }
                            Spacer(Modifier.height(10.dp))
                            QuietButton("End trip", enabled = !busy) { then({ Trip.endTrip(ctx) }, "") }
                        }
                        Trip.State.RIDING -> {
                            BigButton("Pause ride", WARN, enabled = !busy, text = Color(0xFF1A1206)) {
                                then({ Trip.pauseRide(ctx) }, "")
                            }
                            Spacer(Modifier.height(10.dp))
                            QuietButton("End trip", enabled = !busy) { then({ Trip.endTrip(ctx) }, "") }
                        }
                    }
                }
            }
        }
    }

    /**
     * The screenshot test's way in (androidTest ScreensTest): Observer or Hybrid on
     * made-up relay data, so the layout can be looked at without a relay or a ride.
     * The buttons do nothing here.
     */
    @androidx.annotation.VisibleForTesting
    internal fun showForTest(me: Relay.Rider?, other: Relay.Rider, tripState: Trip.State, drawerOpen: Boolean) {
        setContent {
            Themed {
                Surface(Modifier.fillMaxSize(), color = BG) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                        Header(120L, null)
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            WithDrawer(tripState, false, { _, _ -> }, "", drawerOpen) {
                                if (tripState == Trip.State.IDLE)
                                    ObserverScreen(other, other.incident, null, System.currentTimeMillis())
                                else HybridScreen(me, other, other.incident, null, tripState,
                                    System.currentTimeMillis())
                            }
                        }
                        Footer(false, null) {}
                    }
                }
            }
        }
    }

    /** The screen nobody wants: the other rider is in trouble. */
    @Composable
    private fun AlarmScreen(
        other: Relay.Rider, inc: Relay.Incident, tick: Long, busy: Boolean,
        act: (() -> Boolean, String) -> Unit,
    ) {
        val ctx = this
        val yellow = inc.riderClosed
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(if (yellow) Color(0xFF4A3A10) else ALARM)
                .padding(18.dp)
        ) {
            Text(
                if (inc.silenced) "ALARM · SILENCED" else "ALARM · REPEATING",
                color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, letterSpacing = 2.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (yellow) "${other.name} says they are OK" else "Rider in trouble",
                color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            val detail = when {
                inc.kind == "help" -> "${other.name} pressed I need help"
                else -> buildString {
                    append(other.name)
                    if (!inc.g.isNaN() && inc.g > 0)
                        append(String.format(java.util.Locale.US, " · impact %.1f g", inc.g))
                    if (!inc.speedBefore.isNaN())
                        append(String.format(java.util.Locale.US, "\n%.0f → %.0f km/h",
                            inc.speedBefore, if (inc.speedAfter.isNaN()) 0.0 else inc.speedAfter))
                }
            }
            Text(detail, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Start)
            if (inc.lat != null && inc.lon != null) {
                Spacer(Modifier.height(6.dp))
                Text("Last known position", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                Coordinates(inc.lat, inc.lon, onLight = true)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                { call(other.name) }, Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White,
                    contentColor = ALARM)
            ) { Text("Call ${other.name}", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(10.dp))
            if (!inc.silenced) {
                OutlinedButton(
                    { act({ Relay.silence(ctx, inc.id) }, "alarm silenced everywhere") },
                    Modifier.fillMaxWidth().height(54.dp), enabled = !busy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Silence alarm", fontSize = 16.sp) }
            } else if (!inc.observerClosed) {
                OutlinedButton(
                    { act({ Relay.observerClose(ctx, inc.id) }, "you closed your half") },
                    Modifier.fillMaxWidth().height(54.dp), enabled = !busy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Close incident", fontSize = 16.sp) }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (inc.observerClosed)
                    "You have closed your half. It stays open until ${other.name} confirms."
                else "Silencing stops the sound only. This stays open until ${other.name} " +
                    "clears it too.",
                color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp
            )
        }
    }

    // ---------------------------------------------------------------- pairing, footer

    @Composable
    private fun PairScreen(onPaired: () -> Unit) {
        val ctx = this
        val scope = rememberCoroutineScope()
        var code by remember { mutableStateOf("") }
        var result by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(12.dp))
            Text("Pair this phone", color = INK, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Ask for an eight-character code on the Mac, then type it here.",
                color = DIM, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { v ->
                    code = v.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(11)
                },
                label = { Text("Pairing code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            BigButton("Pair this phone", GO,
                enabled = !busy && code.count { it.isLetterOrDigit() } == 8) {
                busy = true
                result = "Pairing…"
                scope.launch {
                    result = withContext(Dispatchers.IO) { Uploader.pair(ctx, code) }
                    busy = false
                    onPaired()
                }
            }
            if (result.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(result, color = DIM, fontSize = 13.sp)
            }
        }
    }

    @Composable
    private fun Footer(open: Boolean, onHelp: (() -> Unit)?, toggle: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tether ${BuildConfig.VERSION_NAME}", color = FAINT, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (onHelp != null) AlarmButton(onHelp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                IconButton(toggle, Modifier.size(52.dp)) {
                    Gear(if (open) INK else DIM, Modifier.size(30.dp))
                }
            }
        }
    }

    /**
     * I need help: a round red alarm siren between the name and the gear. It only asks;
     * [HelpDialog] sends, so a pocket cannot raise a false alarm.
     */
    @Composable
    private fun AlarmButton(onClick: () -> Unit) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(BAD).clickable(onClick = onClick)
                .semantics { contentDescription = "I need help" },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(30.dp)) {
                val w = size.width
                val white = Color.White
                // The dome, its base, and three rays off the top.
                drawArc(white, 180f, 180f, useCenter = true,
                    topLeft = Offset(w * 0.2f, w * 0.34f), size = Size(w * 0.6f, w * 0.6f))
                drawRect(white, Offset(w * 0.12f, w * 0.64f), Size(w * 0.76f, w * 0.14f))
                for (a in listOf(-50f, 0f, 50f)) rotateDraw(a, Offset(w / 2, w * 0.64f)) {
                    drawLine(white, Offset(w / 2, w * 0.2f), Offset(w / 2, w * 0.04f),
                        strokeWidth = w * 0.09f, cap = StrokeCap.Round)
                }
            }
        }
    }

    /** A gear, drawn rather than imported: the icon library is a lot of app for one glyph. */
    @Composable
    private fun Gear(colour: Color, modifier: Modifier) {
        Canvas(modifier) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2
            val w = r * 0.22f
            drawCircle(colour, radius = r * 0.62f, center = c, style = Stroke(width = r * 0.24f))
            drawCircle(colour, radius = r * 0.2f, center = c, style = Stroke(width = r * 0.12f))
            for (i in 0 until 8) rotateDraw(i * 45f, c) {
                drawLine(colour, Offset(c.x, c.y - r * 0.62f), Offset(c.x, c.y - r * 0.95f),
                    strokeWidth = w * 1.5f, cap = StrokeCap.Butt)
            }
        }
    }

    // ---------------------------------------------------------------- I need help

    /** Two steps, so a pocket cannot send a false alarm to the other rider. */
    @Composable
    private fun HelpDialog(who: String, onCancel: () -> Unit, onSend: () -> Unit) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Alert $who of an emergency?") },
            confirmButton = {
                Button(onSend, colors = ButtonDefaults.buttonColors(
                    containerColor = BAD, contentColor = Color.White)) {
                    Text("I Need Help", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Button(onCancel, colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5A606A), contentColor = Color.White)) {
                    Text("Cancel")
                }
            }
        )
    }

    // ---------------------------------------------------------------- settings

    /** One row of Settings: a title, what it is doing now, and optionally a switch. */
    @Composable
    private fun SettingRow(
        title: String, detail: String, titleColour: Color = INK,
        trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit,
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PANEL2)
                .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = titleColour, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (detail.isNotEmpty()) Text(detail, color = DIM, fontSize = 12.sp)
            }
            trailing?.let { Spacer(Modifier.width(10.dp)); it() }
        }
        Spacer(Modifier.height(8.dp))
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun here(): android.location.Location? = suspendCancellableCoroutine { cont ->
        val token = CancellationTokenSource()
        cont.invokeOnCancellation { token.cancel() }
        runCatching {
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }.onFailure { cont.resume(null) }
    }

    private fun setHome(lat: Double, lon: Double) {
        Home.set(this, lat, lon)
        askForBackgroundLocation()
    }

    private fun homeNote() =
        if (hasBackgroundLocation()) "Home set."
        else "Home set. Allow location \"all the time\" so the phone can notice you " +
            "leaving with the app closed."

    /**
     * Choose home on a map: it opens on this phone's position with 100 km each way in
     * view; a long press drops a pin, and only then does the button appear.
     */
    @Composable
    private fun HomePicker() {
        val ctx = this
        var start by remember { mutableStateOf<Pair<Double, Double>?>(null) }
        var pin by remember { mutableStateOf<Pair<Double, Double>?>(null) }
        LaunchedEffect(Unit) {
            val at = here()
            start = when {
                at != null -> at.latitude to at.longitude
                Home.isSet(ctx) -> Home.lat(ctx) to Home.lon(ctx)
                else -> HOME_LAT to HOME_LON
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Set location on map", color = INK, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton({ pickingHome = false }) { Text("Cancel", color = DIM, fontSize = 15.sp) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val at = start
                if (at == null) {
                    Text("Finding where you are…", color = FAINT, fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.Center))
                } else {
                    key(maxHeight) { HomePickerMap(at.first, at.second, maxHeight) { la, lo -> pin = la to lo } }
                }
            }
            // The button's room is kept whether or not it is showing: the map is built
            // for the height it starts with, and must not be resized when a pin lands.
            Box(Modifier.fillMaxWidth().height(72.dp).padding(top = 10.dp)) {
                pin?.let { (la, lo) ->
                    BigButton("Set home location", GO) {
                        setHome(la, lo)
                        android.widget.Toast.makeText(ctx, homeNote(),
                            android.widget.Toast.LENGTH_LONG).show()
                        pickingHome = false
                    }
                }
            }
            Text("Pinch to zoom, drag to move the map. Press and hold where home is to drop a " +
                "pin; hold somewhere else to move it.",
                color = DIM, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
        }
    }

    @Composable
    private fun SettingsScreen(
        paired: Boolean, tripState: Trip.State, onPaired: () -> Unit, onLogSent: () -> Unit,
    ) {
        val ctx = this
        val scope = rememberCoroutineScope()
        var line by remember { mutableStateOf("") }
        var confirmUnpair by remember { mutableStateOf(false) }
        var homeDialog by remember { mutableStateOf(false) }
        var homeSet by remember { mutableStateOf(Home.isSet(ctx)) }
        var auto by remember { mutableStateOf(Trip.autoResume(ctx)) }
        val exempt = remember(resumeCount) { Power.exempt(ctx) }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Settings", color = INK, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

            // 1. Where home is: arriving ends the trip, leaving opens one.
            SettingRow(
                "Set home location",
                if (homeSet) String.format(java.util.Locale.US, "Home is %.4f, %.4f — tap to change",
                    Home.lat(ctx), Home.lon(ctx))
                else "Not set. Stand at home and tap.",
            ) { homeDialog = true }

            // 2. For a trip that is not on the bike at all (Jack, 2026-09-20).
            SettingRow(
                "Disable auto-resume",
                "Trips never open or resume by themselves. Arriving home still ends one.",
                titleColour = if (auto) INK else WARN,
                trailing = {
                    Switch(checked = !auto, onCheckedChange = { off ->
                        auto = !off
                        Trip.setAutoResume(ctx, auto)
                    })
                },
            ) {
                auto = !auto
                Trip.setAutoResume(ctx, auto)
            }

            // 3. Normally automatic; this is for when it was not.
            SettingRow("Upload log", "Sent by itself when a trip ends. Tap to send it now.") {
                scope.launch {
                    line = "Uploading…"
                    line = withContext(Dispatchers.IO) { Uploader.forceLog(ctx) }
                    onLogSent()
                }
            }

            // 4.
            SettingRow(
                "Set battery exemption",
                if (exempt) "Granted" else "Not granted — tap to allow",
                titleColour = if (exempt) INK else WARN,
            ) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                }
            }

            // 5.
            if (paired) {
                SettingRow("Unpair this phone", "Deletes this phone's key.") { confirmUnpair = true }
            } else {
                SettingRow("Pair this phone", "Needs a code from the Mac.") {
                    showSettings = false
                    onPaired()
                }
            }

            if (line.isNotEmpty()) Text(line, color = DIM, fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 4.dp))
            Spacer(Modifier.height(10.dp))
            Text("Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                "${Identity.get(ctx)} · ${DeviceKey.describe(ctx)}", color = FAINT, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
        }

        if (homeDialog) {
            AlertDialog(
                onDismissRequest = { homeDialog = false },
                title = { Text("Set home location") },
                text = {
                    Column {
                        Text("Arriving home ends a trip by itself. Leaving opens one, unless " +
                            "auto-resume is disabled.")
                        Spacer(Modifier.height(14.dp))
                        Button({
                            homeDialog = false
                            scope.launch {
                                line = "Finding where you are…"
                                val at = here()
                                line = when {
                                    at == null -> "No GPS fix yet — try again outdoors."
                                    at.accuracy > 50f ->
                                        "GPS is only good to ${at.accuracy.toInt()} m — wait a moment and try again."
                                    else -> {
                                        setHome(at.latitude, at.longitude)
                                        homeSet = true
                                        homeNote()
                                    }
                                }
                            }
                        }, Modifier.fillMaxWidth()) { Text("Use my current location") }
                        Spacer(Modifier.height(8.dp))
                        Button({ homeDialog = false; pickingHome = true },
                            Modifier.fillMaxWidth()) { Text("Set location on map") }
                        if (homeSet) {
                            Spacer(Modifier.height(8.dp))
                            TextButton({
                                Home.clear(ctx)
                                homeSet = false
                                homeDialog = false
                                line = "Home cleared."
                            }, Modifier.fillMaxWidth()) { Text("Clear home", color = BAD) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ homeDialog = false }) { Text("Cancel") } }
            )
        }
        if (confirmUnpair) {
            AlertDialog(
                onDismissRequest = { confirmUnpair = false },
                title = { Text("Unpair this phone?") },
                text = { Text("The key is deleted. Pairing again needs a new code.") },
                confirmButton = {
                    TextButton({
                        DeviceKey.clear(ctx)
                        confirmUnpair = false
                        onPaired()
                    }) { Text("Unpair") }
                },
                dismissButton = { TextButton({ confirmUnpair = false }) { Text("Cancel") } }
            )
        }
    }
}

// ---------------------------------------------------------------- small helpers

private fun mapsUrl(lat: Double, lon: Double) =
    String.format(java.util.Locale.US, "https://www.google.com/maps?q=%.6f,%.6f", lat, lon)

/** HH:mm:ss from a relay timestamp. */
private fun timeOf(iso: String?): String {
    val ms = Time.parse(iso) ?: return "—"
    return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))
}

private fun clockTime(ms: Long): String =
    if (ms <= 0) "--:--"
    else java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))

/** h:mm:ss since a wall-clock moment, ticking on the real clock. */
private fun elapsed(sinceMs: Long, nowMs: Long): String {
    if (sinceMs <= 0) return "—"
    val s = ((nowMs - sinceMs) / 1000).coerceAtLeast(0)
    return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}
