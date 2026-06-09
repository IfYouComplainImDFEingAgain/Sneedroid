package st.kiwifarms.sneedroid.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import st.kiwifarms.sneedroid.ui.theme.SneedTheme

private fun hsvColor(h: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))

private fun colorToHex(color: Color): String = "#%06X".format(0xFFFFFF and color.toArgb())

/** Parse #RGB / #RRGGBB into HSV components, or null if malformed. */
private fun hexToHsv(text: String): FloatArray? {
    val h = text.trim().removePrefix("#")
    val full = when (h.length) {
        3 -> h.map { "$it$it" }.joinToString("")
        6 -> h
        else -> return null
    }
    val rgb = full.toLongOrNull(16) ?: return null
    return FloatArray(3).also { android.graphics.Color.colorToHSV((0xFF000000 or rgb).toInt(), it) }
}

/**
 * A compact HSV color picker dialog: a saturation/value square, a hue slider, an editable hex field,
 * and a live preview. [onPick] receives a canonical `#RRGGBB` string to drop into a [color] tag.
 */
@Composable
fun CustomColorDialog(initial: Color, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = SneedTheme.colors
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    var hexText by remember { mutableStateOf(colorToHex(hsvColor(start[0], start[1], start[2]))) }

    fun syncHex() { hexText = colorToHex(hsvColor(hue, sat, value)) }
    val current = hsvColor(hue, sat, value)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(300.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(16.dp),
        ) {
            Text("Custom color", color = c.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))

            // Saturation (x) / Value (y) square.
            Box(
                Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(10.dp))
                    .pointerInput(Unit) {
                        detectTapGestures { o ->
                            sat = (o.x / size.width).coerceIn(0f, 1f)
                            value = (1f - o.y / size.height).coerceIn(0f, 1f)
                            syncHex()
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { ch, _ ->
                            sat = (ch.position.x / size.width).coerceIn(0f, 1f)
                            value = (1f - ch.position.y / size.height).coerceIn(0f, 1f)
                            syncHex()
                            ch.consume()
                        }
                    },
            ) {
                Canvas(Modifier.matchParentSize()) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hsvColor(hue, 1f, 1f))))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val cx = sat * size.width
                    val cy = (1f - value) * size.height
                    drawCircle(Color.White, radius = 10f, center = Offset(cx, cy), style = Stroke(3f))
                    drawCircle(Color.Black, radius = 10f, center = Offset(cx, cy), style = Stroke(1.5f))
                }
            }
            Spacer(Modifier.height(14.dp))

            // Hue slider.
            Box(
                Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp))
                    .pointerInput(Unit) {
                        detectTapGestures { o -> hue = (o.x / size.width).coerceIn(0f, 1f) * 360f; syncHex() }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { ch, _ ->
                            hue = (ch.position.x / size.width).coerceIn(0f, 1f) * 360f
                            syncHex(); ch.consume()
                        }
                    },
            ) {
                Canvas(Modifier.matchParentSize()) {
                    drawRect(Brush.horizontalGradient((0..6).map { hsvColor(it * 60f, 1f, 1f) }))
                    val x = (hue / 360f) * size.width
                    val r = size.height / 2f - 2f
                    drawCircle(Color.White, radius = r, center = Offset(x.coerceIn(r, size.width - r), size.height / 2f), style = Stroke(3f))
                }
            }
            Spacer(Modifier.height(14.dp))

            // Preview swatch + editable hex.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(current))
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(c.inputBg)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    BasicTextField(
                        value = hexText,
                        onValueChange = { t ->
                            hexText = t
                            hexToHsv(t)?.let { hue = it[0]; sat = it[1]; value = it[2] }
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = c.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogBtn("Cancel", filled = false) { onDismiss() }
                Spacer(Modifier.width(8.dp))
                DialogBtn("Apply", filled = true) { onPick(colorToHex(hsvColor(hue, sat, value))) }
            }
        }
    }
}

@Composable
private fun DialogBtn(label: String, filled: Boolean, onClick: () -> Unit) {
    val c = SneedTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (filled) c.accent else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (filled) c.accentOn else c.text2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
