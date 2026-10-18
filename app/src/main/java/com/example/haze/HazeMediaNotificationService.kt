package com.example.haze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Foreground Service for displaying media player controls in the Android Notification Center / Shade,
 * just like YouTube Music, Spotify, Apple Music, and Chrome.
 */
class HazeMediaNotificationService : Service() {

    companion object {
        const val CHANNEL_ID = "haze_media_playback_channel_v2"
        const val OLD_CHANNEL_ID = "haze_media_playback_channel"
        const val NOTIFICATION_ID = 4099

        const val ACTION_PLAY = "com.aryandas.haze.ACTION_MEDIA_PLAY"
        const val ACTION_PAUSE = "com.aryandas.haze.ACTION_MEDIA_PAUSE"
        const val ACTION_PREV = "com.aryandas.haze.ACTION_MEDIA_PREV"
        const val ACTION_NEXT = "com.aryandas.haze.ACTION_MEDIA_NEXT"
        const val ACTION_STOP = "com.aryandas.haze.ACTION_MEDIA_STOP"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_ARTWORK_URL = "extra_artwork_url"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_IS_MUTED = "extra_is_muted"

        // Callback interface for communicating back with MainActivity
        interface MediaActionListener {
            fun onNotificationPlay()
            fun onNotificationPause()
            fun onNotificationPrev()
            fun onNotificationNext()
            fun onNotificationStop()
        }

        var listener: MediaActionListener? = null

        private var lastSentTitle = ""
        private var lastSentArtist = ""
        private var lastSentPlaying: Boolean? = null
        private var lastSentArtworkUrl = ""

        fun updateMediaNotification(
            context: Context,
            title: String,
            artist: String,
            artworkUrl: String,
            isPlaying: Boolean,
            isMuted: Boolean
        ) {
            if (title == lastSentTitle && artist == lastSentArtist && isPlaying == lastSentPlaying && artworkUrl == lastSentArtworkUrl) {
                return
            }
            lastSentTitle = title
            lastSentArtist = artist
            lastSentPlaying = isPlaying
            lastSentArtworkUrl = artworkUrl

            val intent = Intent(context, HazeMediaNotificationService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_ARTWORK_URL, artworkUrl)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
                putExtra(EXTRA_IS_MUTED, isMuted)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stopMediaNotification(context: Context) {
            lastSentTitle = ""
            lastSentArtist = ""
            lastSentPlaying = null
            lastSentArtworkUrl = ""

            val intent = Intent(context, HazeMediaNotificationService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private var mediaSession: MediaSessionCompat? = null
    private val imageExecutor = Executors.newSingleThreadExecutor()
    private var lastArtworkUrl = ""
    private var cachedArtworkBitmap: Bitmap? = null

    private var currentTitle = "Playing Media"
    private var currentArtist = "Haze Browser"
    private var currentIsPlaying = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
        val initialNotification = buildNotification(null)
        startForeground(NOTIFICATION_ID, initialNotification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            try {
                nm?.deleteNotificationChannel(OLD_CHANNEL_ID)
            } catch (_: Exception) {}

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Playback Controls",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows live media controls in notification center during playback"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun initMediaSession() {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingClickIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        mediaSession = MediaSessionCompat(this, "HazeMediaSession").apply {
            setSessionActivity(pendingClickIntent)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    listener?.onNotificationPlay()
                }

                override fun onPause() {
                    listener?.onNotificationPause()
                }

                override fun onSkipToPrevious() {
                    listener?.onNotificationPrev()
                }

                override fun onSkipToNext() {
                    listener?.onNotificationNext()
                }

                override fun onStop() {
                    listener?.onNotificationStop()
                    stopForegroundService()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null) {
            when (action) {
                ACTION_PLAY -> listener?.onNotificationPlay()
                ACTION_PAUSE -> listener?.onNotificationPause()
                ACTION_PREV -> listener?.onNotificationPrev()
                ACTION_NEXT -> listener?.onNotificationNext()
                ACTION_STOP -> {
                    listener?.onNotificationStop()
                    stopForegroundService()
                    return START_NOT_STICKY
                }
            }
            return START_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: currentTitle
        val artist = intent?.getStringExtra(EXTRA_ARTIST) ?: currentArtist
        val artworkUrl = intent?.getStringExtra(EXTRA_ARTWORK_URL) ?: ""
        val isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, currentIsPlaying) ?: currentIsPlaying

        val cleanTitle = if (title.isNotBlank()) title else "Playing Media"
        val cleanArtist = if (artist.isNotBlank()) artist else "Haze Browser"

        // Avoid re-entrant state updates and audio focus storms
        if (cleanTitle == currentTitle && cleanArtist == currentArtist && isPlaying == currentIsPlaying && (artworkUrl.isBlank() || artworkUrl == lastArtworkUrl)) {
            val notif = buildNotification(cachedArtworkBitmap)
            startForeground(NOTIFICATION_ID, notif)
            return START_STICKY
        }

        currentTitle = cleanTitle
        currentArtist = cleanArtist
        currentIsPlaying = isPlaying

        // Update MediaSession state
        updateMediaSessionState(isPlaying)

        val notification = buildNotification(cachedArtworkBitmap)
        startForeground(NOTIFICATION_ID, notification)

        // Check if artwork needs to be fetched
        if (artworkUrl.isNotBlank() && artworkUrl != lastArtworkUrl) {
            lastArtworkUrl = artworkUrl
            fetchArtwork(artworkUrl)
        }

        return START_STICKY
    }

    private fun updateMediaSessionState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentArtist)
        val bmp = cachedArtworkBitmap
        if (bmp != null && !bmp.isRecycled) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bmp)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bmp)
        }
        mediaSession?.setMetadata(metadataBuilder.build())
    }

    private fun fetchArtwork(urlStr: String) {
        if (urlStr.startsWith("data:image")) {
            try {
                val base64Data = urlStr.substringAfter("base64,")
                val decoded = Base64.decode(base64Data, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                if (bmp != null) {
                    cachedArtworkBitmap = bmp
                    updateMediaSessionState(currentIsPlaying)
                    val notification = buildNotification(bmp)
                    startForeground(NOTIFICATION_ID, notification)
                    return
                }
            } catch (_: Exception) {}
        }

        imageExecutor.execute {
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 4000
                conn.doInput = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; Mobile) HazeBrowser/2.2")
                conn.connect()
                val inputStream: InputStream = conn.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (bitmap != null) {
                    cachedArtworkBitmap = bitmap
                    updateMediaSessionState(currentIsPlaying)
                    val notification = buildNotification(bitmap)
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIFICATION_ID, notification)
                }
            } catch (_: Exception) {
                val notification = buildNotification(null)
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(artworkBitmap: Bitmap?): Notification {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingClickIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Actions
        val prevPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HazeMediaNotificationService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val playPauseAction = if (currentIsPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseIcon = if (currentIsPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseTitle = if (currentIsPlaying) "Pause" else "Play"
        val playPausePendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, HazeMediaNotificationService::class.java).apply { action = playPauseAction },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val nextPendingIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, HazeMediaNotificationService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopPendingIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, HazeMediaNotificationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val sessionToken = mediaSession?.sessionToken

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
        if (sessionToken != null) {
            mediaStyle.setMediaSession(sessionToken)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_media)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setContentIntent(pendingClickIntent)
            .setDeleteIntent(stopPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(currentIsPlaying)
            .setStyle(mediaStyle)
            .addAction(R.drawable.ic_skip_previous, "Previous", prevPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(R.drawable.ic_skip_next, "Next", nextPendingIntent)
            .addAction(R.drawable.ic_close, "Close", stopPendingIntent)

        if (artworkBitmap != null && !artworkBitmap.isRecycled) {
            builder.setLargeIcon(artworkBitmap)
        }

        return builder.build()
    }

    private fun stopForegroundService() {
        mediaSession?.isActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
