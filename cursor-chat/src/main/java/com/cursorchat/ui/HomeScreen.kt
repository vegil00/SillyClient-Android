package com.cursorchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorchat.chat.relativeTime
import com.cursorchat.chat.repoLabel
import com.cursorchat.data.Account
import com.cursorchat.data.AgentPage
import com.cursorchat.data.CloudAgent
import com.cursorchat.data.CursorClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val agents: List<CloudAgent> = emptyList(),
    val nextCursor: String? = null,
    val includeArchived: Boolean = false,
    val error: String? = null,
    val account: Account? = null,
)

class HomeViewModel(private val client: CursorClient) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val archived = _state.value.includeArchived
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val page = withContext(Dispatchers.IO) { client.listAgents(cursor = null, includeArchived = archived) }
                val account = _state.value.account ?: runCatching { withContext(Dispatchers.IO) { client.me() } }.getOrNull()
                _state.update {
                    it.copy(loading = false, agents = page.items, nextCursor = page.nextCursor, account = account ?: it.account, error = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = error.message ?: "加载失败") }
            }
        }
    }

    fun loadMore() {
        val cursor = _state.value.nextCursor ?: return
        if (_state.value.loadingMore) return
        val archived = _state.value.includeArchived
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val page: AgentPage = withContext(Dispatchers.IO) {
                    client.listAgents(cursor = cursor, includeArchived = archived)
                }
                _state.update { current ->
                    current.copy(
                        loadingMore = false,
                        agents = current.agents + page.items.filter { item -> current.agents.none { it.id == item.id } },
                        nextCursor = page.nextCursor,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(loadingMore = false, error = error.message ?: "加载失败") }
            }
        }
    }

    fun toggleArchived() {
        _state.update { it.copy(includeArchived = !it.includeArchived) }
        refresh()
    }

    companion object {
        fun factory(client: CursorClient): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(client) as T
        }
    }
}

@Composable
fun HomeScreen(
    client: CursorClient,
    session: Int,
    account: Account?,
    onAccount: (Account) -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm: HomeViewModel = viewModel(key = "home-$session", factory = HomeViewModel.factory(client))
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.account) { state.account?.let(onAccount) }
    val who = account?.firstName?.takeIf { it.isNotBlank() } ?: account?.email ?: "已连接"
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Cursor", style = MaterialTheme.typography.headlineLarge)
                    Text(who, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = Ink) }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Ink) }
            }
            TextButton(onClick = vm::toggleArchived, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(if (state.includeArchived) "隐藏已归档" else "显示已归档", color = Muted)
            }
            when {
                state.loading && state.agents.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
                state.error != null && state.agents.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    ErrorLine(state.error)
                    PrimaryButton("重试", onClick = vm::refresh)
                }
                state.agents.isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("还没有对话。", style = MaterialTheme.typography.headlineSmall)
                    Text("点下面开始一次云端 Agent 对话。可以绑定仓库，也可以只聊天。", color = Muted, modifier = Modifier.padding(top = 8.dp))
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.agents, key = { it.id }) { agent ->
                        AgentCard(agent, onClick = { onOpen(agent.id) })
                    }
                    if (state.nextCursor != null) {
                        item {
                            TextButton(onClick = vm::loadMore, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth()) {
                                Text(if (state.loadingMore) "正在加载…" else "加载更多", color = Ink)
                            }
                        }
                    }
                    if (state.error != null) item { ErrorLine(state.error) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNew,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            containerColor = Accent,
            contentColor = AccentInk,
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("新对话")
        }
    }
}

@Composable
private fun AgentCard(agent: CloudAgent, onClick: () -> Unit) {
    GlassCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusPill(agent.status)
            Text(relativeTime(agent.updatedAt ?: agent.createdAt), color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            agent.name,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 14.dp),
        )
        val repo = agent.repos.firstOrNull()?.url?.let(::repoLabel)
        if (!repo.isNullOrBlank()) {
            Text(repo, color = Muted, modifier = Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
