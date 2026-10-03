package com.example.haze

import android.net.Uri

object HazeSecurityWarningManager {

    fun getSecurityWarningHtml(threatUrl: String, category: String, isDark: Boolean): String {
        val host = try { Uri.parse(threatUrl).host ?: threatUrl } catch (e: Exception) { threatUrl }
        val isPhishing = category == HazePrivacyShieldEngine.CATEGORY_PHISHING

        val title = if (isPhishing) "Deceptive Site Ahead" else "Harmful Site Blocked"
        val subtitle = if (isPhishing) {
            "Attackers on <strong>$host</strong> might trick you into doing something dangerous, such as revealing passwords, messages, or credit cards."
        } else {
            "The site <strong>$host</strong> contains harmful programs or scripts that may compromise your device, steal sensitive information, or hijack system resources."
        }

        val bgColor = if (isDark) "#121212" else "#FDFCFB"
        val cardColor = if (isDark) "#1E1E1E" else "#FFFFFF"
        val textColor = if (isDark) "#FFFFFF" else "#202124"
        val subTextColor = if (isDark) "#9AA0A6" else "#5F6368"
        val alertColor = "#EA4335"

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
                <style>
                    @import url('https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700&display=swap');
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: 'Outfit', sans-serif;
                        background-color: $bgColor;
                        color: $textColor;
                        padding: 32px 24px;
                        min-height: 100vh;
                        display: flex;
                        flex-direction: column;
                        justify-content: center;
                        align-items: center;
                    }
                    .card {
                        background: $cardColor;
                        border-radius: 28px;
                        padding: 36px 28px;
                        max-width: 480px;
                        width: 100%;
                        box-shadow: 0 8px 32px rgba(234, 67, 53, 0.12), 0 2px 12px rgba(0,0,0,0.06);
                        border: 1px solid rgba(234, 67, 53, 0.2);
                        text-align: center;
                    }
                    .shield-icon {
                        width: 72px; height: 72px;
                        background: rgba(234, 67, 53, 0.12);
                        border-radius: 50%;
                        display: flex; align-items: center; justify-content: center;
                        margin: 0 auto 20px auto;
                    }
                    .shield-icon svg { width: 40px; height: 40px; fill: $alertColor; }
                    h1 { font-size: 24px; font-weight: 700; color: $alertColor; margin-bottom: 14px; }
                    p { font-size: 14.5px; line-height: 1.55; color: $subTextColor; margin-bottom: 24px; }
                    .badge {
                        display: inline-block;
                        background: rgba(234, 67, 53, 0.1);
                        color: $alertColor;
                        font-size: 12px; font-weight: 600;
                        padding: 4px 12px; border-radius: 12px;
                        margin-bottom: 20px;
                    }
                    .btn-safe {
                        display: block; width: 100%;
                        background: #0A56D0; color: #FFFFFF;
                        border: none; border-radius: 24px;
                        padding: 15px; font-size: 15px; font-weight: 600;
                        cursor: pointer; text-decoration: none;
                        transition: opacity 0.2s;
                        margin-bottom: 14px;
                    }
                    .btn-safe:active { opacity: 0.85; }
                    .advanced-toggle {
                        background: none; border: none;
                        color: $subTextColor; font-size: 13px; font-weight: 500;
                        cursor: pointer; padding: 8px;
                    }
                    .advanced-content {
                        display: none; margin-top: 16px;
                        padding-top: 16px; border-top: 1px solid rgba(128,128,128,0.2);
                        font-size: 12px; color: $subTextColor; text-align: left;
                    }
                    .btn-proceed {
                        display: inline-block; margin-top: 10px;
                        color: $alertColor; font-size: 13px; font-weight: 600;
                        text-decoration: underline; background: none; border: none;
                        cursor: pointer;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="shield-icon">
                        <svg viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5L12 1zm-1 6h2v6h-2V7zm0 8h2v2h-2v-2z"/></svg>
                    </div>
                    <span class="badge">Haze Privacy Shield • Security Block</span>
                    <h1>$title</h1>
                    <p>$subtitle</p>

                    <button class="btn-safe" onclick="goBackOrHome()">Back to Safety</button>

                    <button class="advanced-toggle" onclick="toggleAdvanced()">Advanced Details</button>
                    <div id="advancedContent" class="advanced-content">
                        <p style="margin-bottom: 10px;">URL: <code style="word-break: break-all;">$threatUrl</code></p>
                        <p style="margin-bottom: 10px;">Threat Type: <strong>$category</strong></p>
                        <p style="margin-bottom: 12px;">Haze Privacy Shield has intercepted this request because the destination hostname is flagged in public threat intelligence databases. Proceeding may harm your privacy and security.</p>
                        <button class="btn-proceed" onclick="proceedAnyway()">Proceed to $host (unsafe)</button>
                    </div>
                </div>

                <script>
                    function goBackOrHome() {
                        if (window.history.length > 1) {
                            window.history.back();
                        } else {
                            Android.navigateTo('haze://home');
                        }
                    }
                    function toggleAdvanced() {
                        var el = document.getElementById('advancedContent');
                        el.style.display = (el.style.display === 'block') ? 'none' : 'block';
                    }
                    function proceedAnyway() {
                        Android.bypassSecurityWarning('$threatUrl');
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
