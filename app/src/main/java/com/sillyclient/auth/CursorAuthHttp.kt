package com.sillyclient.auth

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal fun cursorApiUrl(pathAndQuery: String): URL {
    require(pathAndQuery.startsWith("/auth/poll") || pathAndQuery == "/aiserver.v1.DashboardService/GetMe")
    require(pathAndQuery.none { it <= ' ' || it == '\\' || it == '@' })
    val url = URL("https://api2.cursor.sh$pathAndQuery")
    require(url.protocol == "https" && url.host == "api2.cursor.sh" && url.userInfo == null)
    return url
}

class HttpsCursorAuthTransport : CursorAuthTransport {
    override fun postPoll(uuid: String, verifier: String): CursorHttpResult {
        val body = JSONObject().put("uuid", uuid).put("verifier", verifier).toString()
        return exchange("POST", "/auth/poll", body, null)
    }

    override fun getPoll(uuid: String, verifier: String): CursorHttpResult {
        val query = "uuid=${encode(uuid)}&verifier=${encode(verifier)}"
        return exchange("GET", "/auth/poll?$query", null, null)
    }

    override fun getMe(accessToken: String): CursorHttpResult {
        return exchange("POST", "/aiserver.v1.DashboardService/GetMe", "{}", accessToken)
    }

    private fun exchange(method: String, pathAndQuery: String, body: String?, accessToken: String?): CursorHttpResult {
        val connection = cursorApiUrl(pathAndQuery).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "SillyClient-Android")
            if (accessToken != null) {
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Connect-Protocol-Version", "1")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status in 300..399) throw CursorAuthException("Cursor 登录失败")
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return CursorHttpResult(status, readLimited(stream))
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream?): String {
        if (stream == null) return ""
        stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(4_096)
            var total = 0
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                total += count
                if (total > 262_144) throw CursorAuthException("Cursor 登录响应无效")
                buffer.write(chunk, 0, count)
            }
            return buffer.toString(Charsets.UTF_8)
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
