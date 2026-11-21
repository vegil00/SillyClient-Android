package com.cursorchat.data

import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorClientTest {
    private val transport = FakeTransport()
    private val client = CursorClient(transport)

    @Test fun listAgentsOmitsArchivedByDefaultAndKeepsTheCursor() {
        transport.handler = {
            StaticCall(200, """{"items":[{"id":"bc-1","name":"Read me","status":"IDLE"}],"nextCursor":"bc-2"}""")
        }
        val page = client.listAgents(cursor = "bc-0", limit = 200, includeArchived = false)
        assertEquals("/v1/agents?limit=100&includeArchived=false&cursor=bc-0", transport.requests.single().path)
        assertEquals("bc-1", page.items.single().id)
        assertEquals("bc-2", page.nextCursor)
    }

    @Test fun createOmitsModelAndRepoUntilTheyAreSet() {
        transport.handler = {
            StaticCall(200, """{"agent":{"id":"bc-1","name":"Add README","status":"ACTIVE"},"run":{"id":"run-1","status":"CREATING"}}""")
        }
        val created = client.createAgent("  Add a README  ", "  ", null, "  ", ConversationMode.PLAN, true)
        val body = JSONObject(transport.requests.single().body!!)
        assertEquals("Add a README", body.getJSONObject("prompt").getString("text"))
        assertEquals("plan", body.getString("mode"))
        assertFalse(body.getBoolean("autoCreatePR"))
        assertFalse(body.has("model"))
        assertFalse(body.has("repos"))
        assertEquals("run-1", created.run.id)
        assertFalse(transport.requests.single().body!!.contains("secret"))
    }

    @Test fun createSendsRepoBranchModelAndPullRequest() {
        transport.handler = {
            StaticCall(200, """{"agent":{"id":"bc-9","name":"Fix","status":"ACTIVE","repos":[{"url":"https://github.com/acme/app","startingRef":"main"}]},"run":{"id":"run-9","status":"CREATING"}}""")
        }
        val created = client.createAgent(
            "fix the bug",
            "https://github.com/acme/app",
            "main",
            "composer-2",
            ConversationMode.AGENT,
            true,
        )
        val body = JSONObject(transport.requests.single().body!!)
        assertEquals("composer-2", body.getJSONObject("model").getString("id"))
        assertEquals("https://github.com/acme/app", body.getJSONArray("repos").getJSONObject(0).getString("url"))
        assertEquals("main", body.getJSONArray("repos").getJSONObject(0).getString("startingRef"))
        assertTrue(body.getBoolean("autoCreatePR"))
        assertEquals("acme/app", created.agent.repos.single().url.substringAfter("github.com/"))
    }

    @Test fun invalidRepoNeverReachesTheNetwork() {
        assertThrows(CursorException::class.java) {
            client.createAgent("hello", "https://user:secret@github.com/acme/app", null, null, "agent", false)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun branchWithoutRepoIsRejected() {
        assertThrows(CursorException::class.java) {
            client.createAgent("hello", "", "main", null, "agent", false)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun followUpSteerCarriesOnlyPromptAndDelivery() {
        transport.handler = { StaticCall(202, """{"delivery":"steered","runId":"run-2"}""") }
        val result = client.followUp("bc-1", "cover the error path", steer = true) as FollowUpResult.Delivery
        val body = JSONObject(transport.requests.single().body!!)
        assertEquals("cover the error path", body.getJSONObject("prompt").getString("text"))
        assertEquals("steer", body.getString("delivery"))
        assertEquals(setOf("prompt", "delivery"), body.keys().asSequence().toSet())
        assertEquals("steered", result.delivery)
        assertEquals("run-2", result.runId)
        assertEquals("POST", transport.requests.single().method)
        assertEquals("/v1/agents/bc-1/runs", transport.requests.single().path)
    }

    @Test fun conversationUsesTheLegacyTranscriptRoute() {
        transport.handler = {
            StaticCall(
                200,
                """{"id":"bc-1","messages":[
                    {"id":"m1","type":"user_message","text":"Add a README"},
                    {"id":"m2","type":"assistant_message","text":"Done"}
                ]}""",
            )
        }
        val messages = client.conversation("bc-1")
        assertEquals("/v0/agents/bc-1/conversation", transport.requests.single().path)
        assertEquals(listOf("user_message", "assistant_message"), messages.map { it.type })
    }

    @Test fun apiErrorUsesTheServerMessage() {
        transport.handler = { StaticCall(409, """{"code":"agent_busy","message":"Agent 正忙"}""") }
        val error = assertThrows(CursorException::class.java) { client.getAgent("bc-1") }
        assertEquals(409, error.status)
        assertEquals("agent_busy", error.code)
        assertEquals("Agent 正忙", error.message)
    }

    @Test fun streamParsesEventsAndSendsTheResumeHeader() {
        transport.handler = {
            StaticCall(
                200,
                lines = listOf(
                    "event: status",
                    """data: {"status":"RUNNING"}""",
                    "",
                    "id: 1-0",
                    "event: assistant",
                    """data: {"text":"Hi"}""",
                    "",
                ),
            )
        }
        val events = mutableListOf<SseEvent>()
        client.streamAgent("bc-1", "0-9", events::add)
        assertEquals("text/event-stream", transport.requests.single().headers["Accept"])
        assertEquals("0-9", transport.requests.single().headers["Last-Event-ID"])
        assertEquals(listOf("status", "assistant"), events.map { it.event })
        assertEquals("Hi", JSONObject(events[1].data).getString("text"))
    }

    @Test fun unsafeAgentIdIsRejected() {
        assertThrows(CursorException::class.java) { client.getAgent("../bc-1") }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun meReadsTheAccount() {
        transport.handler = { StaticCall(200, """{"apiKeyName":"Phone","userEmail":"dev@example.com","userFirstName":"Alex"}""") }
        val account = client.me()
        assertEquals("/v1/me", transport.requests.single().path)
        assertEquals("dev@example.com", account.email)
        assertEquals("Alex", account.firstName)
    }
}

class CursorHttpTest {
    @Test fun basicAuthorizationIsTheApiKeyWithAnEmptyPassword() {
        val expected = java.util.Base64.getEncoder().encodeToString("crsr_test:".toByteArray())
        assertEquals("Basic $expected", basicAuthorization("crsr_test"))
    }

    @Test fun redirectsStayOnTheApiHost() {
        val current = URL("https://api.cursor.com/v1/me")
        assertEquals(
            "https://api.cursor.com/v1/agents",
            resolveCursorRedirect(current, "/v1/agents").toString(),
        )
        assertThrows(CursorException::class.java) { resolveCursorRedirect(current, "https://evil.example/v1/me") }
        assertThrows(CursorException::class.java) { resolveCursorRedirect(current, "http://api.cursor.com/v1/me") }
        assertThrows(CursorException::class.java) { resolveCursorRedirect(current, "https://user@api.cursor.com/v1/me") }
        assertThrows(CursorException::class.java) { resolveCursorRedirect(current, "https://api.cursor.com.evil.example/v1/me") }
    }
}

class RulesTest {
    @Test fun apiKeyRejectsWhitespaceAndShortValues() {
        assertEquals("crsr_123456789012345", ApiKeyRules.require("  crsr_123456789012345  "))
        assertThrows(CursorException::class.java) { ApiKeyRules.require("short key") }
        assertThrows(CursorException::class.java) { ApiKeyRules.require("crsr_line\nbreak_value") }
    }

    @Test fun repoUrlRejectsCredentialsAndNonHttp() {
        assertNull(RepoUrl.optional("  "))
        assertEquals("https://github.com/acme/app", RepoUrl.optional(" https://github.com/acme/app "))
        for (value in listOf(
            "javascript:alert(1)",
            "file:///tmp/app",
            "https://alice:secret@github.com/acme/app",
            "https://github.com/acme/app path",
            "https://github.com\\acme",
        )) {
            assertThrows(value, CursorException::class.java) { RepoUrl.optional(value) }
        }
    }

    @Test fun promptMustBePresentAndBounded() {
        assertEquals("hello", PromptRules.require(" hello "))
        assertThrows(CursorException::class.java) { PromptRules.require("   ") }
        assertThrows(CursorException::class.java) { PromptRules.require("x".repeat(PromptRules.MAX + 1)) }
    }
}
