package st.kiwifarms.sneedroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import st.kiwifarms.sneedroid.data.DebugFrame
import st.kiwifarms.sneedroid.ui.theme.SneedTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hms = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/** Flatten the frame log to plain text for the clipboard: one "time  TYPE  raw" line each. */
private fun framesToText(frames: List<DebugFrame>): String =
    frames.joinToString("\n") { "${hms.format(Date(it.time))}  ${it.type}  ${it.raw}" }

/** Full-screen log of non-chat WebSocket frames (newest at bottom). */
@Composable
fun DebugFramesScreen(frames: List<DebugFrame>, onClear: () -> Unit, onClose: () -> Unit) {
    val c = SneedTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = c.text2, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("Debug · WS frames", color = c.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("frames & errors (${frames.size})", color = c.text3, fontSize = 11.sp)
            }
            val clipboard = LocalClipboardManager.current
            val context = LocalContext.current
            Text(
                "Copy all", color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).clickable {
                    if (frames.isEmpty()) {
                        Toast.makeText(context, "Nothing to copy", Toast.LENGTH_SHORT).show()
                    } else {
                        clipboard.setText(AnnotatedString(framesToText(frames)))
                        Toast.makeText(context, "Copied ${frames.size} frames", Toast.LENGTH_SHORT).show()
                    }
                }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Text(
                "Clear", color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).clickable(onClick = onClear).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        if (frames.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No non-message frames yet.", color = c.text3, fontSize = 13.sp)
            }
        } else {
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(frames) { f -> FrameRow(f) }
                }
            }
        }
    }
}

@Composable
private fun FrameRow(f: DebugFrame) {
    val c = SneedTheme.colors
    val chip = if (f.type == "Unknown" || f.type == "error") c.danger else c.accent
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(chip.copy(alpha = 0.16f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                Text(f.type, color = chip, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(hms.format(Date(f.time)), color = c.text3, fontSize = 11.sp)
        }
        Spacer(Modifier.size(3.dp))
        Text(
            f.raw, color = c.text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
        )
    }
}
