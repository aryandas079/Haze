package com.example.haze.engine.nativechromium

import android.app.Activity
import android.content.Context
import com.example.haze.engine.EngineConfig
import com.example.haze.engine.IBrowserEngine
import com.example.haze.engine.IBrowserTab
import org.chromium.content_shell.ShellManager
import org.chromium.ui.base.ActivityWindowAndroid
import org.chromium.ui.base.IntentRequestTracker
import org.chromium.ui.base.WindowAndroid

class NativeChromiumBrowserEngine : IBrowserEngine {

    companion object {
        var globalShellManager: ShellManager? = null
        var globalWindowAndroid: WindowAndroid? = null
        var activeTab: NativeChromiumTab? = null
    }

    private fun syncChromiumResourceIds() {
        try {
            gen.base_module.R.integer.min_screen_width_bucket = com.example.haze.R.integer.min_screen_width_bucket
            gen.base_module.R.string.url_hint = com.example.haze.R.string.url_hint
            gen.base_module.R.string.browser_process_initialization_failed = com.example.haze.R.string.browser_process_initialization_failed
            gen.base_module.R.drawable.ic_refresh = com.example.haze.R.drawable.ic_refresh
            gen.base_module.R.drawable.progress = com.example.haze.R.drawable.progress
            gen.base_module.R.layout.shell_view = com.example.haze.R.layout.shell_view
            gen.base_module.R.id.container = com.example.haze.R.id.container
            gen.base_module.R.id.toolbar = com.example.haze.R.id.toolbar
            gen.base_module.R.id.url = com.example.haze.R.id.url
            gen.base_module.R.id.prev = com.example.haze.R.id.prev
            gen.base_module.R.id.next = com.example.haze.R.id.next
            gen.base_module.R.id.stop_reload_button = com.example.haze.R.id.stop_reload_button
            gen.base_module.R.id.contentview_holder = com.example.haze.R.id.contentview_holder
            gen.base_module.R.dimen.config_min_scaling_span = com.example.haze.R.dimen.config_min_scaling_span
            gen.base_module.R.array.ui_com_google_android_gms_fonts_certs = com.example.haze.R.array.ui_com_google_android_gms_fonts_certs
            gen.base_module.R.array.ui_com_google_android_gms_fonts_certs_dev = com.example.haze.R.array.ui_com_google_android_gms_fonts_certs_dev
            gen.base_module.R.array.ui_com_google_android_gms_fonts_certs_prod = com.example.haze.R.array.ui_com_google_android_gms_fonts_certs_prod

            for (hazeInner in com.example.haze.R::class.java.declaredClasses) {
                val typeName = hazeInner.simpleName
                try {
                    val genInner = Class.forName("gen.base_module.R$$typeName")
                    for (field in hazeInner.declaredFields) {
                        try {
                            val genField = genInner.getDeclaredField(field.name)
                            genField.isAccessible = true
                            field.isAccessible = true
                            genField.setInt(null, field.getInt(null))
                        } catch (_: Throwable) {
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("HazeInit", "Failed to sync Chromium resources", e)
        }
    }

    override fun init(context: Context, config: EngineConfig) {
        syncChromiumResourceIds()
        // We are now loading the compiled Chromium engine!
        try {
            val clazz = Class.forName("org.jni_zero.JniZero")
            android.util.Log.e("JniZeroTest", "SUCCESS: Found JniZero class: $clazz")
        } catch (e: Exception) {
            android.util.Log.e("JniZeroTest", "FAILED: Could not find JniZero class", e)
        }
        try {
            android.util.Log.e("HazeInit", "1. Fixing NativeLibraries array")
            org.chromium.build.NativeLibraries.LIBRARIES[0] = "content_shell_content_view"
            if (org.chromium.build.NativeLibraries.LIBRARIES.size > 1) {
                org.chromium.build.NativeLibraries.LIBRARIES[1] = "content_shell_content_view"
            }
            
            android.util.Log.e("HazeInit", "1. Calling loadLibrary")
            System.loadLibrary("content_shell_content_view")
            android.util.Log.e("HazeInit", "2. loadLibrary succeeded")
            
            org.chromium.base.ContextUtils.initApplicationContext(context.applicationContext)
            android.util.Log.e("HazeInit", "3. initApplicationContext succeeded")
            
            org.chromium.base.PathUtils.setPrivateDataDirectorySuffix("haze")
            android.util.Log.e("HazeInit", "3.5 setPrivateDataDirectorySuffix succeeded")
            
            org.chromium.base.library_loader.LibraryLoader.getInstance().setLibraryProcessType(org.chromium.base.library_loader.LibraryProcessType.PROCESS_BROWSER)
            android.util.Log.e("HazeInit", "4. setLibraryProcessType succeeded")

            if (!org.chromium.base.CommandLine.isInitialized()) {
                org.chromium.base.CommandLine.init(null)
            }
            val cl = org.chromium.base.CommandLine.getInstance()
            cl.appendSwitchWithValue("user-agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            cl.appendSwitch("enable-gpu-rasterization")
            cl.appendSwitch("enable-zero-copy")
            cl.appendSwitchWithValue(
                "enable-features",
                "VaapiVideoDecoder,SmoothScrolling"
            )
            cl.appendSwitch("enable-quic")
            cl.appendSwitch("enable-threaded-compositing")
            android.util.Log.e("HazeInit", "4.1 CommandLine initialized with high-performance flags")

            org.chromium.base.library_loader.LibraryLoader.getInstance().ensureInitialized()
            android.util.Log.e("HazeInit", "4.2 LibraryLoader.ensureInitialized succeeded")

            android.util.Log.e("HazeInit", "4.5 Creating ShellManager and WindowAndroid")
            val shellManager = ShellManager(context, null)
            val activity = org.chromium.base.ContextUtils.activityFromContext(context) ?: (context as? Activity)
            val windowAndroid = if (activity != null) {
                val tracker = IntentRequestTracker.createFromActivity(activity)
                ActivityWindowAndroid(activity, false, tracker, null, true)
            } else {
                WindowAndroid(context, false)
            }
            shellManager.setWindow(windowAndroid)
            globalShellManager = shellManager
            globalWindowAndroid = windowAndroid
            android.util.Log.e("HazeInit", "4.6 ShellManager and WindowAndroid registered")
            
            org.chromium.content_public.browser.BrowserStartupController.getInstance().startBrowserProcessesSync(
                org.chromium.base.library_loader.LibraryProcessType.PROCESS_BROWSER, true, false)
            android.util.Log.e("HazeInit", "5. startBrowserProcessesSync succeeded")
        } catch (e: Throwable) {
            android.util.Log.e("HazeInit", "EXCEPTION CAUGHT: ", e)
        }
    }

    override fun createTab(context: Context, isPrivate: Boolean): IBrowserTab {
        // TODO: Create NativeChromiumTab wrapping native WebContents
        return NativeChromiumTab(context, isPrivate)
    }

    override fun clearData(
        clearHistory: Boolean,
        clearCookies: Boolean,
        clearCache: Boolean,
        clearStorage: Boolean,
        onComplete: (() -> Unit)?
    ) {
        // TODO: Interface with native Chromium to clear browsing data partitions
        onComplete?.invoke()
    }

    override fun clearPrivateSession(onComplete: (() -> Unit)?) {
        // TODO: Destroy the off-the-record BrowserContext natively
        onComplete?.invoke()
    }

    override fun clearFormData(context: Context) {
        // TODO: Interface with native autofill/form data manager
    }

    override fun getEngineName(): String = "Full Native Chromium Engine"

    override fun getEngineVersion(): String = "Source Build (Phase 4 Completed)"

    override fun isChromiumNative(): Boolean = true

    override fun shutdown() {
        // TODO: Flush native storage and shutdown BrowserContext
    }
}
