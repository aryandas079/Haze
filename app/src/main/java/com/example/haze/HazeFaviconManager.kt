package com.example.haze

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
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
import java.security.MessageDigest
import java.util.concurrent.Executors

object HazeFaviconManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)

    // Memory Cache (approx 4MB or 120 entries)
    private val memoryCache = object : LruCache<String, Bitmap>(120) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    private fun getFaviconDir(context: Context): File {
        val dir = File(context.cacheDir, "favicons")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getDomain(url: String): String {
        return try {
            val uri = Uri.parse(url)
            uri.host ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun hashKey(key: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest(key.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            key.hashCode().toString()
        }
    }

    fun getCachedFaviconFile(context: Context, url: String): File {
        val domain = getDomain(url)
        val hash = hashKey(domain)
        return File(getFaviconDir(context), "$hash.png")
    }

    fun saveFavicon(context: Context, url: String, bitmap: Bitmap): String? {
        val domain = getDomain(url)
        if (domain.isEmpty()) return null
        val key = hashKey(domain)
        memoryCache.put(key, bitmap)

        val file = getCachedFaviconFile(context, url)
        executor.execute {
            try {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return file.absolutePath
    }

    fun getCachedBitmap(context: Context, url: String): Bitmap? {
        val domain = getDomain(url)
        val key = hashKey(domain)
        val mem = memoryCache.get(key)
        if (mem != null) return mem

        val file = getCachedFaviconFile(context, url)
        if (file.exists()) {
            try {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    memoryCache.put(key, bmp)
                    return bmp
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return null
    }

    fun loadFavicon(
        context: Context,
        url: String,
        targetView: ImageView,
        fallbackText: String = "",
        isDark: Boolean = false,
        onLoaded: ((Bitmap) -> Unit)? = null
    ) {
        val domain = getDomain(url)
        if (domain.isEmpty() || url.startsWith("haze://") || url.startsWith("about:")) {
            targetView.setImageBitmap(generateMonogram(fallbackText.ifEmpty { "H" }, isDark = isDark))
            return
        }

        val key = hashKey(domain)
        targetView.tag = key

        // Check memory cache first
        val cached = memoryCache.get(key)
        if (cached != null) {
            targetView.setImageBitmap(cached)
            onLoaded?.invoke(cached)
            return
        }

        // Set immediate monogram placeholder while loading
        val placeholder = generateMonogram(fallbackText.ifEmpty { domain }, isDark = isDark)
        targetView.setImageBitmap(placeholder)

        executor.execute {
            // Check disk cache
            val file = getCachedFaviconFile(context, url)
            if (file.exists()) {
                try {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        memoryCache.put(key, bmp)
                        mainHandler.post {
                            if (targetView.tag == key) {
                                targetView.setImageBitmap(bmp)
                                onLoaded?.invoke(bmp)
                            }
                        }
                        return@execute
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Fallback: Fetch from Google Favicon Service
            val googleFaviconUrl = "https://www.google.com/s2/favicons?domain=$domain&sz=128"
            var bitmap = fetchBitmapFromUrl(googleFaviconUrl)

            // Second fallback: website /favicon.ico
            if (bitmap == null) {
                try {
                    val scheme = if (url.startsWith("http://")) "http://" else "https://"
                    val directUrl = "$scheme$domain/favicon.ico"
                    bitmap = fetchBitmapFromUrl(directUrl)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (bitmap != null) {
                val finalBmp = bitmap
                memoryCache.put(key, finalBmp)
                try {
                    FileOutputStream(file).use { out ->
                        finalBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.flush()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                mainHandler.post {
                    if (targetView.tag == key) {
                        targetView.setImageBitmap(finalBmp)
                        onLoaded?.invoke(finalBmp)
                    }
                }
            }
        }
    }

    private fun fetchBitmapFromUrl(urlString: String): Bitmap? {
        var connection: HttpURLConnection? = null
        var stream: InputStream? = null
        try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0)")
            }
            connection.connect()
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                stream = connection.inputStream
                return BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            // Silently handle network/timeout error
        } finally {
            try { stream?.close() } catch (ignored: Exception) {}
            try { connection?.disconnect() } catch (ignored: Exception) {}
        }
        return null
    }

    fun generateMonogram(titleOrDomain: String, sizePx: Int = 96, isDark: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val clean = titleOrDomain.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val firstChar = if (clean.isNotEmpty()) clean.first().uppercaseChar() else 'W'

        // Modern Material tonal palette based on char hash
        val palette = if (isDark) {
            intArrayOf(
                Color.parseColor("#37474F"),
                Color.parseColor("#3E2723"),
                Color.parseColor("#263238"),
                Color.parseColor("#1A237E"),
                Color.parseColor("#311B92"),
                Color.parseColor("#004D40"),
                Color.parseColor("#BF360C")
            )
        } else {
            intArrayOf(
                Color.parseColor("#E3F2FD"),
                Color.parseColor("#F3E5F5"),
                Color.parseColor("#E8F5E9"),
                Color.parseColor("#FFF3E0"),
                Color.parseColor("#EDE7F6"),
                Color.parseColor("#E0F2F1"),
                Color.parseColor("#FCE4EC")
            )
        }

        val textPalette = if (isDark) {
            Color.parseColor("#E0E0E0")
        } else {
            Color.parseColor("#1565C0")
        }

        val colorIndex = Math.abs(firstChar.code) % palette.size
        val bgColor = palette[colorIndex]

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }

        val radius = sizePx / 2f
        canvas.drawCircle(radius, radius, radius, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textPalette
            textSize = sizePx * 0.48f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val bounds = Rect()
        val textStr = firstChar.toString()
        textPaint.getTextBounds(textStr, 0, textStr.length, bounds)
        val yOffset = radius + (bounds.height() / 2f) - bounds.bottom

        canvas.drawText(textStr, radius, yOffset, textPaint)
        return bitmap
    }

    fun clearFaviconCache(context: Context) {
        memoryCache.evictAll()
        try {
            getFaviconDir(context).deleteRecursively()
        } catch (e: Exception) {
        }
    }
}
