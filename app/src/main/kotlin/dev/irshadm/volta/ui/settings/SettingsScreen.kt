package dev.irshadm.volta.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irshadm.volta.BuildConfig
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.VoltaIcons
import dev.irshadm.volta.core.designsystem.tabular
import dev.irshadm.volta.core.model.ChargerPreferences
import dev.irshadm.volta.core.model.NetworkPreference
import dev.irshadm.volta.core.model.VehicleProfile
import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.LatLng
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.feature.onboarding.mapskey.MapsKeyMode
import dev.irshadm.volta.feature.onboarding.mapskey.MapsKeyRoute
import dev.irshadm.volta.service.Alerts
import dev.irshadm.volta.service.CarCheckWorker
import dev.irshadm.volta.service.smart.Narrator
import dev.irshadm.volta.ui.components.VoltaButton
import dev.irshadm.volta.ui.components.VoltaButtonStyle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class Page(val title: String) {
    Main("Settings"),
    Account("Car & account"),
    Planning("Trip planning"),
    Chargers("Chargers"),
    Efficiency("Efficiency"),
    Precool("Smart pre-cool"),
    Places("Places"),
    Alerts("Alerts"),
    Electricity("Home electricity"),
    Prices("Public charging prices"),
    About("About Volta"),
    MapsKey("Google Maps key"),
}

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    var page by rememberSaveable { mutableStateOf(Page.Main) }
    BackHandler(enabled = page != Page.Main) { page = Page.Main }
    // The key screen is the same one first run uses, with its own header and back arrow.
    if (page == Page.MapsKey) {
        MapsKeyRoute(MapsKeyMode.Settings, onClose = { page = Page.Main })
        return
    }
    AnimatedContent(
        page,
        transitionSpec = {
            val forward = targetState != Page.Main
            (slideInHorizontally(tween(260)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(220)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(160)))
        },
        label = "settings",
    ) { p ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(p) { if (p == Page.Main) onBack() else page = Page.Main }
            when (p) {
                Page.Main -> MainPage(vm) { page = it }
                Page.Account -> AccountPage(vm)
                Page.Planning -> PlanningPage(vm)
                Page.Chargers -> ChargersPage(vm)
                Page.Efficiency -> EfficiencyPage(vm)
                Page.Precool -> Group { Box(Modifier.padding(14.dp)) { SmartPrecoolSection() } }
                Page.Places -> PlacesPage(vm)
                Page.Alerts -> AlertsPage(vm)
                Page.Electricity -> ElectricityPage(vm)
                Page.Prices -> PricesPage(vm)
                Page.About -> AboutPage(vm)
                Page.MapsKey -> Unit
            }
        }
    }
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun Header(p: Page, onBack: () -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        if (p != Page.Main) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onBack).padding(vertical = 6.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(VoltaIcons.Back, "Back", tint = VoltaColors.Gold, modifier = Modifier.size(22.dp))
                Text("Settings", style = MaterialTheme.typography.bodyMedium, color = VoltaColors.Gold)
            }
        }
        Text(p.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = VoltaColors.Dim, modifier = Modifier.padding(start = 6.dp, bottom = 0.dp))

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(VoltaColors.Surface).border(1.dp, VoltaColors.Border, RoundedCornerShape(18.dp)), content = content)
}

@Composable
private fun Divider() = HorizontalDivider(color = VoltaColors.Border)

@Composable
private fun NavRow(icon: ImageVector, tint: Color, title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(VoltaColors.tint(tint, 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(VoltaIcons.Chevron, null, tint = VoltaColors.Dim, modifier = Modifier.size(16.dp))
    }
}

/** Label on the left, value with − / + on the right; each tap saves. */
@Composable
private fun StepperRow(label: String, value: String, sub: String? = null, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted) }
        }
        StepButton(VoltaIcons.Minus, "Decrease $label", onMinus)
        Text(value, style = MaterialTheme.typography.titleSmall.tabular(), modifier = Modifier.width(78.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton(VoltaIcons.Plus, "Increase $label", onPlus)
    }
}

/** "Lunch  12:30 pm – 2:30 pm": tap either time to change it with the clock (the window stays at least 30 min). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MealWindowRow(label: String, window: IntRange, onChange: (IntRange) -> Unit) {
    var editing by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    fun fmt(min: Int) = "%d:%02d %s".format(((min / 60) + 11) % 12 + 1, min % 60, if (min < 12 * 60) "am" else "pm")
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TimeChip(fmt(window.first)) { editing = true }
        Text("–", color = VoltaColors.Muted, modifier = Modifier.padding(horizontal = 6.dp))
        TimeChip(fmt(window.last)) { editing = false }
    }
    editing?.let { start ->
        val now = if (start) window.first else window.last
        val picker = androidx.compose.material3.rememberTimePickerState(now / 60, now % 60, is24Hour = false)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = VoltaColors.Surface,
            title = { Text("$label ${if (start) "from" else "until"}", style = MaterialTheme.typography.titleMedium) },
            text = {
                androidx.compose.material3.TimePicker(
                    picker,
                    colors = androidx.compose.material3.TimePickerDefaults.colors(
                        clockDialColor = VoltaColors.SurfaceHigh,
                        selectorColor = VoltaColors.Champagne,
                        timeSelectorSelectedContainerColor = VoltaColors.tint(VoltaColors.Champagne, 0.2f),
                        timeSelectorSelectedContentColor = VoltaColors.Champagne,
                        periodSelectorSelectedContainerColor = VoltaColors.tint(VoltaColors.Champagne, 0.2f),
                        periodSelectorSelectedContentColor = VoltaColors.Champagne,
                    ),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    val t = picker.hour * 60 + picker.minute
                    onChange(if (start) t..maxOf(window.last, t + 30) else minOf(window.first, t - 30).coerceAtLeast(0)..t.coerceAtLeast(30))
                    editing = null
                }) { Text("Set", color = VoltaColors.Champagne) }
            },
            dismissButton = { androidx.compose.material3.TextButton({ editing = null }) { Text("Cancel", color = VoltaColors.TextSoft) } },
        )
    }
}

@Composable
private fun TimeChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.tabular(),
        color = VoltaColors.Text,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(VoltaColors.SurfaceHigh).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

/**
 * One-time setup for the home charger (Exicom Spin): scan nearby, tap yours, done. After that the dashboard shows
 * Start / Stop charging whenever Volta is connected to it (car plugged in, phone near the charger).
 */
@Composable
private fun HomeChargerSection(vm: SettingsViewModel) {
    val saved by vm.spin.saved.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<dev.irshadm.volta.data.charger.SpinCharger.Found>?>(null) }
    fun scan() {
        scanning = true
        found = null
        scope.launch {
            found = vm.spin.scan()
            scanning = false
        }
    }
    val permissions = if (android.os.Build.VERSION.SDK_INT >= 31) {
        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) scan()
    }
    Label("Home charger")
    Group {
        val s = saved
        if (s != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.first, style = MaterialTheme.typography.bodyMedium)
                    Text("Exicom Spin · start and stop from the dashboard when you're near it", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
                }
                Text(
                    "Forget",
                    style = MaterialTheme.typography.labelLarge,
                    color = VoltaColors.Amber,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { vm.spin.forget() }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            // Test the link right here: how far it got, in words.
            val status by vm.spin.status.collectAsStateWithLifecycle()
            val link by vm.spin.link.collectAsStateWithLifecycle()
            Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status ?: "Not tried yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (link is dev.irshadm.volta.data.charger.SpinCharger.Link.Ready) VoltaColors.Green else VoltaColors.Muted,
                    modifier = Modifier.weight(1f),
                )
                val testing = link is dev.irshadm.volta.data.charger.SpinCharger.Link.Connecting
                Text(
                    if (testing) "Testing…" else "Test connection",
                    style = MaterialTheme.typography.labelLarge,
                    color = VoltaColors.Champagne,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !testing) {
                        scope.launch {
                            vm.spin.disconnect()
                            vm.spin.connect()
                        }
                    }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Exicom Spin charger", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Stand near your charger, close the Spin app, and find it once. Its Bluetooth name is the charger's serial number (see the charger details in the Spin app).",
                    style = MaterialTheme.typography.bodySmall,
                    color = VoltaColors.Muted,
                )
                Text(
                    if (scanning) "Looking for chargers…" else "Find my home charger",
                    style = MaterialTheme.typography.labelLarge,
                    color = VoltaColors.GoldInk,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (scanning) VoltaColors.SurfaceHigh else VoltaColors.Champagne)
                        .clickable(enabled = !scanning) { if (vm.spin.hasPermission()) scan() else ask.launch(permissions) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
                found?.let { list ->
                    if (list.isEmpty()) Text("Nothing found nearby. Close the Spin app (it holds the charger's Bluetooth) and try again.", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Amber)
                    list.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(VoltaColors.SurfaceHigh).clickable {
                                vm.spin.save(f)
                                found = null
                            }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium, color = if (f.likely) VoltaColors.Champagne else VoltaColors.Text, modifier = Modifier.weight(1f))
                            Text((if (f.likely) "Likely charger · " else "") + if (f.rssi > -70) "close by" else "further away", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(VoltaColors.SurfaceHigh).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = VoltaColors.TextSoft, modifier = Modifier.size(16.dp)) }
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted) }
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = VoltaColors.Champagne, checkedThumbColor = VoltaColors.Bg))
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = VoltaColors.TextSoft, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular(), color = valueColor)
    }
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Dim, modifier = Modifier.padding(horizontal = 6.dp))

private fun Double.r1() = Math.round(this * 10) / 10.0
private fun Double.r2() = Math.round(this * 100) / 100.0

// ------------------------------------------------------------------ main menu

@Composable
private fun MainPage(vm: SettingsViewModel, open: (Page) -> Unit) {
    val link by vm.ismart.link.collectAsStateWithLifecycle()
    val v by vm.vehicle.collectAsStateWithLifecycle()
    val t by vm.trip.collectAsStateWithLifecycle()
    val c by vm.chargers.collectAsStateWithLifecycle()
    val e by vm.energy.collectAsStateWithLifecycle()
    val tariff by vm.tariff.collectAsStateWithLifecycle()
    val pc by vm.precool.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val nano by vm.nano.collectAsStateWithLifecycle()
    val alertsOn by vm.alertsOn.collectAsStateWithLifecycle()
    val car by vm.ismart.car.collectAsStateWithLifecycle()

    val (dot, status) = when (val l = link) {
        is LinkState.Connected -> VoltaColors.Green to "Connected · +91 ${l.phone.take(5)} •••${l.phone.takeLast(2)}" + if (vm.ismart.hasPin) " · PIN saved" else " · no PIN"
        is LinkState.NeedsReconnect -> VoltaColors.Amber to "Needs reconnect"
        LinkState.SignedOut -> VoltaColors.Dim to "Not signed in"
    }
    Group {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open(Page.Account) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(VoltaColors.tint(dot, 0.14f)), contentAlignment = Alignment.Center) {
                Icon(VoltaIcons.Car, null, tint = dot, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("${car.name} · %.1f kWh".format(v.usableCapacityKwh), style = MaterialTheme.typography.titleSmall)
                Text("● $status", style = MaterialTheme.typography.bodySmall, color = dot, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(VoltaIcons.Chevron, null, tint = VoltaColors.Dim, modifier = Modifier.size(16.dp))
        }
    }

    Label("Driving")
    Group {
        val mapsKey by vm.mapsKey.collectAsStateWithLifecycle()
        NavRow(VoltaIcons.Map, VoltaColors.Gold, "Google Maps key", if (mapsKey != null) "Added · used for trip planning" else "Not added · trip planning needs one") { open(Page.MapsKey) }
        Divider()
        NavRow(VoltaIcons.Route, VoltaColors.Gold, "Trip planning", "Charge before ${t.minArrivalAtChargerPercent} % · up to ${t.maxChargeTargetPercent} % · arrive ${t.minArrivalAtDestinationPercent} %") { open(Page.Planning) }
        Divider()
        NavRow(
            VoltaIcons.Bolt,
            VoltaColors.Gold,
            "Chargers",
            "DC CCS2 · " + when (c.networkPreference) {
                NetworkPreference.OFF -> "any network"
                NetworkPreference.PREFER -> if (c.preferredNetworks.isEmpty()) "no preference" else "prefer ${c.preferredNetworks.take(2).joinToString()}"
                NetworkPreference.ONLY -> "only ${c.preferredNetworks.take(2).joinToString()}"
            },
        ) { open(Page.Chargers) }
        Divider()
        NavRow(VoltaIcons.Gauge, VoltaColors.Gold, "Efficiency", "City ${v.cityKmPerKwh} · highway ${v.highwayKmPerKwh} km/kWh") { open(Page.Efficiency) }
    }

    Label("Smart")
    Group {
        NavRow(VoltaIcons.Snow, VoltaColors.BlueSoft, "Smart pre-cool", if (pc.enabled) "On · above ${pc.aboveC}°" + if (nano == Narrator.State.Ready) " · Gemini Nano ready" else "" else "Off") { open(Page.Precool) }
        Divider()
        NavRow(VoltaIcons.Pin, VoltaColors.BlueSoft, "Places", places.joinToString { it.name }.ifEmpty { "None yet — add home and office" }) { open(Page.Places) }
        Divider()
        NavRow(VoltaIcons.Bell, VoltaColors.BlueSoft, "Alerts", "$alertsOn of ${Alerts.kinds.size} on · checks when you leave the car") { open(Page.Alerts) }
    }

    Label("Energy")
    Group {
        NavRow(VoltaIcons.Home, VoltaColors.Champagne, "Home electricity", "${e.householdUnitsPerCycle.roundToInt()} units/bill" + (tariff.verifiedOn?.let { " · checked $it" } ?: " · sample rates")) { open(Page.Electricity) }
        Divider()
        NavRow(VoltaIcons.Rupee, VoltaColors.Champagne, "Public charging prices", "AC ₹%s · DC ₹%s per kWh".format(e.publicAcRatePerKwh.toString().removeSuffix(".0"), e.dcRatePerKwh.toString().removeSuffix(".0"))) { open(Page.Prices) }
    }

    Label("App")
    Group { NavRow(VoltaIcons.Info, VoltaColors.Muted, "About Volta", "Version ${BuildConfig.VERSION_NAME}") { open(Page.About) } }
}

// ------------------------------------------------------------------ pages

@Composable
private fun AccountPage(vm: SettingsViewModel) {
    val v by vm.vehicle.collectAsStateWithLifecycle()
    val locale = androidx.compose.ui.platform.LocalLocale.current.platformLocale
    Group { Box(Modifier.padding(14.dp)) { MgAccountSection() } }
    Label("Car")
    // Once the car has been read enough, the pack size is measured from it (a Windsor Pro is not a Windsor 38)
    // and the stepper goes; before that, whole kWh steps, since the real sizes are 15 kWh apart.
    val measured by vm.capacityLearnedAt.collectAsStateWithLifecycle()
    val measuredAt = measured
    Group {
        if (measuredAt != null) {
            InfoRow("Usable battery", "%.1f kWh".format(v.usableCapacityKwh))
        } else {
            StepperRow(
                "Usable battery",
                "%.0f kWh".format(v.usableCapacityKwh),
                "Until the car has been read a few times",
                { vm.saveVehicle(v.copy(usableCapacityKwh = (v.usableCapacityKwh - 1.0).r1())) },
                { vm.saveVehicle(v.copy(usableCapacityKwh = (v.usableCapacityKwh + 1.0).r1())) },
            )
        }
    }
    Note(
        if (measuredAt != null) {
            "Measured from your car on " + java.text.SimpleDateFormat("d MMM", locale).format(java.util.Date(measuredAt)) + ". It follows the car's own figure."
        } else {
            "Measured from your car once it has been read a few times."
        },
    )
}

@Composable
private fun PlanningPage(vm: SettingsViewModel) {
    val t by vm.trip.collectAsStateWithLifecycle()
    Label("Battery")
    Group {
        StepperRow(
            "Charge before",
            "${t.minArrivalAtChargerPercent} %",
            "Never arrive at a charger below this",
            { vm.saveTrip(t.copy(minArrivalAtChargerPercent = t.minArrivalAtChargerPercent - 5)) },
            { vm.saveTrip(t.copy(minArrivalAtChargerPercent = t.minArrivalAtChargerPercent + 5)) },
        )
        Divider()
        StepperRow(
            "Charge up to",
            "${t.maxChargeTargetPercent} %",
            "Highest % to charge to at a stop",
            { vm.saveTrip(t.copy(maxChargeTargetPercent = t.maxChargeTargetPercent - 5)) },
            { vm.saveTrip(t.copy(maxChargeTargetPercent = t.maxChargeTargetPercent + 5)) },
        )
        Divider()
        StepperRow(
            "Arrive with at least",
            "${t.minArrivalAtDestinationPercent} %",
            null,
            { vm.saveTrip(t.copy(minArrivalAtDestinationPercent = t.minArrivalAtDestinationPercent - 5)) },
            { vm.saveTrip(t.copy(minArrivalAtDestinationPercent = t.minArrivalAtDestinationPercent + 5)) },
        )
    }
    Label("Route")
    Group {
        StepperRow(
            "Max detour to a charger",
            "${t.maxDetourKm} km",
            null,
            { vm.saveTrip(t.copy(maxDetourKm = t.maxDetourKm - 1)) },
            { vm.saveTrip(t.copy(maxDetourKm = t.maxDetourKm + 1)) },
        )
    }
    Label("Live trip")
    Group {
        StepperRow(
            "Check the car every",
            "${t.tripPollIntervalMinutes} min",
            null,
            { vm.saveTrip(t.copy(tripPollIntervalMinutes = t.tripPollIntervalMinutes - 1)) },
            { vm.saveTrip(t.copy(tripPollIntervalMinutes = t.tripPollIntervalMinutes + 1)) },
        )
        Divider()
        StepperRow(
            "Re-plan when behind by",
            "${t.replanDeviationPercent} %",
            null,
            { vm.saveTrip(t.copy(replanDeviationPercent = t.replanDeviationPercent - 1)) },
            { vm.saveTrip(t.copy(replanDeviationPercent = t.replanDeviationPercent + 1)) },
        )
    }
    val m by vm.meals.collectAsStateWithLifecycle()
    Label("Meals & night")
    Group {
        MealWindowRow("Breakfast", m.breakfast) { vm.saveMeals(m.copy(breakfast = it)) }
        Divider()
        MealWindowRow("Lunch", m.lunch) { vm.saveMeals(m.copy(lunch = it)) }
        Divider()
        MealWindowRow("Dinner", m.dinner) { vm.saveMeals(m.copy(dinner = it)) }
        Divider()
        StepperRow(
            "Meal stop length",
            "${m.mealMinutes} min",
            null,
            { vm.saveMeals(m.copy(mealMinutes = (m.mealMinutes - 5).coerceAtLeast(15))) },
            { vm.saveMeals(m.copy(mealMinutes = (m.mealMinutes + 5).coerceAtMost(75))) },
        )
        Divider()
        StepperRow(
            "Night mode from",
            "%d:%02d pm".format(m.nightFrom / 60 - 12, m.nightFrom % 60),
            "No meal stops; on-road chargers only",
            { vm.saveMeals(m.copy(nightFrom = (m.nightFrom - 30).coerceAtLeast(19 * 60))) },
            { vm.saveMeals(m.copy(nightFrom = (m.nightFrom + 30).coerceAtMost(23 * 60 + 30))) },
        )
    }
    Note("Changes save instantly.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChargersPage(vm: SettingsViewModel) {
    val c by vm.chargers.collectAsStateWithLifecycle()
    Group { InfoRow("Connector", "DC fast · CCS2") }
    HomeChargerSection(vm)
    Label("Networks")
    Group {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NetworkPreference.entries.forEach { p ->
                val on = c.networkPreference == p
                Text(
                    when (p) {
                        NetworkPreference.OFF -> "Any"
                        NetworkPreference.PREFER -> "Prefer"
                        NetworkPreference.ONLY -> "Only"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) VoltaColors.Bg else VoltaColors.TextSoft,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) VoltaColors.Champagne else VoltaColors.SurfaceHigh)
                        .clickable { vm.saveChargers(c.copy(networkPreference = p)) }.padding(vertical = 10.dp),
                )
            }
        }
        if (c.networkPreference != NetworkPreference.OFF) {
            FlowRow(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChargerPreferences.KNOWN_NETWORKS.filter { it !in c.avoidedNetworks }.forEach { n ->
                    val on = n in c.preferredNetworks
                    Text(
                        n,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) VoltaColors.Champagne else VoltaColors.TextSoft,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) VoltaColors.tint(VoltaColors.Champagne, 0.14f) else VoltaColors.SurfaceHigh)
                            .border(1.dp, if (on) VoltaColors.tint(VoltaColors.Champagne, 0.5f) else VoltaColors.Border, RoundedCornerShape(50))
                            .clickable { vm.saveChargers(c.copy(preferredNetworks = if (on) c.preferredNetworks - n else c.preferredNetworks + n)) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
    Note(
        when (c.networkPreference) {
            NetworkPreference.OFF -> "All DC chargers are considered equally."
            NetworkPreference.PREFER -> "Plans favour these networks when the time cost is small."
            NetworkPreference.ONLY -> "Only these networks are used." + if (c.preferredNetworks.isEmpty()) " Pick at least one." else ""
        },
    )
    Label("Avoid")
    Group {
        FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChargerPreferences.KNOWN_NETWORKS.forEach { n ->
                val on = n in c.avoidedNetworks
                Text(
                    n,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) VoltaColors.Orange else VoltaColors.TextSoft,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) VoltaColors.tint(VoltaColors.Orange, 0.14f) else VoltaColors.SurfaceHigh)
                        .border(1.dp, if (on) VoltaColors.tint(VoltaColors.Orange, 0.5f) else VoltaColors.Border, RoundedCornerShape(50))
                        .clickable { vm.saveChargers(c.copy(avoidedNetworks = if (on) c.avoidedNetworks - n else c.avoidedNetworks + n, preferredNetworks = c.preferredNetworks - n)) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
    Note("Chargers from these networks are never planned as stops.")
}

@Composable
private fun EfficiencyPage(vm: SettingsViewModel, energyVm: EnergySettingsViewModel = hiltViewModel()) {
    val v by vm.vehicle.collectAsStateWithLifecycle()
    var learned by remember { mutableStateOf<Learned?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { learned = energyVm.learned() }
    learned?.let { l ->
        Group {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Learned %.1f km/kWh".format(l.kmPerKwh), style = MaterialTheme.typography.titleMedium, color = VoltaColors.Champagne)
                Text(l.source.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
                VoltaButton("Use for planning", { energyVm.applyLearned(l) }, style = VoltaButtonStyle.Secondary)
            }
        }
    }
    Label("Used by the planner")
    Group {
        StepperRow("City", "${v.cityKmPerKwh} km/kWh", null, { vm.saveVehicle(v.copy(cityKmPerKwh = (v.cityKmPerKwh - 0.1).r1())) }, { vm.saveVehicle(v.copy(cityKmPerKwh = (v.cityKmPerKwh + 0.1).r1())) })
        Divider()
        StepperRow("Highway", "${v.highwayKmPerKwh} km/kWh", null, { vm.saveVehicle(v.copy(highwayKmPerKwh = (v.highwayKmPerKwh - 0.1).r1())) }, { vm.saveVehicle(v.copy(highwayKmPerKwh = (v.highwayKmPerKwh + 0.1).r1())) })
    }
    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { advanced = !advanced }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Advanced (hills model)", style = MaterialTheme.typography.labelLarge, color = VoltaColors.Gold)
        Icon(VoltaIcons.Chevron, null, tint = VoltaColors.Gold, modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = if (advanced) 270f else 90f })
    }
    AnimatedVisibility(advanced) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Group {
                StepperRow("Mass incl. people", "${v.massKg.roundToInt()} kg", null, { vm.saveVehicle(v.copy(massKg = v.massKg - 10)) }, { vm.saveVehicle(v.copy(massKg = v.massKg + 10)) })
                Divider()
                StepperRow("Drivetrain (climbs)", "%.2f".format(v.driveEfficiency), null, { vm.saveVehicle(v.copy(driveEfficiency = (v.driveEfficiency - 0.01).r2())) }, { vm.saveVehicle(v.copy(driveEfficiency = (v.driveEfficiency + 0.01).r2())) })
                Divider()
                StepperRow("Regen (descents)", "%.2f".format(v.regenEfficiency), null, { vm.saveVehicle(v.copy(regenEfficiency = (v.regenEfficiency - 0.01).r2())) }, { vm.saveVehicle(v.copy(regenEfficiency = (v.regenEfficiency + 0.01).r2())) })
            }
            VoltaButton("Reset to defaults", {
                val d = VehicleProfile()
                vm.saveVehicle(v.copy(cityKmPerKwh = d.cityKmPerKwh, highwayKmPerKwh = d.highwayKmPerKwh, massKg = d.massKg, driveEfficiency = d.driveEfficiency, regenEfficiency = d.regenEfficiency))
            }, style = VoltaButtonStyle.Secondary)
        }
    }
}

@Composable
private fun PlacesPage(vm: SettingsViewModel, pvm: SmartPrecoolViewModel = hiltViewModel()) {
    val places by vm.places.collectAsStateWithLifecycle()
    val e by vm.energy.collectAsStateWithLifecycle()
    val message by pvm.message.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    if (adding) {
        var name by remember { mutableStateOf("") }
        val wifi = remember { dev.irshadm.volta.service.smart.SmartPrecool.currentWifi(context) }
        AlertDialog(
            onDismissRequest = { adding = false },
            containerColor = VoltaColors.Surface,
            title = { Text("Add this place", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        wifi?.let { "Uses where you are now and the Wi-Fi “$it”." } ?: "Not on Wi-Fi — connect to this place's Wi-Fi first for smart pre-cool.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (wifi == null) VoltaColors.AmberText else VoltaColors.Muted,
                    )
                    OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Office") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton({
                    pvm.addHere(name)
                    adding = false
                }, enabled = name.isNotBlank()) { Text("Save", color = VoltaColors.Gold) }
            },
            dismissButton = { TextButton({ adding = false }) { Text("Cancel", color = VoltaColors.Muted) } },
        )
    }
    if (places.isNotEmpty()) {
        Group {
            places.forEachIndexed { i, p ->
                if (i > 0) Divider()
                val isHome = e.homeLat != null && Geo.distanceM(LatLng(e.homeLat!!, e.homeLng!!), LatLng(p.lat, p.lng)) <= e.homeRadiusM
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            if (isHome) {
                                Text(
                                    "HOME",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VoltaColors.Champagne,
                                    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(VoltaColors.tint(VoltaColors.Champagne, 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(p.ssid?.let { "Wi-Fi “$it”" } ?: "No Wi-Fi saved", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
                    }
                    if (!isHome) Text("Set as home", style = MaterialTheme.typography.labelMedium, color = VoltaColors.Gold, modifier = Modifier.clickable { vm.setHome(p) }.padding(6.dp))
                    Text("Remove", style = MaterialTheme.typography.labelMedium, color = VoltaColors.Dim, modifier = Modifier.clickable { pvm.remove(p) }.padding(6.dp))
                }
            }
        }
    }
    VoltaButton("Add this place", { adding = true }, Modifier.fillMaxWidth(), style = VoltaButtonStyle.Secondary, icon = VoltaIcons.Pin)
    message?.let { Note(it) }
    if (e.homeLat != null) {
        val covered by vm.homeCovered.collectAsStateWithLifecycle()
        Group { SwitchRow("Home parking is covered", "No sun heating in the cabin estimate at home", covered) { vm.setHomeCovered(it) } }
    }
    Note("Places name your drives and the car's parking spot. Home also decides which charging counts as home electricity; leaving a place's Wi-Fi is a smart pre-cool signal. Long-press the location chip on the dashboard to name where the car is.")
}

@Composable
private fun AlertsPage(vm: SettingsViewModel) {
    val version by vm.alertVersion.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var wait by remember { mutableStateOf(Alerts.parkedDelayMin(context)) }
    Label("Wait before parked alerts")
    Group {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Alerts.delayChoices.forEach { m ->
                val on = wait == m
                Text(
                    "$m min",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) VoltaColors.Bg else VoltaColors.TextSoft,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) VoltaColors.Champagne else VoltaColors.SurfaceHigh)
                        .clickable {
                            wait = m
                            Alerts.setParkedDelay(context, m)
                        }.padding(vertical = 10.dp),
                )
            }
        }
    }
    Note("Left unlocked, window open and left switched on alert only once the car has been parked that way this long.")
    Label("Alerts")
    Group {
        Alerts.kinds.forEachIndexed { i, k ->
            if (i > 0) Divider()
            key(version) { SwitchRow(k.label, k.detail, vm.alertOn(k.key)) { vm.setAlert(k.key, it) } }
        }
    }
    Note(
        "Checked when you leave the car, when a charge should be done, 45 min before a planned trip, every 10 min while the car is on without you, " +
            "and otherwise every ${CarCheckWorker.intervalMinutes(context)} min as a safety net. Each alert fires once until the condition clears.",
    )
    BatteryNudge()
}

/** Samsung and Android defer background checks unless the app's battery use is Unrestricted; one dialog fixes it. */
@Composable
private fun BatteryNudge() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = context.getSystemService(android.os.PowerManager::class.java)
    var ignoring by remember { mutableStateOf(pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2_000)
            ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        }
    }
    if (ignoring) return
    Label("Background checks")
    Group {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Checks may arrive late", style = MaterialTheme.typography.titleSmall, color = VoltaColors.Text)
            Text(
                "Battery use for Volta is optimised, so Android can hold the left-car and charge-done checks for a long time. Set it to Unrestricted.",
                style = MaterialTheme.typography.bodySmall,
                color = VoltaColors.TextSoft,
            )
            VoltaButton("Allow unrestricted battery", {
                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
                    .onFailure { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
            }, Modifier.fillMaxWidth(), style = VoltaButtonStyle.Secondary)
        }
    }
}

@Composable
private fun key(@Suppress("UNUSED_PARAMETER") v: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(v) { content() }

@Composable
private fun ElectricityPage(vm: SettingsViewModel) {
    val e by vm.energy.collectAsStateWithLifecycle()
    val t by vm.tariff.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        TariffDialog(t, onDismiss = { editing = false }) {
            vm.saveTariff(it)
            editing = false
        }
    }
    Group {
        InfoRow("Home location", if (e.hasHome) "Set · Places" else "Not set — Places", if (e.hasHome) VoltaColors.Green else VoltaColors.Amber)
        Divider()
        StepperRow(
            "Household units per bill",
            "${e.householdUnitsPerCycle.roundToInt()}",
            "Without the car",
            { vm.saveEnergy(e.copy(householdUnitsPerCycle = (e.householdUnitsPerCycle - 10).coerceAtLeast(0.0))) },
            { vm.saveEnergy(e.copy(householdUnitsPerCycle = e.householdUnitsPerCycle + 10)) },
        )
        Divider()
        StepperRow(
            "Bill cycle starts on",
            "${e.billCycleStartDay}",
            "Day of the month",
            { vm.saveEnergy(e.copy(billCycleStartDay = (e.billCycleStartDay - 1).coerceIn(1, 28))) },
            { vm.saveEnergy(e.copy(billCycleStartDay = (e.billCycleStartDay + 1).coerceIn(1, 28))) },
        )
        Divider()
        // Which months the cycle starts in; with a 2-month bill only the parity matters, but the month reads better.
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val cycleMonths = (0 until 12 / t.billingMonths.coerceAtLeast(1)).map { months[(e.billCycleStartMonth - 1 + it * t.billingMonths) % 12] }.joinToString(", ")
        StepperRow(
            "Latest cycle began in",
            months[(e.billCycleStartMonth - 1).coerceIn(0, 11)],
            "Every ${t.billingMonths} months: $cycleMonths",
            { vm.saveEnergy(e.copy(billCycleStartMonth = (e.billCycleStartMonth - 2).mod(12) + 1)) },
            { vm.saveEnergy(e.copy(billCycleStartMonth = e.billCycleStartMonth % 12 + 1)) },
        )
    }
    Label("Electricity tariff (per month)")
    Group {
        var from = 0
        t.telescopic.forEach { (size, rate) ->
            InfoRow("${from + 1}–${from + size} units", "₹$rate")
            from += size
            Divider()
        }
        InfoRow("Above ${t.telescopicLimit}: all units at", t.nonTelescopic.joinToString("  ") { "₹${it.second}" }, VoltaColors.Muted)
        Divider()
        InfoRow("Edit slabs", "›", VoltaColors.Gold) { editing = true }
    }
    Group {
        StepperRow(
            "Electricity duty",
            "${t.dutyPercent.r1()} %",
            null,
            { vm.saveTariff(t.copy(dutyPercent = (t.dutyPercent - 0.5).coerceAtLeast(0.0))) },
            { vm.saveTariff(t.copy(dutyPercent = t.dutyPercent + 0.5)) },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
        Text(t.verifiedOn?.let { "Rates checked $it · " } ?: "Sample rates · ", style = MaterialTheme.typography.bodySmall, color = if (t.verifiedOn == null) VoltaColors.Amber else VoltaColors.Dim)
        Text(
            "Mark checked today",
            style = MaterialTheme.typography.bodySmall,
            color = VoltaColors.Gold,
            modifier = Modifier.clickable { vm.saveTariff(t.copy(verifiedOn = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date()))) },
        )
    }
}

@Composable
private fun TariffDialog(t: dev.irshadm.volta.core.model.Tariff, onDismiss: () -> Unit, onSave: (dev.irshadm.volta.core.model.Tariff) -> Unit) {
    var tele by remember { mutableStateOf(t.telescopic.joinToString("  ") { "${it.first}@${it.second}" }) }
    var flat by remember { mutableStateOf(t.nonTelescopic.joinToString("  ") { "${if (it.first == Int.MAX_VALUE) "∞" else it.first}@${it.second}" }) }
    fun pairs(s: String) = s.split(Regex("\\s+")).mapNotNull { p ->
        val (a, b) = p.split("@").takeIf { it.size == 2 } ?: return@mapNotNull null
        val n = if (a == "∞") Int.MAX_VALUE else a.toIntOrNull() ?: return@mapNotNull null
        n to (b.toDoubleOrNull() ?: return@mapNotNull null)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoltaColors.Surface,
        title = { Text("Tariff slabs", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Copy from your bill as units@₹, separated by spaces.", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
                OutlinedTextField(tele, { tele = it }, label = { Text("Telescopic (up to 250/month)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(flat, { flat = it }, label = { Text("Above 250 — up to units@₹") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton({ onSave(t.copy(telescopic = pairs(tele).ifEmpty { t.telescopic }, nonTelescopic = pairs(flat).ifEmpty { t.nonTelescopic })) }) { Text("Save", color = VoltaColors.Gold) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = VoltaColors.Muted) } },
    )
}

@Composable
private fun PricesPage(vm: SettingsViewModel) {
    val e by vm.energy.collectAsStateWithLifecycle()
    Group {
        StepperRow("Public AC", "₹${e.publicAcRatePerKwh.r1()}", "per kWh", { vm.saveEnergy(e.copy(publicAcRatePerKwh = (e.publicAcRatePerKwh - 0.5).coerceAtLeast(0.0))) }, { vm.saveEnergy(e.copy(publicAcRatePerKwh = e.publicAcRatePerKwh + 0.5)) })
        Divider()
        StepperRow("DC fast", "₹${e.dcRatePerKwh.r1()}", "per kWh", { vm.saveEnergy(e.copy(dcRatePerKwh = (e.dcRatePerKwh - 0.5).coerceAtLeast(0.0))) }, { vm.saveEnergy(e.copy(dcRatePerKwh = e.dcRatePerKwh + 0.5)) })
    }
    Note("Used to cost public charging sessions on the Energy screen. Fix a single session by tapping it there.")
}

@Composable
private fun AboutPage(vm: SettingsViewModel) {
    val nano by vm.nano.collectAsStateWithLifecycle()
    Group {
        InfoRow("Version", BuildConfig.VERSION_NAME)
        Divider()
        InfoRow(
            "On-device AI",
            when (nano) {
                Narrator.State.Ready -> "Gemini Nano ready"
                Narrator.State.Downloading -> "Downloading"
                Narrator.State.Downloadable -> "Not downloaded"
                else -> "Unavailable"
            },
            if (nano == Narrator.State.Ready) VoltaColors.Green else VoltaColors.Muted,
        )
    }
    Note("Volta reads your car from MG i-SMART with your own login, and plans trips with your own Google Maps key. There is no Volta server: everything is stored on this phone and deleted when you uninstall.")
}
