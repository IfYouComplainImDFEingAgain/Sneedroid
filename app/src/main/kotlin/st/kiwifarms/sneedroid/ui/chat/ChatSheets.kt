package st.kiwifarms.sneedroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import st.kiwifarms.sneedroid.core.model.ChatAuthor
import st.kiwifarms.sneedroid.core.model.ChatMessage
import st.kiwifarms.sneedroid.core.model.RoomPermissions
import st.kiwifarms.sneedroid.ui.theme.SneedTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    message: ChatMessage,
    own: Boolean,
    perms: RoomPermissions?,
    onClose: () -> Unit,
    onQuote: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = SneedTheme.colors
    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            // Preview of the message being acted on.
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp)).background(c.bg).padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                BBCodeText(message.raw.ifEmpty { message.message }, color = c.text2, fontSize = 13.sp)
            }
            if (own) {
                if (perms == null || perms.canEditOwn) SheetItem(Icons.Filled.Edit, "Edit message", onClick = onEdit)
                if (perms == null || perms.canDeleteOwn) SheetItem(Icons.Filled.Delete, "Delete message", danger = true, onClick = onDelete)
                SheetItem(Icons.Filled.ContentCopy, "Copy text", onClick = onCopy)
            } else {
                SheetItem(Icons.Filled.FormatQuote, "Quote", onClick = onQuote)
                SheetItem(Icons.Filled.ContentCopy, "Copy text", onClick = onCopy)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileActionsSheet(
    author: ChatAuthor,
    isMuted: Boolean,
    onClose: () -> Unit,
    onMention: () -> Unit,
    onDm: () -> Unit,
    onMute: () -> Unit,
) {
    val c = SneedTheme.colors
    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(colorForName(author.username)), contentAlignment = Alignment.Center) {
                    Text(initials(author.username), color = c.accentOn, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(13.dp))
                Column {
                    Text(author.username, color = colorForName(author.username), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("tap avatar to mention", color = c.text2, fontSize = 12.sp)
                }
            }
            SheetItem(Icons.Filled.AlternateEmail, "Mention", sub = "Insert @${author.username}", onClick = onMention)
            SheetItem(Icons.AutoMirrored.Filled.Chat, "Direct message", sub = "Whisper @${author.username}", onClick = onDm)
            SheetItem(
                if (isMuted) Icons.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                if (isMuted) "Unmute user" else "Mute user",
                sub = if (isMuted) "Show their messages again" else "Hide their messages",
                danger = !isMuted, onClick = onMute,
            )
        }
    }
}

@Composable
private fun SheetItem(icon: ImageVector, label: String, sub: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    val c = SneedTheme.colors
    val tint = if (danger) c.danger else c.text
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = c.text3, fontSize = 12.sp)
        }
    }
}
