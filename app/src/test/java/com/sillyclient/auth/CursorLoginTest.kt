package com.sillyclient.auth

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorLoginTest {
    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

    @Test fun loginUrlCarriesTheChallengeAndNotTheVerifier() {
        val handshake = CursorLogin.handshake(verifier, uuid)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challengeOf(verifier))
        assertTrue(handshake.loginUrl.startsWith("https://cursor.com/loginDeepControl?"))
        assertTrue(handshake.loginUrl.contains("challenge=${challengeOf(verifier)}"))
        assertTrue(handshake.loginUrl.contains("uuid=$uuid"))
        assertTrue(handshake.loginUrl.contains("mode=login"))
        assertTrue(handshake.loginUrl.contains("redirectTarget=sdk"))
        assertFalse(handshake.loginUrl.contains(verifier))
        CursorLogin.assertBrowserUrl(handshake.loginUrl)
    }

    @Test fun generatedHandshakeUsesCursorAndHidesTheVerifier() {
        val handshake = CursorLogin.createHandshake()
        assertEquals(43, handshake.verifier.length)
        assertFalse(handshake.loginUrl.contains(handshake.verifier))
        CursorLogin.assertBrowserUrl(handshake.loginUrl)
    }

    @Test fun pollWaitsForTheBrowserThenReadsTheProfileWithoutKeepingTokens() {
        val access = jwt("""{"email":"hidden@example.com","sub":"user_hidden"}""")
        val transport = ScriptedTransport(
            CursorHttpResult(404, "Not found"),
            CursorHttpResult(200, """{"accessToken":"$access","refreshToken":"refresh-secret"}"""),
            CursorHttpResult(200, """{"email":"ada@example.com","firstName":"Ada","lastName":"Lovelace","authId":"user_ada"}""")
        )
        val account = client(transport).finish(CursorLogin.handshake(verifier, uuid))

        assertEquals(listOf("POST", "POST", "ME"), transport.calls.map { it.substringBefore(':') })
        assertEquals("ada@example.com", account.email)
        assertEquals("Ada Lovelace", account.name)
        assertEquals("user_ada", account.authId)
        assertFalse(account.encode().contains(access))
        assertFalse(account.encode().contains("refresh-secret"))
        assertEquals(access, transport.seenTokens.single())
    }

    @Test fun missingPostRouteFallsBackToGetOnce() {
        val access = jwt("""{"email":"from-jwt@example.com","sub":"user_get"}""")
        val transport = ScriptedTransport(
            CursorHttpResult(404, """{"message":"Route POST:/auth/poll not found"}"""),
            CursorHttpResult(404, "Not found"),
            CursorHttpResult(200, """{"accessToken":"$access","refreshToken":"refresh-secret"}"""),
            CursorHttpResult(500, "profile down")
        )
        val account = client(transport).finish(CursorLogin.handshake(verifier, uuid))

        assertEquals(listOf("POST", "GET", "GET", "ME"), transport.calls.map { it.substringBefore(':') })
        assertEquals("from-jwt@example.com", account.email)
        assertFalse(account.encode().contains("refresh-secret"))
    }

    @Test fun repeatedFailuresDoNotSurfaceTransportDetails() {
        val transport = ScriptedTransport()
        transport.failure = IllegalStateException("verifier=$verifier token=secret")
        val error = assertThrows(CursorAuthException::class.java) {
            client(transport, maxAttempts = 3).finish(CursorLogin.handshake(verifier, uuid))
        }
        assertEquals("无法连接 Cursor，请检查网络后重试", error.message)
        assertFalse(error.message!!.contains(verifier))
        assertEquals(3, transport.calls.size)
    }

    @Test fun rejectedProfileDoesNotFallBackToTheTokenPayload() {
        val access = jwt("""{"email":"forged@example.com","sub":"user_forged"}""")
        val transport = ScriptedTransport(
            CursorHttpResult(200, """{"accessToken":"$access","refreshToken":"refresh-secret"}"""),
            CursorHttpResult(401, """{"message":"unauthorized"}""")
        )
        val error = assertThrows(CursorAuthException::class.java) {
            client(transport).finish(CursorLogin.handshake(verifier, uuid))
        }
        assertEquals("Cursor 没有接受这次登录", error.message)
    }

    @Test fun cancellationStopsBeforeAnyRequest() {
        val transport = ScriptedTransport()
        val error = assertThrows(CursorAuthException::class.java) {
            client(transport).finish(CursorLogin.handshake(verifier, uuid)) { true }
        }
        assertEquals("登录已取消", error.message)
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun storedProfileRejectsCredentials() {
        val account = CursorAccount("ada@example.com", "Ada", "user_ada")
        assertEquals("ada@example.com", CursorAccount.decode(account.encode())?.email)
        assertNull(CursorAccount.decode("""{"email":"ada@example.com","accessToken":"secret"}"""))
        assertNull(CursorAccount.decode("""{"email":"ada@example.com","password":"secret"}"""))
        assertNull(CursorAccount.decode("{"))
    }

    @Test fun apiUrlStaysOnTheCursorApiHost() {
        assertEquals("https://api2.cursor.sh/auth/poll", cursorApiUrl("/auth/poll").toString())
        assertThrows(IllegalArgumentException::class.java) { cursorApiUrl("https://evil.example/auth/poll") }
        assertThrows(IllegalArgumentException::class.java) { cursorApiUrl("//evil.example/auth/poll") }
        assertThrows(IllegalArgumentException::class.java) { cursorApiUrl("/auth/poll?verifier=secret@evil.example") }
    }

    private fun client(transport: ScriptedTransport, maxAttempts: Int = 6) =
        CursorLoginClient(transport, sleep = {}, maxAttempts = maxAttempts)

    private fun challengeOf(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
        )

    private fun jwt(payload: String): String {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        return "${encode("""{"alg":"none"}""")}.${encode(payload)}.sig"
    }

    private class ScriptedTransport(vararg results: CursorHttpResult) : CursorAuthTransport {
        val calls = mutableListOf<String>()
        val seenTokens = mutableListOf<String>()
        private val pending = ArrayDeque(results.toList())
        var failure: Exception? = null

        override fun postPoll(uuid: String, verifier: String): CursorHttpResult = next("POST")
        override fun getPoll(uuid: String, verifier: String): CursorHttpResult = next("GET")
        override fun getMe(accessToken: String): CursorHttpResult {
            seenTokens += accessToken
            return next("ME")
        }

        private fun next(kind: String): CursorHttpResult {
            calls += kind
            failure?.let { throw it }
            return pending.removeFirst()
        }
    }
}
