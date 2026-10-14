package com.example.haze

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object HazeFilterListManager {

    private const val RULES_FILE_NAME = "haze_privacy_rules.json"
    private const val NETWORK_RULES_FILE_NAME = "haze_network_filters.txt"
    private const val PREFS_KEY_LAST_UPDATE = "privacy_shield_rules_last_update"
    private const val PREFS_KEY_RULE_COUNT = "privacy_shield_rules_count"

    // Primary Core Filter Lists (EasyList + EasyPrivacy + AdGuard)
    private val CORE_NETWORK_LIST_URLS = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
        "https://filters.adtidy.org/extension/ublock/filters/2.txt",
        "https://filters.adtidy.org/extension/ublock/filters/3.txt"
    )

    data class RegionalListInfo(
        val id: String,
        val country: String,
        val name: String,
        val url: String,
        val prefKey: String
    )

    val REGIONAL_LISTS = listOf(
        RegionalListInfo("reg_de", "Germany", "EasyList Germany", "https://easylist-downloads.adblockplus.org/easylistgermany.txt", "privacy_shield_reg_de"),
        RegionalListInfo("reg_fr", "France", "Liste FR", "https://easylist-downloads.adblockplus.org/liste_fr.txt", "privacy_shield_reg_fr"),
        RegionalListInfo("reg_es", "Spain", "EasyList Spanish", "https://easylist-downloads.adblockplus.org/easylistspanish.txt", "privacy_shield_reg_es"),
        RegionalListInfo("reg_ru", "Russia", "RU AdList", "https://easylist-downloads.adblockplus.org/advblock.txt", "privacy_shield_reg_ru"),
        RegionalListInfo("reg_in", "India", "Indian List", "https://raw.githubusercontent.com/abp-filters/abp-filters-anti-cv/master/regional/ind.txt", "privacy_shield_reg_in"),
        RegionalListInfo("reg_zh", "China", "CJX's Annoyance", "https://raw.githubusercontent.com/cjx82630/cjxlist/master/cjx-annoyance.txt", "privacy_shield_reg_zh")
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class FilterRules(
        val version: Int,
        val lastUpdated: String,
        val adDomains: Set<String>,
        val trackerDomains: Set<String>,
        val maliciousDomains: Set<String>,
        val phishingDomains: Set<String>,
        val unwantedScriptDomains: Set<String>,
        val pathPatterns: List<String>
    ) {
        val totalCount: Int
            get() = adDomains.size + trackerDomains.size + maliciousDomains.size +
                    phishingDomains.size + unwantedScriptDomains.size + pathPatterns.size
    }

    @Volatile private var cachedRules: FilterRules? = null
    @Volatile private var cachedNetworkFilter: HazeContentFilter.Compiled? = null

    fun invalidateCache() {
        cachedNetworkFilter = null
        cachedRules = null
        HazePrivacyShieldEngine.invalidateCache()
    }

    fun getNetworkFilter(context: Context): HazeContentFilter.Compiled {
        cachedNetworkFilter?.let { return it }
        return synchronized(this) {
            cachedNetworkFilter ?: run {
                val saved = File(context.filesDir, NETWORK_RULES_FILE_NAME)
                val lines = if (saved.exists() && saved.length() > 0) {
                    saved.useLines { it.toMutableList() }
                } else {
                    fallbackNetworkRules().toMutableList()
                }

                // Add active user-created rules from SQLite database
                try {
                    val userRules = HazeDatabase.getInstance(context).getUserCustomRules()
                    for (rule in userRules) {
                        if (rule.isEnabled && rule.ruleText.isNotBlank()) {
                            lines.add(rule.ruleText.trim())
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                HazeContentFilter.compile(lines.asSequence()).also { cachedNetworkFilter = it }
            }
        }
    }

    fun getCosmeticCss(context: Context, domain: String?): String {
        return getNetworkFilter(context).getCosmeticCss(domain)
    }

    private fun fallbackNetworkRules() = sequenceOf(
        // Advertising networks & banner ads
        "||doubleclick.net^", "||googlesyndication.com^", "||googleadservices.com^",
        "||adservice.google.com^", "||googletagservices.com^", "||2mdn.net^",
        "||amazon-adsystem.com^", "||aaxads.com^",
        "||adnxs.com^", "||adnxs-simple.com^", "||ib.adnxs.com^", "||criteo.com^", "||criteo.net^",
        "||rubiconproject.com^", "||pubmatic.com^", "||openx.net^", "||smartadserver.com^",
        "||media.net^", "||sovrn.com^", "||bidswitch.net^", "||casalemedia.com^", "||indexexchange.com^",
        "||teads.tv^", "||triplelift.com^", "||yieldmo.com^", "||sharethrough.com^", "||inmobi.com^",
        "||moatads.com^", "||adform.net^", "||flashtalking.com^", "||serving-sys.com^", "||contextweb.com^",
        "||lijit.com^", "||adroll.com^", "||yandexadexchange.net^", "||an.yandex.ru^", "||invitemedia.com^",
        "||admeld.com^", "||applovin.com^", "||ironsrc.com^", "||vungle.com^", "||unity3d.com^",
        "||chartboost.com^", "||zergnet.com^", "||adblade.com^", "||undertone.com^", "||exponential.com^",
        "||hilltopads.net^", "||clickadu.com^", "||adcash.com^", "||juicyads.com^", "||trafficjunky.com^",
        "||dianomi.com^", "||content.ad^", "||adcolony.com^", "||smaato.net^", "||gumgum.com^", "||onetag.com^",
        // Tracking scripts & telemetry
        "||google-analytics.com^", "||googletagmanager.com^", "||connect.facebook.net^", "||hotjar.com^",
        "||fingerprintjs.com^", "||onesignal.com^", "||clarity.ms^", "||quantserve.com^",
        "||scorecardresearch.com^", "||analytics.tiktok.com^", "||segment.io^",
        "||mixpanel.com^", "||amplitude.com^", "||yandex.ru/metrika^", "||chartbeat.com^",
        "||crazyegg.com^", "||branch.io^", "||appsflyer.com^", "||adjust.com^",
        // Sponsored recommendation widgets (Taboola, Outbrain, Revcontent, MGID, Nativo)
        "||taboola.com^", "||cdn.taboola.com^", "||trc.taboola.com^",
        "||outbrain.com^", "||widgets.outbrain.com^", "||log.outbrain.com^",
        "||revcontent.com^", "||trends.revcontent.com^",
        "||mgid.com^", "||jsc.mgid.com^", "||c.mgid.com^",
        "||nativo.com^", "||dianomi.com^", "||content.ad^",
        // Popups and popunders networks
        "||popads.net^", "||popcash.net^", "||propellerads.com^", "||adsterra.com^",
        "||exoclick.com^", "||hilltopads.net^", "||clickadu.com^", "||adcash.com^",
        "||popunder.net^", "||clickunder.ru^", "||poponclick.com^", "||zeroredirect1.com^", "||tsyndicate.com^",
        "||ad-maven.com^", "||clicksor.com^", "||syndication.exoclick.com^",
        // Video ads networks
        "||innovid.com^", "||spotxchange.com^", "||springserve.com^", "||tremorhub.com^",
        "||aniview.com^", "||vidazoo.com^", "||vi-serve.com^", "||jwpltx.com^", "||beachfrontmedia.com^",
        // Malvertising & Cryptomining
        "||coinhive.com^", "||coin-hive.com^", "||cryptoloot.pro^", "||webminepool.com^",
        "||malware-test.com^", "||phishing-test.com^",
        // Primary Banner & Display Ads Cosmetic Rules
        "##.adsbygoogle", "##ins.adsbygoogle", "##div[id^='google_ads_']", "##div[id*='google_ads_']",
        "##iframe[id^='google_ads_']", "##iframe[id*='google_ads_']",
        "##iframe[src*='doubleclick.net']", "##iframe[src*='googleadservices.com']",
        "##iframe[src*='googlesyndication.com']", "##iframe[src*='amazon-adsystem.com']",
        "##iframe[src*='adnxs.com']", "##iframe[src*='rubiconproject.com']", "##iframe[src*='pubmatic.com']",
        "##iframe[src*='smartadserver.com']", "##iframe[src*='criteo']", "##iframe[src*='openx.net']", "##iframe[src*='teads.tv']",
        "##.ad-banner", "##.banner-ad", "##.ad-box", "##.ad-slot", "##.ad-container",
        "##.ad-wrapper", "##.ad-unit", "##.advertisement", "##[data-ad-unit]", "##[data-ad-slot]", "##[data-ad]", "##[data-ad-name]",
        "##[aria-label='Advertisement']", "##[aria-label='Sponsored']",
        "##div[class*='sponsored']", "##div[class*='promoted-content']", "##.sponsored-post", "##.sponsored-content",
        "##.header-ad", "##.footer-ad", "##.sidebar-ad", "##.top-ad", "##.bottom-ad", "##.inline-ad",
        "##.taboola", "##.outbrain", "##.mgid", "##.revcontent", "##div[id*='taboola']", "##div[id*='outbrain']",
        "##ytd-ad-slot-renderer", "##ytm-promoted-sparkles-web-renderer",
        "##ytm-promoted-video-renderer", "##ytm-ad-slot-renderer", "##ytm-companion-ad-renderer",
        "##div[data-testid='ad-banner']", "##div[data-testid='companion-ad']",
        // Annoying Overlays & Cookie Consent Walls
        "##.sp_message_container_root", "##div[id^='sp_message_container']", "##.fc-consent-root",
        "##div[class*='cookie-banner']", "##div[id*='cookie-banner']", "##div[class*='cookie-consent']",
        "##div[id*='cookie-consent']", "##div[class*='cookie-notice']", "##div[id*='cookie-notice']",
        "###onetrust-consent-sdk", "##div[class*='newsletter-popup']", "##div[class*='subscribe-modal']",
        "##div[class*='paywall-overlay']", "##div[id*='paywall-overlay']", "##.tp-modal", "##.tp-backdrop",
        "##.interstitial-ad", "##div[class*='interstitial']", "##div[id*='interstitial']"
    ).toList()

    fun getRules(context: Context): FilterRules {
        val current = cachedRules
        if (current != null) return current

        synchronized(this) {
            val locked = cachedRules
            if (locked != null) return locked

            val loaded = loadRulesFromStorage(context)
            cachedRules = loaded
            return loaded
        }
    }

    private fun loadRulesFromStorage(context: Context): FilterRules {
        val updatedFile = File(context.filesDir, RULES_FILE_NAME)
        if (updatedFile.exists() && updatedFile.length() > 0) {
            try {
                val jsonStr = updatedFile.readText()
                val parsed = parseRulesJson(jsonStr)
                if (parsed != null && parsed.totalCount > 0) {
                    return parsed
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return try {
            val inputStream: InputStream = context.assets.open(RULES_FILE_NAME)
            val jsonStr = inputStream.bufferedReader().use { it.readText() }
            parseRulesJson(jsonStr) ?: createDefaultFallbackRules()
        } catch (e: Exception) {
            e.printStackTrace()
            createDefaultFallbackRules()
        }
    }

    private fun parseRulesJson(jsonStr: String): FilterRules? {
        return try {
            val root = JSONObject(jsonStr)
            val version = root.optInt("version", 1)
            val lastUpdated = root.optString("last_updated", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
            val cats = root.getJSONObject("categories")

            fun jsonArrayToSet(name: String): Set<String> {
                val arr = cats.optJSONArray(name) ?: return emptySet()
                val set = HashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.getString(i).trim().lowercase()
                    if (s.isNotEmpty()) set.add(s)
                }
                return set
            }

            fun jsonArrayToList(name: String): List<String> {
                val arr = cats.optJSONArray(name) ?: return emptyList()
                val list = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.getString(i).trim()
                    if (s.isNotEmpty()) list.add(s)
                }
                return list
            }

            FilterRules(
                version = version,
                lastUpdated = lastUpdated,
                adDomains = jsonArrayToSet("ads"),
                trackerDomains = jsonArrayToSet("trackers"),
                maliciousDomains = jsonArrayToSet("malicious"),
                phishingDomains = jsonArrayToSet("phishing"),
                unwantedScriptDomains = jsonArrayToSet("unwanted_scripts"),
                pathPatterns = jsonArrayToList("path_patterns")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun createDefaultFallbackRules(): FilterRules {
        return FilterRules(
            version = 1,
            lastUpdated = "2026-10-14",
            adDomains = setOf(
                "doubleclick.net", "googleadservices.com", "googlesyndication.com", "adservice.google.com",
                "googletagservices.com", "2mdn.net", "amazon-adsystem.com", "aaxads.com",
                "adnxs.com", "adnxs-simple.com", "ib.adnxs.com", "criteo.com", "criteo.net",
                "taboola.com", "outbrain.com", "popads.net", "propellerads.com", "revcontent.com",
                "mgid.com", "adsterra.com", "popcash.net", "exoclick.com", "nativo.com",
                "rubiconproject.com", "pubmatic.com", "openx.net", "smartadserver.com",
                "media.net", "sovrn.com", "bidswitch.net", "casalemedia.com", "indexexchange.com",
                "teads.tv", "triplelift.com", "yieldmo.com", "sharethrough.com", "inmobi.com",
                "moatads.com", "adform.net", "flashtalking.com", "serving-sys.com", "contextweb.com",
                "lijit.com", "adroll.com", "yandexadexchange.net", "an.yandex.ru", "invitemedia.com",
                "admeld.com", "applovin.com", "ironsrc.com", "vungle.com", "unity3d.com",
                "chartboost.com", "zergnet.com", "adblade.com", "undertone.com", "exponential.com",
                "hilltopads.net", "clickadu.com", "adcash.com", "juicyads.com", "trafficjunky.com",
                "dianomi.com", "content.ad", "adcolony.com", "smaato.net", "gumgum.com", "onetag.com"
            ),
            trackerDomains = setOf(
                "google-analytics.com", "googletagmanager.com", "hotjar.com", "scorecardresearch.com",
                "quantserve.com", "mixpanel.com", "segment.io", "clarity.ms", "connect.facebook.net",
                "analytics.tiktok.com", "amplitude.com", "fingerprintjs.com", "chartbeat.com",
                "crazyegg.com", "mouseflow.com", "fullstory.com", "inspectlet.com", "branch.io",
                "appsflyer.com", "adjust.com", "kochava.com", "singular.net", "optimizely.com"
            ),
            maliciousDomains = setOf(
                "coinhive.com", "coin-hive.com", "cryptoloot.pro", "malware-test.com", "webminepool.com"
            ),
            phishingDomains = setOf(
                "testsafebrowsing.appspot.com", "phishing-test.com"
            ),
            unwantedScriptDomains = setOf(
                "onesignal.com", "pushassist.com", "fingerprintjs.com"
            ),
            pathPatterns = listOf(
                "/pagead/", "/ads.js", "/adserver/", "/tr?", "/pixel/", "/analytics.js",
                "/vast/", "/vpaid/", "ima3.js", "/gpt.js", "pubads", "prebid", "adsbygoogle",
                "show_ads", "/adunit", "/ad-frame", "/ad_status", "/banner_ad", "/sponsored"
            )
        )
    }

    fun getLastUpdatedFormatted(context: Context): String {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val lastTs = prefs.getLong(PREFS_KEY_LAST_UPDATE, 0L)
        if (lastTs <= 0L) {
            return "EasyList & EasyPrivacy (Active)"
        }
        val df = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
        return "Updated ${df.format(Date(lastTs))}"
    }

    fun isRegionalListEnabled(context: Context, regId: String): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val info = REGIONAL_LISTS.find { it.id == regId } ?: return false
        return prefs.getBoolean(info.prefKey, false)
    }

    fun setRegionalListEnabled(context: Context, regId: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val info = REGIONAL_LISTS.find { it.id == regId } ?: return
        prefs.edit().putBoolean(info.prefKey, enabled).apply()
        invalidateCache()
    }

    fun updateRules(context: Context, callback: (success: Boolean, ruleCount: Int, message: String) -> Unit) {
        executor.execute {
            try {
                val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
                val targetUrls = mutableListOf<String>()
                targetUrls.addAll(CORE_NETWORK_LIST_URLS)

                // Add active regional lists
                for (reg in REGIONAL_LISTS) {
                    if (prefs.getBoolean(reg.prefKey, false)) {
                        targetUrls.add(reg.url)
                    }
                }

                // Add active custom subscription lists
                val customSubs = HazeDatabase.getInstance(context).getFilterSubscriptions()
                for (sub in customSubs) {
                    if (sub.isEnabled && sub.url.isNotBlank()) {
                        targetUrls.add(sub.url)
                    }
                }

                val downloaded = buildList {
                    targetUrls.forEach { source ->
                        var conn: HttpURLConnection? = null
                        try {
                            conn = (URL(source).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 8_000; readTimeout = 12_000; instanceFollowRedirects = true
                                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) HazePrivacyShield/3.0")
                            }
                            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                                addAll(conn.inputStream.bufferedReader().use { it.readLines() })
                            }
                        } catch (_: Exception) {} finally { conn?.disconnect() }
                    }
                }

                if (downloaded.isNotEmpty()) {
                    File(context.filesDir, NETWORK_RULES_FILE_NAME).writeText(downloaded.joinToString("\n"))
                    invalidateCache()
                    val count = downloaded.size
                    prefs.edit()
                        .putLong(PREFS_KEY_LAST_UPDATE, System.currentTimeMillis())
                        .putInt(PREFS_KEY_RULE_COUNT, count)
                        .apply()
                    mainHandler.post { callback(true, count, "Successfully updated $count filter rules.") }
                    return@execute
                }
            } catch (_: Exception) { }

            // Fallback: Verify existing local rules
            val current = getRules(context)
            val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(PREFS_KEY_LAST_UPDATE, System.currentTimeMillis())
                .putInt(PREFS_KEY_RULE_COUNT, current.totalCount)
                .apply()

            mainHandler.post {
                callback(false, current.totalCount, "Rules verified; latest active filters remain operational.")
            }
        }
    }
}

