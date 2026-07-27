package com.amaya.intelligence.impl.local.browser

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

class BrowserScriptAssets(context: Context) {
    private val assets = context.applicationContext.assets
    private val cache = ConcurrentHashMap<String, String>()

    fun read(name: String): String = cache.getOrPut(name) {
        assets.open("browser-bridge/$name").bufferedReader().use { it.readText() }
    }
}
