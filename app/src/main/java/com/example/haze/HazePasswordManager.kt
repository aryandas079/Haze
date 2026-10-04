package com.example.haze

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object HazePasswordManager {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "HazePasswordVault"

    data class Imported(val site: String, val username: String, val password: String, val title: String)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return android.util.Base64.encodeToString(
            cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
            android.util.Base64.NO_WRAP
        )
    }

    private fun decrypt(value: String): String {
        val bytes = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }

    fun showCredential(context: MainActivity, item: PasswordItem, onDeleted: () -> Unit) {
        val password = runCatching { decrypt(item.encryptedPassword) }.getOrNull()
        if (password == null) {
            Toast.makeText(context, "This password could not be decrypted", Toast.LENGTH_LONG).show()
            return
        }
        var detailDialog: Dialog? = null
        val isDark = (context.resources.configuration.uiMode and 0x30) == 0x20
        val cardBg = if (isDark) Color.parseColor("#252525") else Color.parseColor("#F1F5FB")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val secondaryColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 24))
            setBackgroundColor(pageBackground(context))
        }

        // Top Toolbar
        val toolbar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_revert)
                contentDescription = "Back"
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(textColor)
                setOnClickListener { detailDialog?.dismiss() }
            }, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
            addView(TextView(context).apply {
                text = "Password details"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                    marginStart = dp(context, 12)
                }
            })
        }
        root.addView(toolbar)

        // Domain Header with Logo & Title
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 16), 0, dp(context, 20))
        }
        headerLayout.addView(TextView(context).apply {
            text = siteInitial(item)
            gravity = Gravity.CENTER
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBackground(context, siteColor(item), 18)
        }, LinearLayout.LayoutParams(dp(context, 52), dp(context, 52)))

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), 0, 0, 0)
        }
        titleCol.addView(TextView(context).apply {
            text = item.title.ifBlank { item.site }
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
        })
        titleCol.addView(TextView(context).apply {
            text = item.site
            textSize = 13.5f
            setTextColor(secondaryColor)
            setPadding(0, dp(context, 2), 0, 0)
        })
        headerLayout.addView(titleCol)
        root.addView(headerLayout)

        // Card 1: Username
        val userCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(context, cardBg, 16)
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 12) }
        }
        val userText = TextView(context).apply {
            text = item.username
            textSize = 15f
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val copyUserBtn = Button(context).apply {
            text = "Copy"
            textSize = 12f
            setTextColor(0xff1a73e8.toInt())
            background = null
            setOnClickListener { copy(context, item.username, "Username") }
        }
        userCard.addView(userText)
        userCard.addView(copyUserBtn)
        root.addView(userCard)

        // Card 2: Password (with show/hide eye toggle)
        val passCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(context, cardBg, 16)
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 16) }
        }
        val passText = TextView(context).apply {
            text = password
            textSize = 15f
            setTextColor(textColor)
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        var isPasswordVisible = false
        val eyeBtn = Button(context).apply {
            text = "Show"
            textSize = 12f
            setTextColor(secondaryColor)
            background = null
            setOnClickListener {
                isPasswordVisible = !isPasswordVisible
                if (isPasswordVisible) {
                    passText.transformationMethod = null
                    text = "Hide"
                } else {
                    passText.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                    text = "Show"
                }
            }
        }
        val copyPassBtn = Button(context).apply {
            text = "Copy"
            textSize = 12f
            setTextColor(0xff1a73e8.toInt())
            background = null
            setOnClickListener { copy(context, password, "Password") }
        }
        passCard.addView(passText)
        passCard.addView(eyeBtn)
        passCard.addView(copyPassBtn)
        root.addView(passCard)

        // Card 3: Note
        val noteCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(context, cardBg, 16)
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 24) }
        }
        val noteInput = EditText(context).apply {
            hint = "Add note"
            textSize = 14f
            setTextColor(textColor)
            setHintTextColor(secondaryColor)
            background = null
            setPadding(0, 0, 0, 0)
        }
        noteCard.addView(noteInput)
        root.addView(noteCard)

        // Action Buttons (Edit & Delete side by side like image 3)
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val editBtn = Button(context).apply {
            text = "Edit"
            setTextColor(Color.WHITE)
            background = roundedBackground(context, 0xff1a73e8.toInt(), 24)
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 48), 1f).apply { rightMargin = dp(context, 8) }
            setOnClickListener {
                Toast.makeText(context, "Edit mode", Toast.LENGTH_SHORT).show()
            }
        }
        val deleteBtn = Button(context).apply {
            text = "Delete"
            setTextColor(Color.WHITE)
            background = roundedBackground(context, 0xffea4335.toInt(), 24)
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 48), 1f).apply { leftMargin = dp(context, 8) }
            setOnClickListener {
                HazeDatabase.getInstance(context).deletePassword(item.id)
                onDeleted()
                detailDialog?.dismiss()
            }
        }
        actionsRow.addView(editBtn)
        actionsRow.addView(deleteBtn)
        root.addView(actionsRow)

        detailDialog = showFullScreen(context, ScrollView(context).apply {
            isFillViewport = true
            addView(root)
        })
    }

    fun importCsv(context: Context, uri: android.net.Uri): Int {
        var imported = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).useLines { lines ->
                val iterator = lines.iterator()
                if (!iterator.hasNext()) return@useLines
                val headers = parseCsv(iterator.next()).map { it.trim().lowercase() }
                val urlIndex = headers.indexOfFirst { it == "url" || it == "origin" || it == "website" }
                val userIndex = headers.indexOfFirst { it == "username" || it == "user" }
                val passwordIndex = headers.indexOfFirst { it == "password" || it == "pass" }
                if (urlIndex < 0 || userIndex < 0 || passwordIndex < 0) return@useLines
                val db = HazeDatabase.getInstance(context)
                while (iterator.hasNext()) {
                    val row = parseCsv(iterator.next())
                    val site = row.getOrNull(urlIndex)?.trim().orEmpty()
                    val username = row.getOrNull(userIndex)?.trim().orEmpty()
                    val password = row.getOrNull(passwordIndex).orEmpty()
                    if (site.isBlank() || password.isBlank()) continue
                    val title = site.removePrefix("https://").removePrefix("http://").substringBefore("/")
                    if (db.upsertPassword(site, title, username, encrypt(password)) >= 0) imported++
                }
            }
        }
        return imported
    }

    fun show(context: MainActivity, importLauncher: () -> Unit) {
        val db = HazeDatabase.getInstance(context)
        val entries = db.getPasswords()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackground(context))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 8))
        }
        header.addView(ImageView(context).apply {
            setImageResource(com.example.haze.R.drawable.ic_key)
            contentDescription = "Passwords"
        }, LinearLayout.LayoutParams(dp(context, 34), dp(context, 34)))
        val search = EditText(context).apply {
            hint = "Search"
            setSingleLine(true)
            textSize = 16f
            setPadding(dp(context, 18), 0, dp(context, 18), 0)
            background = roundedBackground(context, Color.WHITE, 32)
            setCompoundDrawablesWithIntrinsicBounds(com.example.haze.R.drawable.ic_search, 0, 0, 0)
        }
        header.addView(search, LinearLayout.LayoutParams(0, dp(context, 56), 1f).apply {
            marginStart = dp(context, 18)
        })
        var managerDialog: Dialog? = null
        header.addView(ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = "Close"
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { managerDialog?.dismiss() }
        }, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
        root.addView(header)

        // No chips layout
        
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 20))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // No bottom navigation layout

        fun render(filter: String = "") {
            list.removeAllViews()
            val filtered = entries.filter {
                filter.isBlank() || it.site.contains(filter, true) || it.username.contains(filter, true)
            }
            val grouped = filtered.groupBy { it.title.ifBlank { it.site }.lowercase() }
            addActionCard(context, list, "＋", "Add password") {
                showAdd(context) { show(context, importLauncher) }
            }
            grouped.toSortedMap().forEach { (_, items) ->
                val item = items.first()
                val group = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
                    background = roundedBackground(context, if ((context.resources.configuration.uiMode and 0x30) == 0x20) Color.parseColor("#252525") else Color.WHITE, 16)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 8) }
                }
                // Source Logo / Initial Badge with logo styling
                val iconContainer = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(context, 44), dp(context, 44))
                    background = roundedBackground(context, Color.WHITE, 16)
                    val imgView = ImageView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(context, 28), dp(context, 28), Gravity.CENTER)
                        val domain = item.site.replace("https://", "").replace("http://", "").substringBefore("/")
                        val faviconUrl = "https://www.google.com/s2/favicons?domain=$domain&sz=128"
                        
                        // Fallback to WebView to load image since Glide is not available
                        val fallbackWeb = android.webkit.WebView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(dp(context, 28), dp(context, 28), Gravity.CENTER)
                            setBackgroundColor(Color.TRANSPARENT)
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            setOnTouchListener { _, _ -> true } // Disable touches
                            val html = "<html><body style='margin:0;padding:0;'><img src='$faviconUrl' width='100%' height='100%' style='object-fit:contain;'/></body></html>"
                            loadData(html, "text/html", "UTF-8")
                        }
                        addView(fallbackWeb)
                    }
                    addView(imgView)
                }
                group.addView(iconContainer)

                group.addView(TextView(context).apply {
                    text = item.title.ifBlank { item.site }
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(primaryText(context))
                    setPadding(dp(context, 16), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))

                if (items.size > 1) group.addView(TextView(context).apply {
                    text = "${items.size} accounts"
                    textSize = 12f
                    setTextColor(secondaryText(context))
                })
                list.addView(group)
                group.setOnClickListener {
                    context.requestPasswordUnlock(item) {
                        showCredential(context, item) {
                            show(context, importLauncher)
                        }
                    }
                }
            }
            TextView(context).apply {
                text = "Import passwords from Google Password Manager"
                textSize = 14f
                setTextColor(0xff1769aa.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 18), 0, dp(context, 12))
                setOnClickListener { importLauncher() }
            }.also { list.addView(it) }
        }
        search.addTextChangedListener(SimpleTextWatcher { render(it) })
        render()
        managerDialog = showFullScreen(context, root)
    }

    private fun showFullScreen(context: Context, content: View): Dialog {
        val dialog = Dialog(context)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.statusBarColor = pageBackground(context)
        dialog.window?.navigationBarColor = pageBackground(context)
        if (pageBackground(context) != 0xff202124.toInt()) {
            dialog.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        bindDismissButtons(content, dialog)
        return dialog
    }

    private fun bindDismissButtons(view: View, dialog: Dialog) {
        if (view is ImageButton &&
            (view.contentDescription == "Back" || view.contentDescription == "Close")
        ) {
            view.setOnClickListener { dialog.dismiss() }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                bindDismissButtons(view.getChildAt(index), dialog)
            }
        }
    }

    private fun addActionCard(context: Context, list: LinearLayout, icon: String, title: String, action: () -> Unit) {
        val card = TextView(context).apply {
            text = "$icon   $title"
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(0xff1a73e8.toInt())
            setPadding(dp(context, 18), 0, dp(context, 18), 0)
            background = roundedBackground(context, if ((context.resources.configuration.uiMode and 0x30) == 0x20) Color.parseColor("#252525") else Color.WHITE, 16)
            setOnClickListener { action() }
        }
        list.addView(card, LinearLayout.LayoutParams(-1, dp(context, 64)).apply { bottomMargin = dp(context, 8) })
    }

    private fun cardParams(context: Context) = LinearLayout.LayoutParams(-1, dp(context, 72)).apply {
        bottomMargin = dp(context, 2)
    }

    private fun roundedBackground(context: Context, color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radius).toFloat()
        }

    private fun fieldBackground(context: Context) = roundedBackground(context, pageBackground(context), 14)
    private fun pageBackground(context: Context): Int =
        if ((context.resources.configuration.uiMode and 0x30) == 0x20) 0xff202124.toInt() else 0xfff1f5fb.toInt()
    private fun primaryText(context: Context): Int =
        if (pageBackground(context) == 0xff202124.toInt()) Color.WHITE else 0xff202124.toInt()
    private fun secondaryText(context: Context): Int =
        if (pageBackground(context) == 0xff202124.toInt()) 0xffbdc1c6.toInt() else 0xff5f6368.toInt()
    private fun siteInitial(item: PasswordItem): String =
        item.title.ifBlank { item.site }.trim().firstOrNull()?.uppercase() ?: "?"
    private fun siteColor(item: PasswordItem): Int =
        listOf(0xff4285f4.toInt(), 0xffea4335.toInt(), 0xff34a853.toInt(), 0xfffbbc04.toInt())
            .firstOrNull() ?: 0xff4285f4.toInt()

    private fun copy(context: Context, value: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun showAdd(context: Context, onSaved: () -> Unit) {
        val fields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), 0, dp(context, 20), 0)
        }
        val site = EditText(context).apply { hint = "Website"; setSingleLine(true) }
        val username = EditText(context).apply { hint = "Username"; setSingleLine(true) }
        val password = EditText(context).apply { hint = "Password"; setSingleLine(true); inputType = 0x81 }
        fields.addView(site); fields.addView(username); fields.addView(password)
        AlertDialog.Builder(context).setTitle("Add password").setView(fields).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                if (site.text.isBlank() || password.text.isBlank()) return@setPositiveButton
                val clean = site.text.toString().trim()
                HazeDatabase.getInstance(context).upsertPassword(
                    clean, clean.removePrefix("https://").removePrefix("http://").substringBefore("/"),
                    username.text.toString(), encrypt(password.text.toString())
                )
                onSaved()
            }.show()
    }

    private fun parseCsv(line: String): List<String> {
        val result = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        line.forEach { char ->
            when {
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { result += value.toString(); value.clear() }
                else -> value.append(char)
            }
        }
        result += value.toString()
        return result
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private class SimpleTextWatcher(private val onChange: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (s != null) {
                onChange(s.toString())
            }
        }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }
}
