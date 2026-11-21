package com.cursorchat.data

class FakeTransport : HttpTransport {
    val requests = mutableListOf<ApiRequest>()
    var handler: (ApiRequest) -> ApiCall = { request ->
        error("unexpected ${request.method} ${request.path}")
    }

    override fun execute(request: ApiRequest): ApiCall {
        requests += request
        return handler(request)
    }
}

class StaticCall(
    override val code: Int,
    private val text: String = "",
    private val lines: List<String>? = null,
) : ApiCall() {
    override fun body(): String = text

    override fun readLines(onLine: (String) -> Unit) {
        (lines ?: text.lines()).forEach(onLine)
    }

    override fun close() = Unit
}
