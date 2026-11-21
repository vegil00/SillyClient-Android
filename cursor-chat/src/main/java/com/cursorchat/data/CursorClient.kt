package com.cursorchat.data

import java.io.IOException
import java.net.URLEncoder

/**
 * Cursor Cloud Agents client.
 *
 * Agents, runs, and the live stream use `/v1`. Full transcripts still come from
 * the legacy `GET /v0/agents/{id}/conversation` route, which v1 does not replace.
 * Calls block; callers move them off the main thread.
 */
class CursorClient(private val transport: HttpTransport) {
    fun me(): Account = json(ApiRequest("GET", "/v1/me"), CursorJson::account)

    fun listAgents(cursor: String?, limit: Int = 30, includeArchived: Boolean = false): AgentPage {
        val params = linkedMapOf(
            "limit" to limit.coerceIn(1, 100).toString(),
            "includeArchived" to includeArchived.toString(),
        )
        if (!cursor.isNullOrBlank()) params["cursor"] = cursor
        val query = params.entries.joinToString("&", prefix = "?") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return json(ApiRequest("GET", "/v1/agents$query"), CursorJson::agentPage)
    }

    fun getAgent(id: String): CloudAgent =
        json(ApiRequest("GET", "/v1/agents/${Id.require(id)}"), CursorJson::agent)

    fun conversation(id: String): List<HistoryMessage> =
        json(ApiRequest("GET", "/v0/agents/${Id.require(id)}/conversation"), CursorJson::conversation)

    fun getRun(agentId: String, runId: String): RunSnapshot =
        json(ApiRequest("GET", "/v1/agents/${Id.require(agentId)}/runs/${Id.require(runId)}"), CursorJson::run)

    fun models(): List<ModelOption> = json(ApiRequest("GET", "/v1/models"), CursorJson::models)

    fun repositories(): List<String> =
        json(ApiRequest("GET", "/v1/repositories", readTimeoutMs = 90_000), CursorJson::repositories)

    fun createAgent(
        prompt: String,
        repoUrl: String?,
        ref: String?,
        modelId: String?,
        mode: String,
        autoCreatePr: Boolean,
    ): CreatedAgent {
        val text = PromptRules.require(prompt)
        val repo = RepoUrl.optional(repoUrl)
        val startingRef = RepoUrl.ref(ref)
        if (startingRef != null && repo == null) {
            throw CursorException(0, "invalid_ref", "填写分支前需要先填写仓库地址")
        }
        val body = CursorJson.createBody(
            prompt = text,
            repoUrl = repo,
            ref = startingRef,
            modelId = modelId?.trim()?.ifBlank { null },
            mode = ConversationMode.require(mode),
            autoCreatePr = autoCreatePr && repo != null,
        )
        return json(ApiRequest("POST", "/v1/agents", body = body), CursorJson::created)
    }

    fun followUp(agentId: String, prompt: String, steer: Boolean): FollowUpResult {
        val body = CursorJson.followUpBody(PromptRules.require(prompt), steer)
        return json(ApiRequest("POST", "/v1/agents/${Id.require(agentId)}/runs", body = body), CursorJson::followUp)
    }

    fun cancel(agentId: String, runId: String) {
        empty(ApiRequest("POST", "/v1/agents/${Id.require(agentId)}/runs/${Id.require(runId)}/cancel"))
    }

    fun archive(agentId: String) {
        empty(ApiRequest("POST", "/v1/agents/${Id.require(agentId)}/archive"))
    }

    fun unarchive(agentId: String) {
        empty(ApiRequest("POST", "/v1/agents/${Id.require(agentId)}/unarchive"))
    }

    fun delete(agentId: String) {
        empty(ApiRequest("DELETE", "/v1/agents/${Id.require(agentId)}"))
    }

    fun streamAgent(agentId: String, lastEventId: String?, onEvent: (SseEvent) -> Unit) {
        val headers = buildMap {
            put("Accept", "text/event-stream")
            if (!lastEventId.isNullOrBlank()) put("Last-Event-ID", lastEventId)
        }
        call(ApiRequest("GET", "/v1/agents/${Id.require(agentId)}/stream", headers = headers, stream = true)).use { response ->
            if (response.code !in 200..299) throw CursorJson.apiError(response.code, response.body())
            val assembler = SseAssembler()
            response.readLines { line -> assembler.push(line)?.let(onEvent) }
            assembler.finish()?.let(onEvent)
        }
    }

    private fun <T> json(request: ApiRequest, parse: (String) -> T): T {
        call(request).use { response ->
            val body = response.body()
            if (response.code !in 200..299) throw CursorJson.apiError(response.code, body)
            try {
                return parse(body)
            } catch (error: CursorException) {
                throw error
            } catch (error: Exception) {
                throw CursorException(response.code, "bad_response", "接口返回无法识别", error)
            }
        }
    }

    private fun empty(request: ApiRequest) {
        call(request).use { response ->
            if (response.code !in 200..299) throw CursorJson.apiError(response.code, response.body())
        }
    }

    private fun call(request: ApiRequest): ApiCall {
        try {
            return transport.execute(request)
        } catch (error: CursorException) {
            throw error
        } catch (error: IOException) {
            throw CursorException(0, "network", "网络不可用，请检查连接", error)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
