package st.kiwifarms.sneedroid.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import st.kiwifarms.sneedroid.core.net.LoginPhase
import st.kiwifarms.sneedroid.ui.theme.SneedTheme

@Composable
fun LoginScreen(
    state: LoginUiState,
    mode: LoginMode,
    initialUsername: String?,
    onLogin: (username: String, password: String, remember: Boolean) -> Unit,
    onSubmitCode: (code: String, trust: Boolean) -> Unit,
    onBack: () -> Unit,
    onPreview: (() -> Unit)? = null,
    ipKillswitch: Boolean = false,
    vpnActive: Boolean = false,
    onSetIpKillswitch: (Boolean) -> Unit = {},
) {
    val c = SneedTheme.colors
    Box(
        Modifier.fillMaxSize().background(c.bg).imePadding().padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Brand()
            Spacer(Modifier.height(28.dp))
            when (mode) {
                LoginMode.Credentials ->
                    CredentialsForm(state, initialUsername, onLogin, onPreview, ipKillswitch, vpnActive, onSetIpKillswitch)
                LoginMode.TwoFactor ->
                    TwoFactorForm(state, onSubmitCode, onBack)
            }
            ErrorBox(state)
        }
    }
}

@Composable
private fun Brand() {
    val c = SneedTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("K", color = c.accentOn, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        }
        Spacer(Modifier.size(12.dp))
        Text("Sneedroid", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
    }
    Spacer(Modifier.height(6.dp))
    Text("Sign in to SneedChat", color = SneedTheme.colors.text2, fontSize = 13.sp)
}

@Composable
private fun CredentialsForm(
    state: LoginUiState,
    initialUsername: String?,
    onLogin: (String, String, Boolean) -> Unit,
    onPreview: (() -> Unit)?,
    ipKillswitch: Boolean,
    vpnActive: Boolean,
    onSetIpKillswitch: (Boolean) -> Unit,
) {
    val c = SneedTheme.colors
    var username by remember { mutableStateOf(initialUsername.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(initialUsername != null) }
    val working = state is LoginUiState.Working
    val submit = { onLogin(username, password, remember) }

    OutlinedTextField(
        value = username, onValueChange = { username = it },
        label = { Text("Username") }, singleLine = true, enabled = !working,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = password, onValueChange = { password = it },
        label = { Text("Password") }, singleLine = true, enabled = !working,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (!working) submit() }),
    )
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Stay signed in", color = c.text2, fontSize = 14.sp)
        Switch(checked = remember, onCheckedChange = { remember = it }, enabled = !working)
    }

    // IP killswitch — placed before sign-in so it can be enabled before any request reaches the
    // server. When on with no VPN, even the login/session network calls are refused.
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("IP killswitch (VPN required)", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (vpnActive) "VPN tunnel detected" else "No VPN tunnel detected",
                    color = if (vpnActive) c.accent else c.text3, fontSize = 11.5.sp,
                )
            }
            Switch(checked = ipKillswitch, onCheckedChange = onSetIpKillswitch, enabled = !working)
        }
        Text(
            "Won't contact KiwiFarms unless a VPN tunnel is active, so a dropped VPN can't leak your " +
                "home IP. This checks if a VPN is running on your device.",
            color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }

    Spacer(Modifier.height(20.dp))
    PrimaryButton(label = "Log in", working = working, state = state, onClick = submit)

    if (onPreview != null) {
        Spacer(Modifier.height(20.dp))
        Text(
            "Preview chat (demo data)", color = c.text3, fontSize = 13.sp,
            modifier = Modifier.clickable(enabled = !working) { onPreview() }.padding(8.dp),
        )
    }
}

@Composable
private fun TwoFactorForm(
    state: LoginUiState,
    onSubmitCode: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val c = SneedTheme.colors
    var code by remember { mutableStateOf("") }
    var trust by remember { mutableStateOf(false) }
    val working = state is LoginUiState.Working
    val submit = { onSubmitCode(code, trust) }

    Text("Two-factor authentication", color = c.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    Spacer(Modifier.height(4.dp))
    Text("Enter the code from your authenticator app", color = c.text2, fontSize = 13.sp)
    Spacer(Modifier.height(18.dp))
    OutlinedTextField(
        value = code, onValueChange = { code = it.filter(Char::isDigit).take(8) },
        label = { Text("Authenticator code") }, singleLine = true, enabled = !working,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (!working) submit() }),
    )
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Trust this device", color = c.text2, fontSize = 14.sp)
        Switch(checked = trust, onCheckedChange = { trust = it }, enabled = !working)
    }
    Spacer(Modifier.height(20.dp))
    PrimaryButton(label = "Verify", working = working, state = state, onClick = submit)
    Spacer(Modifier.height(10.dp))
    Text(
        "Back", color = c.text3, fontSize = 13.sp,
        modifier = Modifier.clickable(enabled = !working) { onBack() }.padding(8.dp),
    )
}

@Composable
private fun PrimaryButton(label: String, working: Boolean, state: LoginUiState, onClick: () -> Unit) {
    val c = SneedTheme.colors
    Button(onClick = onClick, enabled = !working, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        if (working) {
            CircularProgressIndicator(Modifier.size(18.dp), color = c.accentOn, strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text(phaseLabel((state as LoginUiState.Working).phase))
        } else {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ErrorBox(state: LoginUiState) {
    if (state !is LoginUiState.Error) return
    val c = SneedTheme.colors
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(12.dp)) {
        Text(state.message, color = c.danger, fontSize = 13.sp)
    }
}

private fun phaseLabel(phase: LoginPhase): String = when (phase) {
    LoginPhase.FetchingPage -> "Connecting…"
    LoginPhase.SolvingChallenge -> "Solving challenge…"
    LoginPhase.VerifyingBrowser -> "Verifying browser…"
    LoginPhase.SubmittingChallenge -> "Verifying…"
    LoginPhase.SigningIn -> "Signing in…"
}
