package st.kiwifarms.sneedroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import st.kiwifarms.sneedroid.core.model.ChatUser
import st.kiwifarms.sneedroid.ui.theme.SneedTheme

/** Start a whisper by picking a user, with autocomplete over the live roster. */
@Composable
fun NewWhisperDialog(
    roster: List<ChatUser>,
    onDismiss: () -> Unit,
    onPick: (Long, String) -> Unit,
) {
    val c = SneedTheme.colors
    var query by remember { mutableStateOf("") }

    val matches = remember(query, roster) {
        val base = roster.distinctBy { it.id }
        if (query.isBlank()) {
            base.sortedBy { it.username.lowercase() }.take(8)
        } else {
            base.filter { it.username.contains(query, ignoreCase = true) }
                .sortedWith(
                    compareByDescending<ChatUser> { it.username.startsWith(query, ignoreCase = true) }
                        .thenBy { it.username.lowercase() },
                )
                .take(8)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New whisper") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search users…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                if (roster.isEmpty()) {
                    Text("No users online yet.", color = c.text3, fontSize = 13.sp)
                } else if (matches.isEmpty()) {
                    Text("No matching users.", color = c.text3, fontSize = 13.sp)
                } else {
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        matches.forEach { u ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(u.id, u.username) }.padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(30.dp).clip(CircleShape).background(colorForName(u.username)), contentAlignment = Alignment.Center) {
                                    Text(initials(u.username), color = c.accentOn, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(u.username, color = c.text, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
