package com.cursorchat.chat

import com.cursorchat.data.CloudAgent
import com.cursorchat.data.FollowUpResult
import com.cursorchat.data.HistoryMessage
import com.cursorchat.data.RunSnapshot
import com.cursorchat.data.SseEvent
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducerTest {
    private val agent = CloudAgent(
        id = "bc-1",
        name = "Add README",
        status = "ACTIVE",
        url = "https://cursor.com/agents/bc-1",
        createdAt = null,
        updatedAt = null,
        latestRunId = "run-1",
        repos = emptyList(),
    )

    @Test fun historyMapsRolesAndKeepsARunResultWhenThereIsNoAssistantYet() {
        val state = ChatReducer.loaded(
            agent.copy(status = "IDLE"),
            listOf(HistoryMessage("m1", "user_message", "Add a README")),
            RunSnapshot("run-1", "bc-1", "FINISHED", "Added README.md", emptyList()),
        )
        assertEquals(Role.USER, state.history[0].role)
        assertEquals("Added README.md", state.history[1].text)
        assertFalse(state.runActive)
    }

    @Test fun assistantDeltasAndToolUpdatesAccumulate() {
        var state = ChatReducer.loaded(agent, emptyList(), RunSnapshot("run-1", "bc-1", "RUNNING", null))
        state = ChatReducer.onStream(state, SseEvent(null, "status", """{"status":"RUNNING","runId":"run-1"}"""))
        state = ChatReducer.onStream(state, SseEvent("1", "thinking", """{"text":"look"}"""))
        state = ChatReducer.onStream(state, SseEvent("2", "assistant", """{"text":"I'll "}"""))
        state = ChatReducer.onStream(state, SseEvent("3", "assistant", """{"text":"update it."}"""))
        state = ChatReducer.onStream(state, SseEvent("4", "tool_call", """{"callId":"c1","name":"read_file","status":"running","args":{"path":"README.md"}}"""))
        state = ChatReducer.onStream(state, SseEvent("5", "tool_call", """{"callId":"c1","name":"read_file","status":"completed","result":{"ok":true}}"""))
        assertEquals("I'll update it.", state.liveText)
        assertEquals("", state.thinking)
        assertEquals(1, state.liveTools.size)
        assertEquals("completed", state.liveTools.single().status)
        assertTrue(state.runActive)
    }

    @Test fun longerLiveReplyHidesThePartialHistoryCopy() {
        val state = ChatUiState(
            history = listOf(ChatMessage("m1", Role.USER, "go"), ChatMessage("m2", Role.ASSISTANT, "I'll")),
            liveText = "I'll update the README",
        )
        assertEquals(listOf("go"), state.visibleHistory.map { it.text })
    }

    @Test fun beginSendCommitsTheLiveReplyBeforeTheNextUserMessage() {
        val state = ChatReducer.beginSend(
            ChatUiState(
                history = listOf(ChatMessage("m1", Role.USER, "go")),
                liveText = "Done",
                draft = "also tests",
                runId = "run-1",
            ),
            "local:user:1",
            "also tests",
        )
        assertEquals(listOf("go", "Done", "also tests"), state.history.map { it.text })
        assertEquals("", state.liveText)
        assertEquals("", state.draft)
        assertTrue(state.sending)
    }

    @Test fun serverHistoryDropsLocalCopiesThatAlreadyLanded() {
        val local = ChatReducer.beginSend(
            ChatUiState(liveText = "Done", agentStatus = "ACTIVE", runId = "run-1"),
            "local:user:1",
            "also tests",
        )
        val merged = ChatReducer.withServer(
            local.copy(liveText = "Tests added"),
            agent.copy(status = "IDLE"),
            listOf(
                HistoryMessage("m1", "assistant_message", "Done"),
                HistoryMessage("m2", "user_message", "also tests"),
            ),
            RunSnapshot("run-2", "bc-1", "FINISHED", "Tests added"),
        )
        assertEquals(listOf("Done", "also tests", "Tests added"), merged.history.map { it.text })
        assertEquals("", merged.liveText)
        assertFalse(merged.sending)
    }

    @Test fun followUpMarksTheAgentActive() {
        val started = ChatReducer.onFollowUp(
            ChatUiState(agentStatus = "IDLE"),
            FollowUpResult.Started(RunSnapshot("run-2", "bc-1", "CREATING", null)),
        )
        assertEquals("ACTIVE", started.agentStatus)
        assertEquals("run-2", started.runId)
        val queued = ChatReducer.onFollowUp(
            ChatUiState(agentStatus = "ACTIVE", runStatus = "RUNNING", runId = "run-2"),
            FollowUpResult.Delivery("queued", null),
        )
        assertEquals("run-2", queued.runId)
        assertEquals("RUNNING", queued.runStatus)
    }

    @Test fun failedSendRestoresTheDraft() {
        val failed = ChatReducer.failSend(
            ChatUiState(history = listOf(ChatMessage("local:user:1", Role.USER, "hello")), sending = true),
            "local:user:1",
            "hello",
            "发送失败",
        )
        assertTrue(failed.history.isEmpty())
        assertEquals("hello", failed.draft)
        assertEquals("发送失败", failed.error)
    }
}

class TextFormatTest {
    @Test fun relativeTimeUsesChineseBuckets() {
        val now = Instant.parse("2026-04-13T18:30:00Z")
        assertEquals("刚刚", relativeTime("2026-04-13T18:29:40Z", now))
        assertEquals("5 分钟前", relativeTime("2026-04-13T18:25:00Z", now))
        assertEquals("2 小时前", relativeTime("2026-04-13T16:30:00Z", now))
        assertEquals("昨天", relativeTime("2026-04-12T18:30:00Z", now))
        assertEquals("3 天前", relativeTime("2026-04-10T18:30:00Z", now))
        assertEquals("", relativeTime("not-a-date", now))
    }

    @Test fun repoLabelKeepsOwnerAndName() {
        assertEquals("acme/app", repoLabel("https://github.com/acme/app.git"))
        assertEquals("group/app", repoLabel("https://gitlab.example.com/group/app"))
    }

    @Test fun fencesSplitCodeFromProse() {
        val blocks = splitBlocks("See\n```kt\nval x = 1\n```\nDone")
        assertEquals(MarkdownBlock.Text("See\n"), blocks[0])
        assertEquals(MarkdownBlock.Code("kt", "val x = 1"), blocks[1])
        assertEquals(MarkdownBlock.Text("Done"), blocks[2])
        val open = splitBlocks("```\npartial")
        assertEquals(MarkdownBlock.Code("", "partial"), open.single())
    }
}
