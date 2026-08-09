package st.kiwifarms.sneedroid

import android.app.Application
import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import st.kiwifarms.sneedroid.data.AuthRepository
import st.kiwifarms.sneedroid.data.ChatRepository
import st.kiwifarms.sneedroid.data.ConnectionController
import st.kiwifarms.sneedroid.data.ConnectivityMonitor
import st.kiwifarms.sneedroid.data.KillswitchGate
import st.kiwifarms.sneedroid.data.KillswitchInterceptor
import st.kiwifarms.sneedroid.data.Killswitches
import st.kiwifarms.sneedroid.data.DebugLog
import st.kiwifarms.sneedroid.data.ErrorReporter
import st.kiwifarms.sneedroid.data.SecureStore
import st.kiwifarms.sneedroid.data.MacroStore
import st.kiwifarms.sneedroid.data.Notifier
import st.kiwifarms.sneedroid.data.SettingsRepository
import st.kiwifarms.sneedroid.data.WebViewMonocleProvider
import st.kiwifarms.sneedroid.data.WhisperStore
import st.kiwifarms.sneedroid.ui.chat.EmoteTable

/** Manual DI container — small enough that Hilt would be overkill (docs/06, Q6). */
class AppContainer(context: Context) {
    val secureStore: SecureStore = SecureStore(context)
    val settingsRepository: SettingsRepository = SettingsRepository(context)
    val connectivityMonitor: ConnectivityMonitor = ConnectivityMonitor(context)
    // Shared IP-killswitch gate: consulted by the auth HTTP client (login/resume) and the chat
    // WebSocket alike, so no path reaches KiwiFarms while blocked.
    val killswitchGate: KillswitchGate = KillswitchGate(settingsRepository, connectivityMonitor)
    val authRepository: AuthRepository = AuthRepository(
        secureStore,
        killswitchBlocked = { killswitchGate.blocked.value },
        // The PoW gate's Spur Monocle step is the one part of login that needs a browser
        // engine; everything else stays on OkHttp. See WebViewMonocleProvider.
        monocle = WebViewMonocleProvider(
            context = context.applicationContext,
            killswitchBlocked = { killswitchGate.blocked.value },
            domain = secureStore.settings.domain,
        ),
    )
    val debugLog: DebugLog = DebugLog()
    val errorReporter: ErrorReporter = ErrorReporter(debugLog)
    val whisperStore: WhisperStore = WhisperStore(context)
    val macroStore: MacroStore = MacroStore(context)
    val notifier: Notifier = Notifier(context)
    val chatRepository: ChatRepository =
        ChatRepository(debug = debugLog, errors = errorReporter, whisperStore = whisperStore, notifier = notifier)
            .also { it.killswitch = killswitchGate }
    val connectionController: ConnectionController =
        ConnectionController(chatRepository, authRepository, secureStore, settingsRepository)

    init {
        // Let the Coil image loaders (built in SneedApp / EmoteTable, outside this container)
        // consult the same gate, so avatars/emotes can't fetch from the CDN without a VPN.
        Killswitches.blocked = { killswitchGate.blocked.value }
    }
}

class SneedApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        EmoteTable.load(this) // parse the bundled custom-emote code → image-URL table once
        appScope.launch { EmoteTable.prefetchAll(this@SneedApp) } // warm the emote cache off the main thread
    }

    /** Register decoders so [coil.compose.AsyncImage] plays GIFs / animated WebPs and renders SVG emotes. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory()) // animated GIF + animated WebP (API 28+)
            } else {
                add(GifDecoder.Factory()) // animated GIF (API 26–27)
            }
            add(SvgDecoder.Factory()) // SVG emotes
        }
        // Honour the IP killswitch: no image fetch reaches a CDN without a VPN.
        .okHttpClient { okhttp3.OkHttpClient.Builder().addInterceptor(KillswitchInterceptor()).build() }
        .build()
}

/** Convenience accessor from a Composable/Activity context. */
val Context.appContainer: AppContainer
    get() = (applicationContext as SneedApp).container
