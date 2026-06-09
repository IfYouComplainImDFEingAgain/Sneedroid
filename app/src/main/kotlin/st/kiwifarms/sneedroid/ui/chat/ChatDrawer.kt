package st.kiwifarms.sneedroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import st.kiwifarms.sneedroid.BuildConfig
import st.kiwifarms.sneedroid.data.AppSettings
import st.kiwifarms.sneedroid.data.AvatarShape
import st.kiwifarms.sneedroid.data.BackgroundMode
import st.kiwifarms.sneedroid.data.Density
import st.kiwifarms.sneedroid.data.MessageLayout
import st.kiwifarms.sneedroid.ui.theme.SneedTheme
import st.kiwifarms.sneedroid.ui.theme.accentColor

@Composable
fun ChatDrawer(
    username: String?,
    currentRoomId: Int,
    recentRooms: List<Int>,
    whispers: List<st.kiwifarms.sneedroid.data.WhisperConversation>,
    dark: Boolean,
    onSelectRoom: (Int) -> Unit,
    onJoinRoom: () -> Unit,
    onOpenWhisper: (Long, String) -> Unit,
    onNewWhisper: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTheme: () -> Unit,
    onLogout: () -> Unit,
    onOpenDebug: (() -> Unit)? = null,
) {
    val c = SneedTheme.colors
    Column(
        // Background fills edge-to-edge; systemBarsPadding keeps content (brand at top, logout
        // at bottom) clear of the status bar and the nav buttons under edge-to-edge.
        Modifier.fillMaxHeight().width(300.dp).background(c.surface).systemBarsPadding(),
    ) {
        // Brand
        Row(Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                Text("K", color = c.accentOn, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text("Sneedroid", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
        }
        // Me
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val me = username ?: "You"
            Box(Modifier.size(40.dp).clip(CircleShape).background(colorForName(me)), contentAlignment = Alignment.Center) {
                Text(initials(me), color = c.accentOn, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text(me, color = c.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Signed in", color = c.text2, fontSize = 12.sp)
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Label("Rooms")
            // Built-in rooms always show (in registry order); any room joined by id is appended.
            (st.kiwifarms.sneedroid.data.Rooms.defaultIds + recentRooms).distinct().forEach { id ->
                RoomItem(id = id, active = id == currentRoomId, onClick = { onSelectRoom(id) })
            }
            DrawerItem(Icons.Filled.Add, "Join another room…", onJoinRoom)

            Label("Whispers")
            if (whispers.isEmpty()) {
                Text(
                    "No whispers yet",
                    color = c.text3, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            } else {
                whispers.forEach { w ->
                    WhisperItem(w) { onOpenWhisper(w.partnerId, w.partnerName) }
                }
            }
            DrawerItem(Icons.Filled.Add, "New whisper…", onNewWhisper)
        }

        DrawerItem(Icons.Filled.Settings, "Settings", onOpenSettings)
        if (onOpenDebug != null) DrawerItem(Icons.Filled.BugReport, "Debug · WS frames", onOpenDebug)
        DrawerItem(if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode, if (dark) "Light mode" else "Dark mode", onToggleTheme)
        DrawerItem(Icons.AutoMirrored.Filled.Logout, "Log out", onLogout, danger = true)
        Text(
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            color = c.text3, fontSize = 11.sp,
            modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        color = SneedTheme.colors.text3, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun RoomItem(id: Int, active: Boolean, onClick: () -> Unit) {
    val c = SneedTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(11.dp))
            .background(if (active) c.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", color = if (active) c.accent else c.text3, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(11.dp))
        Text(st.kiwifarms.sneedroid.data.Rooms.name(id), color = if (active) c.accent else c.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WhisperItem(w: st.kiwifarms.sneedroid.data.WhisperConversation, onClick: () -> Unit) {
    val c = SneedTheme.colors
    val preview = w.lines.lastOrNull()?.raw?.replace(Regex("\\[/?[^\\]]+\\]"), "")?.trim().orEmpty()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(colorForName(w.partnerName)), contentAlignment = Alignment.Center) {
            Text(initials(w.partnerName), color = c.accentOn, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(w.partnerName, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (preview.isNotEmpty()) {
                Text(preview, color = c.text3, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        if (w.unread > 0) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(c.accent),
                contentAlignment = Alignment.Center,
            ) { Text(w.unread.toString(), color = c.accentOn, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val c = SneedTheme.colors
    val tint = if (danger) c.danger else c.text
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onClose: () -> Unit,
    onSetLayout: (MessageLayout) -> Unit,
    onSetDensity: (Density) -> Unit,
    onSetAvatar: (AvatarShape) -> Unit,
    onSetShowTime: (Boolean) -> Unit,
    onSetImmersive: (Boolean) -> Unit,
    onSetNotifyWhispers: (Boolean) -> Unit,
    onSetNotifyMentions: (Boolean) -> Unit,
    onSetBackgroundMode: (BackgroundMode) -> Unit,
    onSetAccent: (Int) -> Unit,
    onSetDark: (Boolean) -> Unit,
    onSetYouTube: (st.kiwifarms.sneedroid.data.YouTubeStyle) -> Unit,
    onAddBot: (String) -> Unit,
    onRemoveBot: (String) -> Unit,
    onAddImageDomain: (String) -> Unit,
    onRemoveImageDomain: (String) -> Unit,
    onAddMuted: (String) -> Unit,
    onRemoveMuted: (String) -> Unit,
    onSetHideMuted: (Boolean) -> Unit,
    onSetZiplineEnabled: (Boolean) -> Unit,
    onSetZiplineUrl: (String) -> Unit,
    onSetZiplineKey: (String) -> Unit,
    vpnActive: Boolean = false,
    onSetIpKillswitch: (Boolean) -> Unit = {},
    onCheckVpn: () -> Unit = {},
) {
    val c = SneedTheme.colors
    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp).padding(bottom = 24.dp),
        ) {
            Text("Settings", color = c.text3, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 8.dp))

            SettingLabel("Message layout")
            Segmented(
                options = listOf("Forum" to MessageLayout.Forum, "Compact" to MessageLayout.Compact, "Bubbles" to MessageLayout.Bubbles),
                selected = settings.layout, onSelect = onSetLayout,
            )
            SettingLabel("Density")
            Segmented(
                options = listOf("Cozy" to Density.Cozy, "Compact" to Density.Compact),
                selected = settings.density, onSelect = onSetDensity,
            )
            SettingLabel("Avatars")
            Segmented(
                options = listOf("Circle" to AvatarShape.Circle, "Square" to AvatarShape.Square),
                selected = settings.avatarShape, onSelect = onSetAvatar,
            )
            ToggleRow("Show timestamps", settings.showTime, onSetShowTime)
            ToggleRow("Immersive (hide top bar & MOTD)", settings.immersive, onSetImmersive)

            SettingLabel("YouTube links")
            Segmented(
                options = listOf(
                    "Card" to st.kiwifarms.sneedroid.data.YouTubeStyle.Card,
                    "Embed" to st.kiwifarms.sneedroid.data.YouTubeStyle.Embed,
                ),
                selected = settings.youtubeStyle, onSelect = onSetYouTube,
            )

            SettingLabel("Accent color")
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(142, 250, 60, 350).forEach { hue ->
                    val col = accentColor(hue, settings.dark)
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(col)
                            .clickable { onSetAccent(hue) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (settings.accentHue == hue) Box(Modifier.size(10.dp).clip(CircleShape).background(c.accentOn))
                    }
                }
            }
            ToggleRow("Dark mode", settings.dark, onSetDark)

            EditableList(
                label = "Bot feed",
                hint = "Messages from these users move to the bot column",
                prefix = "#",
                placeholder = "Add a bot username",
                items = settings.botUsers,
                onAdd = onAddBot,
                onRemove = onRemoveBot,
            )
            EditableList(
                label = "Image domains",
                hint = "Only load inline images from these hosts; others show as a link",
                prefix = "",
                placeholder = "Add a domain (e.g. example.com)",
                items = settings.imageDomains,
                onAdd = onAddImageDomain,
                onRemove = onRemoveImageDomain,
            )
            EditableList(
                label = "Muted users",
                hint = "Messages from these users are collapsed (tap to show)",
                prefix = "@",
                placeholder = "Add a username to mute",
                items = settings.mutedUsers,
                onAdd = onAddMuted,
                onRemove = onRemoveMuted,
            )
            ToggleRow("Hide muted messages completely", settings.hideMuted, onSetHideMuted)

            SettingLabel("Notifications")
            Text(
                "Whispers always notify unless you're viewing that thread; mentions notify while backgrounded.",
                color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp),
            )
            ToggleRow("Whispers (private messages)", settings.notifyWhispers, onSetNotifyWhispers)
            ToggleRow("Mentions (@you)", settings.notifyMentions, onSetNotifyMentions)

            SettingLabel("Background delivery")
            Text(
                "Whispers only keeps a lightweight connection that never joins a room, so DMs still " +
                    "arrive with minimal battery. Adding mentions stays in the room (more battery). " +
                    "Off means notifications only while the app is open.",
                color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp),
            )
            Segmented(
                options = listOf(
                    "Off" to BackgroundMode.Off,
                    "Whispers" to BackgroundMode.WhispersOnly,
                    "+ @" to BackgroundMode.WhispersAndMentions,
                ),
                selected = settings.backgroundMode,
                onSelect = onSetBackgroundMode,
            )

            SettingLabel("Image upload (Zipline)")
            Text(
                "Upload images to your own Zipline host and insert them as [img]",
                color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp),
            )
            ToggleRow("Enable upload button", settings.ziplineEnabled, onSetZiplineEnabled)
            var zipUrl by remember { mutableStateOf(settings.ziplineUrl) }
            OutlinedTextField(
                value = zipUrl,
                onValueChange = { zipUrl = it; onSetZiplineUrl(it) },
                placeholder = { Text("https://zipline.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            var zipKey by remember { mutableStateOf(settings.ziplineKey) }
            OutlinedTextField(
                value = zipKey,
                onValueChange = { zipKey = it; onSetZiplineKey(it) },
                placeholder = { Text("API token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            SettingLabel("Privacy — IP killswitch")
            Text(
                "Refuse to connect unless a VPN tunnel is detected, so a dropped VPN can't expose your " +
                    "home IP to KiwiFarms. Detection is fully on-device — your IP is never looked up or " +
                    "sent anywhere. Note: a router-level/whole-network VPN isn't visible to the phone and " +
                    "will read as unprotected.\n\nFor the strongest guarantee, also enable Android's " +
                    "Always-on VPN with \"Block connections without VPN\" (Settings → Network → VPN) — the " +
                    "OS-level block also covers embedded video players and links opened in other apps.",
                color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp),
            )
            ToggleRow("Block connecting without a VPN", settings.ipKillswitch, onSetIpKillswitch)
            Row(
                Modifier.fillMaxWidth().clickable { onCheckVpn() }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (vpnActive) c.accent else c.danger))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (vpnActive) "VPN tunnel detected" else "No VPN tunnel detected",
                    color = c.text2, fontSize = 12.5.sp, modifier = Modifier.weight(1f),
                )
                Text("Check", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EditableList(
    label: String,
    hint: String,
    prefix: String,
    placeholder: String,
    items: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val c = SneedTheme.colors
    SettingLabel(label)
    Text(hint, color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
    items.forEach { item ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (prefix.isNotEmpty()) {
                Text(prefix, color = c.accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
            }
            Text(item, color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Box(Modifier.clip(CircleShape).clickable { onRemove(item) }.padding(4.dp)) {
                Text("✕", color = c.text3, fontSize = 14.sp)
            }
        }
    }
    var input by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(9.dp))
                .background(if (input.isNotBlank()) c.accent else c.surface3)
                .clickable(enabled = input.isNotBlank()) { onAdd(input.trim()); input = "" }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text("Add", color = if (input.isNotBlank()) c.accentOn else c.text3, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text.uppercase(),
        color = SneedTheme.colors.text3, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun <T> Segmented(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    val c = SneedTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(c.inputBg).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (label, value) ->
            val active = value == selected
            Box(
                Modifier.clip(RoundedCornerShape(7.dp)).background(if (active) c.accent else Color.Transparent)
                    .clickable { onSelect(value) }.padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(label, color = if (active) c.accentOn else c.text2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = SneedTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = c.text, fontSize = 14.5.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
