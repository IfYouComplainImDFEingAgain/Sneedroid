@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package st.kiwifarms.sneedroid.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import st.kiwifarms.sneedroid.data.Zipline
import st.kiwifarms.sneedroid.core.model.ChatAuthor
import st.kiwifarms.sneedroid.core.model.ChatMessage
import st.kiwifarms.sneedroid.data.AppSettings
import st.kiwifarms.sneedroid.data.AvatarShape
import st.kiwifarms.sneedroid.data.ChatUiState
import st.kiwifarms.sneedroid.data.ConnectionState
import st.kiwifarms.sneedroid.data.CustomMacro
import st.kiwifarms.sneedroid.data.Density
import st.kiwifarms.sneedroid.data.MessageLayout
import st.kiwifarms.sneedroid.data.TimelineItem
import st.kiwifarms.sneedroid.ui.theme.SneedTheme

private const val DEFAULT_BASE = "https://kiwifarms.st"

/** How many items above the very bottom still count as "stuck" to the bottom. */
private const val STICKY_THRESHOLD = 2

/**
 * Whether [listState] is at (or within [STICKY_THRESHOLD] of) the bottom — used to drive
 * sticky auto-scroll and the jump-to-bottom button.
 */
@Composable
private fun rememberAtBottom(listState: LazyListState): State<Boolean> = remember(listState) {
    derivedStateOf {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        last == null || last.index >= info.totalItemsCount - 1 - STICKY_THRESHOLD
    }
}

@Composable
fun ChatScreen(
    state: ChatUiState,
    settings: AppSettings,
    myUsername: String?,
    myUserId: Long? = null,
    botUsers: List<String> = emptyList(),
    mutedUsers: List<String> = emptyList(),
    hideMuted: Boolean = false,
    onSend: (String) -> Unit = {},
    onEdit: (uuid: String, text: String) -> Unit = { _, _ -> },
    onDelete: (uuid: String) -> Unit = {},
    onOpenWhisper: (ChatAuthor) -> Unit = {},
    onMute: (String) -> Unit = {},
    onUnmute: (String) -> Unit = {},
    onMenu: () -> Unit = {},
    recentEmotes: List<String> = emptyList(),
    onEmoteUsed: (String) -> Unit = {},
    macros: List<CustomMacro> = emptyList(),
    onMacroSave: (CustomMacro) -> Unit = {},
    onMacroDelete: (String) -> Unit = {},
) {
    val c = SneedTheme.colors
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var botColumnOpen by remember { mutableStateOf(false) }

    fun isBot(name: String) = botUsers.any { it.equals(name, ignoreCase = true) }
    fun mutedName(name: String) = mutedUsers.any { it.equals(name, ignoreCase = true) }
    // Bot users are always isolated to the bot feed; muted users are hidden when hideMuted.
    val botMessages = state.timeline.filterIsInstance<TimelineItem.Msg>().filter { isBot(it.message.author.username) }
    val timeline = state.timeline.filter { item ->
        item !is TimelineItem.Msg ||
            (!isBot(item.message.author.username) && !(hideMuted && mutedName(item.message.author.username)))
    }

    var composerValue by remember { mutableStateOf(TextFieldValue("")) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var msgMenu by remember { mutableStateOf<ChatMessage?>(null) }
    var profileMenu by remember { mutableStateOf<ChatAuthor?>(null) }
    val revealed = remember { mutableStateListOf<String>() }
    var toast by remember { mutableStateOf<String?>(null) }
    var macroAdding by remember { mutableStateOf(false) }
    var macroEditing by remember { mutableStateOf<CustomMacro?>(null) }

    LaunchedEffect(toast) { if (toast != null) { delay(1900); toast = null } }

    fun append(text: String) {
        val cur = composerValue.text
        val sep = if (cur.isEmpty() || cur.endsWith(" ") || cur.endsWith("\n")) "" else " "
        val next = cur + sep + text
        composerValue = TextFieldValue(next, TextRange(next.length))
    }
    fun mention(name: String) { append("@$name "); toast = "Mentioning $name" }
    fun quote(m: ChatMessage) { append("[quote=${m.author.username}]${m.raw}[/quote]\n") }
    fun startEdit(m: ChatMessage) {
        editing = m
        composerValue = TextFieldValue(m.raw, TextRange(m.raw.length))
    }
    fun cancelEdit() { editing = null; composerValue = TextFieldValue("") }
    fun doSend() {
        val text = composerValue.text.trim()
        if (text.isEmpty()) return
        val uuid = editing?.uuid
        if (uuid != null) { onEdit(uuid, text); editing = null } else onSend(text)
        composerValue = TextFieldValue("")
    }
    fun toggleMute(name: String) {
        if (mutedName(name)) { onUnmute(name); toast = "Unmuted $name" }
        else { onMute(name); revealed.remove(name); toast = "Muted $name" }
    }
    // A macro inserts its text at the caret — unless it's flagged auto-send and the box is empty
    // (and we're not editing), in which case fire it straight off as its own message.
    fun pickMacro(m: CustomMacro) {
        if (m.autoSend && composerValue.text.isBlank() && editing == null) onSend(m.insert)
        else composerValue = composerValue.insertAtCaret(m.insert)
    }

    // Zipline image upload: read the picked image and upload to the configured host, returning a URL.
    val context = LocalContext.current
    suspend fun readAndUpload(uri: Uri): Result<String> {
        val read = withContext(Dispatchers.IO) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val mime = context.contentResolver.getType(uri) ?: "image/*"
            bytes?.let { Triple(it, mime, "image." + mime.substringAfter('/', "jpg")) }
        } ?: return Result.failure(IllegalStateException("Couldn't read image"))
        return Zipline.upload(settings.ziplineUrl, settings.ziplineKey, read.first, read.third, read.second)
    }
    fun uploadImage(uri: Uri) {
        scope.launch {
            toast = "Uploading image…"
            readAndUpload(uri)
                .onSuccess { url -> append("[img]$url[/img]"); toast = "Image uploaded" }
                .onFailure { toast = "Upload failed: ${it.message}" }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { uploadImage(it) }
    }

    Box(Modifier.fillMaxSize()) {
        // Include the IME inset so the content column shrinks (lifting the composer above the
        // keyboard) when it opens. enableEdgeToEdge() turns off decorFitsSystemWindows, so the
        // manifest's adjustResize no longer does this for us — Compose must consume the inset.
        // union(systemBars, ime) avoids double-counting the nav bar (bottom = max of the two).
        Scaffold(
            containerColor = c.bg,
            contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        ) { inner ->
            Column(Modifier.fillMaxSize().padding(inner)) {
                // Immersive mode hides the app bar + MOTD to maximise message space; the menu
                // icon floats over the timeline instead (see below).
                if (!settings.immersive) {
                    ChatAppBar(state, onMenu, botColumnOpen, botMessages.size) { botColumnOpen = !botColumnOpen }
                    MotdBar(state.motds)
                }

                val listState = rememberLazyListState()
                val atBottom by rememberAtBottom(listState)
                // Unseen-message count ignores muted users so they never raise the jump button.
                val notifiable = timeline.count { it !is TimelineItem.Msg || !mutedName(it.message.author.username) }
                var lastSeen by remember { mutableStateOf(0) }
                var initialized by remember { mutableStateOf(false) }

                // Jump straight to the latest message when the timeline first populates
                // (screen open, reconnect, room switch) and reset the unseen baseline there.
                LaunchedEffect(timeline.isEmpty()) {
                    if (timeline.isEmpty()) {
                        initialized = false
                    } else if (!initialized) {
                        listState.scrollToItem(timeline.lastIndex)
                        lastSeen = notifiable
                        initialized = true
                    }
                }
                // After that, stay pinned to the bottom whenever we're already there. We react not
                // just to new messages but to late layout growth: inline images and emotes decode
                // asynchronously (Coil) and expand their rows *after* the initial scroll, which would
                // otherwise push the latest message back off-screen with no item-count change to
                // trigger a re-scroll. snapshotFlow watches the content's bottom edge, so any reflow
                // while we're at the bottom is corrected immediately. totalItemsCount is read from
                // layoutInfo (not the captured `timeline`) so the target index never goes stale.
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()
                        Triple(info.totalItemsCount, last?.index ?: -1, (last?.offset ?: 0) + (last?.size ?: 0))
                    }.collect { (total, _, _) ->
                        if (initialized && atBottom && total > 0) listState.scrollToItem(total - 1)
                    }
                }
                // Keep the unseen baseline current while sitting at the bottom.
                LaunchedEffect(atBottom, notifiable) { if (initialized && atBottom) lastSeen = notifiable }
                val newCount = if (initialized) (notifiable - lastSeen).coerceAtLeast(0) else 0

                Box(Modifier.weight(1f).fillMaxWidth()) {
                  if (timeline.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.connection == ConnectionState.Online) "No messages here yet." else "Connecting…",
                            color = c.text3, fontSize = 13.sp,
                        )
                    }
                  } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().background(c.bg),
                        // Leave room at the top for the floating menu button in immersive mode.
                        contentPadding = PaddingValues(top = if (settings.immersive) 52.dp else 6.dp, bottom = 6.dp),
                    ) {
                        itemsIndexed(timeline, key = { _, it -> it.key }) { i, item ->
                            when (item) {
                                is TimelineItem.Msg -> {
                                    val name = item.message.author.username
                                    if (mutedName(name) && !revealed.contains(name)) {
                                        MutedRow(name) { revealed.add(name) }
                                    } else {
                                        val prev = timeline.getOrNull(i - 1)
                                        val grouped = prev is TimelineItem.Msg &&
                                            prev.message.author.username.equals(name, ignoreCase = true)
                                        MessageRow(
                                            item, settings, myUsername, myUserId, grouped,
                                            onLongPress = { msgMenu = item.message },
                                            onProfile = { profileMenu = item.message.author },
                                            onMention = { mention(name) },
                                        )
                                    }
                                }
                                is TimelineItem.Sys -> SystemRow(item.text)
                            }
                        }
                    }
                  }
                  if (!atBottom && newCount > 0) {
                      JumpToBottomButton(
                          count = newCount,
                          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                      ) { scope.launch { listState.animateScrollToItem(timeline.lastIndex) } }
                  }
                  if (settings.immersive) {
                      FloatingIconButton(
                          Icons.Filled.Menu, "Menu", active = false, onClick = onMenu,
                          modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                      )
                      // Mirror the app bar's bot toggle on the right; only when there are bots.
                      if (botMessages.isNotEmpty()) {
                          FloatingIconButton(
                              Icons.Filled.ViewColumn, "Bot feed (${botMessages.size})",
                              active = botColumnOpen, onClick = { botColumnOpen = !botColumnOpen },
                              modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                          )
                      }
                  }
                }

                val canSend = state.permissions?.canSend ?: true
                if (canSend) {
                    Composer(
                        value = composerValue,
                        onValueChange = { composerValue = it },
                        editing = editing != null,
                        onSend = ::doSend,
                        onCancelEdit = ::cancelEdit,
                        onUploadImage = if (settings.ziplineReady) {
                            { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        } else {
                            null
                        },
                        recentEmotes = recentEmotes,
                        onEmoteUsed = onEmoteUsed,
                        macros = macros,
                        onMacroPick = ::pickMacro,
                        onMacroAdd = { macroAdding = true },
                        onMacroEdit = { m -> macroEditing = m },
                    )
                } else {
                    ReadOnlyComposer("You don't have permission to send in this room")
                }
            }
        }

        toast?.let { ToastPill(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) }

        if (macroAdding || macroEditing != null) {
            MacroEditDialog(
                initial = macroEditing,
                uploader = if (settings.ziplineReady) { uri -> readAndUpload(uri) } else null,
                onDismiss = { macroAdding = false; macroEditing = null },
                onSave = { m -> onMacroSave(m); macroAdding = false; macroEditing = null },
                onDelete = if (macroEditing != null) { id -> onMacroDelete(id); macroEditing = null } else null,
            )
        }

        AnimatedVisibility(
            visible = state.connection == ConnectionState.Reconnecting,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
        ) { ReconnectingPill() }

        AnimatedVisibility(
            visible = state.connection == ConnectionState.Blocked,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
        ) { KillswitchPill() }

        if (botColumnOpen) {
            Box(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                    .clickable { botColumnOpen = false },
            )
            BotColumn(botMessages, Modifier.align(Alignment.CenterEnd)) { botColumnOpen = false }
        }
    }

    msgMenu?.let { m ->
        val own = isOwnMessage(m.author, myUserId, myUsername)
        MessageActionsSheet(
            message = m, own = own, perms = state.permissions,
            onClose = { msgMenu = null },
            onQuote = { quote(m); msgMenu = null },
            onCopy = { clipboard.setText(AnnotatedString(m.raw.ifEmpty { m.message })); msgMenu = null; toast = "Copied to clipboard" },
            onEdit = { startEdit(m); msgMenu = null },
            onDelete = { m.uuid?.let { onDelete(it) }; msgMenu = null; toast = "Message deleted" },
        )
    }

    profileMenu?.let { a ->
        ProfileActionsSheet(
            author = a, isMuted = mutedName(a.username),
            onClose = { profileMenu = null },
            onMention = { mention(a.username); profileMenu = null },
            onDm = { profileMenu = null; onOpenWhisper(a) },
            onMute = { toggleMute(a.username); profileMenu = null },
        )
    }
}

@Composable
private fun ChatAppBar(state: ChatUiState, onMenu: () -> Unit, botActive: Boolean, botCount: Int, onToggleBot: () -> Unit) {
    val c = SneedTheme.colors
    val (statusText, dotColor) = when (state.connection) {
        ConnectionState.Online -> "${state.online} online" to c.accent
        ConnectionState.Connecting -> "connecting…" to c.text3
        ConnectionState.Reconnecting -> "reconnecting…" to c.danger
        ConnectionState.Disconnected -> "offline" to c.text3
        ConnectionState.Blocked -> "killswitch — VPN required" to c.danger
    }
    Row(
        Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppBarIcon(Icons.Filled.Menu, "Menu", false, onMenu)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(state.roomTitle.ifEmpty { "SneedChat" }, color = c.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(6.dp))
                Text(statusText, color = c.text2, fontSize = 11.5.sp)
            }
        }
        AppBarIcon(Icons.Filled.ViewColumn, "Bot feed ($botCount)", botActive, onToggleBot)
    }
}

@Composable
private fun AppBarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, active: Boolean, onClick: () -> Unit) {
    val c = SneedTheme.colors
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
            .background(if (active) c.accent else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = if (active) c.accentOn else c.text2, modifier = Modifier.size(24.dp))
    }
}

/** Circular floating control shown over the timeline in immersive mode (no app bar). */
@Composable
private fun FloatingIconButton(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = SneedTheme.colors
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(percent = 50))
            .background(if (active) c.accent.copy(alpha = 0.9f) else c.surface.copy(alpha = 0.88f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (active) c.accentOn else c.text, modifier = Modifier.size(22.dp))
    }
}

/** Top "Reconnecting…" toaster, shown while the socket is re-establishing (any mode). */
@Composable
private fun ReconnectingPill(modifier: Modifier = Modifier) {
    val c = SneedTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(percent = 50)).background(c.surface)
            .border(1.dp, c.danger.copy(alpha = 0.5f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c.danger))
        Spacer(Modifier.width(8.dp))
        Text("Reconnecting…", color = c.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** Shown while the IP killswitch is holding the connection closed (no VPN tunnel detected). */
@Composable
private fun KillswitchPill(modifier: Modifier = Modifier) {
    val c = SneedTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(percent = 50)).background(c.surface)
            .border(1.dp, c.danger.copy(alpha = 0.5f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Shield, contentDescription = null, tint = c.danger, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text("Killswitch — connect a VPN to continue", color = c.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** Pinned MOTD(s) above the timeline (kept out of the scroll). Each is collapsed by default. */
@Composable
private fun MotdBar(motds: List<ChatMessage>) {
    if (motds.isEmpty()) return
    Column(Modifier.fillMaxWidth()) { motds.forEach { MotdItem(it) } }
}

@Composable
private fun MotdItem(m: ChatMessage) {
    val c = SneedTheme.colors
    var expanded by remember(m.uuid) { mutableStateOf(false) }
    val raw = m.raw.ifEmpty { m.message }
    Column(
        Modifier.fillMaxWidth()
            .background(c.accent.copy(alpha = 0.07f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PushPin, contentDescription = null, tint = c.accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text("MOTD", color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand", tint = c.text3, modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            BBCodeText(raw, color = c.text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
        } else {
            Text(
                motdPreview(raw), color = c.text2, fontSize = 12.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** First non-blank line of a MOTD with BBCode tags stripped, for the collapsed one-liner. */
private fun motdPreview(raw: String): String =
    raw.replace(Regex("\\[/?[^\\]]+]"), "").lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/**
 * True if [author] is the signed-in user. Prefer the numeric id (always available from the
 * `xf_user` cookie); fall back to username (only set when "Stay signed in" saved it). Using
 * id-first means own-message edit/delete works even without saved credentials.
 */
private fun isOwnMessage(author: ChatAuthor, myUserId: Long?, myUsername: String?): Boolean =
    (myUserId != null && author.id == myUserId) ||
        (myUsername != null && author.username.equals(myUsername, ignoreCase = true))

@Composable
private fun MessageRow(
    item: TimelineItem.Msg,
    settings: AppSettings,
    myUsername: String?,
    myUserId: Long?,
    grouped: Boolean,
    onLongPress: () -> Unit,
    onProfile: () -> Unit,
    onMention: () -> Unit,
) {
    val c = SneedTheme.colors
    val m = item.message
    val name = m.author.username
    val own = isOwnMessage(m.author, myUserId, myUsername)
    val shape: Shape = if (settings.avatarShape == AvatarShape.Square) RoundedCornerShape(8.dp) else CircleShape
    val vpad = if (settings.density == Density.Compact) 3.dp else 5.dp
    val time = if (settings.showTime) formatTime(m.date) + (if (m.isEdited) " · edited" else "") else ""
    val rawBody = m.raw.ifEmpty { m.message }
    val pressable = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)

    when (settings.layout) {
        MessageLayout.Compact -> {
            if (grouped) {
                Row(
                    pressable.fillMaxWidth().padding(start = 43.dp, end = 14.dp, top = 1.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    BBCodeText(rawBody, color = c.text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                    if (time.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text(time, color = c.text3, fontSize = 10.sp) }
                }
            } else {
                Row(
                    pressable.fillMaxWidth().padding(horizontal = 14.dp, vertical = vpad),
                    verticalAlignment = Alignment.Top,
                ) {
                    Avatar(name, m.author.avatarUrl, 22.dp, shape, onMention, onProfile)
                    Spacer(Modifier.width(7.dp))
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                        NameText(name, 13.sp, onMention, onProfile)
                        Spacer(Modifier.width(7.dp))
                        BBCodeText(rawBody, color = c.text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                        if (time.isNotEmpty()) Text(time, color = c.text3, fontSize = 10.sp)
                    }
                }
            }
        }

        MessageLayout.Bubbles -> Row(
            pressable.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = if (grouped) 1.dp else 4.dp, bottom = 1.dp),
            horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (!own) {
                if (grouped) Spacer(Modifier.width(40.dp))
                else { Avatar(name, m.author.avatarUrl, 32.dp, shape, onMention, onProfile); Spacer(Modifier.width(8.dp)) }
            }
            Column(horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
                if (!own && !grouped) NameText(name, 13.sp, onMention, onProfile)
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
                    if (own && time.isNotEmpty()) { Text(time, color = c.text3, fontSize = 10.sp); Spacer(Modifier.width(6.dp)) }
                    Box(
                        Modifier.widthIn(max = 240.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (own) c.accent.copy(alpha = 0.16f) else c.surface)
                            .border(1.dp, c.border2, RoundedCornerShape(14.dp))
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                    ) { BBCodeText(rawBody, color = c.text, fontSize = 14.sp, lineHeight = 20.sp) }
                    if (!own && time.isNotEmpty()) { Spacer(Modifier.width(6.dp)); Text(time, color = c.text3, fontSize = 10.sp) }
                }
            }
        }

        MessageLayout.Forum -> {
            if (grouped) {
                Row(
                    pressable.fillMaxWidth().padding(start = 62.dp, end = 14.dp, top = 1.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    BBCodeText(rawBody, color = c.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                    if (time.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text(time, color = c.text3, fontSize = 11.sp) }
                }
            } else {
                Row(pressable.fillMaxWidth().padding(horizontal = 14.dp, vertical = vpad)) {
                    Avatar(name, m.author.avatarUrl, 38.dp, shape, onMention, onProfile)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            NameText(name, 14.sp, onMention, onProfile)
                            Spacer(Modifier.weight(1f))
                            if (time.isNotEmpty()) Text(time, color = c.text3, fontSize = 11.sp)
                        }
                        BBCodeText(rawBody, color = c.text, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun NameText(name: String, size: androidx.compose.ui.unit.TextUnit, onMention: () -> Unit, onProfile: () -> Unit) {
    Text(
        name, color = colorForName(name), fontWeight = FontWeight.Bold, fontSize = size,
        modifier = Modifier.combinedClickable(onClick = onMention, onLongClick = onProfile),
    )
}

@Composable
private fun MutedRow(name: String, onReveal: () -> Unit) {
    val c = SneedTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onReveal).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("muted message from ", color = c.text3, fontSize = 12.sp)
        Text(name, color = c.text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text("tap to show", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SystemRow(text: String) {
    val c = SneedTheme.colors
    Text(
        text, color = c.text3, fontStyle = FontStyle.Italic, fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun Avatar(name: String, url: String?, size: Dp, shape: Shape, onMention: () -> Unit, onProfile: () -> Unit) {
    val c = SneedTheme.colors
    val resolved = url?.let { if (it.startsWith("/")) DEFAULT_BASE + it else it }
    Box(
        Modifier.size(size).clip(shape).background(colorForName(name))
            .combinedClickable(onClick = onMention, onLongClick = onProfile),
        contentAlignment = Alignment.Center,
    ) {
        if (!resolved.isNullOrBlank()) {
            AsyncImage(model = resolved, contentDescription = name, modifier = Modifier.fillMaxSize())
        } else {
            Text(initials(name), color = c.accentOn, fontWeight = FontWeight.Bold, fontSize = (size.value / 2.6).sp)
        }
    }
}

@Composable
private fun ReadOnlyComposer(message: String) {
    val c = SneedTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(c.inputBg)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) { Text(message, color = c.text3, fontSize = 13.sp) }
    }
}

@Composable
private fun BotColumn(messages: List<TimelineItem.Msg>, modifier: Modifier, onClose: () -> Unit) {
    val c = SneedTheme.colors
    Column(modifier.fillMaxHeight().width(290.dp).background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.SmartToy, "Bot feed", tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            Text("Bot feed", color = c.accent, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Text("✕", color = c.text2, fontSize = 15.sp)
            }
        }
        Text(
            "kept out of the main chat",
            color = c.text3, fontSize = 11.5.sp,
            modifier = Modifier.padding(start = 16.dp, bottom = 10.dp),
        )
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No bot messages.", color = c.text3, fontSize = 13.sp)
            }
        } else {
            val botListState = rememberLazyListState()
            val botAtBottom by rememberAtBottom(botListState)
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty() && botAtBottom) botListState.animateScrollToItem(messages.size - 1)
            }
            LazyColumn(
                state = botListState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(messages, key = { it.key }) { m -> BotMsgCard(m) }
            }
        }
    }
}

@Composable
private fun BotMsgCard(item: TimelineItem.Msg) {
    val c = SneedTheme.colors
    val m = item.message
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(c.bg).border(1.dp, c.border2, RoundedCornerShape(12.dp)).padding(11.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(m.author.username, color = colorForName(m.author.username), fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            Spacer(Modifier.weight(1f))
            Text(formatTime(m.date), color = c.text3, fontSize = 10.5.sp)
        }
        Spacer(Modifier.size(4.dp))
        BBCodeText(m.raw.ifEmpty { m.message }, color = c.text, fontSize = 13.5.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun JumpToBottomButton(count: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = SneedTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(c.accent).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to latest", tint = c.accentOn, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (count == 1) "1 new message" else "$count new messages",
            color = c.accentOn, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ToastPill(text: String, modifier: Modifier = Modifier) {
    val c = SneedTheme.colors
    Box(
        modifier.clip(RoundedCornerShape(22.dp)).background(c.surface3)
            .border(1.dp, c.border, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(text, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
