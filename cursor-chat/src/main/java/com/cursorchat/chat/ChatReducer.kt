package com.cursorchat.chat

import com.cursorchat.data.CloudAgent
import com.cursorchat.data.FollowUpResult
import com.cursorchat.data.GitBranch
import com.cursorchat.data.HistoryMessage
import com.cursorchat.data.RunSnapshot
import com.cursorchat.data.SseEvent
import com.cursorchat.data.Status
import org.json.JSONObject

enum class Role { USER, ASSISTANT, TOOL, NOTE }

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
)

data class LiveTool(
    val callId: String,
    val name: String,
    val status: String,
    val detail: String,
)

data class ChatUiState(
    val loading: Boolean = true,
    val agentName: String = "",
    val agentStatus: String = "",
    val runStatus: String = "",
    val runId: String? = null,
    val archived: Boolean = false,
    val agentUrl: String? = null,
    val repoLabel: String? = null,
    val history: List<ChatMessage> = emptyList(),
    val liveText: String = "",
    val liveTools: List<LiveTool> = emptyList(),
    val thinking: String = "",
    val branches: List<GitBranch> = emptyList(),
    val error: String? = null,
    val sending: Boolean = false,
    val draft: String = "",
) {
    val runActive: Boolean
        get() = Status.isActive(agentStatus) || Status.isActive(runStatus)

    val visibleHistory: List<ChatMessage>
        get() = ChatReducer.visibleHistory(history, liveText)
}

object ChatReducer {
    fun visibleHistory(history: List<ChatMessage>, liveText: String): List<ChatMessage> {
        if (liveText.isBlank()) return history
        val last = history.lastOrNull() ?: return history
        if (last.role == Role.ASSISTANT && liveText.startsWith(last.text) && last.text.isNotBlank()) {
            return history.dropLast(1)
        }
        return history
    }

    fun loaded(agent: CloudAgent, history: List<HistoryMessage>, run: RunSnapshot?): ChatUiState {
        val messages = history.map { message ->
            ChatMessage(
                id = message.id,
                role = roleOf(message.type),
                text = message.text,
            )
        }.toMutableList()
        val resultText = run?.result?.takeIf { it.isNotBlank() }
        if (run != null && resultText != null && messages.none { it.role == Role.ASSISTANT && it.text.trim() == resultText.trim() }) {
            messages += ChatMessage(id = "run-${run.id}", role = Role.ASSISTANT, text = resultText)
        }
        return ChatUiState(
            loading = false,
            agentName = agent.name,
            agentStatus = agent.status,
            runStatus = run?.status.orEmpty(),
            runId = run?.id ?: agent.latestRunId,
            archived = agent.status == "ARCHIVED",
            agentUrl = agent.url,
            repoLabel = agent.repos.firstOrNull()?.url?.let(::repoLabel),
            history = messages,
            branches = run?.branches.orEmpty(),
        )
    }

    fun withServer(
        state: ChatUiState,
        agent: CloudAgent,
        history: List<HistoryMessage>,
        run: RunSnapshot?,
    ): ChatUiState {
        val loaded = loaded(agent, history, run)
        val extras = state.history.filter { local ->
            local.id.startsWith("local:") && loaded.history.none { server ->
                server.role == local.role && server.text.trim() == local.text.trim()
            }
        }
        val live = state.liveText
        val serverHasLive = live.isNotBlank() && (loaded.history + extras).any { message ->
            message.role == Role.ASSISTANT && message.text.trim() == live.trim()
        }
        return loaded.copy(
            history = loaded.history + extras,
            liveText = if (serverHasLive) "" else live,
            liveTools = if (serverHasLive) emptyList() else state.liveTools,
            thinking = if (Status.isTerminalRun(loaded.runStatus) || serverHasLive) "" else state.thinking,
            error = state.error,
            sending = false,
            draft = state.draft,
        )
    }

    fun beginSend(state: ChatUiState, id: String, text: String): ChatUiState {
        return state.copy(
            sending = true,
            error = null,
            draft = "",
            history = commitLive(state) + ChatMessage(id, Role.USER, text),
            liveText = "",
            liveTools = emptyList(),
            thinking = "",
        )
    }

    fun failSend(state: ChatUiState, id: String, text: String, message: String): ChatUiState {
        return state.copy(
            sending = false,
            error = message,
            draft = text,
            history = state.history.filterNot { it.id == id },
        )
    }

    fun onFollowUp(state: ChatUiState, result: FollowUpResult): ChatUiState = when (result) {
        is FollowUpResult.Started -> state.copy(
            sending = false,
            runId = result.run.id,
            runStatus = result.run.status,
            agentStatus = if (state.archived) state.agentStatus else "ACTIVE",
        )
        is FollowUpResult.Delivery -> state.copy(
            sending = false,
            runId = result.runId ?: state.runId,
            agentStatus = if (state.archived) state.agentStatus else "ACTIVE",
            runStatus = if (result.delivery == "queued") state.runStatus.ifBlank { "CREATING" } else "RUNNING",
        )
    }

    fun onStream(state: ChatUiState, event: SseEvent): ChatUiState {
        val json = runCatching { JSONObject(event.data) }.getOrNull()
        return when (event.event) {
            "assistant" -> {
                val text = json?.optString("text").orEmpty().ifEmpty { if (json == null) event.data else "" }
                state.copy(liveText = state.liveText + text, thinking = "", error = null)
            }
            "thinking" -> state.copy(thinking = (state.thinking + json?.optString("text").orEmpty()).takeLast(4_000))
            "tool_call" -> if (json == null) state else state.copy(liveTools = upsertTool(state.liveTools, json))
            "status" -> applyStatus(state, json?.optString("status").orEmpty(), json?.textOrNull("runId"))
            "result" -> {
                val status = json?.optString("status").orEmpty()
                val text = json?.textOrNull("text")
                applyStatus(state, status, json?.textOrNull("runId") ?: state.runId).copy(
                    liveText = text ?: state.liveText,
                    thinking = "",
                )
            }
            "error" -> state.copy(error = json?.textOrNull("message") ?: "实时连接中断")
            else -> state
        }
    }

    private fun commitLive(state: ChatUiState): List<ChatMessage> {
        if (state.liveText.isBlank()) return state.history
        val last = state.history.lastOrNull()
        if (last?.role == Role.ASSISTANT && state.liveText.startsWith(last.text)) {
            return state.history.dropLast(1) + last.copy(text = state.liveText)
        }
        return state.history + ChatMessage("local:assistant:${state.history.size}", Role.ASSISTANT, state.liveText)
    }

    private fun applyStatus(state: ChatUiState, status: String, runId: String?): ChatUiState {
        if (status.isBlank()) return state
        val active = Status.isActive(status)
        val terminal = Status.isTerminalRun(status)
        return state.copy(
            runStatus = status,
            runId = runId ?: state.runId,
            agentStatus = when {
                state.agentStatus == "ARCHIVED" -> "ARCHIVED"
                active -> "ACTIVE"
                terminal && state.agentStatus == "ACTIVE" -> "IDLE"
                else -> state.agentStatus
            },
            thinking = if (terminal) "" else state.thinking,
        )
    }

    private fun upsertTool(tools: List<LiveTool>, json: JSONObject): List<LiveTool> {
        val callId = json.textOrNull("callId") ?: return tools
        val detail = (json.opt("result") ?: json.opt("args"))?.toString().orEmpty().take(180)
        val tool = LiveTool(
            callId = callId,
            name = json.textOrNull("name") ?: "tool",
            status = json.textOrNull("status") ?: "",
            detail = detail,
        )
        val index = tools.indexOfFirst { it.callId == callId }
        if (index < 0) return tools + tool
        return tools.toMutableList().also { it[index] = tool }
    }

    private fun roleOf(type: String): Role = when (type.lowercase()) {
        "user_message", "user", "human" -> Role.USER
        "assistant_message", "assistant" -> Role.ASSISTANT
        else -> Role.NOTE
    }

    private fun JSONObject.textOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name, "").ifBlank { null }
    }
}
