package st.kiwifarms.sneedroid.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import st.kiwifarms.sneedroid.core.net.LoginOutcome
import st.kiwifarms.sneedroid.core.net.LoginPhase
import st.kiwifarms.sneedroid.data.AuthRepository
import st.kiwifarms.sneedroid.data.ErrorReporter
import st.kiwifarms.sneedroid.data.SessionState

/** Where the app is, at the top level: deciding, logged out, or logged in. */
sealed interface AppRoute {
    data object Deciding : AppRoute
    data object Login : AppRoute
    data object Chat : AppRoute
}

/** Which login form is shown. */
enum class LoginMode { Credentials, TwoFactor }

/** The login form/progress state. */
sealed interface LoginUiState {
    data object Idle : LoginUiState
    data class Working(val phase: LoginPhase) : LoginUiState
    data class Error(val message: String) : LoginUiState
}

class LoginViewModel(
    private val auth: AuthRepository,
    private val errors: ErrorReporter,
    private val killswitchBlocked: () -> Boolean = { false },
) : ViewModel() {

    private val killswitchMsg = "IP killswitch is on — connect a VPN before signing in."

    private val _route = MutableStateFlow<AppRoute>(AppRoute.Deciding)
    val route: StateFlow<AppRoute> = _route.asStateFlow()

    private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _mode = MutableStateFlow(LoginMode.Credentials)
    val mode: StateFlow<LoginMode> = _mode.asStateFlow()

    /** Prefilled username if the user previously chose to be remembered. */
    val savedUsername: String? get() = auth.savedCredentials()?.first

    init {
        viewModelScope.launch {
            _route.value = when (auth.resume()) {
                SessionState.LoggedIn -> AppRoute.Chat
                SessionState.NeedsLogin -> AppRoute.Login
            }
        }
        // If a live session dies mid-use and can't be refreshed silently (needs a password
        // or a fresh 2FA code), bounce to the login screen so the user can re-authenticate
        // through the normal flow (the 2FA step is the same page as first login).
        viewModelScope.launch {
            auth.sessionExpired.collect {
                _mode.value = LoginMode.Credentials
                _state.value = LoginUiState.Error("Your session expired — please sign in again.")
                _route.value = AppRoute.Login
            }
        }
    }

    fun login(username: String, password: String, remember: Boolean) {
        if (username.isBlank() || password.isBlank()) {
            _state.value = LoginUiState.Error("Enter a username and password.")
            return
        }
        if (killswitchBlocked()) { _state.value = LoginUiState.Error(killswitchMsg); return }
        _state.value = LoginUiState.Working(LoginPhase.FetchingPage)
        viewModelScope.launch {
            try {
                val outcome = auth.login(username.trim(), password, remember) { phase ->
                    _state.value = LoginUiState.Working(phase)
                }
                when (outcome) {
                    is LoginOutcome.Success -> {
                        _state.value = LoginUiState.Idle
                        _route.value = AppRoute.Chat
                    }
                    is LoginOutcome.TwoFactorRequired -> {
                        _mode.value = LoginMode.TwoFactor
                        _state.value = LoginUiState.Idle
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Login failed."
                _state.value = LoginUiState.Error(msg)
                errors.report(msg)
            }
        }
    }

    fun submitCode(code: String, trust: Boolean) {
        if (code.isBlank()) {
            _state.value = LoginUiState.Error("Enter your authenticator code.")
            return
        }
        if (killswitchBlocked()) { _state.value = LoginUiState.Error(killswitchMsg); return }
        _state.value = LoginUiState.Working(LoginPhase.SigningIn)
        viewModelScope.launch {
            try {
                val outcome = auth.submitTwoFactor(code.trim(), trust) { phase ->
                    _state.value = LoginUiState.Working(phase)
                }
                when (outcome) {
                    is LoginOutcome.Success -> {
                        _state.value = LoginUiState.Idle
                        _route.value = AppRoute.Chat
                    }
                    is LoginOutcome.TwoFactorRequired -> {
                        _state.value = LoginUiState.Error("That code didn't work. Try again.")
                        errors.report("Two-factor code rejected")
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Verification failed."
                _state.value = LoginUiState.Error(msg)
                errors.report(msg)
            }
        }
    }

    /** Return to the username/password form from the 2FA step. */
    fun backToCredentials() {
        _mode.value = LoginMode.Credentials
        _state.value = LoginUiState.Idle
    }

    fun dismissError() {
        if (_state.value is LoginUiState.Error) _state.value = LoginUiState.Idle
    }

    fun logout() {
        auth.logout()
        _state.value = LoginUiState.Idle
        _route.value = AppRoute.Login
    }

    /** Debug-only: jump to the chat screen (shows sample data when no live session). */
    fun previewChat() {
        _route.value = AppRoute.Chat
    }

    class Factory(
        private val auth: AuthRepository,
        private val errors: ErrorReporter,
        private val killswitchBlocked: () -> Boolean = { false },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LoginViewModel(auth, errors, killswitchBlocked) as T
    }
}
