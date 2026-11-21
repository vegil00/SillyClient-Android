package com.cursorchat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorchat.data.RepoUrl
import com.cursorchat.data.Status

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Surface,
        border = BorderStroke(1.dp, Line),
        content = { Column(Modifier.padding(20.dp), content = content) },
    )
}

@Composable
fun StatusPill(status: String) {
    val active = Status.isActive(status)
    val color = when {
        active -> Accent
        status == "ERROR" || status == "EXPIRED" -> Danger
        status == "ARCHIVED" -> Muted
        else -> Ink
    }
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.14f)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(Status.label(status).ifBlank { "未知" }, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk, disabledContainerColor = SurfaceHigh, disabledContentColor = Muted),
    ) {
        Text(if (busy) "请稍候…" else text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ErrorLine(message: String?) {
    if (!message.isNullOrBlank()) {
        Text(message, color = Danger, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
    }
}

fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("cursor-chat", text))
}

fun openHttps(context: Context, raw: String?) {
    val url = runCatching { RepoUrl.optional(raw) }.getOrNull() ?: return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
