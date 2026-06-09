package st.kiwifarms.sneedroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import st.kiwifarms.sneedroid.data.WhisperLine
import st.kiwifarms.sneedroid.ui.theme.SneedTheme

/** Full-screen private-message thread with one partner. */
@Composable
fun WhisperWindow(
    partnerName: String,
    lines: List<WhisperLine>,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
) {
    val c = SneedTheme.colors
    var draft by remember { mutableStateOf("") }

    // Header clears the status bar; the input row clears the nav buttons / keyboard
    // (bottom = max of nav bar and IME).
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))) {
        // Header
        Row(
            Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = c.text2, modifier = Modifier.size(22.dp))
            }
            Box(Modifier.size(32.dp).clip(CircleShape).background(colorForName(partnerName)), contentAlignment = Alignment.Center) {
                Text(initials(partnerName), color = c.accentOn, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(partnerName, color = colorForName(partnerName), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("direct message", color = c.text3, fontSize = 11.sp)
            }
        }

        // Thread
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }
        if (lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                Text(
                    "This is the start of your conversation with $partnerName.",
                    color = c.text3, fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            ) {
                items(lines) { line -> WhisperBubble(line) }
            }
        }

        // Composer
        Row(
            Modifier.fillMaxWidth().background(c.surface).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(c.inputBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = draft, onValueChange = { draft = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) Text("Message $partnerName…", color = c.text3, fontSize = 14.sp)
                        inner()
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            val can = draft.isNotBlank()
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(if (can) c.accent else c.surface3)
                    .clickable(enabled = can) { onSend(draft.trim()); draft = "" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (can) c.accentOn else c.text3, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun WhisperBubble(line: WhisperLine) {
    val c = SneedTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (line.fromMe) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(14.dp))
                .background(if (line.fromMe) c.accent.copy(alpha = 0.16f) else c.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            BBCodeText(line.raw, color = c.text, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
