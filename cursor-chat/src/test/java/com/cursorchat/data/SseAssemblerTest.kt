package com.cursorchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseAssemblerTest {
    @Test fun blankLineDispatchesMultilineData() {
        val assembler = SseAssembler()
        assertNull(assembler.push("id: 7-1"))
        assertNull(assembler.push("event: assistant"))
        assertNull(assembler.push("data: hello"))
        assertNull(assembler.push("data: world"))
        val event = assembler.push("")
        assertEquals("7-1", event?.id)
        assertEquals("assistant", event?.event)
        assertEquals("hello\nworld", event?.data)
        assertNull(assembler.push(""))
    }

    @Test fun commentsAreIgnoredAndFinishFlushesAPartialEvent() {
        val assembler = SseAssembler()
        assertNull(assembler.push(": keep-alive"))
        assertNull(assembler.push("event: thinking"))
        assertNull(assembler.push("data: still"))
        val event = assembler.finish()
        assertEquals("thinking", event?.event)
        assertEquals("still", event?.data)
        assertNull(assembler.finish())
    }
}
