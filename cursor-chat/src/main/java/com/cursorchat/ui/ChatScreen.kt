package com.cursorchat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorchat.chat.ChatMessage
import com.cursorchat.chat.ChatReducer
import com.cursorchat.chat.ChatUiState
import com.cursorchat.chat.LiveTool
import com.cursorchat.chat.MarkdownBlock
import com.cursorchat.chat.Role
import com.cursorchat.chat.repoLabel
import com.cursorchat.chat.splitBlocks
import com.cursorchat.data.CursorClient
import com.cursorchat.data.CursorException
import com.cursorchat.data.PromptRules
import com.cursorchat.data.Status
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val client: CursorClient,
    private val agentId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private var streamJob: Job? = null

    @Volatile private var streamToken = 0

    @Volatile private var loadToken = 0
    private var lastEventId: String? = null

    init {
        refresh()
    }

    fun updateDraft(value: String) {
        _state.update { it.copy(draft = value) }
    }

    fun refresh() {
        val ticket = ++loadToken
        viewModelScope.launch {
            _state.update { it.copy(loading = it.history.isEmpty(), error = null) }
            try {
                val agent = withContext(Dispatchers.IO) { client.getAgent(agentId) }
                val history = withContext(Dispatchers.IO) { client.conversation(agentId) }
                val run = agent.latestRunId?.let { id ->
                    runCatching { withContext(Dispatchers.IO) { client.getRun(agentId, id) } }.getOrNull()
                }
                _state.update { current ->
                    if (ticket != loadToken) current
                    else ChatReducer.loaded(agent, history, run).copy(draft = current.draft)
                }
                if (ticket == loadToken) {
                    if (_state.value.runActive) startStream(resetCursor = true) else stopStream()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (ticket != loadToken) return@launch
                _state.update { it.copy(loading = false, error = error.message ?: "加载失败") }
            }
        }
    }

    fun send() {
        val snapshot = _state.value
        if (snapshot.sending || snapshot.archived) return
        val text = try {
            PromptRules.require(snapshot.draft)
        } catch (error: CursorException) {
            _state.update { it.copy(error = error.message) }
            return
        }
        val id = "local:user:${UUID.randomUUID()}"
        val active = snapshot.runActive
        _state.update { ChatReducer.beginSend(it, id, text) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { deliver(active, text) }
                _state.update { ChatReducer.onFollowUp(it, result) }
                startStream(resetCursor = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { ChatReducer.failSend(it, id, text, error.message ?: "发送失败") }
            }
        }
    }

    fun stop() {
        val runId = _state.value.runId ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { client.cancel(agentId, runId) }
                markStopped()
            } catch (error: CancellationException) {
                throw error
            } catch (error: CursorException) {
                if (error.status == 409) markStopped() else _state.update { it.copy(error = error.message) }
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "无法停止") }
            }
        }
    }

    fun archive(onDone: () -> Unit) = mutate({ client.archive(agentId) }, onDone)

    fun delete(onDone: () -> Unit) = mutate({ client.delete(agentId) }, onDone)

    fun unarchive() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { client.unarchive(agentId) }
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "无法取消归档") }
            }
        }
    }

    private fun markStopped() {
        _state.update {
            it.copy(
                runStatus = "CANCELLED",
                agentStatus = if (it.agentStatus == "ARCHIVED") "ARCHIVED" else "IDLE",
            )
        }
    }

    private fun mutate(block: () -> Unit, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                onDone()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "操作失败") }
            }
        }
    }

    private fun deliver(active: Boolean, text: String) = try {
        client.followUp(agentId, text, steer = active)
    } catch (error: CursorException) {
        when {
            active && (error.status == 403 || error.code == "feature_unavailable") ->
                client.followUp(agentId, text, steer = false)
            !active && error.status == 409 -> client.followUp(agentId, text, steer = true)
            else -> throw error
        }
    }

    private fun stopStream() {
        streamToken += 1
        streamJob?.cancel()
        streamJob = null
    }

    private fun startStream(resetCursor: Boolean) {
        if (resetCursor) lastEventId = null
        val token = ++streamToken
        streamJob?.cancel()
        streamJob = viewModelScope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive && token == streamToken) {
                var received = false
                try {
                    client.streamAgent(agentId, lastEventId) { event ->
                        if (token != streamToken) return@streamAgent
                        if (!event.id.isNullOrBlank()) lastEventId = event.id
                        received = true
                        val before = _state.value.runStatus
                        _state.update { current ->
                            if (token != streamToken) current else ChatReducer.onStream(current, event)
                        }
                        val after = _state.value.runStatus
                        if (Status.isTerminalRun(after) && !Status.isTerminalRun(before)) {
                            viewModelScope.launch { if (token == streamToken) reloadQuietly() }
                        }
                    }
                    if (token != streamToken || !_state.value.runActive) break
                    failures = if (received) 0 else failures + 1
                    if (failures >= 3) break
                    delay(700)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: CursorException) {
                    if (token != streamToken) break
                    when {
                        error.status == 410 -> {
                            reloadQuietly()
                            break
                        }
                        error.status == 409 -> break
                        error.status == 400 && error.code == "invalid_last_event_id" && failures == 0 -> {
                            lastEventId = null
                            failures += 1
                        }
                        failures >= 2 -> {
                            _state.update { it.copy(error = error.message) }
                            break
                        }
                        else -> {
                            failures += 1
                            delay(800L * failures)
                        }
                    }
                } catch (error: Exception) {
                    if (token != streamToken) break
                    failures += 1
                    if (failures >= 3) {
                        _state.update { it.copy(error = error.message ?: "实时连接中断") }
                        break
                    }
                    delay(800L * failures)
                }
            }
        }
    }

    private suspend fun reloadQuietly() {
        try {
            val agent = withContext(Dispatchers.IO) { client.getAgent(agentId) }
            val history = withContext(Dispatchers.IO) { client.conversation(agentId) }
            val run = agent.latestRunId?.let { id ->
                runCatching { withContext(Dispatchers.IO) { client.getRun(agentId, id) } }.getOrNull()
            }
            _state.update { ChatReducer.withServer(it, agent, history, run) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep the live transcript if the refresh fails.
        }
    }

    companion object {
        fun factory(client: CursorClient, agentId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(client, agentId) as T
        }
    }
}

@Composable
fun ChatScreen(client: CursorClient, agentId: String, session: Int, onBack: () -> Unit) {
    val vm: ChatViewModel = viewModel(key = "chat-$session-$agentId", factory = ChatViewModel.factory(client, agentId))
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val history = state.visibleHistory
    val extra = (if (state.thinking.isNotBlank()) 1 else 0) + state.liveTools.size + (if (state.liveText.isNotBlank() || state.runActive) 1 else 0)
    LaunchedEffect(history.size, state.liveText.length, state.liveTools.size, state.thinking.length) {
        val total = history.size + extra
        if (follow && total > 0) listState.scrollToItem(total - 1)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Ink) }
            Column(Modifier.weight(1f)) {
                Text(state.agentName.ifBlank { "对话" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                val detail = listOfNotNull(Status.label(state.agentStatus).ifBlank { null }, state.repoLabel).joinToString(" · ")
                if (detail.isNotBlank()) Text(detail, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.runActive && state.runId != null) {
                IconButton(onClick = vm::stop) { Icon(Icons.Filled.Close, contentDescription = "停止", tint = Danger) }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = Ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("复制标识") }, onClick = { menu = false; copyText(context, agentId) })
                    if (!state.agentUrl.isNullOrBlank()) {
                        DropdownMenuItem(text = { Text("在浏览器打开") }, onClick = { menu = false; openHttps(context, state.agentUrl) })
                    }
                    if (state.archived) {
                        DropdownMenuItem(text = { Text("取消归档") }, onClick = { menu = false; vm.unarchive() })
                    } else {
                        DropdownMenuItem(text = { Text("归档") }, onClick = { menu = false; vm.archive(onBack) })
                    }
                    DropdownMenuItem(text = { Text("删除", color = Danger) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        if (state.branches.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.branches.forEach { branch ->
                    val label = branch.branch ?: branch.repoUrl?.let(::repoLabel) ?: "分支"
                    TextButton(onClick = { openHttps(context, branch.prUrl ?: branch.repoUrl?.let { "https://$it" }) }) {
                        Text(if (branch.prUrl != null) "$label · PR" else label, color = Accent)
                    }
                }
            }
        }
        when {
            state.loading && history.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (history.isEmpty() && state.liveText.isBlank() && !state.loading) {
                    item { Text("这条对话还没有消息。", color = Muted) }
                }
                items(history, key = { it.id }) { message -> MessageBubble(message) }
                if (state.thinking.isNotBlank()) {
                    item { Text(state.thinking.takeLast(180), color = Muted, style = MaterialTheme.typography.bodyMedium) }
                }
                items(state.liveTools, key = { it.callId }) { tool -> ToolLine(tool) }
                if (state.liveText.isNotBlank()) {
                    item { MessageBubble(ChatMessage("live", Role.ASSISTANT, state.liveText)) }
                } else if (state.runActive) {
                    item { Text("正在工作…", color = Accent) }
                }
                if (!state.error.isNullOrBlank()) item { ErrorLine(state.error) }
            }
        }
        if (state.archived) {
            Text(
                "已归档，不能继续发送。可以先取消归档。",
                color = Muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding(),
            )
        } else {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = vm::updateDraft,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (state.runActive) "插一句，Agent 会接着做" else "继续这个对话", color = Muted) },
                    colors = fieldColors(),
                    maxLines = 6,
                )
                IconButton(onClick = vm::send, enabled = state.draft.isNotBlank() && !state.sending) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = if (state.draft.isNotBlank()) Accent else Muted)
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这则对话？") },
            text = { Text("云端 Agent 和它的对话记录会被永久删除。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("删除", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val mine = message.role == Role.USER
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            color = if (mine) SurfaceHigh else androidx.compose.ui.graphics.Color.Transparent,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            modifier = if (mine) Modifier.fillMaxWidth(0.86f) else Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(if (mine) 14.dp else 2.dp)) {
                if (!mine && message.role == Role.NOTE) Text("记录", color = Muted, fontSize = 12.sp)
                SelectionContainer { MessageBody(message.text) }
            }
        }
    }
}

@Composable
private fun MessageBody(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        splitBlocks(text).forEach { block ->
            when (block) {
                is MarkdownBlock.Text -> Text(block.text.trimEnd(), style = MaterialTheme.typography.bodyLarge)
                is MarkdownBlock.Code -> Surface(color = androidx.compose.ui.graphics.Color(0xFF10140F), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                    Text(
                        block.text.ifBlank { " " },
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolLine(tool: LiveTool) {
    val status = if (tool.status == "completed") "完成" else "进行中"
    Text("${tool.name} · $status", color = Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
