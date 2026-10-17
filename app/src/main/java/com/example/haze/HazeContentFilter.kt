package com.example.haze

import java.net.URI

/**
 * High-performance Adblock Plus / uBlock Origin compatible filter engine.
 * Supports:
 * - Network rules (domain anchors ||, exact paths, exceptions @@, $third-party, $domain)
 * - Cosmetic rules (generic ##, domain-specific domain##, exceptions #@#)
 * - Element-hiding CSS compiler for banner ads, sponsored widgets, and annoying overlays.
 */
object HazeContentFilter {
    data class Request(val url: String, val documentUrl: String?, val resourceType: String? = null)
    data class Match(val rule: String, val exception: Boolean)

    internal data class Rule(
        val source: String, val pattern: Regex, val domainAnchor: Boolean,
        val thirdParty: Boolean?, val includeDomains: Set<String>, val excludeDomains: Set<String>,
        val exception: Boolean, val anchorDomain: String? = null
    )

    internal data class CosmeticRule(
        val source: String,
        val domains: Set<String>,
        val excludeDomains: Set<String>,
        val selector: String,
        val exception: Boolean
    )

    // Built-in high-impact cosmetic rules covering requested ad/tracker/overlay categories
    private val BUILTIN_COSMETIC_SELECTORS = listOf(
        // Banner & display ads
        ".adsbygoogle", "ins.adsbygoogle", "iframe[src*=\"doubleclick\"]",
        "iframe[src*=\"googlesyndication.com\"]", "iframe[src*=\"googleadservices.com\"]",
        "iframe[src*=\"amazon-adsystem.com\"]", "iframe[src*=\"adnxs.com\"]",
        "iframe[src*=\"rubiconproject.com\"]", "iframe[src*=\"pubmatic.com\"]",
        "iframe[src*=\"smartadserver.com\"]", "iframe[src*=\"criteo\"]",
        "iframe[src*=\"openx.net\"]", "iframe[src*=\"teads.tv\"]", "iframe[src*=\"moatads\"]",
        "iframe[src*=\"adform\"]", "iframe[src*=\"serving-sys\"]",
        "div[id^=\"google_ads_\"]", "div[id*=\"google_ads_\"]", "iframe[id^=\"google_ads_\"]", "iframe[id*=\"google_ads_\"]",
        "div[id^=\"dfp-\"]", "div[id^=\"gpt-\"]", "div[id*=\"gpt-ad\"]", "div[id^=\"div-gpt-ad\"]", "div[id*=\"div-gpt-ad\"]",
        "div[id^=\"ad-slot\"]", "div[id^=\"ad-container\"]", "div[id^=\"ad-wrapper\"]", "div[id^=\"ad-banner\"]", "div[id*=\"ad-section\"]",
        ".banner-ad", ".banner_ad", ".ad-banner", ".ad-slot", ".ad-card", ".ad-box", ".ad-container", ".ad-wrapper", ".ad-unit",
        ".ad-holder", ".ad-placeholder", ".ad-placement", ".ad-leaderboard", ".ad-rectangle", ".ad-skyscraper", ".ad-section",
        ".advertisement", ".advertisement-container", ".advertisement-wrapper", ".advertisement-section", ".advertisement-slot", ".advertisement-box", ".advertisement-placeholder",
        "[data-ad-unit]", "[data-ad-slot]", "[data-ad-client]", "[data-ad-name]", "[data-ad-format]", "[data-ad-container]", "[data-ad-wrapper]", "[data-ad-placeholder]",
        "[id*=\"google_ads\"]", "[data-testid=\"ad-banner\"]", "[data-testid=\"companion-ad\"]",
        "[aria-label=\"Advertisement\"]", "[aria-label=\"Sponsored\"]", "[aria-label=\"Advert\"]", "[aria-label=\"Anuncio\"]", "[data-advertisement]", "[data-google-query-id]",
        ".sponsored-post", ".sponsored-content", ".promoted-content", ".sponsored-badge", ".sponsored-wrapper", ".sponsored-container", ".sponsored-box",
        ".header-ad", ".footer-ad", ".sidebar-ad", ".top-ad", ".bottom-ad", ".inline-ad", ".in-article-ad", ".in-content-ad",
        ".sticky-ad", ".floating-ad",
        // Ad sections and semantic containers
        "section[class*=\"ad-\"]", "section[id*=\"ad-\"]", "section[class*=\"advert\"]", "section[id*=\"advert\"]", "section[class*=\"sponsor\"]",
        "aside[class*=\"ad-\"]", "aside[id*=\"ad-\"]", "aside[class*=\"advert\"]", "aside[class*=\"sponsor\"]",
        "div[class*=\"ad-section\"]", "div[id*=\"ad-section\"]", "div[class*=\"ad_section\"]", "div[id*=\"ad_section\"]",
        "div[class*=\"ad-container\"]", "div[id*=\"ad-container\"]", "div[class*=\"ad_container\"]", "div[id*=\"ad_container\"]",
        "div[class*=\"ad-wrapper\"]", "div[id*=\"ad-wrapper\"]", "div[class*=\"ad_wrapper\"]", "div[id*=\"ad_wrapper\"]",
        "div[class*=\"ad-slot\"]", "div[id*=\"ad-slot\"]", "div[class*=\"ad_slot\"]", "div[id*=\"ad_slot\"]",
        "div[class*=\"ad-holder\"]", "div[id*=\"ad-holder\"]", "div[class*=\"ad_holder\"]", "div[id*=\"ad_holder\"]",
        "div[class*=\"ad-placement\"]", "div[id*=\"ad-placement\"]", "div[class*=\"ad_placement\"]",
        "div[class*=\"ad-placeholder\"]", "div[id*=\"ad-placeholder\"]", "div[class*=\"ad_placeholder\"]",
        "div[class*=\"ad-box\"]", "div[id*=\"ad-box\"]", "div[class*=\"ad_box\"]", "div[id*=\"ad_box\"]",
        "div[class*=\"ad-unit\"]", "div[id*=\"ad-unit\"]", "div[class*=\"ad_unit\"]", "div[id*=\"ad_unit\"]",
        "div[class*=\"google-ad\"]", "div[id*=\"google-ad\"]", "div[class*=\"google_ad\"]", "div[id*=\"google_ad\"]",
        ".ad-label", ".ad-title", ".ad-caption", ".advertisement-label", ".ad-badge", ".ad-notice", ".sponsored-label", ".sponsored-tag",
        // Sponsored widgets (Taboola, Outbrain, Revcontent, MGID, Nativo)
        ".tbl-feed-container", ".trc_rbox_div", "div[id*=\"taboola\"]", "[data-taboola-placeholder]",
        ".outbrain_widget", ".OB-widget", "#outbrain_widget_0", "[data-widget-id*=\"outbrain\"]",
        ".rc-item", "div[id*=\"rc-widget\"]", "[data-revcontent]",
        "div[id*=\"MarketGid\"]", ".mgbox", ".mgid-widget", "[data-mgid]",
        ".ntv-ad", "div[id*=\"nativo\"]",
        // Video ads overlays (Target overlays, banners, paywalls and promos specifically without touching video element or player container)
        ".ytp-ad-overlay-container", ".ytp-ad-text", ".ytp-ad-message-container",
        ".ytp-ad-image-overlay", ".ytp-ad-overlay-image", ".ytp-ad-player-overlay",
        ".ytp-ad-player-overlay-layout", ".ytp-ad-player-overlay-skip-or-preview",
        ".ytp-ad-action-interstitial", ".ytp-ad-survey", ".ytp-ad-progress-list",
        "ytd-ad-slot-renderer", "ytd-in-feed-ad-layout-renderer", "ytd-promoted-sparkles-web-renderer",
        "ytd-promoted-video-renderer", "ytd-display-ad-renderer", "ytd-statement-banner-renderer",
        "ytd-banner-promo-renderer", "ytd-compact-promoted-video-renderer", "ytd-companion-ad-renderer",
        "ytd-merch-shelf-renderer", "ytd-action-companion-ad-renderer",
        "ytd-engagement-panel-section-list-renderer[target-id=\"engagement-panel-ads\"]",
        "ytd-player-legacy-desktop-watch-ads-renderer", "#masthead-ad", "#player-ads",
        "ytm-promoted-sparkles-web-renderer", "ytm-promoted-video-renderer", "ytm-ad-slot-renderer",
        "ytm-companion-ad-renderer", "ytm-inline-ad", "ytm-statement-banner-renderer",
        "ytm-banner-promo-renderer", "ytm-compact-promoted-video-renderer",
        "ytm-promoted-sparkles-text-search-web-renderer", "ytm-paid-content-overlay-renderer",
        "ytm-mealbar-promo-renderer", "yt-mealbar-promo-renderer", "ytd-mealbar-promo-renderer",
        "ytm-upsell-dialog-renderer", "ytd-offline-promo-renderer",
        "ytd-enforcement-message-view-model", "#enforcement-message",
        "tp-yt-paper-dialog:has(ytd-enforcement-message-view-model)",
        "tp-yt-paper-dialog:has(#feedback)", "yt-playability-error-supported-renderers",
        "ytm-search ytm-compact-promoted-video-renderer",
        // Generic Video Player Ad Overlays
        ".ima-ad-container", ".video-ad-overlay", ".vjs-ad-loading", ".jw-flag-ads", ".jw-ad-container",
        // Annoying overlays (Cookie consent & anti-adblock modals)
        "#onetrust-consent-sdk", ".cc-window", "#didomi-host", ".cookie-banner",
        "[id*=\"cookie-notice\"]", ".qc-cmp2-container", "#CybotCookiebotDialog",
        "div[class*=\"adblock-modal\"]", "div[class*=\"sp-message\"]", ".fc-ab-root",
        "div[id*=\"adblock-overlay\"]", ".adblocker-overlay", "[id*=\"adblock-detector\"]"
    )

    class Compiled internal constructor(
        private val rules: List<Rule>,
        private val exceptions: List<Rule>,
        private val genericCosmetic: Set<String>,
        private val domainCosmetic: Map<String, Set<String>>,
        private val domainCosmeticExceptions: Map<String, Set<String>>
    ) {
        val totalNetworkRules: Int get() = rules.size + exceptions.size
        val totalCosmeticRules: Int get() = genericCosmetic.size + domainCosmetic.values.sumOf { it.size }

        fun match(request: Request): Match? {
            val host = extractHost(request.url) ?: return null
            val topHost = request.documentUrl?.let { extractHost(it) }
            fun applies(rule: Rule): Boolean {
                if (rule.anchorDomain != null && !(host == rule.anchorDomain || host.endsWith(".${rule.anchorDomain}") || host.contains(rule.anchorDomain))) return false
                val thirdParty = topHost != null && !sameSite(host, topHost)
                if (rule.thirdParty != null && rule.thirdParty != thirdParty) return false
                if (rule.includeDomains.isNotEmpty() && !rule.includeDomains.any { domainMatches(topHost, it) }) return false
                if (rule.excludeDomains.any { domainMatches(topHost, it) }) return false
                return rule.pattern.containsMatchIn(request.url)
            }
            exceptions.firstOrNull(::applies)?.let { return Match(it.source, true) }
            rules.firstOrNull(::applies)?.let { return Match(it.source, false) }
            return null
        }

        /**
         * Generates an optimized element-hiding CSS stylesheet for the given document domain.
         */
        fun getCosmeticCss(documentDomain: String?): String {
            val cleanHost = documentDomain?.lowercase()?.removePrefix("www.")
            val activeSelectors = LinkedHashSet<String>(BUILTIN_COSMETIC_SELECTORS)
            activeSelectors.addAll(genericCosmetic)

            if (!cleanHost.isNullOrEmpty()) {
                // Add domain-specific cosmetic rules
                domainCosmetic[cleanHost]?.let { activeSelectors.addAll(it) }
                // Check parent domain suffixes (e.g. news.site.com matches site.com)
                domainCosmetic.forEach { (d, selectors) ->
                    if (cleanHost.endsWith(".$d")) activeSelectors.addAll(selectors)
                }

                // Remove cosmetic exceptions for this domain
                domainCosmeticExceptions[cleanHost]?.let { activeSelectors.removeAll(it) }
                domainCosmeticExceptions.forEach { (d, exceptions) ->
                    if (cleanHost.endsWith(".$d")) activeSelectors.removeAll(exceptions)
                }
            }

            // Never allow cosmetic rules to hide video elements or video players
            activeSelectors.removeAll { sel ->
                val s = sel.lowercase().trim()
                s == "video" || s.startsWith("video[") || s.startsWith("video.") ||
                    s.contains("html5-video") || s.contains("html5-main") ||
                    s.contains("movie_player") || s.contains("video-stream") ||
                    s == ".video-ads" || s.contains("player-container") ||
                    s == ".ytp-ad-module" || s.contains("ad-showing")
            }

            if (activeSelectors.isEmpty()) return ""

            val joined = activeSelectors.joinToString(", ")
            val hideRules = "$joined { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; height: 0 !important; max-height: 0 !important; min-height: 0 !important; width: 0 !important; max-width: 0 !important; min-width: 0 !important; margin: 0 !important; padding: 0 !important; border: none !important; outline: none !important; overflow: hidden !important; clip: rect(0, 0, 0, 0) !important; }"
            val videoProtectionRule = """
                video,
                video.video-stream,
                video.html5-main-video,
                .html5-main-video,
                [class*="video-stream"] video,
                [class*="player-container"] video {
                    opacity: 1 !important;
                    visibility: visible !important;
                    max-height: none !important;
                    max-width: none !important;
                    pointer-events: auto !important;
                }
            """.trimIndent()
            return "$hideRules\n$videoProtectionRule"
        }
    }

    fun compile(lines: Sequence<String>): Compiled {
        val networkRules = mutableListOf<Rule>()
        val networkExceptions = mutableListOf<Rule>()
        val genericCosmetic = mutableSetOf<String>()
        val domainCosmetic = mutableMapOf<String, MutableSet<String>>()
        val domainCosmeticExceptions = mutableMapOf<String, MutableSet<String>>()

        lines.forEach { raw ->
            val text = raw.trim()
            if (text.isEmpty() || text.startsWith("!") || text.startsWith("[")) return@forEach

            // 1. Cosmetic rules (## and #@#)
            if (text.contains("##") || text.contains("#@#")) {
                val isException = text.contains("#@#")
                val parts = if (isException) text.split("#@#", limit = 2) else text.split("##", limit = 2)
                if (parts.size == 2) {
                    val domainPart = parts[0].trim().lowercase()
                    val selector = parts[1].trim()
                    if (selector.isNotEmpty()) {
                        if (domainPart.isEmpty()) {
                            if (!isException) genericCosmetic.add(selector)
                        } else {
                            val domains = domainPart.split(',')
                            for (d in domains) {
                                val clean = d.trim()
                                if (clean.startsWith("~")) continue
                                if (isException) {
                                    domainCosmeticExceptions.getOrPut(clean) { mutableSetOf() }.add(selector)
                                } else {
                                    domainCosmetic.getOrPut(clean) { mutableSetOf() }.add(selector)
                                }
                            }
                        }
                    }
                }
                return@forEach
            }

            // 2. Network rules
            parseNetworkRule(text)?.let {
                if (it.exception) networkExceptions.add(it) else networkRules.add(it)
            }
        }

        return Compiled(networkRules, networkExceptions, genericCosmetic, domainCosmetic, domainCosmeticExceptions)
    }

    private fun parseNetworkRule(raw: String): Rule? {
        var body = raw
        val exception = body.startsWith("@@").also { if (it) body = body.removePrefix("@@") }
        val pieces = body.split("\$", limit = 2)
        val expression = pieces[0]
        if (expression.isBlank() || expression.startsWith("/")) return null
        var thirdParty: Boolean? = null
        val includeDomains = mutableSetOf<String>()
        val excludeDomains = mutableSetOf<String>()
        pieces.getOrNull(1)?.split(',')?.forEach { option ->
            when {
                option == "third-party" -> thirdParty = true
                option == "~third-party" -> thirdParty = false
                option.startsWith("domain=") -> option.removePrefix("domain=").split('|').forEach { domain ->
                    if (domain.startsWith("~")) excludeDomains += domain.removePrefix("~").lowercase()
                    else includeDomains += domain.lowercase()
                }
            }
        }
        val domainAnchor = expression.startsWith("||")
        val normalized = expression.removePrefix("||").removePrefix("|").removeSuffix("|")
        if (normalized.isBlank()) return null
        val anchorDomain = if (domainAnchor) {
            normalized.substringBefore('^').substringBefore('/').substringBefore('*').lowercase().ifBlank { null }
        } else null
        val regex = buildString {
            if (domainAnchor) append("^[a-z][a-z0-9+.-]*://(?:[^/]*\\.)?")
            normalized.forEach { ch ->
                when (ch) {
                    '*' -> append(".*")
                    '^' -> append("(?:[^A-Za-z0-9_.%-]|$)")
                    else -> append(Regex.escape(ch.toString()))
                }
            }
        }
        return runCatching {
            Rule(raw, Regex(regex, RegexOption.IGNORE_CASE), domainAnchor, thirdParty, includeDomains, excludeDomains, exception, anchorDomain)
        }.getOrNull()
    }

    private fun extractHost(url: String): String? {
        val schemeIndex = url.indexOf("://")
        val start = if (schemeIndex != -1) schemeIndex + 3 else 0
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                end = i
                break
            }
        }
        if (start >= end) return null
        return url.substring(start, end).trim().lowercase().removePrefix("www.").ifEmpty { null }
    }

    private fun domainMatches(host: String?, domain: String) = host != null && (host == domain || host.endsWith(".$domain"))
    private fun sameSite(a: String, b: String): Boolean {
        fun root(host: String) = host.split('.').takeLast(2).joinToString(".")
        return a == b || root(a) == root(b)
    }
}

