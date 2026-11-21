package com.cursorchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorchat.CursorGraph
import com.cursorchat.data.Account
import com.cursorchat.data.ApiKeyRules
import com.cursorchat.data.CursorClient
import com.cursorchat.data.UrlConnectionTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectViewModel(private val graph: CursorGraph) : ViewModel() {
    var key by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun connect(onReady: (Account) -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val normalized = ApiKeyRules.require(key)
                val account = withContext(Dispatchers.IO) {
                    CursorClient(UrlConnectionTransport(normalized)).me()
                }
                graph.keyStore.save(normalized)
                onReady(account)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: "连接失败"
            } finally {
                busy = false
            }
        }
    }

    companion object {
        fun factory(graph: CursorGraph): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ConnectViewModel(graph) as T
        }
    }
}

@Composable
fun ConnectScreen(graph: CursorGraph, session: Int, onConnected: (Account) -> Unit) {
    val vm: ConnectViewModel = viewModel(key = "connect-$session", factory = ConnectViewModel.factory(graph))
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Cursor", style = androidx.compose.material3.MaterialTheme.typography.headlineLarge)
        Text("在手机上继续和 Cloud Agent 对话。", color = Muted, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("API Key", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text(
                    "在 Cursor Dashboard → API Keys 创建。密钥只用 Android 密钥库保存在这台手机上，不会写入备份。",
                    color = Muted,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = vm.key,
                    onValueChange = { vm.key = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("crsr_…", color = Muted) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = fieldColors(),
                )
                ErrorLine(vm.error)
                PrimaryButton(text = "连接", busy = vm.busy, enabled = vm.key.isNotBlank(), onClick = { vm.connect(onConnected) })
            }
        }
        Text(
            "这是独立的 Android 客户端，连接的是 Cursor Cloud Agents，不是桌面里的本地对话。离开聊天页不会停止云端运行。",
            color = Muted,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
    focusedBorderColor = Accent,
    unfocusedBorderColor = Line,
    cursorColor = Accent,
    focusedContainerColor = SurfaceHigh,
    unfocusedContainerColor = SurfaceHigh,
)
