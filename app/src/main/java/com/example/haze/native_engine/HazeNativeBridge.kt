package com.example.haze.native_engine

object HazeNativeBridge {

    private var isLibraryLoaded = false

    init {
        try {
            System.loadLibrary("hazeshield")
            isLibraryLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            isLibraryLoaded = false
        } catch (e: Exception) {
            isLibraryLoaded = false
        }
    }

    fun isNativeLoaded(): Boolean = isLibraryLoaded

    external fun nativeAddDomain(domain: String, category: String)
    external fun nativeAddPatternRule(pattern: String, category: String, isException: Boolean)
    external fun nativeClearRules()
    external fun nativeEvaluateRequest(requestUrl: String, pageUrl: String?, isMainFrame: Boolean): NativeDecision?
}
