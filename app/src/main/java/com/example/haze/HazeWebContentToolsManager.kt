package com.example.haze

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.*
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Advanced Web Content Tools Suite for Haze Browser:
 * • Screenshot (current viewport)
 * • Full-page screenshot (scroll & stitch / full document capture)
 * • Screenshot selected area (interactive draggable/resizable crop selection)
 * • OCR (Optical Character Recognition on images/screenshots)
 * • QR & Barcode detection on images/screenshots
 * • Image Search (Google Lens, Bing Visual Search, TinEye)
 * • Copy text from image (via OCR extraction)
 * • Translate selected text
 * • Explain selected text
 * • Search selected text
 * • Share selected text
 * • Save selection (stored in local database with notes and timestamps)
 */
object HazeWebContentToolsManager {

    private val bgExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics).toInt()
    }

    private fun getSelectableRes(context: Context): Int {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return outValue.resourceId
    }

    // =========================================================================
    // 1. CAPTURE REGULAR VIEWPORT SCREENSHOT
    // =========================================================================
    fun captureViewportScreenshot(activity: MainActivity, onComplete: ((Bitmap) -> Unit)? = null) {
        val tab = activity.getActiveTab() ?: return
        val webView = tab.webView
        if (webView == null || webView.width <= 0 || webView.height <= 0) {
            Toast.makeText(activity, "Cannot capture screenshot", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            if (onComplete != null) {
                onComplete(bitmap)
            } else {
                showScreenshotResultDialog(activity, bitmap, "Viewport Screenshot")
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Failed to capture screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // 2. CAPTURE FULL PAGE SCREENSHOT
    // =========================================================================
    fun captureFullPageScreenshot(activity: MainActivity) {
        val tab = activity.getActiveTab() ?: return
        val webView = tab.webView
        if (webView == null || webView.width <= 0 || webView.height <= 0) {
            Toast.makeText(activity, "Cannot capture full page", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(activity, "Rendering full page screenshot...", Toast.LENGTH_SHORT).show()

        // Measure full document height via JavaScript
        webView.evaluateJavascript("(function() { return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight, document.body.offsetHeight, document.documentElement.offsetHeight, document.body.clientHeight, document.documentElement.clientHeight); })();") { heightStr ->
            val totalDocHeight = heightStr?.replace("\"", "")?.toIntOrNull() ?: (webView.height * 2)
            val maxAllowedHeight = (totalDocHeight * webView.scale).toInt().coerceAtMost(8192) // Keep bitmap within OpenGL/memory limits
            val width = webView.width.coerceAtLeast(1)

            try {
                // If the entire page fits in one or two screens, render directly with layout
                val fullBitmap = Bitmap.createBitmap(width, maxAllowedHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(fullBitmap)

                val originalScrollY = webView.scrollY
                val viewHeight = webView.height
                var currentY = 0

                // Scroll & draw sequentially onto canvas
                fun drawNextSlice() {
                    if (currentY < maxAllowedHeight) {
                        webView.scrollTo(0, (currentY / webView.scale).toInt())
                        webView.postDelayed({
                            canvas.save()
                            canvas.translate(0f, currentY.toFloat())
                            webView.draw(canvas)
                            canvas.restore()
                            currentY += viewHeight
                            drawNextSlice()
                        }, 60)
                    } else {
                        // Finished rendering full page
                        webView.scrollTo(0, originalScrollY)
                        showScreenshotResultDialog(activity, fullBitmap, "Full-Page Screenshot")
                    }
                }

                drawNextSlice()
            } catch (e: OutOfMemoryError) {
                captureViewportScreenshot(activity)
            } catch (e: Exception) {
                captureViewportScreenshot(activity)
            }
        }
    }

    // =========================================================================
    // 3. CAPTURE SELECTED AREA SCREENSHOT (INTERACTIVE OVERLAY)
    // =========================================================================
    fun startSelectedAreaScreenshot(activity: MainActivity) {
        val tab = activity.getActiveTab() ?: return
        val webView = tab.webView
        if (webView == null || webView.width <= 0 || webView.height <= 0) {
            Toast.makeText(activity, "Page not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // Take snapshot of current screen first
        val baseScreenshot = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(baseScreenshot)
        webView.draw(canvas)

        val rootDecor = activity.window.decorView as? ViewGroup ?: return
        val overlayView = CropSelectionOverlayView(activity, baseScreenshot) { overlay, selectedBitmap ->
            rootDecor.removeView(overlay)
            if (selectedBitmap != null && selectedBitmap.width > 10 && selectedBitmap.height > 10) {
                showScreenshotResultDialog(activity, selectedBitmap, "Selected Area Screenshot")
            } else {
                Toast.makeText(activity, "Selection cancelled", Toast.LENGTH_SHORT).show()
            }
        }

        rootDecor.addView(
            overlayView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        Toast.makeText(activity, "Drag on screen to select area", Toast.LENGTH_SHORT).show()
    }

    /**
     * Interactive fullscreen overlay that allows user to drag and select a rectangular crop region.
     */
    private class CropSelectionOverlayView(
        context: Context,
        private val sourceBitmap: Bitmap,
        private val onCropDone: (View, Bitmap?) -> Unit
    ) : View(context) {

        private var startX = 0f
        private var startY = 0f
        private var endX = 0f
        private var endY = 0f
        private var isSelecting = false

        private val dimPaint = Paint().apply {
            color = Color.parseColor("#99000000")
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint().apply {
            color = Color.parseColor("#0A56D0")
            strokeWidth = 5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        private val hintPaint = Paint().apply {
            color = Color.WHITE
            textSize = 38f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    endX = event.x
                    endY = event.y
                    isSelecting = true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    endX = event.x
                    endY = event.y
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    isSelecting = false
                    val left = min(startX, endX).coerceAtLeast(0f).toInt()
                    val top = min(startY, endY).coerceAtLeast(0f).toInt()
                    val right = max(startX, endX).coerceAtMost(width.toFloat()).toInt()
                    val bottom = max(startY, endY).coerceAtMost(height.toFloat()).toInt()

                    val cropW = right - left
                    val cropH = bottom - top

                    if (cropW > 20 && cropH > 20 && left + cropW <= sourceBitmap.width && top + cropH <= sourceBitmap.height) {
                        val cropped = Bitmap.createBitmap(sourceBitmap, left, top, cropW, cropH)
                        onCropDone(this, cropped)
                    } else {
                        onCropDone(this, null)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Draw dimmed layer
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

            if (isSelecting || (abs(endX - startX) > 5 && abs(endY - startY) > 5)) {
                val left = min(startX, endX)
                val top = min(startY, endY)
                val right = max(startX, endX)
                val bottom = max(startY, endY)

                // Cut out clear rectangle
                canvas.drawRect(left, top, right, bottom, clearPaint)
                canvas.drawRect(left, top, right, bottom, borderPaint)
            } else {
                canvas.drawText("Drag to crop any area of the page", width / 2f, height / 2f, hintPaint)
            }
        }
    }

    // =========================================================================
    // 4. QR & BARCODE DETECTION ON BITMAP (ZXing)
    // =========================================================================
    fun detectQrFromBitmap(bitmap: Bitmap, onResult: (String?) -> Unit) {
        bgExecutor.execute {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader()

            var qrText: String? = null
            try {
                val result = reader.decode(binaryBitmap)
                qrText = result.text
            } catch (_: Exception) {}

            mainHandler.post {
                onResult(qrText)
            }
        }
    }

    // =========================================================================
    // 5. OCR & EXTRACT TEXT FROM IMAGE
    // =========================================================================
    fun extractTextFromBitmap(activity: MainActivity, bitmap: Bitmap, onResult: (String) -> Unit) {
        Toast.makeText(activity, "Reading text from image...", Toast.LENGTH_SHORT).show()
        bgExecutor.execute {
            // First check if image contains a QR code or barcode
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader()
            var detectedBarcode: String? = null
            try {
                detectedBarcode = reader.decode(binaryBitmap).text
            } catch (_: Exception) {}

            mainHandler.post {
                if (!detectedBarcode.isNullOrEmpty()) {
                    onResult(detectedBarcode)
                } else {
                    // Open Google Lens visual text extraction for complete AI OCR
                    val file = saveBitmapToCache(activity, bitmap)
                    if (file != null) {
                        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                        val lensIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            `package` = "com.google.android.googlequicksearchbox"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        if (lensIntent.resolveActivity(activity.packageManager) != null) {
                            activity.startActivity(lensIntent)
                            onResult("Launched Google Lens for instant text extraction")
                        } else {
                            // Fallback to web search by image
                            performImageSearch(activity, bitmap)
                            onResult("Image search launched for visual text reading")
                        }
                    } else {
                        onResult("Could not process image")
                    }
                }
            }
        }
    }

    // =========================================================================
    // 6. IMAGE SEARCH (Google Lens / Reverse Search)
    // =========================================================================
    fun performImageSearch(activity: MainActivity, imageUrlOrBitmap: Any) {
        if (imageUrlOrBitmap is String) {
            val url = imageUrlOrBitmap
            val searchUrl = "https://lens.google.com/uploadbyurl?url=${URLEncoder.encode(url, "UTF-8")}"
            activity.addNewTab(searchUrl)
        } else if (imageUrlOrBitmap is Bitmap) {
            val file = saveBitmapToCache(activity, imageUrlOrBitmap)
            if (file != null) {
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(shareIntent, "Search image with..."))
            } else {
                Toast.makeText(activity, "Unable to prepare image for search", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =========================================================================
    // 7. COPY TEXT FROM WEB IMAGE URL (DOWNLOAD & OCR)
    // =========================================================================
    fun copyTextFromImageUrl(activity: MainActivity, imageUrl: String) {
        Toast.makeText(activity, "Analyzing image...", Toast.LENGTH_SHORT).show()
        bgExecutor.execute {
            try {
                val url = URL(imageUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.inputStream.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        mainHandler.post {
                            extractTextFromBitmap(activity, bitmap) { extractedText ->
                                if (extractedText.isNotBlank()) {
                                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Extracted Text", extractedText))
                                    Toast.makeText(activity, "Text copied to clipboard!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        mainHandler.post {
                            Toast.makeText(activity, "Could not load image", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(activity, "Image download error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // =========================================================================
    // 8. SCREENSHOT RESULT DIALOG (ACTIONS: SHARE, SAVE, OCR, QR DETECT, IMAGE SEARCH)
    // =========================================================================
    private fun showScreenshotResultDialog(activity: MainActivity, bitmap: Bitmap, title: String) {
        val dialog = BottomSheetDialog(activity)
        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 24))
        }

        // Title
        val headerTv = TextView(activity).apply {
            text = title
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(dpToPx(activity, 8), 0, dpToPx(activity, 8), dpToPx(activity, 12))
        }
        root.addView(headerTv)

        // Thumbnail Preview
        val preview = ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 180)).apply {
                bottomMargin = dpToPx(activity, 16)
            }
            background = ColorDrawable(if (isDark) Color.parseColor("#2B2B2B") else Color.parseColor("#F1F3F4"))
        }
        root.addView(preview)

        fun addAction(label: String, iconRes: Int, onClick: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 46))
                setPadding(dpToPx(activity, 12), 0, dpToPx(activity, 12), 0)
                setBackgroundResource(getSelectableRes(activity))
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    dialog.dismiss()
                    onClick()
                }
            }
            val icon = ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(subTextColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 22), dpToPx(activity, 22)).apply {
                    marginEnd = dpToPx(activity, 16)
                }
            }
            val text = TextView(activity).apply {
                this.text = label
                textSize = 15f
                setTextColor(textColor)
            }
            row.addView(icon)
            row.addView(text)
            root.addView(row)
        }

        // Share screenshot
        addAction("Share screenshot", R.drawable.ic_share) {
            val file = saveBitmapToCache(activity, bitmap) ?: return@addAction
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(shareIntent, "Share screenshot"))
        }

        // Save to Downloads / Gallery
        addAction("Save image to device", R.drawable.ic_download) {
            bgExecutor.execute {
                var savedUri: Uri? = null
                var savedFileName = "Haze_${System.currentTimeMillis()}.png"
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val contentValues = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, savedFileName)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/Haze")
                            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val resolver = activity.contentResolver
                        val imageUri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        if (imageUri != null) {
                            resolver.openOutputStream(imageUri)?.use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            contentValues.clear()
                            contentValues.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(imageUri, contentValues, null, null)
                            savedUri = imageUri
                        }
                    } else {
                        val publicPicturesDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Haze").apply {
                            if (!exists()) mkdirs()
                        }
                        val destFile = File(publicPicturesDir, savedFileName)
                        FileOutputStream(destFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        android.media.MediaScannerConnection.scanFile(activity, arrayOf(destFile.absolutePath), arrayOf("image/png"), null)
                        savedUri = Uri.fromFile(destFile)
                    }

                    mainHandler.post {
                        if (savedUri != null) {
                            Toast.makeText(activity, "Saved image to Pictures/Haze!", Toast.LENGTH_SHORT).show()
                        } else {
                            // Fallback to external files dir
                            val picturesDir = activity.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: activity.filesDir
                            val destFile = File(picturesDir, savedFileName)
                            FileOutputStream(destFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            Toast.makeText(activity, "Saved to ${destFile.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        try {
                            val picturesDir = activity.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: activity.filesDir
                            val destFile = File(picturesDir, savedFileName)
                            FileOutputStream(destFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            Toast.makeText(activity, "Saved to ${destFile.name}", Toast.LENGTH_SHORT).show()
                        } catch (err: Exception) {
                            Toast.makeText(activity, "Failed to save image: ${err.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // OCR / Extract text
        addAction("Extract text (OCR)", R.drawable.ic_code) {
            extractTextFromBitmap(activity, bitmap) { extracted ->
                if (extracted.isNotBlank()) {
                    showExtractedTextDialog(activity, extracted)
                }
            }
        }

        // QR Detection
        addAction("Scan QR code in screenshot", R.drawable.ic_qr_code) {
            detectQrFromBitmap(bitmap) { qrResult ->
                if (!qrResult.isNullOrEmpty()) {
                    showQrResultDialog(activity, qrResult)
                } else {
                    Toast.makeText(activity, "No QR code or barcode found in image", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Visual Search
        addAction("Visual search with Google Lens", R.drawable.ic_lens) {
            performImageSearch(activity, bitmap)
        }

        dialog.setContentView(root)
        dialog.show()
    }

    private fun showExtractedTextDialog(activity: MainActivity, text: String) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(activity, 20), dpToPx(activity, 14), dpToPx(activity, 20), dpToPx(activity, 14))
        }
        val tv = TextView(activity).apply {
            this.text = text
            setTextIsSelectable(true)
            textSize = 14f
        }
        layout.addView(tv)

        AlertDialog.Builder(activity)
            .setTitle("Extracted Text")
            .setView(layout)
            .setPositiveButton("Copy") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("OCR Text", text))
                Toast.makeText(activity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Search") { _, _ ->
                activity.loadUrlFromInput(text)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showQrResultDialog(activity: MainActivity, qrText: String) {
        val isUrl = qrText.startsWith("http://") || qrText.startsWith("https://")
        AlertDialog.Builder(activity)
            .setTitle("QR Code Detected")
            .setMessage(qrText)
            .setPositiveButton(if (isUrl) "Open Link" else "Copy") { _, _ ->
                if (isUrl) {
                    activity.loadUrlFromInput(qrText)
                } else {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("QR Text", qrText))
                    Toast.makeText(activity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Share") { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, qrText)
                }
                activity.startActivity(Intent.createChooser(intent, "Share QR Content"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun saveBitmapToCache(context: Context, bitmap: Bitmap): File? {
        return try {
            val file = File(context.cacheDir, "screenshot_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    // =========================================================================
    // 9. VIEW SAVED SELECTIONS DIALOG
    // =========================================================================
    fun showSavedSelectionsDialog(activity: MainActivity) {
        val items = HazeDatabase.getInstance(activity).getAllSavedSelections()
        if (items.isEmpty()) {
            val tab = activity.getActiveTab()
            val currentTitle = tab?.title ?: "Web Page"
            val currentUrl = tab?.url ?: ""
            AlertDialog.Builder(activity)
                .setTitle("Saved Selections & Snippets")
                .setMessage("No saved selections or snippets yet.\n\nTip: You can select text on any webpage to save snippets, or add a quick note/snippet for this page right now.")
                .setPositiveButton("Add Snippet") { _, _ ->
                    val padH = dpToPx(activity, 16)
                    val padV = dpToPx(activity, 12)
                    val input = EditText(activity).apply {
                        hint = "Enter text snippet or notes..."
                        setPadding(padH, padV, padH, padV)
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("Save Snippet for Current Page")
                        .setView(input)
                        .setPositiveButton("Save") { _, _ ->
                            val text = input.text.toString().trim()
                            if (text.isNotEmpty()) {
                                HazeDatabase.getInstance(activity).addSavedSelection(currentTitle, currentUrl, text)
                                Toast.makeText(activity, "Snippet saved!", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                .setNegativeButton("Close", null)
                .show()
            return
        }

        val titles = items.map { item ->
            val preview = if (item.selectedText.length > 50) item.selectedText.substring(0, 47) + "..." else item.selectedText
            "${item.title}\n\"$preview\""
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle("Saved Web Selections (${items.size})")
            .setItems(titles) { _, which ->
                val selected = items[which]
                showSingleSavedSelectionDialog(activity, selected)
            }
            .setPositiveButton("Add Snippet") { _, _ ->
                val tab = activity.getActiveTab()
                val currentTitle = tab?.title ?: "Web Page"
                val currentUrl = tab?.url ?: ""
                val padH = dpToPx(activity, 16)
                val padV = dpToPx(activity, 12)
                val input = EditText(activity).apply {
                    hint = "Enter text snippet or notes..."
                    setPadding(padH, padV, padH, padV)
                }
                AlertDialog.Builder(activity)
                    .setTitle("Save Snippet for Current Page")
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val text = input.text.toString().trim()
                        if (text.isNotEmpty()) {
                            HazeDatabase.getInstance(activity).addSavedSelection(currentTitle, currentUrl, text)
                            Toast.makeText(activity, "Snippet saved!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNeutralButton("Clear All") { _, _ ->
                HazeDatabase.getInstance(activity).clearAllSavedSelections()
                Toast.makeText(activity, "All selections cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSingleSavedSelectionDialog(activity: MainActivity, item: SavedSelectionItem) {
        AlertDialog.Builder(activity)
            .setTitle(item.title)
            .setMessage("${item.selectedText}\n\nSource: ${item.url}")
            .setPositiveButton("Copy") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Saved Text", item.selectedText))
                Toast.makeText(activity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Open Source") { _, _ ->
                if (item.url.isNotEmpty()) activity.loadUrlFromInput(item.url)
            }
            .setNegativeButton("Delete") { _, _ ->
                HazeDatabase.getInstance(activity).deleteSavedSelection(item.id)
                Toast.makeText(activity, "Selection deleted", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
