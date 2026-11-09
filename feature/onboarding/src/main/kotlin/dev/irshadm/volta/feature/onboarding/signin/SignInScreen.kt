package dev.irshadm.volta.feature.onboarding.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irshadm.volta.core.designsystem.NoteTone
import dev.irshadm.volta.core.designsystem.VoltaButton
import dev.irshadm.volta.core.designsystem.VoltaCarArt
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.VoltaNote
import dev.irshadm.volta.core.designsystem.VoltaTheme
import dev.irshadm.volta.core.domain.SignInError
import java.text.DateFormat
import java.util.Date

/** Step 1 of first run: sign in with the MG i-SMART owner account. */
@Composable
fun SignInRoute(vm: SignInViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    SignInScreen(
        state = state,
        onPhoneChange = vm::onPhoneChange,
        onPasswordChange = vm::onPasswordChange,
        onTogglePassword = vm::onTogglePassword,
        onSubmit = vm::submit,
    )
}

/** Stateless: everything comes in through [state] and goes out through the callbacks, so previews and tests need no ViewModel. */
@Composable
fun SignInScreen(
    state: SignInUiState,
    onPhoneChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onSubmit: () -> Unit,
) {
    val rejected = state.error is SignInError.Rejected
    Column(
        Modifier.fillMaxSize().background(VoltaColors.Bg).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Same order as the splash: the car with its bolt, then the name under it.
        if (state.error == null) CarHero() else Spacer(Modifier.height(28.dp))
        Text("VOLTA", style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 0.32.em), color = VoltaColors.Gold)
        Text("Your MG EV, live on your phone", style = MaterialTheme.typography.bodyMedium, color = VoltaColors.Muted, modifier = Modifier.padding(top = 4.dp))

        state.error?.let { error ->
            VoltaNote(NoteTone.Error, body = errorBody(error), title = errorTitle(error), modifier = Modifier.padding(top = 28.dp))
        }

        Column(Modifier.padding(top = 26.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                value = state.phone,
                onValueChange = onPhoneChange,
                label = { Text("Mobile number") },
                prefix = { Text("+91 ") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password, onValueChange = onPasswordChange, label = { Text("MG i-SMART password") }, singleLine = true,
                isError = rejected,
                visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Text(
                        if (state.passwordVisible) "Hide" else "Show",
                        style = MaterialTheme.typography.labelMedium,
                        color = VoltaColors.Muted,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onTogglePassword).padding(10.dp),
                    )
                },
            )
            if (rejected) {
                Text(
                    "Forgot it? Reset it in the MG i-SMART app, then sign in here. Volta never retries a rejected password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VoltaColors.Muted,
                )
            } else {
                VoltaNote(NoteTone.Caution, "Signing in here signs the official MG app out. Only one can be signed in at a time, and wrong passwords count against MG's daily limit.")
            }
        }

        Spacer(Modifier.height(28.dp))
        VoltaButton(
            text = when {
                state.busy -> "Signing in…"
                rejected -> "Try again"
                else -> "Continue"
            },
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canSubmit || state.busy,
            busy = state.busy,
        )
        Text(
            "Your password goes to MG only and is kept encrypted on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = VoltaColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** Just the car from the splash, on the screen's own background, with its lights switching on once. */
@Composable
private fun CarHero() {
    Box(Modifier.padding(top = 10.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        VoltaCarArt(Modifier.fillMaxWidth(0.96f), animate = true, bolt = false, lines = false, background = VoltaColors.Bg, blendEdges = true)
    }
}

private fun errorTitle(error: SignInError) = when (error) {
    is SignInError.Rejected -> "MG rejected the sign-in"
    is SignInError.ServiceUnavailable -> "MG's sign-in service isn't answering"
    is SignInError.Throttled -> "Too many sign-ins this hour"
    SignInError.NotOwner -> "This is a shared account"
    SignInError.Offline -> "Can't reach MG"
    is SignInError.Other -> "Sign-in didn't work"
}

private fun errorBody(error: SignInError) = when (error) {
    is SignInError.Rejected -> error.message
    is SignInError.ServiceUnavailable -> "MG said: ${error.message}\n\nYour password wasn't rejected. If this is an MG outage, try again later. " +
        "If your car is a Hector Tomahawk EV, it's on MG's newer i-SMART 3.0, which Volta can't sign in to yet."
    is SignInError.Throttled -> "To protect your account, Volta waits until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(error.retryAtMillis))} before trying again."
    SignInError.NotOwner -> "Battery and charging data only reach the owner's account. Sign in with the number the car is registered to."
    SignInError.Offline -> "Check your connection and try again."
    is SignInError.Other -> error.message
}

@Preview
@Composable
private fun SignInPreview() = VoltaTheme {
    SignInScreen(SignInUiState(phone = "9800000012", password = "secret12"), {}, {}, {}, {})
}

@Preview
@Composable
private fun SignInRejectedPreview() = VoltaTheme {
    SignInScreen(SignInUiState(phone = "9800000012", error = SignInError.Rejected("Incorrect mobile number or password.")), {}, {}, {}, {})
}
