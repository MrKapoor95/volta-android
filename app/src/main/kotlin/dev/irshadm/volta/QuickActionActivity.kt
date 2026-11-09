package dev.irshadm.volta

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.VoltaIcons
import dev.irshadm.volta.core.designsystem.VoltaTheme
import dev.irshadm.volta.core.ismart.RemoteCommand
import dev.irshadm.volta.data.ismart.CommandState
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.ui.components.VoltaButton
import dev.irshadm.volta.ui.components.VoltaButtonStyle
import dev.irshadm.volta.ui.components.confirmWithBiometrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Opened by an app shortcut, the home-screen widget or a Samsung Modes and Routines action (volta://action/precool|lock|unlock|find).
 * Shows the car's current state and one button; the command is only sent after the user taps it
 * and confirms with fingerprint/screen lock. Dismissing the sheet sends nothing.
 */
@AndroidEntryPoint
class QuickActionActivity : FragmentActivity() {
    @Inject lateinit var ismart: IsmartRepository

    @Inject lateinit var weatherClient: dev.irshadm.volta.data.weather.WeatherClient

    @Inject lateinit var location: dev.irshadm.volta.data.trip.LocationProvider

    @Inject lateinit var settings: dev.irshadm.volta.data.SettingsRepository

    private enum class Action { Precool, Lock, Unlock, Find }

    private companion object {
        const val TARGET_C = dev.irshadm.volta.service.smart.Cooling.TARGET_C
        const val COMFY_BELOW = dev.irshadm.volta.service.smart.Cooling.COMFY_BELOW
    }

    /**
     * Set when opened from a smart pre-cool suggestion: answers teach the per-place threshold. Only trusted with the
     * latest suggestion's token, since this activity is exported and any app could pass a place.
     */
    private val suggestionPlace: String? by lazy {
        intent?.takeIf { dev.irshadm.volta.service.smart.Feedback.isSuggestionToken(this, it.getStringExtra("suggestion_token")) }
            ?.getStringExtra("suggestion_place")
    }
    private var answered = false

    private fun learn(accepted: Boolean) {
        val place = suggestionPlace ?: return
        if (answered) return
        answered = true
        if (accepted) dev.irshadm.volta.service.smart.Feedback.accepted(this, place) else dev.irshadm.volta.service.smart.Feedback.dismissed(this, place)
        androidx.core.app.NotificationManagerCompat.from(this).cancel(dev.irshadm.volta.service.smart.DepartureWorker.NOTIFICATION_ID)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = when (intent?.data?.lastPathSegment) {
            "lock" -> Action.Lock
            "unlock" -> Action.Unlock
            "find" -> Action.Find
            else -> Action.Precool
        }
        ismart.clearCommand()
        // Fresh temperatures/lock state if the last reading is older than 10 minutes.
        val stale = ismart.status.value?.soc?.value?.measuredAtMillis?.let { System.currentTimeMillis() - it > 10 * 60_000 } ?: true
        if (stale && ismart.link.value is LinkState.Connected) lifecycleScope.launch { ismart.refresh(includeCharge = false) }

        setContent {
            VoltaTheme {
                Box(
                    Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) {
                        learn(false)
                        finish()
                    },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Sheet(action)
                }
            }
        }
    }

    @Composable
    private fun Sheet(action: Action) {
        val status by ismart.status.collectAsStateWithLifecycle()
        val link by ismart.link.collectAsStateWithLifecycle()
        val pinSaved by ismart.pinSaved.collectAsStateWithLifecycle()
        val pinVm: dev.irshadm.volta.feature.onboarding.pin.ControlPinViewModel = androidx.hilt.navigation.compose.hiltViewModel()
        val pinState by pinVm.state.collectAsStateWithLifecycle()
        val liveCommand by ismart.command.collectAsStateWithLifecycle()
        val command = liveCommand
        val acOn = status?.climateRunning?.value == true
        val locked = status?.locked?.value
        val driving = status?.ignitionOn?.value == true
        val gps = status?.gps?.value
        val weather by androidx.compose.runtime.produceState<dev.irshadm.volta.data.weather.Weather?>(null, gps?.latitude, gps?.longitude) {
            value = gps?.let { weatherClient.now(it.latitude, it.longitude) }
        }
        val temps = dev.irshadm.volta.data.weather.CabinModel.temps(status, weather)
        val inside = temps.cabinC?.roundToInt()
        val outside = temps.outsideC?.roundToInt()
        val capacity by androidx.compose.runtime.produceState(37.1) { value = settings.vehicleProfile.first().usableCapacityKwh }

        val (icon, title, detail) = when (action) {
            Action.Precool -> Triple(
                VoltaIcons.Snow,
                if (acOn) "AC is running" else "Pre-cool your car",
                listOfNotNull(inside?.let { if (temps.cabinEstimated) "Cabin about $it°" else "Cabin $it°" }, outside?.let { "outside $it°" }).joinToString(" · ").ifEmpty { "Starts the AC remotely" },
            )

            Action.Lock -> Triple(
                if (locked == false) VoltaIcons.Unlocked else VoltaIcons.Locked,
                if (locked == true) "Already locked" else "Lock your car",
                if (locked == false) "The car is unlocked" else "Locks all doors",
            )

            Action.Unlock -> Triple(
                if (locked == false) VoltaIcons.Unlocked else VoltaIcons.Locked,
                if (locked == false) "Already unlocked" else "Unlock your car",
                if (locked == false) "The car is unlocked" else "Unlocks the doors",
            )

            Action.Find -> Triple(VoltaIcons.Horn, "Find your car", "Flashes the lights and sounds the horn")
        }
        val cmd = when (action) {
            Action.Precool -> if (acOn) RemoteCommand.ClimateOff else RemoteCommand.ClimateOn
            Action.Lock -> RemoteCommand.Lock
            Action.Unlock -> RemoteCommand.Unlock
            Action.Find -> RemoteCommand.FindMyCar
        }
        val button = when (action) {
            Action.Precool -> if (acOn) "Turn AC off" else "Start AC"
            Action.Lock -> "Lock"
            Action.Unlock -> "Unlock"
            Action.Find -> "Flash & honk"
        }

        LaunchedEffect(command) {
            val c = command
            if (c is CommandState.Done) {
                // The cool starts (or ends) here: the progress notification follows it from now on.
                if (c.command == RemoteCommand.ClimateOn) dev.irshadm.volta.service.smart.Cooling.started(this@QuickActionActivity, temps.cabinC ?: 32.0, capacity)
                if (c.command == RemoteCommand.ClimateOff) dev.irshadm.volta.service.smart.Cooling.stopped(this@QuickActionActivity)
                delay(1500)
                finish()
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(VoltaColors.Surface)
                .clickable(remember { MutableInteractionSource() }, indication = null) {} // taps inside don't dismiss
                .navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(VoltaColors.BorderStrong))
            if (action == Action.Precool) {
                PrecoolHeader(status, temps, acOn, sheltered = weather?.sheltered == true, capacity = capacity)
            } else {
                CarHeader(action, status)
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = VoltaColors.Text)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
                }
            }
            AnimatedContent(
                command,
                contentKey = { it::class },
                transitionSpec = { (fadeIn(tween(200)) + slideInVertically { it / 4 }) togetherWith fadeOut(tween(120)) },
                label = "quick",
            ) { c ->
                when {
                    link !is LinkState.Connected -> Note("Volta isn't connected to your car. Open the app to sign in or reconnect.", VoltaColors.Amber) { open() }

                    // No PIN yet: ask for it here, then the action below takes over.
                    !pinSaved -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Add your MG control PIN first. You only enter it once.", style = MaterialTheme.typography.bodyMedium, color = VoltaColors.TextSoft)
                        dev.irshadm.volta.feature.onboarding.pin.ControlPinForm(pinState, pinVm::onPinChange, { pinVm.check() }, "Check with MG", autoFocus = false)
                    }

                    // No remote lock or unlock while the car is on: someone is in it.
                    driving && (action == Action.Lock || action == Action.Unlock) -> Note("Lock and unlock are off while the car is on.", VoltaColors.Amber) { finish() }

                    c is CommandState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = VoltaColors.Gold, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Text("Sending… the car can take 10–30 seconds", style = MaterialTheme.typography.bodyMedium, color = VoltaColors.TextSoft)
                    }

                    c is CommandState.Done -> Text("Done ✓", style = MaterialTheme.typography.titleMedium, color = VoltaColors.Green)

                    c is CommandState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(c.message, style = MaterialTheme.typography.bodySmall, color = VoltaColors.AmberText)
                        VoltaButton("Close", { finish() }, Modifier.fillMaxWidth(), style = VoltaButtonStyle.Secondary)
                    }

                    (action == Action.Lock && locked == true) || (action == Action.Unlock && locked == false) -> VoltaButton("Close", { finish() }, Modifier.fillMaxWidth(), style = VoltaButtonStyle.Secondary)

                    // Slide, then fingerprint; tapping outside or swiping the sheet away is "not now".
                    action == Action.Precool -> {
                        val coolMin = temps.cabinC?.let { dev.irshadm.volta.service.smart.Cooling.minutes(it) } ?: 6
                        val send = {
                            confirmWithBiometrics(this@QuickActionActivity, "Confirm: ${button.lowercase()}", "Sends the command to your car") {
                                learn(true)
                                lifecycleScope.launch {
                                    ismart.send(cmd)
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when {
                                acOn -> {
                                    CoolingProgress()
                                    SlideToSend("Slide to stop", stop = true, onSlide = send)
                                }

                                comfortable(temps) -> {
                                    VoltaButton("Fine, thanks", {
                                        learn(false)
                                        finish()
                                    }, Modifier.fillMaxWidth())
                                    SlideToSend("Slide to cool anyway · $coolMin min", stop = false, quiet = true, onSlide = send)
                                }

                                else -> SlideToSend("Slide to cool · $coolMin min", stop = false, onSlide = send)
                            }
                            if (!acOn) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val place = suggestionPlace
                                    if (place != null) {
                                        Text(
                                            "Not today here",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = VoltaColors.Muted,
                                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                                dev.irshadm.volta.service.smart.Feedback.muteToday(this@QuickActionActivity, place)
                                                answered = true
                                                androidx.core.app.NotificationManagerCompat.from(this@QuickActionActivity).cancel(dev.irshadm.volta.service.smart.DepartureWorker.NOTIFICATION_ID)
                                                finish()
                                            }.padding(vertical = 6.dp),
                                        )
                                    }
                                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                    Text("swipe down for not now", style = MaterialTheme.typography.labelSmall, color = VoltaColors.Dim)
                                }
                            }
                        }
                    }

                    // One action; tapping outside or swiping the sheet away is "not now".
                    else -> VoltaButton(button, {
                        confirmWithBiometrics(this@QuickActionActivity, "Confirm: ${button.lowercase()}", "Sends the command to your car") {
                            learn(true)
                            lifecycleScope.launch { ismart.send(cmd) }
                        }
                    }, Modifier.fillMaxWidth(), icon = icon)
                }
            }
        }
    }

    private fun comfortable(t: dev.irshadm.volta.data.weather.Temps) = (t.cabinC ?: 99.0) < COMFY_BELOW

    /**
     * Pre-cool header, as designed: a context chip (where you are, how far the car is), the cabin now → target with
     * the outside muted, the car on a tinted badge (warm while hot, blue while cooling, green when fine), then one
     * title and one sentence with the time and the battery cost.
     */
    @Composable
    private fun PrecoolHeader(v: dev.irshadm.volta.core.model.VehicleStatus?, temps: dev.irshadm.volta.data.weather.Temps, acOn: Boolean, sheltered: Boolean, capacity: Double) {
        val reason = intent?.getStringExtra("suggestion_reason")
        val place = suggestionPlace?.takeIf { it != "elsewhere" }
        val usualAt = intent?.getStringExtra("suggestion_usual")
        val inside = temps.cabinC?.roundToInt()
        val outside = temps.outsideC?.roundToInt()
        val car = v?.gps?.value?.let { dev.irshadm.volta.core.planner.LatLng(it.latitude, it.longitude) }
        val walkM by androidx.compose.runtime.produceState<Double?>(null, car) {
            if (car != null && location.hasPermission()) value = location.current(4_000)?.let { dev.irshadm.volta.core.planner.Geo.distanceM(it.position, car) }
        }
        val walk = walkM?.takeIf { it in 80.0..2_000.0 }
        val walkMin = walk?.let { kotlin.math.ceil(it / 80).toInt() }
        val onFoot = dev.irshadm.volta.service.smart.Walking.now(this) == dev.irshadm.volta.service.smart.Walking.Mode.Foot
        val walking = walkMin != null && (onFoot || reason == "approach")
        val coolMin = temps.cabinC?.let { dev.irshadm.volta.service.smart.Cooling.minutes(it) } ?: 6
        val cost = dev.irshadm.volta.service.smart.Cooling.batteryText(dev.irshadm.volta.service.smart.Cooling.batteryPercent(coolMin, capacity))
        val comfy = !acOn && comfortable(temps)
        val session = if (acOn) dev.irshadm.volta.service.smart.Cooling.session(this) else null
        val hot = !acOn && (inside ?: 0) >= 35

        val chip: Pair<ImageVector, String>? = when {
            acOn -> VoltaIcons.Snow to "Cooling" + (session?.let { " · started ${clock(it.startedAt)}" } ?: "")
            walking -> VoltaIcons.Walk to listOfNotNull(place?.takeIf { reason != "approach" }?.let { "Leaving $it" }, "$walkMin min walk").joinToString(" · ")
            place != null -> VoltaIcons.Pin to "Leaving $place" + if (sheltered) " · parked under cover" else ""
            sheltered -> VoltaIcons.Pin to "Parked under cover"
            else -> null
        }
        val chipRight = when {
            session != null -> "ready about ${clock(session.startedAt + session.minutes * 60_000L)}"
            usualAt != null -> "like usual around $usualAt"
            else -> null
        }
        val (title, text) = when {
            acOn -> "Cooling the cabin" to "Heading for $TARGET_C°. The number follows Volta's model; the car only reports its cabin while parked and off."
            comfy -> "Already comfortable" to (if (sheltered) "Under cover the cabin stays close to the outside air. No need to run the AC." else "It's close to the outside air. No need to run the AC.")
            walking -> "Cool it while you walk there" to "About ${minutes(coolMin)} to $TARGET_C°, $cost of the battery. Ready when you reach the car."
            reason == "left" || reason == "wifi" -> "On your way to the car?" to "About ${minutes(coolMin)} to $TARGET_C°, $cost of the battery. Start now and it's ready when you are."
            usualAt != null -> "Heading out soon?" to "You usually leave around $usualAt. About ${minutes(coolMin)} to $TARGET_C°, $cost of the battery."
            hot -> "It's hot in there" to "It's been heating up while parked. About ${minutes(coolMin)} to $TARGET_C°, $cost of the battery."
            else -> "Ready in about ${minutes(coolMin)}" to "Start now and the cabin will be close to $TARGET_C° when you reach the car."
        }
        val accent = when {
            acOn -> androidx.compose.ui.graphics.Color(0xFF7DD3E8)
            comfy -> VoltaColors.Green
            else -> VoltaColors.Amber
        }
        val badge = when {
            acOn -> androidx.compose.ui.graphics.Color(0xFF1F4A58)
            comfy -> androidx.compose.ui.graphics.Color(0xFF163A2A)
            else -> androidx.compose.ui.graphics.Color(0xFF5A2E14)
        }
        val now = session?.cabinNow()?.roundToInt() ?: inside

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (chip != null || chipRight != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    chip?.let { (icon, label) ->
                        Row(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(VoltaColors.SurfaceHigh).padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(icon, null, tint = if (comfy) VoltaColors.Muted else VoltaColors.Champagne, modifier = Modifier.size(14.dp))
                            Text(label, style = MaterialTheme.typography.labelMedium, color = if (comfy) VoltaColors.TextSoft else VoltaColors.Champagne)
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    chipRight?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = VoltaColors.Dim) }
                }
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    val big = MaterialTheme.typography.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, letterSpacing = (-1).sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(now?.let { "$it°" } ?: "—", style = big, color = accent)
                        if (comfy) {
                            Icon(VoltaIcons.Check, null, tint = VoltaColors.Green, modifier = Modifier.size(22.dp))
                        } else {
                            Icon(VoltaIcons.Chevron, null, tint = VoltaColors.Dim, modifier = Modifier.size(18.dp))
                            Text("$TARGET_C°", style = big, color = VoltaColors.Green)
                        }
                    }
                    Text(
                        when {
                            session != null -> "${kotlin.math.ceil(session.leftMin()).toInt()} min left" + (outside?.let { " · outside $it°" } ?: "")
                            else -> listOfNotNull("cabin" + if (temps.cabinEstimated) ", estimated" else "", outside?.let { "outside $it°" }).joinToString(" · ")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = VoltaColors.Muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).drawBehind {
                        drawRect(androidx.compose.ui.graphics.Brush.radialGradient(listOf(badge, badge.copy(alpha = 0.55f)), center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.35f), radius = size.width * 0.8f))
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(VoltaIcons.Car, null, tint = accent, modifier = Modifier.size(36.dp)) }
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = VoltaColors.Text)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = VoltaColors.TextSoft, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }

    /** While the AC runs: how far along the cool is, from Volta's model of it. */
    @Composable
    private fun CoolingProgress() {
        val session = dev.irshadm.volta.service.smart.Cooling.session(this) ?: return
        var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(5_000)
                now = System.currentTimeMillis()
            }
        }
        val f = (session.elapsedMin(now) / session.minutes).coerceIn(0.0, 1.0).toFloat()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(VoltaColors.PanelTrack)) {
                Box(
                    Modifier.fillMaxWidth(f.coerceAtLeast(0.02f)).height(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(VoltaColors.Amber, androidx.compose.ui.graphics.Color(0xFF7DD3E8)))),
                )
            }
            Row {
                Text("started ${clock(session.startedAt)}", style = MaterialTheme.typography.labelSmall, color = VoltaColors.Dim, modifier = Modifier.weight(1f))
                Text("ready about ${clock(session.startedAt + session.minutes * 60_000L)}", style = MaterialTheme.typography.labelSmall, color = VoltaColors.Dim)
            }
        }
    }

    private fun clock(ms: Long) = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ms)).lowercase()

    private fun minutes(n: Int) = if (n == 1) "1 minute" else "$n minutes"

    /**
     * Slide the knob across to send (or back, to stop the AC); the fingerprint prompt follows. Springs home if let
     * go early, so a brush of the thumb never sends anything.
     */
    @Composable
    private fun SlideToSend(label: String, stop: Boolean, quiet: Boolean = false, onSlide: () -> Unit) {
        val tint = if (stop) {
            VoltaColors.BlueSoft
        } else if (quiet) {
            VoltaColors.SurfaceHigh
        } else {
            VoltaColors.Champagne
        }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val offset = remember { androidx.compose.animation.core.Animatable(0f) }
        var trackPx by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        val knob = if (quiet) 36.dp else 44.dp
        val maxPx = (trackPx - with(density) { (knob + 8.dp).toPx() }).coerceAtLeast(1f)
        val progress = (offset.value / maxPx).coerceIn(0f, 1f)
        val dir = if (stop) -1f else 1f
        Box(
            Modifier.fillMaxWidth().height(if (quiet) 46.dp else 56.dp).clip(RoundedCornerShape(50))
                .then(if (quiet) Modifier.border(1.dp, VoltaColors.Border, RoundedCornerShape(50)) else Modifier.background(VoltaColors.PanelTrack))
                .onSizeChanged { trackPx = it.width.toFloat() }
                .pointerInput(maxPx, stop) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offset.value > maxPx * 0.8f) {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    offset.animateTo(maxPx, tween(120))
                                    onSlide()
                                }
                                offset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 400f))
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + drag * dir).coerceIn(0f, maxPx)) }
                    }
                }
                .semantics { contentDescription = label },
            contentAlignment = if (stop) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Text(
                if (stop) "‹‹‹  $label" else "$label  ›››",
                style = MaterialTheme.typography.labelLarge,
                color = if (quiet) VoltaColors.Muted else VoltaColors.TextSoft,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = 1f - progress },
            )
            Box(
                Modifier.padding(horizontal = 6.dp).offset { IntOffset((offset.value * dir).roundToInt(), 0) }.size(knob).clip(CircleShape).background(tint),
                contentAlignment = Alignment.Center,
            ) { Icon(VoltaIcons.Snow, null, tint = if (quiet) VoltaColors.TextSoft else VoltaColors.Bg, modifier = Modifier.size(if (quiet) 18.dp else 22.dp)) }
        }
    }

    /**
     * The car as it is now (the dashboard's photo for its state) on a soft glow in the action's colour: cool blue
     * for the AC, champagne for the locks, warm for Find.
     */
    @Composable
    private fun CarHeader(action: Action, v: dev.irshadm.volta.core.model.VehicleStatus?) {
        val glow = when (action) {
            Action.Precool -> androidx.compose.ui.graphics.Color(0xFF64D2FF)
            Action.Find -> VoltaColors.Amber
            else -> VoltaColors.Champagne
        }
        val shot = when {
            v?.charging?.value == true -> dev.irshadm.volta.ui.components.CarShot.Charging
            v?.lightsOn?.value == true -> dev.irshadm.volta.ui.components.CarShot.Lights
            v?.locked?.value == false -> dev.irshadm.volta.ui.components.CarShot.Unlocked
            else -> dev.irshadm.volta.ui.components.CarShot.Locked
        }
        val res = dev.irshadm.volta.ui.components.CarPictures.small(dev.irshadm.volta.data.ismart.CarIdentity.saved(this).model, shot)
        Box(
            Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(20.dp)).background(VoltaColors.Bg)
                .drawBehind {
                    drawRect(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(glow.copy(alpha = 0.32f), androidx.compose.ui.graphics.Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.42f),
                            radius = size.width * 0.45f,
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(res),
                null,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }

    @Composable
    private fun Note(text: String, color: androidx.compose.ui.graphics.Color, onOpen: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
            VoltaButton("Open Volta", onOpen, Modifier.fillMaxWidth(), style = VoltaButtonStyle.Secondary)
        }
    }

    private fun open() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
