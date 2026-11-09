package dev.irshadm.volta.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.ismart.Vehicle
import dev.irshadm.volta.data.ismart.CarIdentity
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.feature.onboarding.pin.ControlPinForm
import dev.irshadm.volta.feature.onboarding.pin.ControlPinViewModel
import dev.irshadm.volta.ui.components.VoltaButton
import dev.irshadm.volta.ui.components.VoltaButtonStyle
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(val ismart: IsmartRepository) : ViewModel()

/** MG i-SMART account: who is signed in, the control PIN for remote commands, and sign-out. */
@Composable
fun MgAccountSection(vm: AccountViewModel = hiltViewModel()) {
    val link by vm.ismart.link.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pinSet by vm.ismart.pinSaved.collectAsStateWithLifecycle()
    val pinVm: ControlPinViewModel = hiltViewModel()
    val pinState by pinVm.state.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedContent(link is LinkState.SignedOut, label = "account") { signedOut ->
            if (signedOut) {
                // Signed out, the sign-in screen covers the app, so this side never shows.
                Text("Not signed in", style = MaterialTheme.typography.titleSmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val number = when (val l = link) {
                        is LinkState.Connected -> l.phone
                        is LinkState.NeedsReconnect -> l.phone
                        else -> ""
                    }
                    Text("Signed in as +91 $number", style = MaterialTheme.typography.titleSmall)
                    CarPicker(vm.ismart)
                    if (link is LinkState.NeedsReconnect) {
                        Text("Signed out by another app.", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Amber)
                        VoltaButton("Reconnect", { scope.launch { vm.ismart.reconnect() } }, Modifier.fillMaxWidth())
                    }
                    Text("Control PIN", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Your 4-digit MG control PIN, needed for lock/unlock and AC. Only a hash is stored, encrypted on this phone. Each command also asks for your fingerprint or screen lock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VoltaColors.Muted,
                    )
                    if (pinSet) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("PIN saved", style = MaterialTheme.typography.bodyMedium, color = VoltaColors.Green, modifier = Modifier.weight(1f))
                            VoltaButton("Change", { vm.ismart.clearPin() }, style = VoltaButtonStyle.Secondary)
                        }
                    } else {
                        ControlPinForm(pinState, pinVm::onPinChange, { pinVm.check() }, "Check with MG", autoFocus = false)
                    }
                    VoltaButton("Sign out", {
                        vm.ismart.signOut()
                    }, Modifier.fillMaxWidth(), style = VoltaButtonStyle.Secondary)
                }
            }
        }
    }
}

/** Shown only when the account has more than one car: which one the dashboard, alerts and commands use. */
@Composable
private fun CarPicker(ismart: IsmartRepository) {
    val vehicles by ismart.vehicles.collectAsStateWithLifecycle()
    val car by ismart.car.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var switching by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (vehicles.isEmpty()) ismart.loadVehicles() }
    if (vehicles.size < 2) return
    // Re-read when the car changes, so the "In use" row follows the switch.
    val selected = remember(car, vehicles) { ismart.selectedVin }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Car", style = MaterialTheme.typography.titleSmall)
        Text(
            "This account has ${vehicles.size} cars. Volta reads and controls one at a time.",
            style = MaterialTheme.typography.bodySmall,
            color = VoltaColors.Muted,
        )
        vehicles.forEach { v ->
            val pick = {
                switching = true
                scope.launch {
                    try {
                        ismart.selectCar(v.vin)
                    } finally {
                        switching = false
                    }
                }
                Unit
            }
            CarRow(v, v.vin == selected, enabled = !switching, onPick = pick)
        }
    }
}

@Composable
private fun CarRow(v: Vehicle, selected: Boolean, enabled: Boolean, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(CarIdentity.of(v).name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "VIN •••${v.vin.takeLast(6)}" + if (v.isSubAccount) " · shared with you" else "",
                style = MaterialTheme.typography.bodySmall,
                color = VoltaColors.Muted,
            )
        }
        when {
            selected -> Text("In use", style = MaterialTheme.typography.bodyMedium, color = VoltaColors.Green)
            v.isSubAccount -> Text("Owner only", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
            !enabled -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else -> VoltaButton("Use this", onPick, style = VoltaButtonStyle.Secondary)
        }
    }
}
