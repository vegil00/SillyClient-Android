package com.sillyclient.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReceiveScriptTest {
    @Test
    fun chatGenerationEndpointsAreTheOnlyStreamsThatKeepAReplyAwake() {
        val watched = listOf(
            "/api/backends/chat-completions/generate",
            "/api/backends/text-completions/generate",
            "/api/backends/kobold/generate",
            "/api/backends/koboldhorde/generate",
            "http://127.0.0.1:8000/api/novelai/generate?stream=true",
            "https://tavern.example/api/backends/chat-completions/generate/",
        )
        watched.forEach { assertTrue(it, SessionReceiveScript.isChatGenerate(it)) }

        val ignored = listOf(
            "/api/sd/together/generate",
            "/api/novelai/generate-image",
            "/api/openai/generate-voice",
            "/api/azure/generate",
            "/api/backends/chat-completions/generate/extra",
            "javascript:generate",
            "http://127.0.0.1:8000/api/backends/chat-completions/status",
            "",
            "/api/backends/chat-completions/gen\nerate",
        )
        ignored.forEach { assertFalse(it, SessionReceiveScript.isChatGenerate(it)) }
    }

    @Test
    fun thePageScriptFollowsSillyTavernGenerationAndIgnoresDryRuns() {
        val source = SessionReceiveScript.source()
        assertTrue(source.contains("window.${SessionReceiveScript.BRIDGE}"))
        assertTrue(source.contains("GENERATION_STARTED"))
        assertTrue(source.contains("GENERATION_ENDED"))
        assertTrue(source.contains("if (dryRun) return"))
        assertTrue(source.contains("dataset.generating"))
        assertTrue(source.contains(SessionReceiveScript.CHAT_GENERATE.pattern))
        assertTrue(source.contains("__scSessionReceiveInstalled"))
        assertTrue(source.contains("setReceiving"))
        assertTrue(source.contains("pulse"))
        assertFalse(source.contains("generate-image"))
        assertFalse(source.contains("/api/azure/generate"))
    }
}
