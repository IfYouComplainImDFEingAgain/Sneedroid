package st.kiwifarms.sneedroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import st.kiwifarms.sneedroid.ui.theme.SneedTheme

/** App-wide error toast: shows the latest reported error briefly, on any screen. */
@Composable
fun BoxScope.AppErrorToast(toasts: SharedFlow<String>) {
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { toasts.collect { message = it } }
    LaunchedEffect(message) { if (message != null) { delay(4000); message = null } }
    val m = message ?: return
    Row(
        Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 28.dp)
            .clip(RoundedCornerShape(22.dp)).background(SneedTheme.colors.danger)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(m, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
