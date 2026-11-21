package com.cursorchat

import android.app.Application

class CursorChatApplication : Application() {
    lateinit var graph: CursorGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = CursorGraph(this)
    }
}
