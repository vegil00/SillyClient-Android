package com.sillyclient.auth

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow

/**
 * Browser sign-in shared with the Cursor SDK.
 *
 * The app opens cursor.com/loginDeepControl with a PKCE challenge, then polls
 * api2.cursor.sh until that browser session finishes. The access token is used
 * once to read the account profile and then dropped. No Cursor API key is minted
 * or stored.
 */
data class CursorAccount(val email: String, val name: String, val authId: String) {
    fun encode(): String {
        require(email.isNotEmpty() || name.isNotEmpty() || authId.isNotEmpty())
        return JSONObject()
            .put("email", email)
            .put("name", name)
            .put("authId", authId)
            .toString()
    }

    companion object {
        fun decode(raw: String): CursorAccount? {
            val json = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return null
            }
            if (FORBIDDEN_KEYS.any { json.has(it) }) return null
            val email = field(json.optString("email"), 320) ?: return null
            val name = field(json.optString("name"), 200) ?: return null
            val authId = field(json.optString("authId"), 320) ?: return null
            if (email.isEmpty() && name.isEmpty() && authId.isEmpty()) return null
            return CursorAccount(email, name, authId)
        }
    }
}

class CursorAuthException(message: String) : Exception(message)

data class CursorLoginHandshake(val uuid: String, val verifier: String, val loginUrl: String)

data class CursorHttpResult(val status: Int, val body: String)

interface CursorAuthTransport {
    fun postPoll(uuid: String, verifier: String): CursorHttpResult
    fun getPoll(uuid: String, verifier: String): CursorHttpResult
    fun getMe(accessToken: String): CursorHttpResult
}

object CursorLogin {
    const val WEBSITE = "https://cursor.com"
    const val API = "https://api2.cursor.sh"

    fun createHandshake(): CursorLoginHandshake {
        val verifier = base64Url(SecureRandom().generateSeed(32))
        return handshake(verifier, UUID.randomUUID().toString())
    }

    fun handshake(verifier: String, uuid: String, website: String = WEBSITE): CursorLoginHandshake {
        require(verifier.length in 43..128 && verifier.isPkceToken()) { "Invalid PKCE verifier" }
        require(UUID_PATTERN.matches(uuid)) { "Invalid login id" }
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return CursorLoginHandshake(uuid, verifier, buildLoginUrl(website, challenge, uuid))
    }

    fun buildLoginUrl(website: String, challenge: String, uuid: String): String {
        require(website == "https://cursor.com" || website == "https://www.cursor.com")
        require(challenge.length in 43..128 && challenge.isPkceToken())
        require(UUID_PATTERN.matches(uuid))
        val url = "$website/loginDeepControl?challenge=$challenge&uuid=$uuid&mode=login&redirectTarget=sdk"
        assertBrowserUrl(url)
        return url
    }

    fun assertBrowserUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https")
        require(uri.host == "cursor.com" || uri.host == "www.cursor.com")
        require(uri.path == "/loginDeepControl")
        require(uri.rawUserInfo == null)
        val query = uri.rawQuery ?: ""
        require(!query.contains("verifier", ignoreCase = true))
        require(!url.contains("accessToken") && !url.contains("refreshToken"))
    }
}

class CursorLoginClient(
    private val transport: CursorAuthTransport,
    private val sleep: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    private val maxAttempts: Int = 90,
) {
    fun finish(handshake: CursorLoginHandshake, isCancelled: () -> Boolean = { false }): CursorAccount {
        var useGet = false
        var pendingConfirmed = false
        var consecutiveErrors = 0
        for (attempt in 0 until maxAttempts) {
            if (isCancelled()) throw CursorAuthException("登录已取消")
            val delay = min(1_000.0 * 1.2.pow(attempt.toDouble()), 10_000.0).toLong()
            try {
                val result = if (useGet) {
                    transport.getPoll(handshake.uuid, handshake.verifier)
                } else {
                    transport.postPoll(handshake.uuid, handshake.verifier)
                }
                if (result.status == 404) {
                    if (!pendingConfirmed) {
                        val body = result.body.trim()
                        if (!useGet && body != "Not found") {
                            useGet = true
                            continue
                        }
                        if (useGet && isMissingRoute(body)) {
                            throw CursorAuthException("Cursor 登录接口不可用")
                        }
                        pendingConfirmed = true
                    }
                    consecutiveErrors = 0
                    pause(delay, isCancelled)
                    continue
                }
                if (result.status !in 200..299) {
                    consecutiveErrors += 1
                    if (consecutiveErrors >= 3) throw CursorAuthException("无法连接 Cursor，请检查网络后重试")
                    pause(delay, isCancelled)
                    continue
                }
                val accessToken = parseAccessToken(result.body) ?: throw CursorAuthException("Cursor 登录响应无效")
                return accountFor(accessToken)
            } catch (error: CursorAuthException) {
                throw error
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CursorAuthException("登录已取消")
            } catch (_: Exception) {
                if (isCancelled()) throw CursorAuthException("登录已取消")
                consecutiveErrors += 1
                if (consecutiveErrors >= 3) throw CursorAuthException("无法连接 Cursor，请检查网络后重试")
                pause(delay, isCancelled)
            }
        }
        throw CursorAuthException("登录超时，请在浏览器中完成后再试")
    }

    private fun accountFor(accessToken: String): CursorAccount {
        if (accessToken.length > 16_384) throw CursorAuthException("Cursor 登录响应无效")
        val profile = try {
            transport.getMe(accessToken)
        } catch (error: CursorAuthException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (profile != null && (profile.status == 401 || profile.status == 403)) {
            throw CursorAuthException("Cursor 没有接受这次登录")
        }
        if (profile != null && profile.status in 200..299) {
            parseProfile(profile.body)?.let { return it }
        }
        return identityFromAccessToken(accessToken) ?: throw CursorAuthException("无法确认 Cursor 账号")
    }

    private fun pause(millis: Long, isCancelled: () -> Boolean) {
        var left = millis
        while (left > 0) {
            if (isCancelled()) throw CursorAuthException("登录已取消")
            val step = min(left, 250L)
            sleep(step)
            left -= step
        }
    }
}

internal fun parseAccessToken(body: String): String? {
    val json = try {
        JSONObject(body)
    } catch (_: Exception) {
        return null
    }
    val accessToken = json.optString("accessToken")
    val refreshToken = json.optString("refreshToken")
    if (accessToken.isBlank() || refreshToken.isBlank()) return null
    if (accessToken.length > 16_384 || refreshToken.length > 16_384) return null
    return accessToken
}

internal fun parseProfile(body: String): CursorAccount? {
    val json = try {
        JSONObject(body)
    } catch (_: Exception) {
        return null
    }
    if (FORBIDDEN_KEYS.any { json.has(it) }) return null
    val email = field(json.optString("email"), 320) ?: return null
    val explicitName = field(json.optString("name"), 200) ?: return null
    val first = field(json.optString("firstName").ifBlank { json.optString("first_name") }, 80) ?: return null
    val last = field(json.optString("lastName").ifBlank { json.optString("last_name") }, 80) ?: return null
    val authId = field(json.optString("authId").ifBlank { json.optString("auth_id") }, 320) ?: return null
    val name = explicitName.ifBlank { listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ") }
    if (email.isEmpty() && name.isEmpty() && authId.isEmpty()) return null
    return CursorAccount(email, name.ifBlank { "Cursor 账号" }, authId)
}

internal fun identityFromAccessToken(token: String): CursorAccount? {
    val payload = token.split('.').getOrNull(1) ?: return null
    if (payload.length > 16_384) return null
    val json = try {
        val bytes = Base64.getUrlDecoder().decode(padBase64(payload))
        if (bytes.size > 16_384) return null
        JSONObject(bytes.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        return null
    }
    val email = field(json.optString("email"), 320) ?: return null
    val name = field(json.optString("name"), 200) ?: return null
    val subject = field(json.optString("sub"), 320) ?: return null
    if (email.isEmpty() && name.isEmpty() && subject.isEmpty()) return null
    return CursorAccount(email, name.ifBlank { "Cursor 账号" }, subject)
}

internal fun isMissingRoute(body: String): Boolean {
    val trimmed = body.trim()
    if (!trimmed.startsWith("{")) return false
    val message = try {
        JSONObject(trimmed).optString("message")
    } catch (_: Exception) {
        return false
    }
    return message.startsWith("Route ") &&
        message.contains("not found") &&
        Regex("^Route \\w+:").containsMatchIn(message)
}

private val FORBIDDEN_KEYS = listOf("accessToken", "refreshToken", "apiKey", "token", "verifier", "password")
private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private fun field(value: String, max: Int): String? {
    val trimmed = value.trim()
    if (trimmed.length > max) return null
    if (trimmed.any { it <= '\u001f' || it == '\u007f' }) return null
    return trimmed
}

private fun String.isPkceToken(): Boolean = all { it.isLetterOrDigit() || it == '-' || it == '_' }

internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun padBase64(value: String): String {
    val remainder = value.length % 4
    return if (remainder == 0) value else value + "=".repeat(4 - remainder)
}
