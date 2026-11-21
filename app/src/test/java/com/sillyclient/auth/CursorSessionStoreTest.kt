package com.sillyclient.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CursorSessionStoreTest {
    @Test fun roundTripKeepsOnlyTheProfile() {
        val vault = MemoryVault()
        val store = CursorSessionStore(vault)
        store.save(CursorAccount("ada@example.com", "Ada Lovelace", "user_ada"))

        val loaded = store.load()
        assertEquals("ada@example.com", loaded?.email)
        assertEquals("Ada Lovelace", loaded?.name)
        assertFalse(vault.value!!.contains("accessToken"))
        assertFalse(vault.value!!.contains("refreshToken"))
    }

    @Test fun credentialShapedPayloadIsDiscarded() {
        val vault = MemoryVault()
        vault.value = """{"email":"ada@example.com","refreshToken":"secret"}"""
        val store = CursorSessionStore(vault)

        assertNull(store.load())
        assertNull(vault.value)
    }

    private class MemoryVault : SecretVault {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }
}
