package com.cursorchat.data

import java.net.URI

class CursorException(
    val status: Int,
    val code: String?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class Account(
    val apiKeyName: String?,
    val email: String?,
    val firstName: String?,
)

data class CloudAgent(
    val id: String,
    val name: String,
    val status: String,
    val url: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val latestRunId: String?,
    val repos: List<RepoRef> = emptyList(),
    val autoCreatePr: Boolean? = null,
    val envType: String? = null,
)

data class RepoRef(val url: String, val startingRef: String?)

data class AgentPage(val items: List<CloudAgent>, val nextCursor: String?)

data class GitBranch(val repoUrl: String?, val branch: String?, val prUrl: String?)

data class RunSnapshot(
    val id: String,
    val agentId: String?,
    val status: String,
    val result: String?,
    val branches: List<GitBranch> = emptyList(),
)

data class CreatedAgent(val agent: CloudAgent, val run: RunSnapshot)

data class HistoryMessage(val id: String, val type: String, val text: String)

data class ModelOption(val id: String, val displayName: String)

sealed interface FollowUpResult {
    data class Started(val run: RunSnapshot) : FollowUpResult
    data class Delivery(val delivery: String, val runId: String?) : FollowUpResult
}

data class SseEvent(val id: String?, val event: String, val data: String)

object Status {
    private val active = setOf("ACTIVE", "CREATING", "RUNNING", "WAITING_FOR_BACKGROUND_WORK")
    private val terminalRuns = setOf("FINISHED", "ERROR", "CANCELLED", "EXPIRED")

    fun isActive(status: String): Boolean = status in active

    fun isTerminalRun(status: String): Boolean = status in terminalRuns

    fun label(status: String): String = when (status) {
        "ACTIVE", "CREATING", "RUNNING", "WAITING_FOR_BACKGROUND_WORK" -> "进行中"
        "IDLE", "FINISHED" -> "空闲"
        "ARCHIVED" -> "已归档"
        "ERROR" -> "出错"
        "CANCELLED" -> "已停止"
        "EXPIRED" -> "已过期"
        else -> status
    }
}

object Id {
    private val pattern = Regex("[A-Za-z0-9][A-Za-z0-9_\\-]{0,127}")

    fun require(id: String): String {
        if (!pattern.matches(id)) throw CursorException(0, "invalid_id", "对话标识无效")
        return id
    }
}

object ApiKeyRules {
    fun require(raw: String): String {
        val value = raw.trim()
        val valid = value.length in 16..500 && value.none { it.isWhitespace() || it.isISOControl() }
        if (!valid) throw CursorException(0, "invalid_key", "API Key 格式不正确")
        return value
    }
}

object PromptRules {
    const val MAX = 20_000

    fun require(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) throw CursorException(0, "empty_prompt", "请输入要发送的内容")
        if (value.length > MAX) throw CursorException(0, "prompt_too_long", "内容过长，请控制在 20000 字以内")
        return value
    }
}

object RepoUrl {
    fun optional(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.any { it.isISOControl() || it == '\\' || it.isWhitespace() }) {
            throw CursorException(0, "invalid_repo", "仓库地址无效")
        }
        val uri = try {
            URI(value)
        } catch (error: Exception) {
            throw CursorException(0, "invalid_repo", "仓库地址无效", error)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            throw CursorException(0, "invalid_repo", "仓库地址需要以 http 或 https 开头")
        }
        if (uri.rawAuthority.isNullOrBlank() || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw CursorException(0, "invalid_repo", "仓库地址不能包含账号或密码")
        }
        return value
    }

    fun ref(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.length > 255 || value.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) {
            throw CursorException(0, "invalid_ref", "分支名无效")
        }
        return value
    }
}

object ConversationMode {
    const val AGENT = "agent"
    const val PLAN = "plan"

    fun require(raw: String): String {
        if (raw == AGENT || raw == PLAN) return raw
        throw CursorException(0, "invalid_mode", "对话模式无效")
    }
}
