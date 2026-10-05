package com.example.haze.engine

import android.content.Context

/**
 * Top-level browser engine interface representing the Chromium engine runtime.
 */
interface IBrowserEngine {
    /**
     * Initializes the engine with the provided application context and configuration.
     */
    fun init(context: Context, config: EngineConfig = EngineConfig())

    /**
     * Creates a new tab instance.
     * @param isPrivate When true, creates an isolated private session with separate cookies, cache, and storage.
     */
    fun createTab(context: Context, isPrivate: Boolean = false): IBrowserTab

    /**
     * Clears specified browsing data partitions across the engine.
     */
    fun clearData(
        clearHistory: Boolean,
        clearCookies: Boolean,
        clearCache: Boolean,
        clearStorage: Boolean,
        onComplete: (() -> Unit)? = null
    )

    /**
     * Engine branding name (e.g. "Chromium Engine").
     */
    fun getEngineName(): String

    /**
     * Engine version string (e.g. "Chromium 124.0.6367.82").
     */
    fun getEngineVersion(): String

    /**
     * Indicates whether the engine is running on full native Chromium components.
     */
    fun isChromiumNative(): Boolean

    /**
     * Shuts down engine background processes and flushes storage.
     */
    fun shutdown()
}
