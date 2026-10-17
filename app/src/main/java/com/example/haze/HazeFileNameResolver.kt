package com.example.haze

import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.net.URLDecoder
import java.util.regex.Pattern

object HazeFileNameResolver {

    private val INVALID_CHARS_REGEX = Regex("[\\\\/:*?\"<>|]")
    private val CONTENT_DISPOSITION_PATTERN = Pattern.compile(
        """(?:filename\*=UTF-8''|filename\s*=\s*["']?)([^"';\n]+)["']?""",
        Pattern.CASE_INSENSITIVE
    )

    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/watch") ||
                lower.contains("youtu.be/") ||
                lower.contains("youtube.com/shorts/") ||
                lower.contains("youtube.com/embed/") ||
                lower.contains("m.youtube.com/watch")
    }

    fun extractYouTubeVideoId(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: ""
            if (host.contains("youtu.be")) {
                uri.lastPathSegment
            } else if (host.contains("youtube.com")) {
                if (uri.path?.contains("/shorts/") == true || uri.path?.contains("/embed/") == true) {
                    uri.lastPathSegment
                } else {
                    uri.getQueryParameter("v")
                }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun resolveBestFileName(
        url: String,
        contentDisposition: String? = null,
        mimeType: String? = null,
        suggestedName: String? = null,
        defaultExt: String? = null
    ): String {
        // 1. If explicit suggested name given and does not end in .bin
        if (!suggestedName.isNullOrBlank() && !suggestedName.endsWith(".bin", ignoreCase = true)) {
            val clean = sanitizeFileName(suggestedName)
            if (clean.isNotBlank()) return clean
        }

        // 2. Check Content-Disposition header
        if (!contentDisposition.isNullOrBlank()) {
            val matcher = CONTENT_DISPOSITION_PATTERN.matcher(contentDisposition)
            if (matcher.find()) {
                val rawName = matcher.group(1)?.trim()
                if (!rawName.isNullOrEmpty()) {
                    val decoded = try { URLDecoder.decode(rawName, "UTF-8") } catch (_: Exception) { rawName }
                    val clean = sanitizeFileName(decoded)
                    if (clean.isNotBlank()) return clean
                }
            }
        }

        // 3. YouTube link special handling
        if (isYouTubeUrl(url)) {
            val videoId = extractYouTubeVideoId(url)
            return if (!videoId.isNullOrEmpty()) "youtube_$videoId.mp4" else "youtube_video.mp4"
        }

        // 4. Data URI handling
        if (url.startsWith("data:")) {
            val sub = url.substringAfter("data:").substringBefore(";").substringAfter("/")
            val ext = if (sub.isNotBlank() && sub.length in 2..5) sub else (defaultExt ?: "jpg")
            return "image_${System.currentTimeMillis()}.$ext"
        }

        // 5. Blob URL handling
        if (url.startsWith("blob:")) {
            val ext = defaultExt ?: "bin"
            return "blob_${System.currentTimeMillis()}.$ext"
        }

        // 6. Inspect URL query parameters (e.g. ?format=jpg, ?file=doc.pdf, ?ext=png)
        try {
            val uri = Uri.parse(url)
            val formatParam = uri.getQueryParameter("format")
                ?: uri.getQueryParameter("ext")
                ?: uri.getQueryParameter("extension")
            val fileParam = uri.getQueryParameter("filename")
                ?: uri.getQueryParameter("file")
                ?: uri.getQueryParameter("name")

            if (!fileParam.isNullOrBlank()) {
                val clean = sanitizeFileName(fileParam)
                if (clean.contains(".")) return clean
            }

            val path = uri.path.orEmpty()
            val rawLastSegment = path.substringAfterLast("/", "")
            val decodedLastSegment = try { URLDecoder.decode(rawLastSegment, "UTF-8") } catch (_: Exception) { rawLastSegment }

            if (!formatParam.isNullOrBlank() && formatParam.length in 2..5) {
                val base = if (decodedLastSegment.isNotBlank()) sanitizeFileName(decodedLastSegment) else "download_${System.currentTimeMillis()}"
                val baseWithoutExt = base.substringBeforeLast(".")
                return "$baseWithoutExt.$formatParam"
            }

            // 7. Check if path has valid extension
            if (decodedLastSegment.contains(".")) {
                val ext = decodedLastSegment.substringAfterLast(".").lowercase()
                if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) {
                    return sanitizeFileName(decodedLastSegment)
                }
            }
        } catch (_: Exception) {}

        // 8. Guess using URLUtil
        val guessed = try {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        } catch (_: Exception) {
            "download_${System.currentTimeMillis()}"
        }

        // If guessed ends in .bin or has no extension, try to improve it
        if (guessed.endsWith(".bin", ignoreCase = true) || !guessed.contains(".")) {
            val extFromMime = getExtensionFromMime(mimeType)
            val base = guessed.substringBeforeLast(".")
            if (extFromMime != null) {
                return "$base.$extFromMime"
            }
            if (!defaultExt.isNullOrBlank()) {
                return "$base.$defaultExt"
            }
            // Heuristics based on URL domain/path
            val lowerUrl = url.lowercase()
            when {
                lowerUrl.contains("twimg.com") || lowerUrl.contains("unsplash.com") ||
                lowerUrl.contains("gstatic.com") || lowerUrl.contains("fbcdn.net") ||
                lowerUrl.contains("pinimg.com") || lowerUrl.contains("imgur.com") -> return "$base.jpg"
                lowerUrl.contains("videoplayback") || lowerUrl.contains("googlevideo") -> return "$base.mp4"
            }
        }

        return sanitizeFileName(guessed)
    }

    fun getExtensionFromMime(mimeType: String?): String? {
        if (mimeType.isNullOrBlank() || mimeType == "*/*") return null
        val cleanMime = mimeType.substringBefore(";").trim().lowercase()
        return when (cleanMime) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/svg+xml" -> "svg"
            "image/bmp" -> "bmp"
            "image/*" -> "jpg"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/quicktime" -> "mov"
            "video/3gpp" -> "3gp"
            "video/*" -> "mp4"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/*" -> "mp3"
            "application/pdf" -> "pdf"
            "application/vnd.android.package-archive" -> "apk"
            "application/zip", "application/x-zip-compressed" -> "zip"
            "application/x-rar-compressed" -> "rar"
            "application/x-7z-compressed" -> "7z"
            "application/x-tar", "application/gzip" -> "tar.gz"
            "application/json" -> "json"
            "application/msword" -> "doc"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.ms-excel" -> "xls"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/vnd.ms-powerpoint" -> "ppt"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            "text/plain" -> "txt"
            "text/html" -> "html"
            "text/css" -> "css"
            "text/javascript", "application/javascript" -> "js"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(cleanMime)
        }
    }

    fun sanitizeFileName(name: String): String {
        var clean = name.replace(INVALID_CHARS_REGEX, "_").trim()
        clean = clean.trimStart('.', ' ')
        if (clean.length > 120) {
            val ext = if (clean.contains(".")) ".${clean.substringAfterLast(".")}" else ""
            clean = clean.substring(0, 120 - ext.length) + ext
        }
        return if (clean.isBlank()) "download_${System.currentTimeMillis()}" else clean
    }
}
