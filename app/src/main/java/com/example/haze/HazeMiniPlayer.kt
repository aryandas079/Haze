package com.example.haze

import android.annotation.SuppressLint
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import com.example.haze.engine.IBrowserTab
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private val topBar: LinearLayout = rootLayout.findViewById(R.id.miniPlayerTopBar)
    private val title: TextView = rootLayout.findViewById(R.id.tvMiniPlayerTitle)
    private val playPause: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerPlayPause)
    private val rewind: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerRewind)
    private val forward: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerForward)
    private val minimize: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerMinimize)
    private val expand: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerExpand)
    private val fullscreen: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerFullscreen)
    private val closeButton: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerClose)
    private val volume: ImageButton = rootLayout.findViewById(R.id.btnMiniPlayerVolume)
    private val seekBar: SeekBar = rootLayout.findViewById(R.id.miniPlayerSeekBar)
    private val time: TextView = rootLayout.findViewById(R.id.tvMiniPlayerTime)
    private val resizeHandle: ImageView = rootLayout.findViewById(R.id.miniPlayerResizeHandle)
    private val buffering: ProgressBar = rootLayout.findViewById(R.id.miniPlayerBuffering)
    private val error: TextView = rootLayout.findViewById(R.id.miniPlayerError)
    private val handler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable {
        if (isPlaying && !isCompactMode) controlsOverlay.animate().alpha(0f).setDuration(220).start()
    }
    private var browserTab: IBrowserTab? = null
    private var session: MediaSession? = null
    private var isPlaying = false
    private var isMuted = false
    private var durationSeconds = 0
    private var positionSeconds = 0
    private var dragX = 0f
    private var dragY = 0f
    private var startX = 0f
    private var startY = 0f
    private var startWidth = 0
    private var startHeight = 0
    private var expandedWidth = dp(320)
    private var expandedHeight = dp(180)
    private val minWidth get() = dp(160)
    private val maxWidth get() = max(minWidth, min(dp(560), (rootLayout.parent as? View)?.width ?: dp(560)))

    var isActive = false
        private set
    var isCompactMode = false
        private set

    init {
        setupDrag()
        setupResize()
        setupControls()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        topBar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = rootLayout.translationX
                    dragY = rootLayout.translationY
                    startX = event.rawX
                    startY = event.rawY
                    showControls()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    moveWithinParent(dragX + event.rawX - startX, dragY + event.rawY - startY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    snapToNearestEdge()
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
                    startWidth = rootLayout.width
                    startHeight = rootLayout.height
                    startX = event.rawX
                    startY = event.rawY
                    showControls()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parent = rootLayout.parent as? View ?: return@setOnTouchListener true
                    val ratio = if (startHeight > 0) startWidth.toFloat() / startHeight else 16f / 9f
                    val width = (startWidth + event.rawX - startX).toInt().coerceIn(minWidth, maxWidth)
                    val height = (width / ratio).toInt()
                    val maxHeight = max(dp(90), parent.height - rootLayout.translationY.toInt())
                    val boundedHeight = min(height, maxHeight)
                    val boundedWidth = min(width, (boundedHeight * ratio).toInt())
                    rootLayout.layoutParams = rootLayout.layoutParams.apply {
                        this.width = boundedWidth
                        this.height = boundedHeight
                    }
                    expandedWidth = boundedWidth
                    expandedHeight = boundedHeight
                    moveWithinParent(rootLayout.translationX, rootLayout.translationY)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupControls() {
        playPause.setOnClickListener { evaluate("if(window.hazeActiveVideo) window.hazeActiveVideo.paused ? window.hazeActiveVideo.play() : window.hazeActiveVideo.pause()"); showControls() }
        rewind.setOnClickListener { seekBy(-10); showControls() }
        forward.setOnClickListener { seekBy(10); showControls() }
        volume.setOnClickListener { evaluate("if(window.hazeActiveVideo) window.hazeActiveVideo.muted = !window.hazeActiveVideo.muted"); showControls() }
        minimize.setOnClickListener { setCompactMode(true) }
        expand.setOnClickListener { expandToApp() }
        fullscreen.setOnClickListener { evaluate("if(window.hazeActiveVideo){if(document.fullscreenElement)document.exitFullscreen();else if(window.hazeActiveVideo.requestFullscreen)window.hazeActiveVideo.requestFullscreen();}") }
        closeButton.setOnClickListener { close() }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) evaluate("if(window.hazeActiveVideo) window.hazeActiveVideo.currentTime=$progress")
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { handler.removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(bar: SeekBar?) { scheduleHide() }
        })
        videoContainer.setOnClickListener {
            if (isCompactMode) setCompactMode(false) else if (controlsOverlay.alpha == 0f) showControls() else hideControlsNow()
        }
    }

    fun attach(tab: IBrowserTab, videoTitle: String) {
        browserTab = tab
        val view = tab.renderView
        title.text = videoTitle.ifBlank { "Video Player" }
        isActive = true
        error.visibility = View.GONE
        rootLayout.visibility = View.VISIBLE
        (view.parent as? ViewGroup)?.removeView(view)
        videoContainer.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.visibility = View.VISIBLE
        view.bringToFront()
        rootLayout.bringToFront()
        if (rootLayout.translationX == 0f && rootLayout.translationY == 0f) {
            rootLayout.post {
                val parent = rootLayout.parent as? View
                if (parent != null) moveWithinParent((parent.width - rootLayout.width) / 2f, (parent.height - rootLayout.height) / 2f)
            }
        }
        session = MediaSession(activity, "HazeMiniPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = evaluate("if(window.hazeActiveVideo) window.hazeActiveVideo.play()")
                override fun onPause() = evaluate("if(window.hazeActiveVideo) window.hazeActiveVideo.pause()")
                override fun onSeekTo(pos: Long) = evaluate("if(window.hazeActiveVideo) window.hazeActiveVideo.currentTime=${pos / 1000.0}")
            })
            isActive = true
        }
        videoContainer.requestLayout()
        injectBridge()
        setCompactMode(false)
        showControls()
    }

    fun close() {
        releaseVideo()
        onClose()
    }

    fun release() {
        if (isActive) releaseVideo()
    }

    fun togglePlayback() {
        evaluate("if(window.hazeActiveVideo) window.hazeActiveVideo.paused ? window.hazeActiveVideo.play() : window.hazeActiveVideo.pause()")
    }

    private fun expandToApp() {
        releaseVideo()
        onExpand()
    }

    private fun releaseVideo() {
        handler.removeCallbacks(hideControls)
        evaluate("if(window.hazeActiveVideo){window.hazeActiveVideo.setAttribute('style',window.hazeActiveVideo.dataset.hazeMiniStyle||'');window.hazeActiveVideo=null}")
        browserTab?.removeJavascriptInterface("HazeMiniPlayerBridge")
        browserTab?.renderView?.let { videoContainer.removeView(it) }
        session?.run { isActive = false; release() }
        session = null
        isActive = false
        browserTab = null
        rootLayout.visibility = View.GONE
    }

    private fun injectBridge() {
        browserTab?.addJavascriptInterface(Bridge(), "HazeMiniPlayerBridge")
        evaluate("""
            (function(){
              var v=window.hazeActiveVideo;
              if(!v){var a=document.querySelectorAll('video');for(var i=0;i<a.length;i++)if(!a[i].paused||a[i].currentTime>0){v=a[i];break;}if(!v&&a.length)v=a[0];}
              if(!v)return; window.hazeActiveVideo=v;
              var wasPlaying=!v.paused&&!v.ended;
              if(!v.dataset.hazeMiniStyle){v.dataset.hazeMiniStyle=v.getAttribute('style')||'';}
              v.style.cssText+=';position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;';
              function s(){HazeMiniPlayerBridge.updateState(!v.paused,v.currentTime||0,isFinite(v.duration)?v.duration:0,v.muted,v.readyState<3,v.error?String(v.error.code):'');}
              ['timeupdate','play','playing','pause','ended','volumechange','durationchange','progress','waiting','canplay','error'].forEach(function(e){v.addEventListener(e,s);});s();
              if(wasPlaying){var p=v.play();if(p&&p.catch)p.catch(function(){});}
            })()
        """.trimIndent())
    }

    private fun evaluate(script: String) { browserTab?.evaluateJavascript(script, null) }
    private fun seekBy(seconds: Int) { evaluate("if(window.hazeActiveVideo) window.hazeActiveVideo.currentTime=Math.max(0,window.hazeActiveVideo.currentTime+$seconds)") }

    private fun setCompactMode(compact: Boolean) {
        isCompactMode = compact
        if (compact) {
            handler.removeCallbacks(hideControls)
            controlsOverlay.alpha = 0f
            resizeHandle.visibility = View.GONE
            rootLayout.layoutParams = rootLayout.layoutParams.apply { width = minWidth; height = dp(90) }
        } else {
            controlsOverlay.alpha = 1f
            resizeHandle.visibility = View.VISIBLE
            rootLayout.layoutParams = rootLayout.layoutParams.apply { width = expandedWidth; height = expandedHeight }
            scheduleHide()
        }
        moveWithinParent(rootLayout.translationX, rootLayout.translationY)
    }

    private fun moveWithinParent(x: Float, y: Float) {
        val parent = rootLayout.parent as? View ?: return
        val right = max(0, parent.width - rootLayout.width).toFloat()
        val bottom = max(0, parent.height - rootLayout.height).toFloat()
        rootLayout.translationX = x.coerceIn(0f, right)
        rootLayout.translationY = y.coerceIn(0f, bottom)
    }

    private fun snapToNearestEdge() {
        val parent = rootLayout.parent as? View ?: return
        val target = if (rootLayout.translationX + rootLayout.width / 2f < parent.width / 2f) 0f else (parent.width - rootLayout.width).toFloat()
        rootLayout.animate().translationX(target.coerceAtLeast(0f)).setDuration(180).start()
    }

    private fun showControls() { controlsOverlay.animate().alpha(1f).setDuration(160).start(); scheduleHide() }
    private fun hideControlsNow() { handler.removeCallbacks(hideControls); controlsOverlay.animate().alpha(0f).setDuration(160).start() }
    private fun scheduleHide() { handler.removeCallbacks(hideControls); if (isPlaying && !isCompactMode) handler.postDelayed(hideControls, 3000) }

    private fun updateSession() {
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session?.setPlaybackState(PlaybackState.Builder().setState(state, positionSeconds * 1000L, 1f).setActions(
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO
        ).build())
        session?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title.text.toString()).build())
    }

    private fun format(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    inner class Bridge {
        @JavascriptInterface
        fun updateState(playing: Boolean, current: Double, duration: Double, muted: Boolean, waiting: Boolean, errorCode: String) {
            activity.runOnUiThread {
                isPlaying = playing; isMuted = muted
                activity.isMediaPlaying = playing
                positionSeconds = current.toInt().coerceAtLeast(0)
                durationSeconds = duration.toInt().coerceAtLeast(0)
                seekBar.max = durationSeconds
                seekBar.progress = positionSeconds.coerceAtMost(durationSeconds)
                time.text = "${format(positionSeconds)} / ${format(durationSeconds)}"
                playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
                volume.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
                buffering.visibility = if (waiting && errorCode.isEmpty()) View.VISIBLE else View.GONE
                error.visibility = if (errorCode.isNotEmpty()) View.VISIBLE else View.GONE
                updateSession()
                if (playing) scheduleHide()
            }
        }
    }
}
