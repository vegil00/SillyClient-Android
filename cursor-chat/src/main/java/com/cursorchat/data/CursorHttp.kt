package com.cursorchat.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

data class ApiRequest(
    val method: String,
    val path: String,
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val stream: Boolean = false,
    val readTimeoutMs: Int = 60_000,
)

interface HttpTransport {
    fun execute(request: ApiRequest): ApiCall
}

abstract class ApiCall : AutoCloseable {
    abstract val code: Int
    abstract fun body(): String
    abstract fun readLines(onLine: (String) -> Unit)
}

fun basicAuthorization(apiKey: String): String {
    val token = Base64.getEncoder().encodeToString("$apiKey:".toByteArray(Charsets.UTF_8))
    return "Basic $token"
}

fun resolveCursorRedirect(current: URL, location: String?): URL {
    if (location.isNullOrBlank() || location.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) {
        throw CursorException(0, "redirect", "接口重定向无效")
    }
    val next = URL(current, location)
    val host = next.host
    if (!next.protocol.equals("https", ignoreCase = true) || host == null || !host.equals("api.cursor.com", ignoreCase = true)) {
        throw CursorException(0, "redirect", "拒绝跳转到接口以外的地址")
    }
    if (next.userInfo != null) throw CursorException(0, "redirect", "拒绝带有账号的跳转")
    return next
}

class UrlConnectionTransport(
    private val apiKey: String,
    private val opener: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
) : HttpTransport {
    override fun execute(request: ApiRequest): ApiCall {
        var url = URL(BASE + request.path)
        var redirects = 0
        while (true) {
            val connection = opener(url)
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = request.method
                connection.connectTimeout = 20_000
                connection.readTimeout = if (request.stream) 120_000 else request.readTimeoutMs
                connection.setRequestProperty("Authorization", basicAuthorization(apiKey))
                connection.setRequestProperty("Accept", request.headers["Accept"] ?: "application/json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                request.headers.forEach { (name, value) ->
                    if (!name.equals("Accept", ignoreCase = true)) connection.setRequestProperty(name, value)
                }
                if (request.body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { output ->
                        output.write(request.body.toByteArray(Charsets.UTF_8))
                    }
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirects >= 3) {
                        connection.disconnect()
                        throw CursorException(code, "redirect", "接口重定向次数过多")
                    }
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    url = resolveCursorRedirect(url, location)
                    redirects += 1
                    continue
                }
                return UrlCall(connection, streaming = request.stream && code in 200..299)
            } catch (error: CursorException) {
                connection.disconnect()
                throw error
            } catch (error: IOException) {
                connection.disconnect()
                throw CursorException(0, "network", "网络不可用，请检查连接", error)
            }
        }
    }

    private class UrlCall(
        private val connection: HttpURLConnection,
        private val streaming: Boolean,
    ) : ApiCall() {
        override val code: Int = connection.responseCode
        private var buffered: String? = null

        override fun body(): String {
            buffered?.let { return it }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = if (stream == null) "" else readLimited(stream, if (code in 200..299) MAX_BODY else MAX_ERROR)
            buffered = text
            return text
        }

        override fun readLines(onLine: (String) -> Unit) {
            if (!streaming) {
                body().lineSequence().forEach(onLine)
                return
            }
            val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
            reader.use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    onLine(line)
                }
            }
        }

        override fun close() {
            connection.disconnect()
        }
    }

    companion object {
        const val BASE = "https://api.cursor.com"
        private const val USER_AGENT = "CursorChatAndroid/0.1"
        private const val MAX_BODY = 8 * 1024 * 1024
        private const val MAX_ERROR = 64 * 1024

        private fun readLimited(stream: InputStream, max: Int): String {
            stream.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > max) throw CursorException(0, "too_large", "响应过大")
                    buffer.write(chunk, 0, read)
                }
                return String(buffer.toByteArray(), Charsets.UTF_8)
            }
        }
    }
}
