package com.cursorchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cursorchat.data.Account

@Composable
fun SettingsScreen(account: Account?, onBack: () -> Unit, onSignOut: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Ink) }
            Text("设置", style = MaterialTheme.typography.headlineSmall)
        }
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(account?.email ?: "已连接", style = MaterialTheme.typography.titleMedium)
                val keyName = account?.apiKeyName
                if (!keyName.isNullOrBlank()) Text(keyName, color = Muted)
                Text("退出后会从这台手机删除 API Key。云端对话不会被删除。", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        PrimaryButton("退出登录", onClick = onSignOut)
    }
}
