package com.example.haze

import java.net.URI

/**
 * Small, deterministic subset of the Adblock Plus/uBlock network-rule grammar.
 * Cosmetic/scriptlet rules are deliberately ignored here: WebView exposes no safe
 * document-level equivalent for them.  Network rules are evaluated before WebView
 * starts the resource fetch through WebViewClient.shouldInterceptRequest.
 */
object HazeContentFilter {
    data class Request(val url: String, val documentUrl: String?, val resourceType: String? = null)
    data class Match(val rule: String, val exception: Boolean)

    internal data class Rule(
        val source: String, val pattern: Regex, val domainAnchor: Boolean,
        val thirdParty: Boolean?, val includeDomains: Set<String>, val excludeDomains: Set<String>,
        val exception: Boolean
    )

    class Compiled internal constructor(private val rules: List<Rule>, private val exceptions: List<Rule>) {
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
    }

    fun compile(lines: Sequence<String>): Compiled {
        val parsed = lines.mapNotNull(::parse).toList()
        return Compiled(parsed.filterNot { it.exception }, parsed.filter { it.exception })
    }

    private fun parse(raw: String): Rule? {
        val text = raw.trim()
        if (text.isEmpty() || text.startsWith("!") || text.startsWith("[") || text.contains("##") || text.contains("#@#")) return null
        var body = text
        val exception = body.startsWith("@@").also { if (it) body = body.removePrefix("@@") }
        val pieces = body.split("\$", limit = 2)
        val expression = pieces[0]
        if (expression.isBlank() || expression.startsWith("/")) return null // regexp rules are intentionally unsupported
        var thirdParty: Boolean? = null
        val includeDomains = mutableSetOf<String>(); val excludeDomains = mutableSetOf<String>()
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
                when (ch) { '*' -> append(".*"); '^' -> append("(?:[^A-Za-z0-9_.%-]|$)")
                    else -> append(Regex.escape(ch.toString())) }
            }
        }
        return runCatching { Rule(text, Regex(regex, RegexOption.IGNORE_CASE), domainAnchor, thirdParty, includeDomains, excludeDomains, exception) }.getOrNull()
    }

    private fun domainMatches(host: String?, domain: String) = host != null && (host == domain || host.endsWith(".$domain"))
    private fun sameSite(a: String, b: String): Boolean {
        fun root(host: String) = host.split('.').takeLast(2).joinToString(".")
        return a == b || root(a) == root(b)
    }
}
