package com.cursorchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorchat.chat.repoLabel
import com.cursorchat.data.ConversationMode
import com.cursorchat.data.CursorClient
import com.cursorchat.data.ModelOption
import com.cursorchat.data.RecentRepos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NewChatUiState(
    val prompt: String = "",
    val repo: String = "",
    val ref: String = "",
    val mode: String = ConversationMode.AGENT,
    val autoPr: Boolean = false,
    val modelId: String? = null,
    val models: List<ModelOption> = emptyList(),
    val recent: List<String> = emptyList(),
    val repos: List<String> = emptyList(),
    val reposLoading: Boolean = false,
    val showRepos: Boolean = false,
    val showModels: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

class NewChatViewModel(
    private val client: CursorClient,
    private val recents: RecentRepos,
) : ViewModel() {
    private val _state = MutableStateFlow(NewChatUiState(recent = recents.load()))
    val state: StateFlow<NewChatUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val models = runCatching { withContext(Dispatchers.IO) { client.models() } }.getOrDefault(emptyList())
            _state.update { it.copy(models = models) }
        }
    }

    fun update(block: (NewChatUiState) -> NewChatUiState) {
        _state.update(block)
    }

    fun loadRepos() {
        if (_state.value.reposLoading) return
        _state.update { it.copy(showRepos = true, reposLoading = it.repos.isEmpty(), error = null) }
        if (_state.value.repos.isNotEmpty()) return
        viewModelScope.launch {
            try {
                val repos = withContext(Dispatchers.IO) { client.repositories() }
                _state.update { it.copy(reposLoading = false, repos = repos) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(reposLoading = false, showRepos = false, error = error.message ?: "仓库列表暂时不可用，可以直接粘贴地址") }
            }
        }
    }

    fun create(onCreated: (String) -> Unit) {
        val snapshot = _state.value
        if (snapshot.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val created = withContext(Dispatchers.IO) {
                    client.createAgent(
                        prompt = snapshot.prompt,
                        repoUrl = snapshot.repo,
                        ref = snapshot.ref,
                        modelId = snapshot.modelId,
                        mode = snapshot.mode,
                        autoCreatePr = snapshot.autoPr,
                    )
                }
                runCatching { recents.remember(snapshot.repo) }
                _state.update { it.copy(busy = false) }
                onCreated(created.agent.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, error = error.message ?: "无法开始对话") }
            }
        }
    }

    companion object {
        fun factory(client: CursorClient, recents: RecentRepos): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NewChatViewModel(client, recents) as T
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewChatScreen(
    client: CursorClient,
    recents: RecentRepos,
    session: Int,
    onClose: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val vm: NewChatViewModel = viewModel(key = "new-$session", factory = NewChatViewModel.factory(client, recents))
    val state by vm.state.collectAsStateWithLifecycle()
    val modelLabel = state.models.firstOrNull { it.id == state.modelId }?.displayName ?: "账号默认模型"
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("新对话", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Ink) }
        }
        OutlinedTextField(
            value = state.prompt,
            onValueChange = { value -> vm.update { it.copy(prompt = value) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            placeholder = { Text("要 Agent 做什么？", color = Muted) },
            colors = fieldColors(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.mode == ConversationMode.AGENT,
                onClick = { vm.update { it.copy(mode = ConversationMode.AGENT) } },
                label = { Text("直接做") },
            )
            FilterChip(
                selected = state.mode == ConversationMode.PLAN,
                onClick = { vm.update { it.copy(mode = ConversationMode.PLAN) } },
                label = { Text("先规划") },
            )
        }
        TextButton(onClick = { vm.update { it.copy(showModels = true) } }) { Text(modelLabel, color = Ink) }
        OutlinedTextField(
            value = state.repo,
            onValueChange = { value -> vm.update { it.copy(repo = value, autoPr = if (value.isBlank()) false else it.autoPr) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("仓库地址，可留空") },
            singleLine = true,
            colors = fieldColors(),
        )
        if (state.recent.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.recent.forEach { url ->
                    FilterChip(selected = state.repo == url, onClick = { vm.update { it.copy(repo = url) } }, label = { Text(repoLabel(url)) })
                }
            }
        }
        TextButton(onClick = vm::loadRepos) { Text("从已连接的 GitHub 选择", color = Muted) }
        OutlinedTextField(
            value = state.ref,
            onValueChange = { value -> vm.update { it.copy(ref = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("分支，默认仓库默认分支") },
            singleLine = true,
            colors = fieldColors(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("完成后自动开 PR")
                Text("需要填写仓库地址。", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(
                checked = state.autoPr,
                onCheckedChange = { checked -> vm.update { it.copy(autoPr = checked) } },
                enabled = state.repo.isNotBlank(),
                colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = AccentInk),
            )
        }
        ErrorLine(state.error)
        PrimaryButton(
            text = "开始",
            busy = state.busy,
            enabled = state.prompt.isNotBlank(),
            onClick = { vm.create(onCreated) },
        )
    }
    if (state.showModels) {
        AlertDialog(
            onDismissRequest = { vm.update { it.copy(showModels = false) } },
            confirmButton = {},
            title = { Text("模型") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { vm.update { it.copy(modelId = null, showModels = false) } }) { Text("账号默认模型", color = Ink) }
                    state.models.forEach { model ->
                        TextButton(onClick = { vm.update { it.copy(modelId = model.id, showModels = false) } }) { Text(model.displayName, color = Ink) }
                    }
                }
            },
        )
    }
    if (state.showRepos) {
        AlertDialog(
            onDismissRequest = { vm.update { it.copy(showRepos = false) } },
            confirmButton = { TextButton(onClick = { vm.update { it.copy(showRepos = false) } }) { Text("关闭") } },
            title = { Text("GitHub 仓库") },
            text = {
                when {
                    state.reposLoading -> CircularProgressIndicator(color = Accent)
                    state.repos.isEmpty() -> Text("没有列出仓库。服务账号或未安装 GitHub App 时列表会是空的，可以直接粘贴地址。", color = Muted)
                    else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        state.repos.forEach { url ->
                            TextButton(onClick = { vm.update { it.copy(repo = url, showRepos = false) } }) { Text(repoLabel(url), color = Ink) }
                        }
                    }
                }
            },
        )
    }
}
