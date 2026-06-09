package st.kiwifarms.sneedroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import st.kiwifarms.sneedroid.ui.chat.ChatDrawer
import st.kiwifarms.sneedroid.ui.chat.ChatScreen
import st.kiwifarms.sneedroid.ui.chat.ChatViewModel
import st.kiwifarms.sneedroid.ui.chat.DebugFramesScreen
import st.kiwifarms.sneedroid.ui.chat.NewWhisperDialog
import st.kiwifarms.sneedroid.ui.AppErrorToast
import st.kiwifarms.sneedroid.ui.chat.SettingsSheet
import st.kiwifarms.sneedroid.ui.chat.WhisperWindow
import st.kiwifarms.sneedroid.ui.login.AppRoute
import st.kiwifarms.sneedroid.ui.login.LoginScreen
import st.kiwifarms.sneedroid.ui.login.LoginViewModel
import st.kiwifarms.sneedroid.data.BackgroundMode
import st.kiwifarms.sneedroid.ui.theme.SneedTheme
import st.kiwifarms.sneedroid.ui.theme.SneedroidTheme

class MainActivity : ComponentActivity() {
    // Track the app's foreground state (single-activity app) and reconfigure the connection:
    // foreground = joined to the room for the UI; background = whatever the BackgroundMode wants.
    override fun onStart() {
        super.onStart()
        appContainer.chatRepository.appForeground = true
        appContainer.connectionController.goForeground()
    }

    override fun onStop() {
        super.onStop()
        appContainer.chatRepository.appForeground = false
        appContainer.connectionController.goBackground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer
        val auth = container.authRepository
        val settingsRepo = container.settingsRepository
        setContent {
            val appSettings by settingsRepo.settings.collectAsState()
            SneedroidTheme(dark = appSettings.dark, accentHue = appSettings.accentHue) {
              androidx.compose.runtime.CompositionLocalProvider(
                st.kiwifarms.sneedroid.ui.chat.LocalImageDomains provides appSettings.imageDomains,
                st.kiwifarms.sneedroid.ui.chat.LocalYouTubeStyle provides appSettings.youtubeStyle,
              ) {
                Box(Modifier.fillMaxSize()) {
                val vm: LoginViewModel = viewModel(
                    factory = LoginViewModel.Factory(
                        auth, container.errorReporter,
                        killswitchBlocked = { container.killswitchGate.blocked.value },
                    ),
                )
                val route by vm.route.collectAsState()
                when (route) {
                    AppRoute.Deciding -> SplashScreen()
                    AppRoute.Login -> {
                        val state by vm.state.collectAsState()
                        val mode by vm.mode.collectAsState()
                        val vpnActive by container.connectivityMonitor.vpnActive.collectAsState()
                        LoginScreen(
                            state = state,
                            mode = mode,
                            initialUsername = vm.savedUsername,
                            onLogin = vm::login,
                            onSubmitCode = vm::submitCode,
                            onBack = vm::backToCredentials,
                            onPreview = if (BuildConfig.DEBUG) vm::previewChat else null,
                            ipKillswitch = appSettings.ipKillswitch,
                            vpnActive = vpnActive,
                            onSetIpKillswitch = settingsRepo::setIpKillswitch,
                        )
                    }
                    AppRoute.Chat -> {
                        val chatVm: ChatViewModel = viewModel(
                            factory = ChatViewModel.Factory(
                                auth = auth,
                                store = container.secureStore,
                                settings = settingsRepo,
                                repo = container.chatRepository,
                                allowDemo = BuildConfig.DEBUG,
                            ),
                        )
                        ChatRoute(
                            chatVm = chatVm,
                            appSettings = appSettings,
                            container = container,
                            onLoggedOut = { chatVm.leave(); vm.logout() },
                        )
                    }
                }
                AppErrorToast(container.errorReporter.toasts)
                }
              }
            }
        }
    }
}

@Composable
private fun ChatRoute(
    chatVm: ChatViewModel,
    appSettings: st.kiwifarms.sneedroid.data.AppSettings,
    container: AppContainer,
    onLoggedOut: () -> Unit,
) {
    val chatState by chatVm.state.collectAsState()
    val whispers by chatVm.whispers.collectAsState()
    val macros by container.macroStore.macros.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showRoomDialog by remember { mutableStateOf(false) }
    var showNewWhisper by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var whisperPartner by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val settingsRepo = container.settingsRepository

    // Ask for notification permission (API 33+) the first time chat opens with a toggle enabled.
    // Re-runs if the user later flips one on; the OS silently ignores the launch once permanently
    // denied, so there's no prompt loop.
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {}
        LaunchedEffect(appSettings.notifyWhispers, appSettings.notifyMentions) {
            val wants = appSettings.notifyWhispers || appSettings.notifyMentions
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (wants && !granted) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Start/stop the background-connection service to match the selected mode, and prompt for a
    // battery-optimisation exemption the first time it's enabled (without it, aggressive OEMs —
    // the Titan especially — kill the service shortly after backgrounding).
    LaunchedEffect(appSettings.backgroundMode) {
        val intent = Intent(context, BackgroundService::class.java)
        if (appSettings.backgroundMode != BackgroundMode.Off) {
            runCatching { ContextCompat.startForegroundService(context, intent) }
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        } else {
            runCatching { context.stopService(intent) }
        }
    }

    fun openWhisper(id: Long, name: String) {
        whisperPartner = id to name
        chatVm.markWhisperRead(id)
        chatVm.setOpenWhisper(id)
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawer(
                username = chatState.selfName ?: chatVm.username,
                currentRoomId = chatVm.currentRoomId,
                recentRooms = appSettings.recentRooms,
                whispers = whispers,
                dark = appSettings.dark,
                onSelectRoom = { id -> chatVm.switchRoom(id); scope.launch { drawerState.close() } },
                onJoinRoom = { showRoomDialog = true },
                onOpenWhisper = { id, name -> openWhisper(id, name) },
                onNewWhisper = { showNewWhisper = true },
                onOpenSettings = { showSettings = true; scope.launch { drawerState.close() } },
                onToggleTheme = { settingsRepo.setDark(!appSettings.dark) },
                onLogout = {
                    scope.launch { drawerState.close() }
                    runCatching { context.stopService(Intent(context, BackgroundService::class.java)) }
                    onLoggedOut()
                },
                onOpenDebug = if (BuildConfig.DEBUG) {
                    { showDebug = true; scope.launch { drawerState.close() } }
                } else {
                    null
                },
            )
        },
    ) {
        ChatScreen(
            state = chatState,
            settings = appSettings,
            myUsername = chatVm.username,
            myUserId = chatVm.myUserId,
            botUsers = appSettings.botUsers,
            mutedUsers = appSettings.mutedUsers,
            hideMuted = appSettings.hideMuted,
            onSend = chatVm::send,
            onEdit = chatVm::edit,
            onDelete = chatVm::delete,
            onOpenWhisper = { a -> openWhisper(a.id, a.username) },
            onMute = settingsRepo::addMutedUser,
            onUnmute = settingsRepo::removeMutedUser,
            onMenu = { scope.launch { drawerState.open() } },
            recentEmotes = appSettings.recentEmotes,
            onEmoteUsed = settingsRepo::rememberEmote,
            macros = macros,
            onMacroSave = container.macroStore::save,
            onMacroDelete = container.macroStore::remove,
        )
    }

    if (showDebug) {
        val frames by container.debugLog.frames.collectAsState()
        DebugFramesScreen(frames = frames, onClear = container.debugLog::clear, onClose = { showDebug = false })
    }

    if (showNewWhisper) {
        NewWhisperDialog(
            roster = chatState.roster,
            onDismiss = { showNewWhisper = false },
            onPick = { id, name -> showNewWhisper = false; openWhisper(id, name) },
        )
    }

    whisperPartner?.let { (id, name) ->
        val lines = whispers.firstOrNull { it.partnerId == id }?.lines ?: emptyList()
        WhisperWindow(
            partnerName = name,
            lines = lines,
            onSend = { text -> chatVm.sendWhisper(id, name, text) },
            onClose = { whisperPartner = null; chatVm.setOpenWhisper(null) },
        )
    }

    if (showSettings) {
        val vpnActive by container.connectivityMonitor.vpnActive.collectAsState()
        SettingsSheet(
            settings = appSettings,
            onClose = { showSettings = false },
            vpnActive = vpnActive,
            onSetIpKillswitch = settingsRepo::setIpKillswitch,
            onCheckVpn = container.connectivityMonitor::refresh,
            onSetLayout = settingsRepo::setLayout,
            onSetDensity = settingsRepo::setDensity,
            onSetAvatar = settingsRepo::setAvatarShape,
            onSetShowTime = settingsRepo::setShowTime,
            onSetImmersive = settingsRepo::setImmersive,
            onSetNotifyWhispers = settingsRepo::setNotifyWhispers,
            onSetNotifyMentions = settingsRepo::setNotifyMentions,
            onSetBackgroundMode = settingsRepo::setBackgroundMode,
            onSetAccent = settingsRepo::setAccentHue,
            onSetDark = settingsRepo::setDark,
            onSetYouTube = settingsRepo::setYouTubeStyle,
            onAddBot = settingsRepo::addBotUser,
            onRemoveBot = settingsRepo::removeBotUser,
            onAddImageDomain = settingsRepo::addImageDomain,
            onRemoveImageDomain = settingsRepo::removeImageDomain,
            onAddMuted = settingsRepo::addMutedUser,
            onRemoveMuted = settingsRepo::removeMutedUser,
            onSetHideMuted = settingsRepo::setHideMuted,
            onSetZiplineEnabled = settingsRepo::setZiplineEnabled,
            onSetZiplineUrl = settingsRepo::setZiplineUrl,
            onSetZiplineKey = settingsRepo::setZiplineKey,
        )
    }

    if (showRoomDialog) {
        var roomText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRoomDialog = false },
            title = { Text("Join room") },
            text = {
                OutlinedTextField(
                    value = roomText,
                    onValueChange = { roomText = it.filter(Char::isDigit).take(6) },
                    label = { Text("Room ID") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    roomText.toIntOrNull()?.let { chatVm.switchRoom(it) }
                    showRoomDialog = false
                    scope.launch { drawerState.close() }
                }) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = { showRoomDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(SneedTheme.colors.bg), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = SneedTheme.colors.accent)
    }
}
