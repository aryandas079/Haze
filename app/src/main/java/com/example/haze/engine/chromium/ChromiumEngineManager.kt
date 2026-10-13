package com.example.haze.engine.chromium

import android.content.Context
import com.example.haze.engine.EngineConfig
import com.example.haze.engine.IBrowserEngine

/**
 * Singleton manager providing access to the Chromium Browser Engine.
 */
object ChromiumEngineManager {
    private var engine: IBrowserEngine? = null

    /**
     * Initializes and returns the active browser engine.
     */
    fun getEngine(context: Context, config: EngineConfig = EngineConfig()): IBrowserEngine {
        val current = engine
        if (current != null) return current

        return synchronized(this) {
            engine ?: ChromiumBrowserEngine.getInstance().also {
                it.init(context, config)
                engine = it
            }
        }
    }
}
