package com.cursorchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorchat.CursorGraph
import com.cursorchat.data.Account
import java.util.ArrayDeque

sealed interface Route {
    data object Connect : Route
    data object Home : Route
    data object NewChat : Route
    data object Settings : Route
    data class Chat(val agentId: String) : Route
}

class AppViewModel(private val graph: CursorGraph) : ViewModel() {
    private val stack = ArrayDeque<Route>()
    var route by mutableStateOf<Route>(if (graph.keyStore.hasKey()) Route.Home else Route.Connect)
        private set
    var session by mutableIntStateOf(0)
        private set
    var account by mutableStateOf<Account?>(null)
        private set

    fun open(next: Route) {
        stack.addLast(route)
        route = next
    }

    fun replace(next: Route) {
        route = next
    }

    fun back() {
        if (stack.isEmpty()) return
        route = stack.removeLast()
    }

    fun connected(account: Account) {
        this.account = account
        stack.clear()
        session += 1
        route = Route.Home
    }

    fun rememberAccount(account: Account) {
        this.account = account
    }

    fun signedOut() {
        graph.keyStore.clear()
        account = null
        stack.clear()
        session += 1
        route = Route.Connect
    }

    companion object {
        fun factory(graph: CursorGraph): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph) as T
        }
    }
}

@Composable
fun CursorApp(graph: CursorGraph) {
    val appVm: AppViewModel = viewModel(factory = AppViewModel.factory(graph))
    val route = appVm.route
    val session = appVm.session
    val client = remember(session) { runCatching { graph.client() }.getOrNull() }
    LaunchedEffect(route, client) {
        if (route !is Route.Connect && client == null) appVm.signedOut()
    }
    BackHandler(enabled = route !is Route.Home && route !is Route.Connect) { appVm.back() }
    Box(Modifier.fillMaxSize().background(Background)) {
        when (val current = route) {
            Route.Connect -> ConnectScreen(graph, session, onConnected = appVm::connected)
            Route.Home -> if (client != null) {
                HomeScreen(
                    client = client,
                    session = session,
                    account = appVm.account,
                    onAccount = appVm::rememberAccount,
                    onOpen = { appVm.open(Route.Chat(it)) },
                    onNew = { appVm.open(Route.NewChat) },
                    onSettings = { appVm.open(Route.Settings) },
                )
            }
            Route.NewChat -> if (client != null) {
                NewChatScreen(
                    client = client,
                    recents = graph.recents,
                    session = session,
                    onClose = appVm::back,
                    onCreated = { appVm.replace(Route.Chat(it)) },
                )
            }
            is Route.Chat -> if (client != null) {
                ChatScreen(
                    client = client,
                    agentId = current.agentId,
                    session = session,
                    onBack = appVm::back,
                )
            }
            Route.Settings -> SettingsScreen(
                account = appVm.account,
                onBack = appVm::back,
                onSignOut = appVm::signedOut,
            )
        }
    }
}
