package com.cursorchat.data

import android.content.Context
import org.json.JSONArray

class RecentRepos(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<String> {
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val url = array.optString(index)
                    if (url.isNotBlank()) add(url)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun remember(url: String) {
        val normalized = RepoUrl.optional(url) ?: return
        val next = listOf(normalized) + load().filterNot { it == normalized }
        val array = JSONArray()
        next.take(8).forEach(array::put)
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "cursor_chat_recents"
        const val KEY = "repos"
    }
}
