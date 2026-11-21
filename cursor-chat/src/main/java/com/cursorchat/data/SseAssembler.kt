package com.cursorchat.data

/** Incremental parser for `text/event-stream` blocks. Blank lines dispatch one event. */
class SseAssembler {
    private val data = StringBuilder()
    private var event: String? = null
    private var id: String? = null
    private var sawField = false

    fun push(line: String): SseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val separator = line.indexOf(':')
        val field = if (separator < 0) line else line.substring(0, separator)
        var value = if (separator < 0) "" else line.substring(separator + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
                sawField = true
            }
            "event" -> {
                event = value
                sawField = true
            }
            "id" -> if (value != "\u0000" && !value.contains("\u0000")) {
                id = value
                sawField = true
            }
        }
        return null
    }

    fun finish(): SseEvent? = if (sawField) dispatch() else null

    private fun dispatch(): SseEvent? {
        if (!sawField) return null
        val parsed = SseEvent(id = id, event = event ?: "message", data = data.toString())
        data.setLength(0)
        event = null
        id = null
        sawField = false
        return parsed
    }
}
