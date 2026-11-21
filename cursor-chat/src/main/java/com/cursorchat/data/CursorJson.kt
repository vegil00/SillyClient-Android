package com.cursorchat.data

import org.json.JSONArray
import org.json.JSONObject

object CursorJson {
    fun account(json: String): Account {
        val root = JSONObject(json)
        return Account(
            apiKeyName = root.textOrNull("apiKeyName"),
            email = root.textOrNull("userEmail"),
            firstName = root.textOrNull("userFirstName"),
        )
    }

    fun agentPage(json: String): AgentPage {
        val root = JSONObject(json)
        return AgentPage(
            items = agents(root.optJSONArray("items")),
            nextCursor = root.textOrNull("nextCursor"),
        )
    }

    fun agent(json: String): CloudAgent = agentFrom(JSONObject(json))

    fun created(json: String): CreatedAgent {
        val root = JSONObject(json)
        return CreatedAgent(
            agent = agentFrom(root.getJSONObject("agent")),
            run = runFrom(root.getJSONObject("run")),
        )
    }

    fun run(json: String): RunSnapshot = runFrom(JSONObject(json))

    fun conversation(json: String): List<HistoryMessage> {
        val messages = JSONObject(json).optJSONArray("messages") ?: return emptyList()
        val result = ArrayList<HistoryMessage>(messages.length())
        val seen = HashSet<String>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val text = message.text("text").ifBlank { message.text("content") }
            if (text.isBlank()) continue
            val type = message.text("type").ifBlank { "note" }
            var id = message.text("id").ifBlank { "history-$index" }
            if (!seen.add(id)) id = "$id-$index"
            result += HistoryMessage(id, type, text)
        }
        return result
    }

    fun followUp(json: String): FollowUpResult {
        val root = JSONObject(json)
        if (root.has("run") && !root.isNull("run")) {
            return FollowUpResult.Started(runFrom(root.getJSONObject("run")))
        }
        val delivery = root.text("delivery")
        if (delivery.isBlank()) throw CursorException(0, "bad_response", "接口返回无法识别")
        return FollowUpResult.Delivery(delivery, root.textOrNull("runId"))
    }

    fun models(json: String): List<ModelOption> {
        val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
        val result = ArrayList<ModelOption>(items.length())
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.text("id")
            if (id.isBlank()) continue
            result += ModelOption(id, item.text("displayName").ifBlank { id })
        }
        return result
    }

    fun repositories(json: String): List<String> {
        val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
        val result = ArrayList<String>(items.length())
        for (index in 0 until items.length()) {
            val url = items.optJSONObject(index)?.text("url").orEmpty()
            if (url.isNotBlank()) result += url
        }
        return result
    }

    fun createBody(
        prompt: String,
        repoUrl: String?,
        ref: String?,
        modelId: String?,
        mode: String,
        autoCreatePr: Boolean,
    ): String {
        val root = JSONObject()
            .put("prompt", JSONObject().put("text", prompt))
            .put("mode", mode)
            .put("autoCreatePR", autoCreatePr)
        if (!modelId.isNullOrBlank()) root.put("model", JSONObject().put("id", modelId))
        if (!repoUrl.isNullOrBlank()) {
            val repo = JSONObject().put("url", repoUrl)
            if (!ref.isNullOrBlank()) repo.put("startingRef", ref)
            root.put("repos", JSONArray().put(repo))
        }
        return root.toString()
    }

    fun followUpBody(prompt: String, steer: Boolean): String {
        val root = JSONObject().put("prompt", JSONObject().put("text", prompt))
        if (steer) root.put("delivery", "steer")
        return root.toString()
    }

    fun apiError(status: Int, body: String): CursorException {
        val root = runCatching { JSONObject(body) }.getOrNull()
        val nested = root?.optJSONObject("error")
        val code = nested?.textOrNull("code") ?: root?.textOrNull("code")
        val message = nested?.textOrNull("message")
            ?: root?.textOrNull("message")
            ?: defaultStatus(status)
        return CursorException(status, code, message)
    }

    fun defaultStatus(status: Int): String = when (status) {
        401, 403 -> "API Key 无效或没有权限"
        404 -> "找不到这则对话"
        409 -> "Agent 正忙，请稍后再发或先停止"
        429 -> "请求过于频繁，请稍后再试"
        0 -> "网络不可用，请检查连接"
        else -> "请求失败（$status）"
    }

    private fun agents(items: JSONArray?): List<CloudAgent> {
        if (items == null) return emptyList()
        val result = ArrayList<CloudAgent>(items.length())
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            result += agentFrom(item)
        }
        return result
    }

    private fun agentFrom(json: JSONObject): CloudAgent {
        val id = json.text("id")
        if (id.isBlank()) throw CursorException(0, "bad_response", "接口返回无法识别")
        return CloudAgent(
            id = id,
            name = json.text("name").ifBlank { "未命名对话" },
            status = json.text("status"),
            url = json.textOrNull("url"),
            createdAt = json.textOrNull("createdAt"),
            updatedAt = json.textOrNull("updatedAt"),
            latestRunId = json.textOrNull("latestRunId"),
            repos = repos(json.optJSONArray("repos")),
            autoCreatePr = if (json.has("autoCreatePR") && !json.isNull("autoCreatePR")) json.optBoolean("autoCreatePR") else null,
            envType = json.optJSONObject("env")?.textOrNull("type"),
        )
    }

    private fun repos(items: JSONArray?): List<RepoRef> {
        if (items == null) return emptyList()
        val result = ArrayList<RepoRef>(items.length())
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val url = item.text("url")
            if (url.isBlank()) continue
            result += RepoRef(url, item.textOrNull("startingRef"))
        }
        return result
    }

    private fun runFrom(json: JSONObject): RunSnapshot {
        val id = json.text("id")
        if (id.isBlank()) throw CursorException(0, "bad_response", "接口返回无法识别")
        return RunSnapshot(
            id = id,
            agentId = json.textOrNull("agentId"),
            status = json.text("status"),
            result = json.textOrNull("result"),
            branches = branches(json.optJSONObject("git")?.optJSONArray("branches")),
        )
    }

    private fun branches(items: JSONArray?): List<GitBranch> {
        if (items == null) return emptyList()
        val result = ArrayList<GitBranch>(items.length())
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            result += GitBranch(
                repoUrl = item.textOrNull("repoUrl"),
                branch = item.textOrNull("branch"),
                prUrl = item.textOrNull("prUrl"),
            )
        }
        return result
    }

    private fun JSONObject.text(name: String): String {
        if (!has(name) || isNull(name)) return ""
        return optString(name, "")
    }

    private fun JSONObject.textOrNull(name: String): String? = text(name).ifBlank { null }
}
