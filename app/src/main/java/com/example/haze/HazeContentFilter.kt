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
        val exception: Boolean
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
        "div[class*=\"ad-\"]", "div[id*=\"ad-\"]", ".adsbygoogle", "iframe[src*=\"doubleclick\"]",
        ".banner-ad", ".ad-banner", ".ad-container", ".ad_wrapper", "[data-ad-unit]", "ins.adsbygoogle",
        ".ad-slot", ".ad-card", ".advertisement", "[id*=\"google_ads\"]",
        // Sponsored widgets (Taboola, Outbrain, Revcontent, MGID, Nativo)
        ".tbl-feed-container", ".trc_rbox_div", "div[id*=\"taboola\"]", "[data-taboola-placeholder]",
        ".outbrain_widget", ".OB-widget", "#outbrain_widget_0", "[data-widget-id*=\"outbrain\"]",
        ".rc-item", "div[id*=\"rc-widget\"]", "[data-revcontent]",
        "div[id*=\"MarketGid\"]", ".mgbox", ".mgid-widget", "[data-mgid]",
        ".ntv-ad", "div[id*=\"nativo\"]",
        // Video ads overlays
        ".ytp-ad-module", ".ytp-ad-overlay-container", ".ytp-ad-player-overlay", ".video-ads",
        "ytd-ad-slot-renderer", "ytd-promoted-video-renderer", "ytd-banner-promo-renderer",
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
            val uri = runCatching { URI(request.url) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val topHost = request.documentUrl?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }
            fun applies(rule: Rule): Boolean {
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

            if (activeSelectors.isEmpty()) return ""

            val joined = activeSelectors.joinToString(", ")
            return "$joined { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; height: 0 !important; max-height: 0 !important; width: 0 !important; }"
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
            Rule(raw, Regex(regex, RegexOption.IGNORE_CASE), domainAnchor, thirdParty, includeDomains, excludeDomains, exception)
        }.getOrNull()
    }

    private fun domainMatches(host: String?, domain: String) = host != null && (host == domain || host.endsWith(".$domain"))
    private fun sameSite(a: String, b: String): Boolean {
        fun root(host: String) = host.split('.').takeLast(2).joinToString(".")
        return a == b || root(a) == root(b)
    }
}

