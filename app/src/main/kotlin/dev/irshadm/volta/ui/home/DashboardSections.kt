package dev.irshadm.volta.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.VoltaIcons
import dev.irshadm.volta.core.designsystem.tabular
import dev.irshadm.volta.core.ismart.RemoteCommand
import dev.irshadm.volta.core.model.TyrePressures
import dev.irshadm.volta.core.model.VehicleStatus
import dev.irshadm.volta.data.ismart.CommandState
import dev.irshadm.volta.ui.components.TopDownCar
import dev.irshadm.volta.ui.components.VoltaCard
import dev.irshadm.volta.ui.components.confirmWithBiometrics
import dev.irshadm.volta.ui.components.pressScale
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal val parked: VehicleStatus.() -> Boolean = { ignitionOn?.value != true && (speedKmh?.value ?: 0.0) <= 3 }

/**
 * Alerts come in three tiers, and each problem shows in exactly one place:
 *  - [Tier.Act] (red): security or something that can go wrong soon. The banner over the car photo, with its fix.
 *  - [Tier.Check] (amber): needs attention, not this minute. The status word in the panel names it, with a detail line.
 *  - Tips (champagne) are separate: one quiet line under the charge bar (see [HomeUiState.tip]).
 */
enum class Tier { Act, Check }

/** One alert: [short] is the status word ("Unlocked"), [text] the full line, [fix] the command that sorts it. */
data class CarAlert(val tier: Tier, val short: String, val text: String, val fix: RemoteCommand? = null)

/** "Driver window open" for one, "4 windows open" for several. */
private fun openText(names: List<String>, noun: String): String = if (names.size == 1) names[0].cap() + " open" else "${names.size} ${noun}s open"

/**
 * Everything worth flagging now, most serious first. Act-now items about a parked car wait until it has been
 * parked that way for your wait time (Settings → Alerts), so getting out and unloading doesn't set them off.
 */
fun carAlerts(v: VehicleStatus, now: Long, waitMin: Int, tyreChecks: List<TyreCare.Check> = emptyList()): List<CarAlert> = buildList {
    val wait = waitMin * 60_000L
    val parkedLong = v.parked() && (v.ignitionChangedAtMillis?.let { now - it > wait } ?: true)
    // Act now
    val unlockedFor = v.lockChangedAtMillis?.let { now - it }
    if (v.parked() && v.locked?.value == false && unlockedFor != null && unlockedFor > wait) {
        add(CarAlert(Tier.Act, "Unlocked", "Unlocked for ${ago(unlockedFor)}", RemoteCommand.Lock))
    }
    if (parkedLong) {
        v.body?.value?.openNames?.takeIf { it.isNotEmpty() }?.let {
            val short = when {
                it.size > 1 -> "Doors open"
                it[0] == "boot" -> "Boot open"
                it[0] == "bonnet" -> "Bonnet open"
                else -> "Door open"
            }
            add(CarAlert(Tier.Act, short, if (it.size == 1) it[0].cap() + " open" else "${it.size} doors open"))
        }
    }
    if (parkedLong) {
        v.windows?.value?.let { w ->
            w.openNames.takeIf { it.isNotEmpty() }?.let {
                add(CarAlert(Tier.Act, if (it.size == 1) "Window open" else "Windows", openText(it, "window"), RemoteCommand.WindowsClose.takeIf { w.frontLeftOpen || w.frontRightOpen }))
            }
        }
    }
    val soc = v.soc?.value?.socPercent
    // "Not plugged in" only when the car said so: some models never send the charging frame, so it's unknown.
    if (soc != null && soc < 15 && v.pluggedIn?.value != true) {
        add(CarAlert(Tier.Act, "Battery low", "Battery ${soc.roundToInt()} %" + if (v.pluggedIn?.value == false) ", not plugged in" else ""))
    }
    // Check soon. A tyre under the placard target reads as a line with the number, the others and what to do.
    val tyres = v.tyrePsi?.value
    if (tyreChecks.isNotEmpty() && tyres != null) {
        // "Rear-left 31 psi · others 32–33 · top up 2 psi", or for several "Rear-left 31, front-right 30 psi · others 33 · top up 2–3 psi".
        val lowWheels = tyreChecks.map { it.wheel }.toSet()
        val named = tyreChecks.joinToString(", ") { c -> "${c.wheel.label.lowercase()} ${c.psi.roundToInt()}" }.replaceFirstChar { it.uppercase() } + " psi"
        val others = dev.irshadm.volta.ui.components.Wheel.entries.filter { it !in lowWheels }.map { it.psi(tyres).roundToInt() }
        val othersText = when {
            others.isEmpty() -> null
            others.min() == others.max() -> "others ${others.min()}"
            else -> "others ${others.min()}–${others.max()}"
        }
        val gaps = tyreChecks.map { Math.ceil(it.gap).toInt() }.filter { it >= 1 }
        val action = when {
            gaps.isEmpty() -> null
            gaps.min() == gaps.max() -> "top up ${gaps.min()} psi"
            else -> "top up ${gaps.min()}–${gaps.max()} psi"
        }
        // With several named there's no room for the others; the sheet has them.
        add(CarAlert(Tier.Check, if (tyreChecks.size > 1) "${tyreChecks.size} tyres" else "Tyres", listOfNotNull(named, othersText.takeIf { tyreChecks.size == 1 }, action).joinToString(" · ")))
    } else {
        tyres?.takeIf { it.min < TyrePressures.LOW_PSI }?.let { t ->
            add(CarAlert(Tier.Check, "Tyres", "${lowestTyre(t)} ${t.min.roundToInt()} psi"))
        }
    }
    v.auxBatteryVolts?.value?.takeIf { it < 11.8 }?.let { add(CarAlert(Tier.Check, "Weak 12 V", "12 V battery weak (%.1f V) · a drive usually tops it up".format(it))) }
    if (v.parked() && v.lights?.value?.dipped == true) add(CarAlert(Tier.Check, "Lights", "Headlights on while parked"))
    // The car's own warnings arrive as numbers; the ones we know get names (4 = tyre pressure, as the car shows it).
    val tyreAlready = any { it.short == "Tyres" }
    v.warningCodes?.value.orEmpty().forEach { code ->
        when (code) {
            4 -> if (!tyreAlready) {
                add(
                    CarAlert(
                        Tier.Check,
                        "Tyres",
                        "Tyre pressure low" +
                            (v.tyrePsi?.value?.let { t -> " · ${lowestTyre(t).removeSuffix(" tyre").lowercase()} ${t.min.roundToInt()} psi" } ?: ""),
                    ),
                )
            }

            else -> add(CarAlert(Tier.Check, "Warning", "The car reports warning $code"))
        }
    }
}

private fun lowestTyre(t: TyrePressures): String = listOf(
    "Front-left tyre" to t.frontLeft,
    "Front-right tyre" to t.frontRight,
    "Rear-left tyre" to t.rearLeft,
    "Rear-right tyre" to t.rearRight,
).minByOrNull { it.second }!!.first

/**
 * Act-now alerts: a red banner over the top of the car photo with the fix one tap away. Only the most serious
 * shows; others read "+1 more". Check-soon items never come here.
 */
@Composable
fun AttentionBanner(v: VehicleStatus, now: Long, onFix: (RemoteCommand) -> Unit, modifier: Modifier = Modifier) {
    val wait = dev.irshadm.volta.service.Alerts.parkedDelayMin(LocalContext.current)
    val list = remember(v, now / 60_000, wait) { carAlerts(v, now, wait).filter { it.tier == Tier.Act } }
    // The exit animation still draws the banner after the list has emptied, so keep the last non-empty list for it.
    var shown by remember { androidx.compose.runtime.mutableStateOf(list) }
    if (list.isNotEmpty()) shown = list
    AnimatedVisibility(
        list.isNotEmpty(),
        modifier,
        enter = androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn(tween(200)),
        exit = androidx.compose.animation.slideOutVertically { -it / 2 } + androidx.compose.animation.fadeOut(tween(180)),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xF22A1414)).border(1.dp, VoltaColors.tint(VoltaColors.Red, 0.45f), RoundedCornerShape(22.dp))
                .padding(start = 12.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(VoltaIcons.Warning, null, tint = VoltaColors.Red, modifier = Modifier.size(16.dp))
            val first = shown.firstOrNull() ?: return@AnimatedVisibility
            Text(
                first.text + if (shown.size > 1) " · +${shown.size - 1} more" else "",
                style = MaterialTheme.typography.labelLarge,
                color = VoltaColors.Red,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            )
            first.fix?.let { fix ->
                Text(
                    fix.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = VoltaColors.RedInk,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(VoltaColors.Red).clickable { onFix(fix) }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * Everything below the car on one soft panel, no outlines: range and charge as two big numbers; the charge
 * bar, whose faded stretch is what you've used since the last charge, with the full-charge range under it;
 * AC beside the lock slider; then Windows, Find and the status (tap for the details).
 * Without the car link (manual mode) only the numbers and the bar show.
 */
@Composable
fun HomePanel(state: HomeUiState, vm: HomeViewModel, onNeedPin: (RemoteCommand, String) -> Unit, modifier: Modifier = Modifier, onTyres: () -> Unit = {}) {
    val context = LocalContext.current
    val v = state.vehicle
    val tyreVersion by TyreCare.version.collectAsState()
    val tyreChecks = remember(v?.tyrePsi, v?.warningCodes, tyreVersion) { TyreCare.checks(context, v?.tyrePsi?.value, carFlag = TyreCare.carFlag(v?.warningCodes?.value)) }
    val p = state.profile
    val soc = state.soc?.socPercent?.toFloat()
    val reduced = dev.irshadm.volta.core.designsystem.LocalReducedMotion.current
    val shown = remember { Animatable(if (reduced) soc ?: 0f else 0f) }
    LaunchedEffect(soc) { if (soc != null) shown.animateTo(soc, if (reduced) tween(0) else tween(1200, easing = dev.irshadm.volta.core.designsystem.VoltaMotion.EaseOut)) }
    val locked = v?.locked?.value ?: true
    val acOn = v?.climateRunning?.value == true
    val win = v?.windows?.value
    val windowsOpen = win != null && (win.frontLeftOpen || win.frontRightOpen)
    val running = (state.command as? CommandState.Running)?.command
    val failed = (state.command as? CommandState.Failed)?.command
    val live = state.mode != DataMode.Manual && v != null
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    // While the car is on, lock, unlock and the windows stay off: someone is in it, and a remote lock or an opening
    // window at speed is never what you meant from a phone.
    val driving = v != null && !v.parked()
    val drivingOnly = setOf(RemoteCommand.Lock, RemoteCommand.Unlock, RemoteCommand.WindowsOpen, RemoteCommand.WindowsClose)
    fun send(cmd: RemoteCommand, what: String) {
        if (driving && cmd in drivingOnly) return
        if (!state.hasPin) {
            onNeedPin(cmd, what)
            return
        }
        if (running != null) return
        confirmWithBiometrics(context, "Confirm: $what", "Sends the command to your car") { vm.send(cmd) }
    }
    val low = soc != null && soc < 20
    val accent = if (low) VoltaColors.Amber else VoltaColors.Champagne

    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(VoltaColors.Panel).padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 14.dp)) {
        // Range and charge, read together. (The cabin temperature sits in the AC button.)
        val big = MaterialTheme.typography.displayLarge.copy(fontSize = 46.sp, lineHeight = 48.sp, letterSpacing = (-1.5).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal)
        val lowKm = soc?.let { p.highwayRangeKm(it.toDouble()).roundToInt() }
        val highKm = soc?.let { p.cityRangeKm(it.toDouble()).roundToInt() }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(if (lowKm == null || highKm == null) "—" else "${(lowKm + highKm) / 2}")
                        withStyle(androidx.compose.ui.text.SpanStyle(fontSize = 20.sp, color = VoltaColors.Muted)) { append(" km") }
                    },
                    style = big,
                    modifier = Modifier.semantics { contentDescription = if (lowKm == null) "No range yet" else "About ${(lowKm + highKm!!) / 2} kilometres left" },
                )
                Text(
                    if (lowKm == null) "Waiting for the car…" else "$lowKm–$highKm realistic",
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = VoltaColors.Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(if (soc == null) "—" else shown.value.roundToInt().toString())
                        withStyle(androidx.compose.ui.text.SpanStyle(fontSize = 20.sp, color = VoltaColors.Muted)) { append(" %") }
                    },
                    style = big,
                    color = if (low) VoltaColors.Amber else VoltaColors.Text,
                    modifier = Modifier.semantics { contentDescription = soc?.let { "Battery ${it.roundToInt()} percent" } ?: "No battery reading" },
                )
                // The percentage already says how full it is, so the line under it is the odometer.
                val odo = state.soc?.odometerKm ?: v?.soc?.value?.odometerKm
                Text(
                    odo?.let { "Odometer %,d km".format(it.toLong()) } ?: "",
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = VoltaColors.Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (v != null && (v.charging?.value == true || v.pluggedIn?.value == true)) {
            ChargeBar(soc, v, state.plugInSoc, state.charger, Modifier.padding(top = 18.dp)) { start ->
                confirmWithBiometrics(context, if (start) "Confirm: start charging" else "Confirm: stop charging", "Sends the command to your home charger") { vm.setCharging(start) }
            }
        } else {
            SinceChargeBar(soc, v, p.usableCapacityKwh, accent, Modifier.padding(top = 18.dp))
        }
        state.tip?.let { tip -> TipLine(tip, onDismiss = { vm.dismissTip(tip.key) }, modifier = Modifier.padding(top = 12.dp)) }

        if (live) {
            v!!
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AcButton(
                    acOn,
                    state.temps.cabinC?.roundToInt(),
                    state.temps.cabinEstimated,
                    busy = running == RemoteCommand.ClimateOn || running == RemoteCommand.ClimateOff,
                    failed = failed == RemoteCommand.ClimateOn || failed == RemoteCommand.ClimateOff,
                ) {
                    send(if (acOn) RemoteCommand.ClimateOff else RemoteCommand.ClimateOn, if (acOn) "turn AC off" else "turn AC on")
                }
                SlideToggle(
                    locked = locked,
                    busy = running == RemoteCommand.Lock || running == RemoteCommand.Unlock,
                    failed = failed == RemoteCommand.Lock || failed == RemoteCommand.Unlock,
                    disabled = driving,
                    modifier = Modifier.weight(1f),
                ) { send(if (locked) RemoteCommand.Unlock else RemoteCommand.Lock, if (locked) "unlock car" else "lock car") }
            }
            val windowCmds = setOf(RemoteCommand.WindowsOpen, RemoteCommand.WindowsClose)
            val alerts = remember(v, state.nowMillis / 60_000, tyreChecks) { carAlerts(v, state.nowMillis, dev.irshadm.volta.service.Alerts.parkedDelayMin(context), tyreChecks) }
            val worst = alerts.firstOrNull()
            val checksNow = alerts.filter { it.tier == Tier.Check }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                PanelAction(
                    VoltaIcons.Window,
                    if (windowsOpen && !driving) "Close windows" else "Windows",
                    tint = if (driving) {
                        VoltaColors.Dim
                    } else if (failed in windowCmds || (windowsOpen && v.parked())) {
                        VoltaColors.Amber
                    } else {
                        VoltaColors.TextSoft
                    },
                    busy = running in windowCmds,
                    enabled = !driving,
                    modifier = Modifier.weight(1f),
                ) {
                    send(if (windowsOpen) RemoteCommand.WindowsClose else RemoteCommand.WindowsOpen, if (windowsOpen) "close front windows" else "open front windows")
                }
                PanelAction(
                    VoltaIcons.Horn,
                    "Find",
                    tint = if (failed == RemoteCommand.FindMyCar) VoltaColors.Amber else VoltaColors.TextSoft,
                    busy = running == RemoteCommand.FindMyCar,
                    modifier = Modifier.weight(1f),
                ) { send(RemoteCommand.FindMyCar, "flash lights and sound horn") }
                // Act-now keeps the red warning; check-soon is the item's own icon with a small amber dot, no triangle.
                val checkIcon = when {
                    worst?.short?.endsWith("yres") == true -> VoltaIcons.Tyre
                    worst?.short == "Weak 12 V" -> VoltaIcons.Battery12V
                    worst?.short == "Lights" -> VoltaIcons.Bulb
                    else -> VoltaIcons.Warning
                }
                PanelAction(
                    when (worst?.tier) {
                        null -> VoltaIcons.Check
                        Tier.Act -> VoltaIcons.Warning
                        Tier.Check -> checkIcon
                    },
                    when (alerts.size) {
                        0 -> "All good"
                        1 -> worst!!.short
                        else -> "${alerts.size} to check"
                    },
                    tint = when (worst?.tier) {
                        null -> VoltaColors.Champagne
                        Tier.Act -> VoltaColors.Red
                        Tier.Check -> VoltaColors.TextSoft
                    },
                    busy = false,
                    modifier = Modifier.weight(1f),
                    badge = if (worst?.tier == Tier.Check) VoltaColors.Amber else null,
                ) { if (alerts.size == 1 && worst!!.short.endsWith("yres")) onTyres() else open = !open }
            }
            // Check-soon items: one quiet list, a line each, the tyre line opening the tyre detail (act-now ones have the banner over the photo).
            if (checksNow.isNotEmpty()) {
                Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(VoltaColors.tint(VoltaColors.Amber, 0.08f))) {
                    checksNow.forEachIndexed { i, a ->
                        if (i > 0) androidx.compose.material3.HorizontalDivider(color = VoltaColors.tint(VoltaColors.Amber, 0.14f))
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { if (a.short.endsWith("yres")) onTyres() else open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(VoltaColors.Amber))
                            Text(a.text, style = MaterialTheme.typography.bodySmall, color = VoltaColors.AmberText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(VoltaIcons.Chevron, null, tint = VoltaColors.Dim, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
            if (driving) {
                Text("Lock and windows are off while the car is on.", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            } else if (!state.hasPin) {
                Text("These ask for your MG control PIN the first time.", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            }
            // Opening the details scrolls the page so they're fully in view.
            val bringer = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
            var detailsPx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            val margin = with(LocalDensity.current) { 56.dp.toPx() } // the panel's bottom edge and some air under it
            LaunchedEffect(open) {
                if (open) {
                    kotlinx.coroutines.delay(320)
                    bringer.bringIntoView(androidx.compose.ui.geometry.Rect(0f, 0f, detailsPx.width.toFloat(), detailsPx.height + margin))
                }
            }
            state.hero?.let { hero ->
                AnimatedVisibility(open) {
                    StatusDetails(v, hero, Modifier.padding(top = 10.dp).onSizeChanged { detailsPx = it }.bringIntoViewRequester(bringer))
                }
            }
        }
    }
    LaunchedEffect(state.command) {
        when (state.command) {
            is CommandState.Done -> vm.clearCommand()

            is CommandState.Failed -> {
                kotlinx.coroutines.delay(5_000)
                vm.clearCommand()
            }

            else -> Unit
        }
    }
}

/**
 * The charge bar. Solid = what's in the battery now; the faded stretch after it = what you've used since the
 * last charge (labelled with the km), ending where you charged to.
 */
@Composable
private fun SinceChargeBar(soc: Float?, v: VehicleStatus?, capacityKwh: Double, accent: Color, modifier: Modifier) {
    val fill by androidx.compose.animation.core.animateFloatAsState((soc ?: 0f) / 100f, tween(1200, easing = dev.irshadm.volta.core.designsystem.VoltaMotion.EaseOut), label = "fill")
    val km = v?.distanceSinceLastChargeKm?.value?.takeIf { it >= 1 }
    val kwh = v?.energySinceLastChargeKwh?.value?.takeIf { it > 0 }
    val usedPct = kwh?.let { (it / capacityKwh * 100).toFloat() }
    val from = if (soc != null && usedPct != null) (soc + usedPct).coerceAtMost(100f) else null

    Column(modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val w = maxWidth
            Column {
                // "63 km used" over the middle of the faded stretch (no space taken when there's no since-charge data).
                if (from != null) {
                    Box(Modifier.fillMaxWidth().height(18.dp)) {
                        if (from != null && km != null && soc != null) {
                            val mid = w * (((soc + from) / 2f) / 100f)
                            Text(
                                "${km.roundToInt()} km used",
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = VoltaColors.TextSoft,
                                maxLines = 1,
                                modifier = Modifier.align(Alignment.BottomStart).offset(x = (mid - 32.dp).coerceIn(0.dp, w - 70.dp)),
                            )
                        }
                    }
                }
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(8.dp).padding(top = 0.dp)) {
                    val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                    drawRoundRect(VoltaColors.PanelTrack, cornerRadius = r)
                    if (from != null && soc != null) {
                        // Dashed: the charge used since plugging out.
                        val x0 = size.width * soc / 100f
                        val x1 = size.width * from / 100f
                        var x = x0
                        val dash = 3.dp.toPx()
                        while (x < x1) {
                            drawRect(accent.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Size(minOf(dash, x1 - x), size.height))
                            x += dash * 2
                        }
                    }
                    drawRoundRect(accent, size = androidx.compose.ui.geometry.Size(size.width * fill, size.height), cornerRadius = r)
                }
                if (from != null) {
                    Box(Modifier.fillMaxWidth().height(18.dp)) {
                        run {
                            Text(
                                "charged to ${from.roundToInt()} %",
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = VoltaColors.Muted,
                                maxLines = 1,
                                modifier = Modifier.align(Alignment.TopStart).offset(x = (w * (from / 100f) - 44.dp).coerceIn(0.dp, w - 96.dp)).padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Minutes to full from the live charging power (AC loses about 8 % on the way in; the last few % slow down),
 * or the car's own estimate when the power isn't known. The car's figure can be far too long (seven hours
 * for 20 kWh at 6.5 kW), so the measured power wins.
 */
internal fun chargeMinutesLeft(v: VehicleStatus, capacityKwh: Double): Int? {
    val soc = v.soc?.value?.socPercent ?: return v.chargeMinutesRemaining?.value
    val kw = v.chargePowerKw?.value?.takeIf { it >= 0.5 } ?: return v.chargeMinutesRemaining?.value
    if (soc >= 99.5) return 0
    val kwh = (100 - soc) / 100 * capacityKwh
    val taper = if (soc < 95) 15 else 5
    return (kwh / (kw * 0.92) * 60).roundToInt() + taper
}

/**
 * The bar while plugged in: champagne up to where you plugged in, green for what's been added since (pulsing while
 * charging), a dotted run to full. While Volta is connected to the home charger, a bolt knob rides the charge
 * level: slide it right to start charging, all the way back to the left to stop (fingerprint either way). Faint
 * chevrons show which way. The chip at the top already says Plugged in / Charging · full by, so the line under the
 * bar only adds what it doesn't: where the charge started, and the power.
 */
@Composable
private fun ChargeBar(soc: Float?, v: VehicleStatus, plugInSoc: Double?, charger: ChargerUi, modifier: Modifier, onCharge: (Boolean) -> Unit) {
    // While connected to the home charger, its own state counts too: it knows within seconds, the car takes minutes.
    val charging = v.charging?.value == true || charger.chargerCharging
    val now = soc ?: return
    val from = plugInSoc?.toFloat()?.coerceAtMost(now)
    val green = VoltaColors.Green
    val link = charger.link
    val failed = link as? dev.irshadm.volta.data.charger.SpinCharger.Link.Failed
    val busy = link is dev.irshadm.volta.data.charger.SpinCharger.Link.Sending || charger.pending != null
    val connected = link is dev.irshadm.volta.data.charger.SpinCharger.Link.Ready || busy || failed != null
    val full = now >= 99.5f
    val canSlide = connected && !busy && (charging || !full)
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "charge").animateFloat(0.55f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(1100), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    val density = LocalDensity.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var dragPct by remember { mutableStateOf<Float?>(null) }
    var moved by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val startDistance = with(density) { 40.dp.toPx() } // ~4 cm of sliding right starts, even when the bar is nearly full
    val knobSize = 32.dp
    Column(modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(knobSize)) {
            val wPx = constraints.maxWidth.toFloat()
            val shown = dragPct ?: now
            fun arm(should: Boolean) {
                if (should != armed) {
                    armed = should
                    if (should) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                }
            }
            Box(
                Modifier.fillMaxSize().pointerInput(canSlide, charging, now, wPx) {
                    if (!canSlide) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragPct = now
                            moved = 0f
                            armed = false
                        },
                        onDragEnd = {
                            val p = dragPct ?: now
                            if (!charging && (p >= 99.5f || moved >= startDistance)) {
                                onCharge(true)
                            } else if (charging && p <= 4f) {
                                onCharge(false)
                            }
                            dragPct = null
                            armed = false
                        },
                        onDragCancel = {
                            dragPct = null
                            armed = false
                        },
                    ) { change, dx ->
                        change.consume()
                        moved += dx
                        val next = (dragPct ?: now) + dx / wPx * 100f
                        dragPct = if (charging) next.coerceIn(0f, now) else next.coerceIn(now, 100f)
                        arm(if (charging) dragPct!! <= 4f else (dragPct!! >= 99.5f || moved >= startDistance))
                    }
                },
            ) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(10.dp).align(Alignment.CenterStart)) {
                    val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                    drawRoundRect(VoltaColors.PanelTrack, cornerRadius = r)
                    val xNow = size.width * shown / 100f
                    if (charging) {
                        var x = size.width * now / 100f
                        val dash = 3.dp.toPx()
                        while (x < size.width) {
                            drawRect(green.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Size(minOf(dash, size.width - x), size.height))
                            x += dash * 2
                        }
                    }
                    drawRoundRect(if (charging) green.copy(alpha = pulse) else VoltaColors.Champagne, size = androidx.compose.ui.geometry.Size(xNow, size.height), cornerRadius = r)
                    if (from != null && charging) drawRoundRect(VoltaColors.Champagne, size = androidx.compose.ui.geometry.Size(size.width * minOf(from, shown) / 100f, size.height), cornerRadius = r)
                    // Chevrons: where the knob can go (right to start, left to stop), only while it's at rest.
                    if (canSlide && dragPct == null) {
                        val h = size.height * 0.32f
                        val step = 7.dp.toPx()
                        val c = if (charging) VoltaColors.Bg.copy(alpha = 0.45f) else Color(0xFF4A4F57)
                        val start = if (charging) 10.dp.toPx() else size.width - 10.dp.toPx() - step * 2
                        for (k in 0 until 3) {
                            val cx = start + k * step
                            val dir = if (charging) -1f else 1f
                            val p = androidx.compose.ui.graphics.Path().apply {
                                moveTo(cx - dir * h * 0.6f, size.height / 2 - h)
                                lineTo(cx + dir * h * 0.6f, size.height / 2)
                                lineTo(cx - dir * h * 0.6f, size.height / 2 + h)
                            }
                            drawPath(p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                    }
                }
                if (connected && (charging || !full)) {
                    val knobPx = with(density) { knobSize.toPx() }
                    val x = (wPx * shown / 100f - knobPx / 2).coerceIn(0f, wPx - knobPx)
                    Box(
                        Modifier.offset { IntOffset(x.roundToInt(), 0) }.size(knobSize).clip(CircleShape)
                            .background(
                                when {
                                    dragPct != null && armed -> VoltaColors.Text
                                    charging -> green
                                    else -> VoltaColors.Champagne
                                },
                            )
                            .semantics { contentDescription = if (charging) "Charging. Slide the knob all the way left to stop" else "Slide the knob right to start charging" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(color = VoltaColors.Bg, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        } else {
                            Icon(VoltaIcons.Bolt, null, tint = VoltaColors.Bg, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
        // Only what the chip doesn't say: where the charge started (and what's been added), and the power.
        val added0 = charging && from != null && now - from >= 1
        val hasFooter = failed != null || dragPct != null || busy || added0 || from != null || charging || full
        if (hasFooter) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val added = if (charging && from != null && now - from >= 1) {
                    "+${(now - from).roundToInt()} %" + (v.chargeStartedAtMillis?.let { " since ${clock(it)}" } ?: "")
                } else {
                    null
                }
                val starting = (link as? dev.irshadm.volta.data.charger.SpinCharger.Link.Sending)?.start ?: charger.pending
                val leftText = when {
                    failed != null -> failed.message
                    dragPct != null -> if (charging) (if (armed) "Release to stop" else "Slide all the way left to stop") else (if (armed) "Release to start" else "Slide right to start")
                    busy -> if (starting == true) "Starting…" else "Stopping…"
                    added != null -> added
                    else -> from?.let { "Plugged in at ${it.roundToInt()} %" } ?: ""
                }
                Text(
                    leftText,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = when {
                        failed != null -> VoltaColors.Amber
                        dragPct != null || busy -> VoltaColors.TextSoft
                        else -> VoltaColors.Muted
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val right = when {
                    charging -> v.chargePowerKw?.value?.takeIf { it >= 0.5 }?.let { "%.1f kW".format(it) }
                    full -> "Full"
                    else -> null
                }
                if (right != null) Text(right, style = MaterialTheme.typography.bodySmall.tabular(), color = green)
            }
        }
    }
}

/** A tip under the charge bar (champagne, never urgent). Swipe it away and it stays gone for the day. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TipLine(tip: dev.irshadm.volta.service.BatteryCare.Tip, onDismiss: () -> Unit, modifier: Modifier) {
    val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState(confirmValueChange = { it != androidx.compose.material3.SwipeToDismissBoxValue.Settled })
    LaunchedEffect(swipe.currentValue) { if (swipe.currentValue != androidx.compose.material3.SwipeToDismissBoxValue.Settled) onDismiss() }
    androidx.compose.material3.SwipeToDismissBox(swipe, backgroundContent = {}, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(VoltaColors.PanelTrack).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(VoltaIcons.BatteryTab, null, tint = VoltaColors.Champagne, modifier = Modifier.padding(top = 1.dp).size(17.dp))
            Column {
                Text(tip.title, style = MaterialTheme.typography.labelLarge, color = VoltaColors.Text)
                Text(tip.text, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
            }
        }
    }
}

/** Round AC button beside the lock slider, with the cabin temperature under the snowflake: blue while running. */
@Composable
private fun AcButton(on: Boolean, cabin: Int?, estimated: Boolean, busy: Boolean, failed: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val tint = when {
        failed -> VoltaColors.Amber
        on -> VoltaColors.BlueSoft
        else -> VoltaColors.TextSoft
    }
    val bg by animateColorAsState(if (on) VoltaColors.tint(VoltaColors.BlueSoft, 0.18f) else VoltaColors.PanelTrack, tween(220), label = "acBg")
    Box(
        Modifier.size(52.dp).pressScale(interaction).clip(CircleShape).background(bg)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = (if (on) "AC on. Tap to turn off" else "AC off. Tap to turn on") + (cabin?.let { ", cabin about $it degrees" } ?: "") },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(VoltaIcons.Snow, null, tint = tint, modifier = Modifier.size(if (cabin == null) 22.dp else 17.dp))
                if (cabin != null) {
                    Text(
                        (if (estimated) "~" else "") + "$cabin°",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = when {
                            on -> VoltaColors.BlueSoft
                            cabin >= 35 -> VoltaColors.Amber
                            else -> VoltaColors.Muted
                        },
                    )
                }
            }
        }
    }
}

/** A quiet icon + word action on the panel (no outline). */
@Composable
private fun PanelAction(icon: ImageVector, label: String, tint: Color, busy: Boolean, modifier: Modifier, enabled: Boolean = true, badge: Color? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.pressScale(interaction).clip(RoundedCornerShape(14.dp)).clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.55f }.padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        } else {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        androidx.compose.foundation.text.BasicText(
            "  $label",
            style = MaterialTheme.typography.labelLarge.copy(color = tint),
            maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
        )
        if (badge != null) Box(Modifier.padding(start = 6.dp).size(7.dp).clip(CircleShape).background(badge))
    }
}

/** Pill with a draggable thumb: drag past ~80 % to act, otherwise it springs back. */
@Composable
private fun SlideToggle(locked: Boolean, busy: Boolean, failed: Boolean, modifier: Modifier, disabled: Boolean = false, onSlide: () -> Unit) {
    val tint = if (disabled) {
        VoltaColors.Dim
    } else if (locked) {
        VoltaColors.Champagne
    } else {
        VoltaColors.Amber
    }
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var trackPx by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    val thumb = 40.dp
    val maxPx = (trackPx - with(density) { (thumb + 8.dp).toPx() }).coerceAtLeast(1f)
    val progress = (offset.value / maxPx).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(52.dp).clip(shape).background(VoltaColors.PanelTrack)
            .onSizeChanged { trackPx = it.width.toFloat() }
            .pointerInput(locked, busy, disabled, maxPx) {
                if (busy || disabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (offset.value > maxPx * 0.8f) {
                                offset.animateTo(maxPx, tween(120))
                                onSlide()
                            }
                            offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f))
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { change, drag ->
                    change.consume()
                    scope.launch { offset.snapTo((offset.value + drag).coerceIn(0f, maxPx)) }
                }
            }
            .semantics {
                contentDescription = if (disabled) {
                    "Lock and unlock are off while the car is on"
                } else if (locked) {
                    "Locked. Slide to unlock"
                } else {
                    "Unlocked. Slide to lock"
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxHeight().width(with(density) { offset.value.toDp() } + thumb + 8.dp).clip(shape).background(VoltaColors.tint(tint, 0.10f + 0.12f * progress)))
        Text(
            when {
                disabled -> "Not while driving"
                busy -> if (locked) "Unlocking…" else "Locking…"
                failed -> "Didn't go through"
                else -> if (locked) "Slide to unlock" else "Slide to lock"
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (failed) VoltaColors.Amber else VoltaColors.TextSoft,
            maxLines = 1,
            modifier = Modifier.padding(start = thumb + 14.dp, end = 6.dp).graphicsLayer { alpha = 1f - progress },
        )
        Box(
            Modifier.padding(start = 6.dp).offset { IntOffset(offset.value.roundToInt(), 0) }.size(thumb).clip(CircleShape).background(tint),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(color = VoltaColors.Bg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(if (locked) VoltaIcons.Locked else VoltaIcons.Unlocked, null, tint = VoltaColors.Bg, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private data class Check(val icon: ImageVector, val name: String, val value: String, val bad: Boolean)

/** "Passenger open", "Driver, boot open", or from three on just "4 open" (the car beside it shows which). */
private fun openList(names: List<String>, suffix: String): String? = when {
    names.isEmpty() -> null
    names.size <= 2 -> names.joinToString(", ") { it.removeSuffix(suffix) }.cap() + " open"
    else -> "${names.size} open"
}

private fun checks(v: VehicleStatus): List<Check> {
    val body = v.body?.value
    val win = v.windows?.value
    val tyres = v.tyrePsi?.value
    val lights = v.lights?.value
    val volts = v.auxBatteryVolts?.value
    return listOf(
        Check(VoltaIcons.Car, "Doors", body?.openNames?.let { openList(it, " door") } ?: "Closed", body?.anyOpen == true),
        Check(VoltaIcons.Window, "Windows", win?.openNames?.let { openList(it, " window") } ?: if (win == null) "—" else "Closed", win?.anyOpen == true),
        Check(VoltaIcons.Bulb, "Lights", lights?.label ?: v.lightsOn?.value?.let { if (it) "On" else "Off" } ?: "—", v.parked() && lights?.dipped == true),
        Check(VoltaIcons.Tyre, "Tyres", tyres?.let { "${it.min.roundToInt()}–${it.max.roundToInt()} psi" } ?: "—", tyres != null && tyres.min < TyrePressures.LOW_PSI),
        Check(VoltaIcons.Battery12V, "12 V", volts?.let { "%.1f V".format(it) } ?: "—", (volts ?: 13.0) < 11.8),
    )
}

/** The top-down car and the full checklist. */
@Composable
private fun StatusDetails(v: VehicleStatus, hero: dev.irshadm.volta.ui.components.CarHeroState, modifier: Modifier) {
    VoltaCard(modifier.fillMaxWidth(), background = VoltaColors.PanelTrack, border = Color.Transparent, radius = 18.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TopDownCar(hero, Modifier.width(112.dp).height(150.dp), compact = true)
            Column(Modifier.weight(1f)) {
                checks(v).forEach { CheckRow(it.name, it.value, it.bad) }
                v.handbrakeOn?.value?.let { CheckRow("Handbrake", if (it) "On" else "Off", false) }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, value: String, bad: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted, modifier = Modifier.weight(0.8f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.tabular(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            color = if (bad) VoltaColors.Amber else VoltaColors.TextSoft,
            modifier = Modifier.weight(1.2f),
        )
    }
}

/**
 * Where the car is, as one centred line under the photo: "Home · MG Road, Bengaluru ›".
 * Tap for walking directions; long-press to name the spot. (How long it's been parked is in the chip up top.)
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LocationLine(state: HomeUiState, onName: (String) -> Unit, modifier: Modifier = Modifier) {
    val v = state.vehicle ?: return
    val gps = v.gps?.value ?: return
    val context = LocalContext.current
    val parts = state.placeName?.split(", ").orEmpty()
    val primary = parts.firstOrNull() ?: "Car's location"
    val area = parts.drop(1).takeLast(2).joinToString(", ")
    var naming by remember { androidx.compose.runtime.mutableStateOf(false) }
    if (naming) {
        NamePlaceDialog(primary.removePrefix("Near ").takeIf { parts.isNotEmpty() } ?: "", onDismiss = { naming = false }) {
            onName(it)
            naming = false
        }
    }
    Row(
        modifier.clip(RoundedCornerShape(12.dp))
            .combinedClickable(onLongClick = { naming = true }, onLongClickLabel = "Name this place", onClickLabel = "Walking directions") {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${gps.latitude},${gps.longitude}&travelmode=walking"))
                            .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(VoltaIcons.Pin, null, tint = VoltaColors.Champagne, modifier = Modifier.size(15.dp))
        androidx.compose.animation.Crossfade(primary to area, animationSpec = tween(360), label = "placeName", modifier = Modifier.weight(1f, fill = false)) { (p, a) ->
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    append("  $p")
                    if (a.isNotEmpty()) withStyle(androidx.compose.ui.text.SpanStyle(color = VoltaColors.Muted)) { append(" · $a") }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = VoltaColors.TextSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(VoltaIcons.Chevron, null, tint = VoltaColors.Dim, modifier = Modifier.padding(start = 4.dp).size(13.dp))
    }
}

@Composable
private fun NamePlaceDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { androidx.compose.runtime.mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoltaColors.Surface,
        title = { Text("Name this place", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shown on the dashboard whenever the car is parked within about 150 m of here.", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
                androidx.compose.material3.OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("Apartment") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { androidx.compose.material3.TextButton({ onSave(text) }, enabled = text.isNotBlank()) { Text("Save", color = VoltaColors.Gold) } },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { Text("Cancel", color = VoltaColors.Muted) } },
    )
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, color: Color = VoltaColors.Text) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall.tabular(), color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = VoltaColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

private fun String.cap() = replaceFirstChar { it.uppercase() }

private val clockFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private fun clock(millis: Long) = clockFmt.format(Date(millis)).lowercase()

private fun ago(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min"
        m < 48 * 60 -> "${m / 60} h ${m % 60} min"
        else -> "${m / 1440} days"
    }
}
