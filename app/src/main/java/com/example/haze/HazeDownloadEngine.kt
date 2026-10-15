package com.example.haze

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.URLUtil
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadProgressInfo(
    val downloadId: Long,
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val progressPercent: Int,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val status: String // DOWNLOADING, QUEUED, PAUSED, COMPLETED, FAILED, CANCELLED
)

interface DownloadProgressListener {
    fun onProgressUpdate(info: DownloadProgressInfo)
    fun onStatusChange(info: DownloadProgressInfo)
}

object HazeDownloadEngine {

    const val STATUS_QUEUED = "QUEUED"
    const val STATUS_DOWNLOADING = "DOWNLOADING"
    const val STATUS_PAUSED = "PAUSED"
    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_CANCELLED = "CANCELLED"

    private const val MAX_CONCURRENT_DOWNLOADS = 3
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS)

    // Active downloading tasks mapped by downloadId
    private val activeTasks = ConcurrentHashMap<Long, DownloadTask>()
    // Queue of pending tasks waiting for a slot
    private val queuedTasks = ConcurrentLinkedQueue<DownloadTask>()
    // Latest progress snapshot by downloadId
    private val latestProgress = ConcurrentHashMap<Long, DownloadProgressInfo>()
    // Registered UI listeners
    private val listeners = mutableListOf<DownloadProgressListener>()

    private val lock = Any()

    fun addListener(listener: DownloadProgressListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }
    }

    fun removeListener(listener: DownloadProgressListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun notifyProgress(info: DownloadProgressInfo) {
        latestProgress[info.downloadId] = info
        mainHandler.post {
            synchronized(listeners) {
                for (l in listeners) l.onProgressUpdate(info)
            }
        }
    }

    private fun notifyStatus(info: DownloadProgressInfo) {
        latestProgress[info.downloadId] = info
        mainHandler.post {
            synchronized(listeners) {
                for (l in listeners) l.onStatusChange(info)
            }
        }
    }

    fun getProgress(downloadId: Long): DownloadProgressInfo? {
        return latestProgress[downloadId]
    }

    fun enqueueDownload(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long,
        suggestedFileName: String? = null,
        isPrivate: Boolean = false,
        customTargetDirUri: String? = null
    ): Long {
        val appCtx = context.applicationContext
        val destDir = getDownloadDestinationDir(appCtx, customTargetDirUri)
        val fileName = resolveFileName(appCtx, destDir, suggestedFileName ?: URLUtil.guessFileName(url, contentDisposition, mimeType))
        val downloadId = System.currentTimeMillis() + (0..999).random()

        val targetFile = File(destDir, fileName)

        val task = DownloadTask(
            context = appCtx,
            downloadId = downloadId,
            url = url,
            userAgent = userAgent,
            fileName = fileName,
            targetFile = targetFile,
            mimeType = mimeType,
            expectedTotalBytes = if (contentLength > 0) contentLength else 0L
        )

        // Save initial record to database
        if (!isPrivate) {
            HazeDatabase.getInstance(appCtx).addDownload(
                downloadId = downloadId,
                fileName = fileName,
                filePath = targetFile.absolutePath,
                fileSize = contentLength,
                url = url,
                mimeType = mimeType,
                status = STATUS_QUEUED
            )
        }

        val initialInfo = DownloadProgressInfo(
            downloadId = downloadId,
            fileName = fileName,
            downloadedBytes = 0L,
            totalBytes = contentLength,
            progressPercent = 0,
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            status = STATUS_QUEUED
        )
        notifyStatus(initialInfo)

        synchronized(lock) {
            if (activeTasks.size < MAX_CONCURRENT_DOWNLOADS) {
                activeTasks[downloadId] = task
                executor.execute(task)
            } else {
                queuedTasks.offer(task)
            }
        }

        return downloadId
    }

    fun pauseDownload(downloadId: Long) {
        synchronized(lock) {
            val task = activeTasks[downloadId]
            if (task != null) {
                task.isPaused.set(true)
            } else {
                // If in queue, remove from queue
                val iterator = queuedTasks.iterator()
                while (iterator.hasNext()) {
                    val qTask = iterator.next()
                    if (qTask.downloadId == downloadId) {
                        iterator.remove()
                        val info = DownloadProgressInfo(
                            downloadId = downloadId,
                            fileName = qTask.fileName,
                            downloadedBytes = qTask.downloadedBytes,
                            totalBytes = qTask.expectedTotalBytes,
                            progressPercent = 0,
                            speedBytesPerSec = 0L,
                            etaSeconds = 0L,
                            status = STATUS_PAUSED
                        )
                        HazeDatabase.getInstance(qTask.context).updateDownloadStatus(downloadId, STATUS_PAUSED)
                        notifyStatus(info)
                        break
                    }
                }
            }
        }
    }

    fun resumeDownload(context: Context, downloadId: Long) {
        val appCtx = context.applicationContext
        val db = HazeDatabase.getInstance(appCtx)
        val item = db.getAllDownloads().find { it.downloadId == downloadId } ?: return

        synchronized(lock) {
            if (activeTasks.containsKey(downloadId)) return // already active

            val targetFile = File(item.filePath)
            val task = DownloadTask(
                context = appCtx,
                downloadId = downloadId,
                url = item.url,
                userAgent = "Mozilla/5.0 (Android; Mobile; rv:109.0)",
                fileName = item.fileName,
                targetFile = targetFile,
                mimeType = item.mimeType,
                expectedTotalBytes = item.fileSize
            )

            HazeDatabase.getInstance(appCtx).updateDownloadStatus(downloadId, STATUS_QUEUED)
            val info = DownloadProgressInfo(
                downloadId = downloadId,
                fileName = item.fileName,
                downloadedBytes = if (targetFile.exists()) targetFile.length() else 0L,
                totalBytes = item.fileSize,
                progressPercent = if (item.fileSize > 0 && targetFile.exists()) ((targetFile.length() * 100) / item.fileSize).toInt() else 0,
                speedBytesPerSec = 0L,
                etaSeconds = 0L,
                status = STATUS_QUEUED
            )
            notifyStatus(info)

            if (activeTasks.size < MAX_CONCURRENT_DOWNLOADS) {
                activeTasks[downloadId] = task
                executor.execute(task)
            } else {
                queuedTasks.offer(task)
            }
        }
    }

    fun cancelDownload(downloadId: Long) {
        synchronized(lock) {
            val task = activeTasks[downloadId]
            if (task != null) {
                task.isCancelled.set(true)
            } else {
                val iterator = queuedTasks.iterator()
                while (iterator.hasNext()) {
                    val qTask = iterator.next()
                    if (qTask.downloadId == downloadId) {
                        iterator.remove()
                        val info = DownloadProgressInfo(
                            downloadId = downloadId,
                            fileName = qTask.fileName,
                            downloadedBytes = 0L,
                            totalBytes = qTask.expectedTotalBytes,
                            progressPercent = 0,
                            speedBytesPerSec = 0L,
                            etaSeconds = 0L,
                            status = STATUS_CANCELLED
                        )
                        HazeDatabase.getInstance(qTask.context).updateDownloadStatus(downloadId, STATUS_CANCELLED)
                        notifyStatus(info)
                        // delete part file if exists
                        if (qTask.targetFile.exists()) qTask.targetFile.delete()
                        break
                    }
                }
            }
        }
    }

    fun retryDownload(context: Context, downloadId: Long) {
        val appCtx = context.applicationContext
        val db = HazeDatabase.getInstance(appCtx)
        val item = db.getAllDownloads().find { it.downloadId == downloadId } ?: return

        // Delete partial or broken file
        val file = File(item.filePath)
        if (file.exists()) file.delete()

        resumeDownload(context, downloadId)
    }

    private fun onTaskFinished(task: DownloadTask, finalStatus: String, errorMsg: String? = null) {
        synchronized(lock) {
            activeTasks.remove(task.downloadId)

            val info = DownloadProgressInfo(
                downloadId = task.downloadId,
                fileName = task.fileName,
                downloadedBytes = task.downloadedBytes,
                totalBytes = if (task.expectedTotalBytes > 0) task.expectedTotalBytes else task.downloadedBytes,
                progressPercent = if (finalStatus == STATUS_COMPLETED) 100 else task.progressPercent,
                speedBytesPerSec = 0L,
                etaSeconds = 0L,
                status = finalStatus
            )

            val finalSize = if (task.targetFile.exists()) task.targetFile.length() else task.downloadedBytes
            HazeDatabase.getInstance(task.context).updateDownloadStatus(
                downloadId = task.downloadId,
                status = finalStatus,
                filePath = task.targetFile.absolutePath,
                fileSize = finalSize
            )
            notifyStatus(info)

            // Auto-promote next queued task
            promoteNextQueuedTask()
        }
    }

    private fun promoteNextQueuedTask() {
        synchronized(lock) {
            while (activeTasks.size < MAX_CONCURRENT_DOWNLOADS && !queuedTasks.isEmpty()) {
                val nextTask = queuedTasks.poll() ?: break
                activeTasks[nextTask.downloadId] = nextTask
                executor.execute(nextTask)
            }
        }
    }

    fun getFileFromUri(context: Context, uri: android.net.Uri): File? {
        try {
            if ("content" == uri.scheme) {
                val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                val parts = treeDocId.split(":")
                if (parts.size >= 2) {
                    val type = parts[0]
                    val relativePath = parts[1]
                    if ("primary".equals(type, ignoreCase = true)) {
                        val target = File(Environment.getExternalStorageDirectory(), relativePath)
                        if (target.exists() || target.mkdirs()) return target
                    } else {
                        val storageDirs = context.getExternalFilesDirs(null)
                        for (dir in storageDirs) {
                            if (dir != null && dir.absolutePath.contains(type)) {
                                val rootPath = dir.absolutePath.substringBefore("/Android")
                                val target = File(rootPath, relativePath)
                                if (target.exists() || target.mkdirs()) return target
                            }
                        }
                    }
                }
            } else if ("file" == uri.scheme) {
                val file = File(uri.path ?: "")
                if (file.exists() || file.mkdirs()) return file
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun getDownloadDestinationDir(context: Context, customFolderUri: String? = null): File {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val uriStr = customFolderUri ?: prefs.getString("custom_download_dir_uri", null)

        if (!uriStr.isNullOrEmpty()) {
            try {
                val resolvedFile = getFileFromUri(context, android.net.Uri.parse(uriStr))
                if (resolvedFile != null && (resolvedFile.exists() || resolvedFile.mkdirs())) {
                    return resolvedFile
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Preferred: Public Downloads directory
        try {
            val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (pubDir.exists() || pubDir.mkdirs()) return pubDir
        } catch (ignored: Exception) {}

        // Fallback: App-specific external files dir
        val appExt = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (appExt != null && (appExt.exists() || appExt.mkdirs())) return appExt

        // Fallback: App files dir
        return context.filesDir
    }

    private fun resolveFileName(context: Context, destDir: File, rawFileName: String): String {
        var clean = rawFileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (clean.isBlank()) clean = "download_${System.currentTimeMillis()}"

        var file = File(destDir, clean)
        if (!file.exists()) return clean

        // Check if there's an extension
        val dotIndex = clean.lastIndexOf('.')
        val baseName = if (dotIndex > 0) clean.substring(0, dotIndex) else clean
        val extension = if (dotIndex > 0) clean.substring(dotIndex) else ""

        var count = 1
        while (file.exists()) {
            val candidate = "$baseName ($count)$extension"
            file = File(destDir, candidate)
            count++
        }
        return file.name
    }

    class DownloadTask(
        val context: Context,
        val downloadId: Long,
        val url: String,
        val userAgent: String,
        val fileName: String,
        val targetFile: File,
        val mimeType: String,
        var expectedTotalBytes: Long
    ) : Runnable {

        val isPaused = AtomicBoolean(false)
        val isCancelled = AtomicBoolean(false)

        var downloadedBytes: Long = 0L
        var progressPercent: Int = 0

        override fun run() {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: RandomAccessFile? = null

            try {
                // Determine existing partial file size
                val existingBytes = if (targetFile.exists()) targetFile.length() else 0L
                downloadedBytes = existingBytes

                val targetUrl = URL(url)
                connection = (targetUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                    if (existingBytes > 0) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }

                connection.connect()
                val responseCode = connection.responseCode

                val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
                val isOk = responseCode == HttpURLConnection.HTTP_OK

                if (!isPartial && !isOk) {
                    throw Exception("HTTP Error: $responseCode ${connection.responseMessage}")
                }

                if (isOk) {
                    // Server restarted download from 0
                    downloadedBytes = 0L
                    val contentLength = connection.contentLength.toLong()
                    if (contentLength > 0) expectedTotalBytes = contentLength
                    outputStream = RandomAccessFile(targetFile, "rw")
                    outputStream.setLength(0) // truncate
                } else {
                    // Partial content resumed
                    val contentLength = connection.contentLength.toLong()
                    if (contentLength > 0) expectedTotalBytes = existingBytes + contentLength
                    outputStream = RandomAccessFile(targetFile, "rw")
                    outputStream.seek(existingBytes)
                }

                inputStream = connection.inputStream

                val infoStart = DownloadProgressInfo(
                    downloadId = downloadId,
                    fileName = fileName,
                    downloadedBytes = downloadedBytes,
                    totalBytes = expectedTotalBytes,
                    progressPercent = if (expectedTotalBytes > 0) ((downloadedBytes * 100) / expectedTotalBytes).toInt() else 0,
                    speedBytesPerSec = 0L,
                    etaSeconds = 0L,
                    status = STATUS_DOWNLOADING
                )
                notifyStatus(infoStart)
                HazeDatabase.getInstance(context).updateDownloadStatus(downloadId, STATUS_DOWNLOADING)

                val buffer = ByteArray(16384)
                var read: Int
                var lastUpdateTime = System.currentTimeMillis()
                var bytesInSample = 0L
                var currentSpeed = 0L

                while (inputStream.read(buffer).also { read = it } != -1) {
                    if (isCancelled.get()) {
                        try { outputStream.close() } catch (ignored: Exception) {}
                        if (targetFile.exists()) targetFile.delete()
                        onTaskFinished(this, STATUS_CANCELLED)
                        return
                    }

                    if (isPaused.get()) {
                        try { outputStream.close() } catch (ignored: Exception) {}
                        onTaskFinished(this, STATUS_PAUSED)
                        return
                    }

                    outputStream.write(buffer, 0, read)
                    downloadedBytes += read
                    bytesInSample += read

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastUpdateTime

                    // Update UI every 500ms
                    if (elapsed >= 500) {
                        currentSpeed = (bytesInSample * 1000) / elapsed
                        bytesInSample = 0L
                        lastUpdateTime = now

                        val percent = if (expectedTotalBytes > 0) {
                            ((downloadedBytes * 100) / expectedTotalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        progressPercent = percent

                        val remainingBytes = (expectedTotalBytes - downloadedBytes).coerceAtLeast(0L)
                        val eta = if (currentSpeed > 0 && remainingBytes > 0) remainingBytes / currentSpeed else 0L

                        val currentInfo = DownloadProgressInfo(
                            downloadId = downloadId,
                            fileName = fileName,
                            downloadedBytes = downloadedBytes,
                            totalBytes = expectedTotalBytes,
                            progressPercent = percent,
                            speedBytesPerSec = currentSpeed,
                            etaSeconds = eta,
                            status = STATUS_DOWNLOADING
                        )
                        notifyProgress(currentInfo)
                    }
                }

                // Completed
                try { outputStream.fd.sync() } catch (ignored: Exception) {}
                progressPercent = 100
                onTaskFinished(this, STATUS_COMPLETED)

            } catch (e: Exception) {
                e.printStackTrace()
                if (isCancelled.get()) {
                    if (targetFile.exists()) targetFile.delete()
                    onTaskFinished(this, STATUS_CANCELLED)
                } else if (isPaused.get()) {
                    onTaskFinished(this, STATUS_PAUSED)
                } else {
                    onTaskFinished(this, STATUS_FAILED, e.message)
                }
            } finally {
                try { outputStream?.close() } catch (ignored: Exception) {}
                try { inputStream?.close() } catch (ignored: Exception) {}
                try { connection?.disconnect() } catch (ignored: Exception) {}
            }
        }
    }
}
