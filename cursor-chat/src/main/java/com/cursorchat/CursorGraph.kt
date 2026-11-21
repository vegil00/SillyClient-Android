package com.cursorchat

import android.content.Context
import com.cursorchat.data.ApiKeyStore
import com.cursorchat.data.CursorClient
import com.cursorchat.data.CursorException
import com.cursorchat.data.RecentRepos
import com.cursorchat.data.UrlConnectionTransport

class CursorGraph(context: Context) {
    val keyStore = ApiKeyStore(context)
    val recents = RecentRepos(context)

    fun client(): CursorClient {
        val key = keyStore.load() ?: throw CursorException(0, "signed_out", "尚未连接")
        return CursorClient(UrlConnectionTransport(key))
    }
}
