package com.example.haze

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class HazeMiniPlayer(
    private val activity: MainActivity,
    private val rootLayout: FrameLayout,
    private val onExpand: () -> Unit,
    private val onClose: () -> Unit
) {

    private val videoContainer: FrameLayout = rootLayout.findViewById(R.id.miniPlayerVideoContainer)
    private val controlsOverlay: FrameLayout = rootLayout.findViewById(R.id.miniPlayerControls)
    private val tvTitle: TextView = rootLayout.findViewById(R.id.tvMiniPlayerTitle)
    private val btnMinimize: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerMinimize)
    private val btnExpand: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerExpand)
    private val btnClose: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerClose)
    private val btnRewind: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerRewind)
    private val btnPlayPause: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerPlayPause)
    private val btnForward: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerForward)
    private val tvTime: TextView = rootLayout.findViewById(R.id.tvMiniPlayerTime)
    private val seekBar: SeekBar = rootLayout.findViewById(R.id.miniPlayerSeekBar)
    private val btnVolume: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerVolume)
    private val resizeHandle: ImageView = rootLayout.findViewById(R.id.miniPlayerResizeHandle)
    private val bottomBar: LinearLayout = rootLayout.findViewById(R.id.miniPlayerBottomBar)

    private var webView: WebView? = null
    var isActive = false
        private set
    var isCompactMode = false
        private set
    private var isPlaying = false
    private var isMuted = false

    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
    private var initialWidth = 0
    private var initialHeight = 0
    private var expandedWidth = dpToPx(320)
    private var expandedHeight = dpToPx(180)

    private val minWidth = dpToPx(160)
    private val minHeight = dpToPx(90)
    private val maxWidth = dpToPx(400)
    private val maxHeight = dpToPx(225)

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        if (isPlaying && !isCompactMode) {
            controlsOverlay.animate().alpha(0f).setDuration(300).start()
        }
    }

    init {
        setupDrag()
        setupResize()
        setupControls()
        setupOverlayToggle()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        controlsOverlay.setOnTouchListener { view, event ->
            if (isCompactMode && event.action == MotionEvent.ACTION_UP) {
                // Return to expanded mode
                setCompactMode(false)
                return@setOnTouchListener true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = rootLayout.translationX
                    initialY = rootLayout.translationY
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    
                    controlsOverlay.alpha = 1f
                    resetHideTimer()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    
                    val parent = rootLayout.parent as ViewGroup
                    val newX = max(0f, min(parent.width - rootLayout.width.toFloat(), initialX + dx))
                    val newY = max(0f, min(parent.height - rootLayout.height.toFloat(), initialY + dy))
                    
                    rootLayout.translationX = newX
                    rootLayout.translationY = newY
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResize() {
        resizeHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = rootLayout.width
                    initialHeight = rootLayout.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    resetHideTimer()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    val parent = rootLayout.parent as? ViewGroup
                    val availableWidth = parent?.width ?: maxWidth
                    val availableHeight = parent?.height ?: maxHeight
                    val boundedMaxWidth = min(maxWidth, availableWidth)
                    val boundedMaxHeight = min(maxHeight, availableHeight)
                    val newWidth = max(minWidth, min(boundedMaxWidth, (initialWidth + dx).toInt()))
                    val newHeight = (newWidth * 9) / 16
                    if (newHeight > boundedMaxHeight) return@setOnTouchListener true

                    val params = rootLayout.layoutParams
                    params.width = newWidth
                    params.height = newHeight
                    rootLayout.layoutParams = params
                    expandedWidth = newWidth
                    expandedHeight = newHeight
                    true
                }
                else -> false
            }
        }
    }

    private fun setupControls() {
        btnPlayPause.setOnClickListener { togglePlayPause(); resetHideTimer() }
        btnRewind.setOnClickListener { skipBy(-10); resetHideTimer() }
        btnForward.setOnClickListener { skipBy(10); resetHideTimer() }
        btnVolume.setOnClickListener { toggleMute(); resetHideTimer() }
        
        btnMinimize.setOnClickListener { setCompactMode(true) }
        btnExpand.setOnClickListener { expandToApp() }
        btnClose.setOnClickListener { close() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) seekTo(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { hideControlsHandler.removeCallbacks(hideControlsRunnable) }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { resetHideTimer() }
        })
    }

    private fun setupOverlayToggle() {
        rootLayout.setOnClickListener {
            if (isCompactMode) {
                setCompactMode(false)
            } else {
                if (controlsOverlay.alpha > 0f) {
                    controlsOverlay.animate().alpha(0f).setDuration(200).start()
                } else {
                    controlsOverlay.animate().alpha(1f).setDuration(200).start()
                    resetHideTimer()
                }
            }
        }
    }

    private fun setCompactMode(compact: Boolean) {
        isCompactMode = compact
        if (compact) {
            controlsOverlay.alpha = 0f
            hideControlsHandler.removeCallbacks(hideControlsRunnable)
            
            // Shrink size
            val params = rootLayout.layoutParams
            params.width = minWidth
            params.height = minHeight
            rootLayout.layoutParams = params
        } else {
            controlsOverlay.alpha = 1f
            resetHideTimer()
            
            // Restore default size
            val params = rootLayout.layoutParams
            params.width = expandedWidth
            params.height = expandedHeight
            rootLayout.layoutParams = params
        }
    }

    fun attach(webView: WebView, title: String) {
        this.webView = webView
        tvTitle.text = title
        isActive = true
        rootLayout.visibility = View.VISIBLE
        
        // Remove from original parent
        (webView.parent as? ViewGroup)?.removeView(webView)
        videoContainer.addView(webView)
        
        // Center on screen initially without resetting a position already chosen this session.
        val parent = rootLayout.parent as? ViewGroup
        if (parent != null && parent.width > 0 && rootLayout.translationX == 0f && rootLayout.translationY == 0f) {
            rootLayout.translationX = (parent.width - expandedWidth) / 2f
            rootLayout.translationY = (parent.height - expandedHeight) / 2f
        }

        injectVideoBridge()
        setCompactMode(false)
        resetHideTimer()
    }

    private fun expandToApp() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        restoreVideoElement()
        
        videoContainer.removeView(webView)
        rootLayout.visibility = View.GONE
        isActive = false
        
        onExpand()
    }

    fun close() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        restoreVideoElement()
        
        videoContainer.removeView(webView)
        rootLayout.visibility = View.GONE
        isActive = false
        
        onClose()
    }

    @SuppressLint("JavascriptInterface")
    private fun injectVideoBridge() {
        webView?.addJavascriptInterface(VideoBridge(), "HazeMiniPlayerBridge")
        
        val js = """
            (function() {
                var vids = document.getElementsByTagName('video');
                if(vids.length === 0) return;
                var v = vids[0];
                for(var i=0; i<vids.length; i++) {
                    if(!vids[i].paused || vids[i].currentTime > 0) { v = vids[i]; break; }
                }
                window.hazeActiveVideo = v;
                
                // Style for mini player
                v.dataset.origStyle = v.getAttribute('style') || '';
                v.style.position = 'fixed';
                v.style.top = '0';
                v.style.left = '0';
                v.style.width = '100vw';
                v.style.height = '100vh';
                v.style.zIndex = '2147483647';
                v.style.backgroundColor = '#000';
                v.style.objectFit = 'contain';
                
                function sendState() {
                    if(!window.hazeActiveVideo) return;
                    HazeMiniPlayerBridge.updateState(
                        !window.hazeActiveVideo.paused,
                        window.hazeActiveVideo.currentTime,
                        window.hazeActiveVideo.duration || 0,
                        window.hazeActiveVideo.muted
                    );
                }
                
                v.addEventListener('timeupdate', sendState);
                v.addEventListener('play', sendState);
                v.addEventListener('pause', sendState);
                v.addEventListener('volumechange', sendState);
                
                // Initialize
                sendState();
            })();
        """.trimIndent()
        
        webView?.evaluateJavascript(js, null)
    }

    private fun restoreVideoElement() {
        val js = """
            if(window.hazeActiveVideo) {
                window.hazeActiveVideo.setAttribute('style', window.hazeActiveVideo.dataset.origStyle || '');
                window.hazeActiveVideo = null;
            }
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
        webView?.removeJavascriptInterface("HazeMiniPlayerBridge")
    }

    private fun togglePlayPause() {
        val cmd = if (isPlaying) "pause()" else "play()"
        webView?.evaluateJavascript("if(window.hazeActiveVideo) window.hazeActiveVideo.$cmd;", null)
    }

    private fun skipBy(seconds: Int) {
        webView?.evaluateJavascript("if(window.hazeActiveVideo) window.hazeActiveVideo.currentTime += $seconds;", null)
    }

    private fun seekTo(progress: Int) {
        webView?.evaluateJavascript("if(window.hazeActiveVideo) window.hazeActiveVideo.currentTime = $progress;", null)
    }

    private fun toggleMute() {
        val newMute = !isMuted
        webView?.evaluateJavascript("if(window.hazeActiveVideo) window.hazeActiveVideo.muted = $newMute;", null)
    }

    private fun resetHideTimer() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 3000)
    }

    private fun formatTime(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return String.format("%d:%02d", min, sec)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * activity.resources.displayMetrics.density).toInt()
    }

    inner class VideoBridge {
        @JavascriptInterface
        fun updateState(playing: Boolean, current: Double, duration: Double, muted: Boolean) {
            activity.runOnUiThread {
                isPlaying = playing
                isMuted = muted
                btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
                btnVolume.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
                
                val c = current.toInt()
                val d = duration.toInt()
                
                if (d > 0) {
                    seekBar.max = d
                    seekBar.progress = c
                    tvTime.text = "${formatTime(c)} / ${formatTime(d)}"
                }
            }
        }
    }
}