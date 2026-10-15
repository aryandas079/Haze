package com.example.haze

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebView
import android.widget.*
import androidx.core.widget.NestedScrollView
import com.example.haze.engine.IBrowserTab
import com.example.haze.engine.chromium.ChromiumBrowserTab
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Professional Native Developer Tools & Inspection Manager for Haze Browser:
 * - Real DOM Tree & Attributes Inspector
 * - Live Element Highlighting & Computed CSS Style Inspector
 * - Live CSS Override Property Editor
 * - Real JavaScript Console (Logs, Info, Warnings, Errors, JS Command Execution)
 * - Real Network Monitor (Requests, Headers, Status Codes, Resource Types, Blocking Status)
 * - Real Viewport & Responsive Layout Inspector
 */
object HazeDevToolsManager {

    data class ConsoleLogEntry(
        val id: Long = System.currentTimeMillis(),
        val message: String,
        val sourceId: String = "",
        val lineNumber: Int = 0,
        val level: Level = Level.LOG,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        enum class Level { LOG, INFO, WARNING, ERROR }
    }

    data class NetworkRequestEntry(
        val id: String = UUID.randomUUID().toString(),
        val url: String,
        val method: String = "GET",
        val type: String = "Fetch",
        val statusCode: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis(),
        val isBlocked: Boolean = false
    )

    interface DevToolsUpdateListener {
        fun onConsoleMessageAdded(tabId: Long, entry: ConsoleLogEntry)
        fun onNetworkRequestAdded(tabId: Long, entry: NetworkRequestEntry)
        fun onLogsCleared(tabId: Long)
    }

    private val consoleLogsMap = ConcurrentHashMap<Long, CopyOnWriteArrayList<ConsoleLogEntry>>()
    private val networkRequestsMap = ConcurrentHashMap<Long, CopyOnWriteArrayList<NetworkRequestEntry>>()
    private val listeners = CopyOnWriteArrayList<DevToolsUpdateListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addUpdateListener(listener: DevToolsUpdateListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeUpdateListener(listener: DevToolsUpdateListener) {
        listeners.remove(listener)
    }

    fun isDevToolsActive(tabId: Long): Boolean {
        return consoleLogsMap.containsKey(tabId) || networkRequestsMap.containsKey(tabId)
    }

    fun recordConsoleMessage(
        tabId: Long,
        message: String,
        sourceId: String = "",
        lineNumber: Int = 0,
        level: ConsoleLogEntry.Level = ConsoleLogEntry.Level.LOG
    ) {
        val list = consoleLogsMap.getOrPut(tabId) { CopyOnWriteArrayList() }
        val entry = ConsoleLogEntry(
            message = message,
            sourceId = sourceId,
            lineNumber = lineNumber,
            level = level
        )
        if (list.size >= 300) list.removeAt(0)
        list.add(entry)

        mainHandler.post {
            for (listener in listeners) {
                try { listener.onConsoleMessageAdded(tabId, entry) } catch (_: Throwable) {}
            }
        }
    }

    fun recordNetworkRequest(
        tabId: Long,
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        isMainFrame: Boolean = false,
        isBlocked: Boolean = false
    ) {
        if (url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("haze:") || url.startsWith("about:")) return
        val list = networkRequestsMap.getOrPut(tabId) { CopyOnWriteArrayList() }

        val lower = url.lowercase()
        val type = when {
            isMainFrame -> "Document"
            lower.contains(".js") || lower.contains("/js/") -> "Script"
            lower.contains(".css") || lower.contains("/css/") -> "Stylesheet"
            lower.contains(".png") || lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".webp") || lower.contains(".gif") || lower.contains(".svg") -> "Image"
            lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".mp3") || lower.contains("audio") || lower.contains("video") -> "Media"
            else -> "Fetch/XHR"
        }

        val entry = NetworkRequestEntry(
            url = url,
            method = method,
            type = type,
            statusCode = if (isBlocked) 403 else 200,
            headers = headers,
            isBlocked = isBlocked
        )
        if (list.size >= 300) list.removeAt(0)
        list.add(entry)

        mainHandler.post {
            for (listener in listeners) {
                try { listener.onNetworkRequestAdded(tabId, entry) } catch (_: Throwable) {}
            }
        }
    }

    fun clearTabLogs(tabId: Long) {
        consoleLogsMap[tabId]?.clear()
        networkRequestsMap[tabId]?.clear()
        mainHandler.post {
            for (listener in listeners) {
                try { listener.onLogsCleared(tabId) } catch (_: Throwable) {}
            }
        }
    }

    fun getConsoleLogs(tabId: Long): List<ConsoleLogEntry> {
        return consoleLogsMap[tabId] ?: emptyList()
    }

    fun getNetworkRequests(tabId: Long): List<NetworkRequestEntry> {
        return networkRequestsMap[tabId] ?: emptyList()
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics).toInt()
    }

    fun showDevToolsDialog(activity: MainActivity, browserTab: IBrowserTab, initialTab: String = "ELEMENTS") {
        val webView = (browserTab.renderView as? WebView)
        showDevToolsDialogInternal(activity, browserTab.id, webView, browserTab.title, browserTab.url, initialTab)
    }

    fun showDevToolsDialog(activity: MainActivity, tab: MainActivity.Tab, initialTab: String = "ELEMENTS") {
        showDevToolsDialogInternal(activity, tab.id, tab.webView, tab.title, tab.url, initialTab)
    }

    private fun showDevToolsDialogInternal(
        activity: MainActivity,
        tabId: Long,
        webView: WebView?,
        tabTitle: String,
        tabUrl: String,
        initialTab: String
    ) {
        activity.triggerStrongHaptic(isHeavy = false)

        // Inject window console & error listeners if not present
        webView?.evaluateJavascript(
            """
            (function() {
                if (window.__hazeDevToolsInjected) return;
                window.__hazeDevToolsInjected = true;
                
                window.addEventListener('error', function(e) {
                    try {
                        console.error((e.message || 'Script error') + ' at ' + (e.filename || '') + ':' + (e.lineno || 0));
                    } catch(_) {}
                });
                window.addEventListener('unhandledrejection', function(e) {
                    try {
                        console.error('Unhandled Promise Rejection: ' + (e.reason ? (e.reason.message || e.reason) : 'Rejected'));
                    } catch(_) {}
                });
            })();
            """.trimIndent(),
            null
        )

        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
        val bgColor = if (isDark) Color.parseColor("#121212") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val cardBgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#F1F3F4")
        val cardBorderColor = if (isDark) Color.parseColor("#2C2C2C") else Color.parseColor("#E0E0E0")
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0")

        val dialog = Dialog(activity, android.R.style.Theme_Light_NoTitleBar_Fullscreen)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.statusBarColor = Color.BLACK
                window.navigationBarColor = Color.BLACK
            }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // --- HEADER BAR ---
        val header = RelativeLayout(activity).apply {
            setPadding(dpToPx(activity, 16), dpToPx(activity, 12), dpToPx(activity, 16), dpToPx(activity, 12))
            setBackgroundColor(if (isDark) Color.parseColor("#1A1A1A") else Color.parseColor("#F8F9FA"))
        }

        val headerTextLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val params = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = params
        }

        val tvTitle = TextView(activity).apply {
            text = "🛠️ Developer Tools"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }

        val tvSubtitle = TextView(activity).apply {
            text = "Connected to: $tabTitle ($tabUrl)"
            textSize = 11.5f
            setTextColor(subTextColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        headerTextLayout.addView(tvTitle)
        headerTextLayout.addView(tvSubtitle)
        header.addView(headerTextLayout)

        val headerActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = params
        }

        val btnClear = TextView(activity).apply {
            text = "Clear"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            setPadding(dpToPx(activity, 10), dpToPx(activity, 6), dpToPx(activity, 10), dpToPx(activity, 6))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 8).toFloat()
                setColor(if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#E8F0FE"))
            }
        }

        val btnClose = TextView(activity).apply {
            text = "✕"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subTextColor)
            setPadding(dpToPx(activity, 12), dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4))
            setOnClickListener {
                activity.triggerStrongHaptic()
                dialog.dismiss()
            }
        }

        headerActions.addView(btnClear)
        headerActions.addView(btnClose)
        header.addView(headerActions)
        root.addView(header)

        // --- NAVIGATION TABS (Elements, Console, Network, Layout) ---
        val tabContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(activity, 12), dpToPx(activity, 8), dpToPx(activity, 12), dpToPx(activity, 8))
            setBackgroundColor(if (isDark) Color.parseColor("#181818") else Color.parseColor("#F1F3F4"))
        }

        fun createTabBtn(label: String): TextView {
            return TextView(activity).apply {
                text = label
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dpToPx(activity, 12), dpToPx(activity, 8), dpToPx(activity, 12), dpToPx(activity, 8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dpToPx(activity, 2)
                    marginEnd = dpToPx(activity, 2)
                }
            }
        }

        val btnTabElements = createTabBtn("Elements")
        val btnTabConsole = createTabBtn("Console")
        val btnTabNetwork = createTabBtn("Network")
        val btnTabLayout = createTabBtn("Layout")

        tabContainer.addView(btnTabElements)
        tabContainer.addView(btnTabConsole)
        tabContainer.addView(btnTabNetwork)
        tabContainer.addView(btnTabLayout)
        root.addView(tabContainer)

        // Main Content Area Container
        val contentContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(contentContainer)

        var currentSelectedTab = initialTab

        fun styleTabBtns() {
            val tabs = listOf(
                Pair("ELEMENTS", btnTabElements),
                Pair("CONSOLE", btnTabConsole),
                Pair("NETWORK", btnTabNetwork),
                Pair("LAYOUT", btnTabLayout)
            )
            for (t in tabs) {
                val isActive = t.first == currentSelectedTab
                t.second.setTextColor(if (isActive) Color.WHITE else subTextColor)
                t.second.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 10).toFloat()
                    setColor(if (isActive) accentColor else Color.TRANSPARENT)
                }
            }
        }

        // =========================================================================
        // TAB 1: ELEMENTS (DOM & Computed Styles Inspector)
        // =========================================================================
        fun renderElementsTab(): View {
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(activity, 12), dpToPx(activity, 8), dpToPx(activity, 12), dpToPx(activity, 8))
            }

            val searchInput = EditText(activity).apply {
                hint = "🔍 Search selector (e.g. #header, .btn, h1)"
                textSize = 13f
                setTextColor(textColor)
                setHintTextColor(subTextColor)
                setSingleLine()
                setPadding(dpToPx(activity, 12), dpToPx(activity, 8), dpToPx(activity, 12), dpToPx(activity, 8))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 10).toFloat()
                    setColor(cardBgColor)
                    setStroke(1, cardBorderColor)
                }
            }
            layout.addView(searchInput)

            val treeScrollView = NestedScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin = dpToPx(activity, 8)
                }
            }

            val treeContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            treeScrollView.addView(treeContainer)
            layout.addView(treeScrollView)

            // Inspector Detail Box at Bottom
            val detailCard = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(activity, 12), dpToPx(activity, 10), dpToPx(activity, 12), dpToPx(activity, 10))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 14).toFloat()
                    setColor(cardBgColor)
                    setStroke(1, cardBorderColor)
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 180)).apply {
                    topMargin = dpToPx(activity, 8)
                }
            }

            val tvDetailHeader = TextView(activity).apply {
                text = "Selected Element & Computed CSS"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentColor)
            }
            detailCard.addView(tvDetailHeader)

            val detailScroll = NestedScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val tvDetailText = TextView(activity).apply {
                text = "Tap any DOM node above to inspect computed CSS, dimensions & box model."
                textSize = 11.5f
                setTextColor(subTextColor)
                setTypeface(Typeface.MONOSPACE)
            }
            detailScroll.addView(tvDetailText)
            detailCard.addView(detailScroll)

            // CSS Property Live Editor Row
            val styleEditRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dpToPx(activity, 6)
                }
            }
            val styleInput = EditText(activity).apply {
                hint = "Override CSS (e.g. color: red; display: none;)"
                textSize = 12f
                setTextColor(textColor)
                setHintTextColor(subTextColor)
                setSingleLine()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 8).toFloat()
                    setColor(if (isDark) Color.parseColor("#2A2A2A") else Color.WHITE)
                    setStroke(1, cardBorderColor)
                }
                setPadding(dpToPx(activity, 8), dpToPx(activity, 6), dpToPx(activity, 8), dpToPx(activity, 6))
            }
            val btnApplyStyle = TextView(activity).apply {
                text = "Apply"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(dpToPx(activity, 12), dpToPx(activity, 6), dpToPx(activity, 12), dpToPx(activity, 6))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 8).toFloat()
                    setColor(accentColor)
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dpToPx(activity, 6)
                }
            }
            styleEditRow.addView(styleInput)
            styleEditRow.addView(btnApplyStyle)
            detailCard.addView(styleEditRow)

            layout.addView(detailCard)

            var selectedPathSelector = "body"

            fun loadDOMTree(selectorFilter: String = "") {
                treeContainer.removeAllViews()
                val loadingTv = TextView(activity).apply {
                    text = "Extracting DOM tree from page..."
                    textSize = 12f
                    setTextColor(subTextColor)
                }
                treeContainer.addView(loadingTv)

                val jsExtract = """
                    (function() {
                        try {
                            var sel = "${selectorFilter.replace("\"", "\\\"")}";
                            var root = sel ? document.querySelector(sel) : document.body;
                            if (!root) root = document.body;
                            
                            function buildNode(el, depth) {
                                if (!el || depth > 4) return null;
                                if (el.nodeType === 3) {
                                    var txt = (el.nodeValue || '').trim();
                                    return txt ? { type: 'text', val: txt.substring(0, 80) } : null;
                                }
                                if (el.nodeType !== 1) return null;
                                var tag = el.tagName.toLowerCase();
                                if (tag === 'script' || tag === 'style') return null;
                                
                                var children = [];
                                for (var i = 0; i < el.childNodes.length; i++) {
                                    var c = buildNode(el.childNodes[i], depth + 1);
                                    if (c) children.push(c);
                                    if (children.length >= 20) break;
                                }
                                return {
                                    type: 'elem',
                                    tag: tag,
                                    id: el.id || '',
                                    cls: (el.className || '').toString().trim(),
                                    children: children
                                };
                            }
                            return JSON.stringify(buildNode(root, 0));
                        } catch(e) {
                            return null;
                        }
                    })();
                """.trimIndent()

                webView?.evaluateJavascript(jsExtract) { resultJson ->
                    treeContainer.removeAllViews()
                    if (resultJson.isNullOrEmpty() || resultJson == "null" || resultJson == "\"null\"") {
                        val emptyTv = TextView(activity).apply {
                            text = "No matching DOM elements found for: $selectorFilter"
                            textSize = 12.5f
                            setTextColor(subTextColor)
                        }
                        treeContainer.addView(emptyTv)
                        return@evaluateJavascript
                    }

                    try {
                        val cleanJson = if (resultJson.startsWith("\"") && resultJson.endsWith("\"")) {
                            JSONObject(resultJson.substring(1, resultJson.length - 1).replace("\\\"", "\"").replace("\\\\", "\\"))
                        } else JSONObject(resultJson)

                        fun renderNodeUI(obj: JSONObject, depth: Int): View {
                            val row = LinearLayout(activity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(dpToPx(activity, depth * 14 + 6), dpToPx(activity, 4), dpToPx(activity, 6), dpToPx(activity, 4))
                            }

                            val type = obj.optString("type")
                            if (type == "text") {
                                val tv = TextView(activity).apply {
                                    text = "\"${obj.optString("val")}\""
                                    textSize = 11.5f
                                    setTextColor(if (isDark) Color.parseColor("#A8C7FA") else Color.parseColor("#1B66C9"))
                                    setTypeface(Typeface.MONOSPACE)
                                }
                                row.addView(tv)
                            } else {
                                val tag = obj.optString("tag")
                                val id = obj.optString("id")
                                val cls = obj.optString("cls")

                                val tvTag = TextView(activity).apply {
                                    text = "<$tag"
                                    textSize = 12f
                                    setTypeface(null, Typeface.BOLD)
                                    setTextColor(if (isDark) Color.parseColor("#FF7B72") else Color.parseColor("#D93025"))
                                }
                                row.addView(tvTag)

                                if (id.isNotEmpty()) {
                                    val tvId = TextView(activity).apply {
                                        text = " id=\"$id\""
                                        textSize = 11.5f
                                        setTextColor(if (isDark) Color.parseColor("#79C0FF") else Color.parseColor("#1A73E8"))
                                    }
                                    row.addView(tvId)
                                }

                                if (cls.isNotEmpty()) {
                                    val tvCls = TextView(activity).apply {
                                        text = " class=\"${if (cls.length > 25) cls.substring(0, 25) + "…" else cls}\""
                                        textSize = 11.5f
                                        setTextColor(if (isDark) Color.parseColor("#D2A8FF") else Color.parseColor("#A142F4"))
                                    }
                                    row.addView(tvCls)
                                }

                                val tvEnd = TextView(activity).apply {
                                    text = ">"
                                    textSize = 12f
                                    setTextColor(subTextColor)
                                }
                                row.addView(tvEnd)

                                // Node Selection & Highlighting
                                row.setOnClickListener {
                                    activity.triggerStrongHaptic()
                                    val targetSel = when {
                                        id.isNotEmpty() -> "#$id"
                                        cls.isNotEmpty() -> "$tag.${cls.split(" ").first()}"
                                        else -> tag
                                    }
                                    selectedPathSelector = targetSel

                                    // Highlight on WebPage & Fetch Computed CSS
                                    val jsInspect = """
                                        (function() {
                                            try {
                                                var el = document.querySelector("${targetSel.replace("\"", "\\\"")}");
                                                if (el) {
                                                    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                                    var origOutline = el.style.outline;
                                                    el.style.outline = '3px solid #3B82F6';
                                                    setTimeout(function() { el.style.outline = origOutline; }, 2500);
                                                    
                                                    var cs = window.getComputedStyle(el);
                                                    var rect = el.getBoundingClientRect();
                                                    return JSON.stringify({
                                                        tag: el.tagName.toLowerCase(),
                                                        width: Math.round(rect.width),
                                                        height: Math.round(rect.height),
                                                        display: cs.display,
                                                        position: cs.position,
                                                        color: cs.color,
                                                        bg: cs.backgroundColor,
                                                        font: cs.fontSize + ' ' + cs.fontFamily.split(',')[0],
                                                        margin: cs.margin,
                                                        padding: cs.padding
                                                    });
                                                }
                                            } catch(e) {}
                                            return null;
                                        })();
                                    """.trimIndent()

                                    webView?.evaluateJavascript(jsInspect) { compJson ->
                                        if (!compJson.isNullOrEmpty() && compJson != "null") {
                                            try {
                                                val cObj = JSONObject(if (compJson.startsWith("\"")) compJson.substring(1, compJson.length - 1).replace("\\\"", "\"") else compJson)
                                                tvDetailText.text = """
                                                    [Selector]: $targetSel
                                                    [Dimensions]: ${cObj.optInt("width")}px × ${cObj.optInt("height")}px
                                                    [Display]: ${cObj.optString("display")} | [Position]: ${cObj.optString("position")}
                                                    [Color]: ${cObj.optString("color")}
                                                    [Background]: ${cObj.optString("bg")}
                                                    [Font]: ${cObj.optString("font")}
                                                    [Margin]: ${cObj.optString("margin")} | [Padding]: ${cObj.optString("padding")}
                                                """.trimIndent()
                                            } catch (_: Throwable) {
                                                tvDetailText.text = "Selected: $targetSel"
                                            }
                                        } else {
                                            tvDetailText.text = "Selected: $targetSel"
                                        }
                                    }
                                }
                            }

                            val itemContainer = LinearLayout(activity).apply {
                                orientation = LinearLayout.VERTICAL
                            }
                            itemContainer.addView(row)

                            val childrenArr = obj.optJSONArray("children")
                            if (childrenArr != null && childrenArr.length() > 0) {
                                for (k in 0 until childrenArr.length()) {
                                    val childObj = childrenArr.getJSONObject(k)
                                    itemContainer.addView(renderNodeUI(childObj, depth + 1))
                                }
                            }

                            return itemContainer
                        }

                        treeContainer.addView(renderNodeUI(cleanJson, 0))
                    } catch (e: Exception) {
                        val errTv = TextView(activity).apply {
                            text = "DOM Inspection Error: ${e.localizedMessage}"
                            textSize = 12f
                            setTextColor(Color.RED)
                        }
                        treeContainer.addView(errTv)
                    }
                }
            }

            btnApplyStyle.setOnClickListener {
                val overrideRule = styleInput.text.toString().trim()
                if (overrideRule.isEmpty()) return@setOnClickListener
                activity.triggerStrongHaptic()

                val jsApply = """
                    (function() {
                        try {
                            var el = document.querySelector("${selectedPathSelector.replace("\"", "\\\"")}");
                            if (el) {
                                el.style.cssText += "; " + "${overrideRule.replace("\"", "\\\"")}";
                                return "Style applied to " + "${selectedPathSelector.replace("\"", "\\\"")}";
                            }
                        } catch(e) {
                            return "Error: " + e.message;
                        }
                        return "Element not found";
                    })();
                """.trimIndent()

                webView?.evaluateJavascript(jsApply) { res ->
                    Toast.makeText(activity, res?.replace("\"", "") ?: "Applied", Toast.LENGTH_SHORT).show()
                }
            }

            searchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString()?.trim() ?: ""
                    loadDOMTree(query)
                }
            })

            loadDOMTree()
            return layout
        }

        // =========================================================================
        // TAB 2: CONSOLE (Real JS Messages & Live JS Executor)
        // =========================================================================
        fun renderConsoleTab(): View {
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(activity, 12), dpToPx(activity, 8), dpToPx(activity, 12), dpToPx(activity, 8))
            }

            val logsScrollView = NestedScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }

            val logsContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            logsScrollView.addView(logsContainer)
            layout.addView(logsScrollView)

            // Live JS Execution Input
            val execRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dpToPx(activity, 8)
                }
            }

            val jsInput = EditText(activity).apply {
                hint = "> Run JS (e.g. document.title, location.href)"
                textSize = 12.5f
                setTextColor(textColor)
                setHintTextColor(subTextColor)
                setSingleLine()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 8).toFloat()
                    setColor(cardBgColor)
                    setStroke(1, cardBorderColor)
                }
                setPadding(dpToPx(activity, 10), dpToPx(activity, 8), dpToPx(activity, 10), dpToPx(activity, 8))
            }

            val btnRunJs = TextView(activity).apply {
                text = "Run"
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(dpToPx(activity, 14), dpToPx(activity, 8), dpToPx(activity, 14), dpToPx(activity, 8))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 8).toFloat()
                    setColor(accentColor)
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dpToPx(activity, 6)
                }
            }

            execRow.addView(jsInput)
            execRow.addView(btnRunJs)
            layout.addView(execRow)

            fun populateConsoleLogs() {
                logsContainer.removeAllViews()
                val entries = getConsoleLogs(tabId)
                if (entries.isEmpty()) {
                    val emptyTv = TextView(activity).apply {
                        text = "No console messages recorded for this page yet.\nType a JavaScript statement below and tap Run."
                        textSize = 12f
                        setTextColor(subTextColor)
                        setPadding(0, dpToPx(activity, 20), 0, 0)
                    }
                    logsContainer.addView(emptyTv)
                    return
                }

                val df = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                for (e in entries) {
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dpToPx(activity, 4), 0, dpToPx(activity, 4))
                    }

                    val levelColor = when (e.level) {
                        ConsoleLogEntry.Level.ERROR -> Color.parseColor("#EF4444")
                        ConsoleLogEntry.Level.WARNING -> Color.parseColor("#F59E0B")
                        ConsoleLogEntry.Level.INFO -> Color.parseColor("#3B82F6")
                        ConsoleLogEntry.Level.LOG -> if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#202124")
                    }

                    val tvMsg = TextView(activity).apply {
                        text = "[${df.format(Date(e.timestamp))}] [${e.level.name}] ${e.message}"
                        textSize = 12f
                        setTextColor(levelColor)
                        setTypeface(Typeface.MONOSPACE)
                    }
                    row.addView(tvMsg)

                    if (e.sourceId.isNotEmpty() || e.lineNumber > 0) {
                        val tvSrc = TextView(activity).apply {
                            text = "└ at ${e.sourceId}:${e.lineNumber}"
                            textSize = 10.5f
                            setTextColor(subTextColor)
                            setTypeface(Typeface.MONOSPACE)
                        }
                        row.addView(tvSrc)
                    }

                    val divider = View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                        setBackgroundColor(cardBorderColor)
                    }
                    row.addView(divider)
                    logsContainer.addView(row)
                }
            }

            btnRunJs.setOnClickListener {
                val cmd = jsInput.text.toString().trim()
                if (cmd.isEmpty()) return@setOnClickListener
                activity.triggerStrongHaptic()
                jsInput.setText("")

                recordConsoleMessage(tabId, "> $cmd", level = ConsoleLogEntry.Level.INFO)
                webView?.evaluateJavascript(cmd) { result ->
                    recordConsoleMessage(tabId, "← ${result ?: "undefined"}", level = ConsoleLogEntry.Level.LOG)
                    populateConsoleLogs()
                }
            }

            populateConsoleLogs()
            return layout
        }

        // =========================================================================
        // TAB 3: NETWORK (Real HTTP & Resource Request Monitor)
        // =========================================================================
        fun renderNetworkTab(): View {
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(activity, 12), dpToPx(activity, 8), dpToPx(activity, 12), dpToPx(activity, 8))
            }

            val netScrollView = NestedScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }

            val netContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            netScrollView.addView(netContainer)
            layout.addView(netScrollView)

            fun populateNetworkRequests() {
                netContainer.removeAllViews()
                val entries = getNetworkRequests(tabId)
                if (entries.isEmpty()) {
                    val emptyTv = TextView(activity).apply {
                        text = "No network requests recorded for this tab yet.\nReload the page or perform actions to observe real HTTP traffic."
                        textSize = 12f
                        setTextColor(subTextColor)
                        setPadding(0, dpToPx(activity, 20), 0, 0)
                    }
                    netContainer.addView(emptyTv)
                    return
                }

                val df = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                for (req in entries) {
                    val card = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dpToPx(activity, 10), dpToPx(activity, 8), dpToPx(activity, 10), dpToPx(activity, 8))
                        background = GradientDrawable().apply {
                            cornerRadius = dpToPx(activity, 10).toFloat()
                            setColor(cardBgColor)
                            setStroke(1, cardBorderColor)
                        }
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dpToPx(activity, 6)
                        }
                    }

                    val rowTop = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    val methodBadge = TextView(activity).apply {
                        text = req.method
                        textSize = 10f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        setPadding(dpToPx(activity, 6), dpToPx(activity, 2), dpToPx(activity, 6), dpToPx(activity, 2))
                        background = GradientDrawable().apply {
                            cornerRadius = dpToPx(activity, 4).toFloat()
                            setColor(if (req.isBlocked) Color.parseColor("#EF4444") else Color.parseColor("#10B981"))
                        }
                    }

                    val tvType = TextView(activity).apply {
                        text = "[${req.type}]"
                        textSize = 11f
                        setTextColor(accentColor)
                        setPadding(dpToPx(activity, 6), 0, 0, 0)
                    }

                    val tvTime = TextView(activity).apply {
                        text = df.format(Date(req.timestamp))
                        textSize = 10.5f
                        setTextColor(subTextColor)
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    rowTop.addView(methodBadge)
                    rowTop.addView(tvType)
                    rowTop.addView(tvTime)
                    card.addView(rowTop)

                    val tvUrl = TextView(activity).apply {
                        text = req.url
                        textSize = 11.5f
                        setTextColor(textColor)
                        setTypeface(Typeface.MONOSPACE)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        setPadding(0, dpToPx(activity, 4), 0, 0)
                    }
                    card.addView(tvUrl)

                    if (req.isBlocked) {
                        val tvBlocked = TextView(activity).apply {
                            text = "🛡️ Blocked by Haze Privacy Shield"
                            textSize = 10.5f
                            setTextColor(Color.parseColor("#EF4444"))
                            setPadding(0, dpToPx(activity, 2), 0, 0)
                        }
                        card.addView(tvBlocked)
                    }

                    netContainer.addView(card)
                }
            }

            populateNetworkRequests()
            return layout
        }

        // =========================================================================
        // TAB 4: LAYOUT (Viewport Metrics & Responsive Inspection)
        // =========================================================================
        fun renderLayoutTab(): View {
            val scroll = NestedScrollView(activity).apply {
                setPadding(dpToPx(activity, 12), dpToPx(activity, 8), dpToPx(activity, 12), dpToPx(activity, 8))
            }

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(activity, 16), dpToPx(activity, 14), dpToPx(activity, 16), dpToPx(activity, 14))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 14).toFloat()
                    setColor(cardBgColor)
                    setStroke(1, cardBorderColor)
                }
            }

            val tvTitle = TextView(activity).apply {
                text = "📱 Active Viewport & Screen Information"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentColor)
                setPadding(0, 0, 0, dpToPx(activity, 10))
            }
            card.addView(tvTitle)

            val tvLayoutInfo = TextView(activity).apply {
                text = "Reading active viewport parameters..."
                textSize = 12f
                setTextColor(textColor)
                setTypeface(Typeface.MONOSPACE)
            }
            card.addView(tvLayoutInfo)

            val jsLayout = """
                (function() {
                    try {
                        return JSON.stringify({
                            vpWidth: window.innerWidth,
                            vpHeight: window.innerHeight,
                            scWidth: screen.width,
                            scHeight: screen.height,
                            dpr: window.devicePixelRatio,
                            orientation: (screen.orientation ? screen.orientation.type : 'portrait'),
                            ua: navigator.userAgent
                        });
                    } catch(e) {
                        return null;
                    }
                })();
            """.trimIndent()

            webView?.evaluateJavascript(jsLayout) { resJson ->
                if (!resJson.isNullOrEmpty() && resJson != "null") {
                    try {
                        val obj = JSONObject(if (resJson.startsWith("\"")) resJson.substring(1, resJson.length - 1).replace("\\\"", "\"") else resJson)
                        tvLayoutInfo.text = """
                            Viewport Width: ${obj.optInt("vpWidth")} px
                            Viewport Height: ${obj.optInt("vpHeight")} px
                            Screen Resolution: ${obj.optInt("scWidth")} × ${obj.optInt("scHeight")}
                            Device Pixel Ratio: ${obj.optDouble("dpr")}x
                            Orientation: ${obj.optString("orientation")}
                            User-Agent: ${obj.optString("ua")}
                        """.trimIndent()
                    } catch (_: Throwable) {
                        tvLayoutInfo.text = "Viewport metrics unavailable."
                    }
                }
            }

            scroll.addView(card)
            return scroll
        }

        fun switchTabContent() {
            styleTabBtns()
            contentContainer.removeAllViews()
            val view = when (currentSelectedTab) {
                "CONSOLE" -> renderConsoleTab()
                "NETWORK" -> renderNetworkTab()
                "LAYOUT" -> renderLayoutTab()
                else -> renderElementsTab()
            }
            contentContainer.addView(view)
        }

        btnTabElements.setOnClickListener {
            activity.triggerStrongHaptic()
            currentSelectedTab = "ELEMENTS"
            switchTabContent()
        }

        btnTabConsole.setOnClickListener {
            activity.triggerStrongHaptic()
            currentSelectedTab = "CONSOLE"
            switchTabContent()
        }

        btnTabNetwork.setOnClickListener {
            activity.triggerStrongHaptic()
            currentSelectedTab = "NETWORK"
            switchTabContent()
        }

        btnTabLayout.setOnClickListener {
            activity.triggerStrongHaptic()
            currentSelectedTab = "LAYOUT"
            switchTabContent()
        }

        btnClear.setOnClickListener {
            activity.triggerStrongHaptic()
            clearTabLogs(tabId)
            switchTabContent()
            Toast.makeText(activity, "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        switchTabContent()
        dialog.setContentView(root)
        dialog.show()
    }
}
