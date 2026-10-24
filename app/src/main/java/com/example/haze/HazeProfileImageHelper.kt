package com.example.haze

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object HazeProfileImageHelper {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)

    private val memoryCache = object : LruCache<String, Bitmap>(20) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    private fun getAvatarFile(context: Context): File {
        return File(context.filesDir, "haze_profile_avatar.png")
    }

    fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val srcRect = Rect(
            (bitmap.width - size) / 2,
            (bitmap.height - size) / 2,
            (bitmap.width + size) / 2,
            (bitmap.height + size) / 2
        )
        val dstRect = Rect(0, 0, size, size)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)

        return output
    }

    fun generateInitialAvatar(initial: String, sizePx: Int = 128, bgColor: Int = Color.parseColor("#1A73E8")): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        val radius = sizePx / 2f
        canvas.drawCircle(radius, radius, radius, circlePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.48f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        val text = if (initial.isNotEmpty()) initial.take(1).uppercase() else "H"
        val yOffset = (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, radius, radius - yOffset, textPaint)

        return bitmap
    }

    fun getCachedAvatar(context: Context): Bitmap? {
        val cached = memoryCache.get("current_avatar")
        if (cached != null) return cached

        val file = getAvatarFile(context)
        if (file.exists() && file.length() > 0) {
            return try {
                val raw = BitmapFactory.decodeFile(file.absolutePath)
                if (raw != null) {
                    val circular = getCircularBitmap(raw)
                    memoryCache.put("current_avatar", circular)
                    circular
                } else null
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    fun saveAvatarBitmap(context: Context, rawBitmap: Bitmap): Bitmap {
        val circular = getCircularBitmap(rawBitmap)
        memoryCache.put("current_avatar", circular)
        try {
            val file = getAvatarFile(context)
            FileOutputStream(file).use { out ->
                circular.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
        } catch (e: Exception) {
        }
        return circular
    }

    fun clearAvatar(context: Context) {
        memoryCache.evictAll()
        try {
            val file = getAvatarFile(context)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
        }
    }

    fun resolvePhotoUrl(context: Context, photoUrl: String?): String? {
        if (!photoUrl.isNullOrEmpty()) return photoUrl
        val prefs = context.getSharedPreferences("HazeAccountPrefs", Context.MODE_PRIVATE)
        val email = prefs.getString("user_email", "") ?: ""
        if (email.isNotEmpty()) {
            val cached = prefs.getString("cached_photo_url_" + email.lowercase(), null)
            if (!cached.isNullOrEmpty()) return cached
        }
        return null
    }

    fun hasCachedAvatar(context: Context): Boolean {
        if (memoryCache.get("current_avatar") != null) return true
        val file = getAvatarFile(context)
        return file.exists() && file.length() > 0
    }

    fun fetchAndCacheAvatar(context: Context, photoUrl: String?, onLoaded: ((Bitmap) -> Unit)? = null) {
        val resolvedUrl = resolvePhotoUrl(context, photoUrl) ?: return
        if (resolvedUrl.isBlank()) return
        executor.execute {
            var stream: InputStream? = null
            var conn: HttpURLConnection? = null
            try {
                val url = URL(resolvedUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                }
                conn.connect()
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    stream = conn.inputStream
                    val downloaded = BitmapFactory.decodeStream(stream)
                    if (downloaded != null) {
                        val circular = saveAvatarBitmap(context, downloaded)
                        mainHandler.post {
                            onLoaded?.invoke(circular)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { stream?.close() } catch (_: Exception) {}
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    fun loadAvatar(
        context: Context,
        photoUrl: String?,
        fallbackInitial: String,
        imageView: ImageView,
        sizePx: Int = 128,
        onLoaded: ((Bitmap) -> Unit)? = null
    ) {
        val resolvedUrl = resolvePhotoUrl(context, photoUrl)
        val cached = getCachedAvatar(context)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            onLoaded?.invoke(cached)
            if (resolvedUrl.isNullOrEmpty()) return
        } else {
            val fallback = generateInitialAvatar(fallbackInitial, sizePx)
            imageView.setImageBitmap(fallback)
        }

        if (resolvedUrl.isNullOrEmpty()) return

        executor.execute {
            var stream: InputStream? = null
            var conn: HttpURLConnection? = null
            try {
                val url = URL(resolvedUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                }
                conn.connect()
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    stream = conn.inputStream
                    val downloaded = BitmapFactory.decodeStream(stream)
                    if (downloaded != null) {
                        val circular = saveAvatarBitmap(context, downloaded)
                        mainHandler.post {
                            imageView.setImageBitmap(circular)
                            onLoaded?.invoke(circular)
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { stream?.close() } catch (e: Exception) {}
                try { conn?.disconnect() } catch (e: Exception) {}
            }
        }
    }
}
