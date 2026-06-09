@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package st.kiwifarms.sneedroid.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import kotlinx.coroutines.launch
import st.kiwifarms.sneedroid.data.CustomMacro
import st.kiwifarms.sneedroid.ui.theme.SneedTheme
import java.util.UUID

private val IMG_RE = Regex("""(?is)\[img\](.*?)\[/img\]""")

/**
 * The custom-macro popover: a wrapped grid of user macros (image icon or text label) plus an Add
 * tile. Tap a macro to insert it; long-press to edit/delete.
 */
@Composable
fun MacroPanel(
    macros: List<CustomMacro>,
    onPick: (CustomMacro) -> Unit,
    onAdd: () -> Unit,
    onEdit: (CustomMacro) -> Unit,
) {
    val c = SneedTheme.colors
    val ctx = LocalContext.current
    val loader = EmoteTable.loader ?: ctx.imageLoader
    Column(Modifier.fillMaxWidth().background(c.surface2)) {
        if (macros.isEmpty()) {
            Text(
                "No custom emotes yet — tap + to add one",
                color = c.text3, fontSize = 12.sp,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
            )
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            macros.forEach { m ->
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(c.surface)
                        .combinedClickable(onClick = { onPick(m) }, onLongClick = { onEdit(m) }),
                    contentAlignment = Alignment.Center,
                ) {
                    if (m.iconUrl != null) {
                        AsyncImage(model = m.iconUrl, contentDescription = m.label, imageLoader = loader, modifier = Modifier.size(34.dp))
                    } else {
                        Text(
                            m.label, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 3.dp),
                        )
                    }
                }
            }
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(c.inputBg).clickable { onAdd() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add custom emote", tint = c.text2, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * Add/edit form for a [CustomMacro]. A macro is either a text/BBCode snippet or an image (`[img]`):
 * for images the user can pick from the device (uploaded via [uploader] → a hosted URL) or paste a
 * URL, and optionally use that image as the grid icon. [onDelete] is non-null only when editing.
 */
@Composable
fun MacroEditDialog(
    initial: CustomMacro?,
    uploader: (suspend (Uri) -> Result<String>)?,
    onDismiss: () -> Unit,
    onSave: (CustomMacro) -> Unit,
    onDelete: ((String) -> Unit)?,
) {
    val c = SneedTheme.colors
    val scope = rememberCoroutineScope()
    val initImg = initial?.insert?.let { IMG_RE.find(it)?.groupValues?.get(1)?.trim() }

    var label by remember { mutableStateOf(initial?.label ?: "") }
    var isImage by remember { mutableStateOf(initImg != null) }
    var insertText by remember { mutableStateOf(if (initImg == null) initial?.insert ?: "" else "") }
    var imageUrl by remember { mutableStateOf(initImg ?: "") }
    var useImageAsIcon by remember { mutableStateOf(initial?.iconUrl != null || initial == null) }
    var autoSend by remember { mutableStateOf(initial?.autoSend ?: false) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && uploader != null) {
            uploading = true; error = null
            scope.launch {
                uploader(uri)
                    .onSuccess { url -> imageUrl = url; uploading = false }
                    .onFailure { error = it.message ?: "Upload failed"; uploading = false }
            }
        }
    }

    val valid = label.isNotBlank() && (if (isImage) imageUrl.isNotBlank() else insertText.isNotBlank())

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(330.dp).clip(RoundedCornerShape(16.dp)).background(c.surface)
                .padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(if (initial == null) "Add custom emote" else "Edit custom emote", color = c.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(14.dp))

            FieldLabel("Display text / icon")
            TextInput(label, { label = it }, "e.g. :pepe: or 👍")
            Spacer(Modifier.height(14.dp))

            // What the macro inserts: a text/BBCode snippet, or an image tag.
            FieldLabel("Inserts")
            Row(Modifier.clip(RoundedCornerShape(9.dp)).background(c.inputBg).padding(2.dp)) {
                ModeSeg("Text / BBCode", active = !isImage) { isImage = false }
                ModeSeg("Image", active = isImage) { isImage = true }
            }
            Spacer(Modifier.height(8.dp))

            if (isImage) {
                TextInput(imageUrl, { imageUrl = it }, "https://image.url")
                if (uploader != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(9.dp)).background(c.inputBg)
                            .clickable(enabled = !uploading) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = c.text2, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (uploading) "Uploading…" else "Pick from device", color = c.text2, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                CheckRow("Use image as icon", useImageAsIcon) { useImageAsIcon = it }
            } else {
                TextInput(insertText, { insertText = it }, "[b]hi[/b] or any text", minLines = 2)
            }
            Spacer(Modifier.height(10.dp))

            CheckRow("Auto-send when input box is empty", autoSend) { autoSend = it }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = c.danger, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null && initial != null) {
                    Box(Modifier.clip(RoundedCornerShape(9.dp)).clickable { onDelete(initial.id) }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text("Delete", color = c.danger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextBtn("Cancel", filled = false, enabled = true) { onDismiss() }
                Spacer(Modifier.width(8.dp))
                TextBtn("Save", filled = true, enabled = valid) {
                    val insert = if (isImage) "[img]${imageUrl.trim()}[/img]" else insertText
                    val icon = if (isImage && useImageAsIcon) imageUrl.trim() else null
                    onSave(
                        CustomMacro(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            label = label.trim(), insert = insert, iconUrl = icon, autoSend = autoSend,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = SneedTheme.colors.text3, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp))
}

@Composable
private fun TextInput(value: String, onChange: (String) -> Unit, placeholder: String, minLines: Int = 1) {
    val c = SneedTheme.colors
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(c.inputBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = c.text, fontSize = 14.sp),
            cursorBrush = SolidColor(c.accent),
            minLines = minLines,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = c.text3, fontSize = 14.sp)
                inner()
            },
        )
    }
}

@Composable
private fun ModeSeg(label: String, active: Boolean, onClick: () -> Unit) {
    val c = SneedTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(7.dp)).background(if (active) c.accent else Color.Transparent)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (active) c.accentOn else c.text3, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val c = SneedTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onToggle(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(5.dp))
                .background(if (checked) c.accent else c.inputBg),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = c.accentOn, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = c.text, fontSize = 13.sp)
    }
}

@Composable
private fun TextBtn(label: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = SneedTheme.colors
    val alpha = if (enabled) 1f else 0.4f
    Box(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (filled) c.accent.copy(alpha = alpha) else Color.Transparent)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, color = (if (filled) c.accentOn else c.text2).copy(alpha = alpha), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
