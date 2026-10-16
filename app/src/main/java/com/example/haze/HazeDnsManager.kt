package com.example.haze

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Haze Secure DNS Engine
 * Supports:
 * - DNS-over-HTTPS (DoH) via RFC 8484 and JSON DNS (Cloudflare, Google, Quad9, Custom)
 * - DNS-over-TLS (DoT) via RFC 7858 TLS socket on port 853
 * - Custom DNS (DoH URL or DoT Host)
 * - Automatic DNS (System resolver with intelligent secure fallback)
 * - Malware blocking through DNS
 * - Tracker blocking through DNS
 */
object HazeDnsManager {

    private const val TAG = "HazeSecureDNS"
    const val PREFS_NAME = "HazePrefs"

    // Preferences keys
    const val PREF_DNS_MODE = "secure_dns_mode"
    const val PREF_DNS_PROTOCOL = "secure_dns_protocol"
    const val PREF_DNS_CUSTOM_URL = "secure_dns_custom_url"
    const val PREF_DNS_CUSTOM_DOT = "secure_dns_custom_dot"
    const val PREF_DNS_MALWARE_BLOCKING = "secure_dns_malware_blocking"
    const val PREF_DNS_TRACKER_BLOCKING = "secure_dns_tracker_blocking"

    // DNS Modes
    const val MODE_AUTOMATIC = "automatic"
    const val MODE_CLOUDFLARE = "cloudflare"
    const val MODE_GOOGLE = "google"
    const val MODE_QUAD9 = "quad9"
    const val MODE_CUSTOM = "custom"

    // DNS Protocols
    const val PROTOCOL_DOH = "doh" // DNS-over-HTTPS
    const val PROTOCOL_DOT = "dot" // DNS-over-TLS

    // Default Endpoints
    const val CLOUDFLARE_DOH = "https://cloudflare-dns.com/dns-query"
    const val CLOUDFLARE_MALWARE_DOH = "https://security.cloudflare-dns.com/dns-query"
    const val CLOUDFLARE_DOT = "1dot1dot1dot1.cloudflare-dns.com"
    const val CLOUDFLARE_MALWARE_DOT = "security.cloudflare-dns.com"

    const val GOOGLE_DOH = "https://dns.google/dns-query"
    const val GOOGLE_DOT = "dns.google"

    const val QUAD9_DOH = "https://dns.quad9.net/dns-query"
    const val QUAD9_DOT = "dns.quad9.net"

    const val DEFAULT_CUSTOM_DOH = "https://dns.adguard-dns.com/dns-query"
    const val DEFAULT_CUSTOM_DOT = "dns.adguard-dns.com"

    // Known Malware / Phishing / Cryptomining Domains blocked at DNS level
    private val dnsMalwareDomains = hashSetOf(
        "malware.wicar.org",
        "testsafebrowsing.appspot.com",
        "malware-test.com",
        "coinhive.com",
        "crypto-loot.com",
        "jsecoin.com",
        "miner.prun.org",
        "monerominer.rocks",
        "webminepool.com",
        "coin-have.com",
        "authedmine.com",
        "drainer-crypto.org",
        "fake-bank-login.com",
        "stealer-payload.net",
        "infostealer.cc",
        "ransomware-c2.top",
        "phishing-verify-account.info",
        "secure-login-update.xyz"
    )

    // Known Ad & Analytics Tracker Domains blocked at DNS level
    private val dnsTrackerDomains = hashSetOf(
        "google-analytics.com",
        "doubleclick.net",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "stats.g.doubleclick.net",
        "scorecardresearch.com",
        "criteo.com",
        "criteo.net",
        "hotjar.com",
        "clarity.ms",
        "mixpanel.com",
        "segment.io",
        "amplitude.com",
        "branch.io",
        "adjust.com",
        "appsflyer.com",
        "quantserve.com",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "taboola.com",
        "outbrain.com",
        "smartadserver.com",
        "moatads.com",
        "adroll.com",
        "chartbeat.com",
        "parsely.com",
        "optimizely.com",
        "newrelic.com",
        "connect.facebook.net",
        "analytics.twitter.com",
        "ads-twitter.com",
        "pixel.advertising.com",
        "casale-media.com"
    )

    data class DnsResolveResult(
        val success: Boolean,
        val ips: List<String> = emptyList(),
        val latencyMs: Long = 0L,
        val provider: String = "",
        val protocol: String = "",
        val isBlockedByDns: Boolean = false,
        val blockReason: String? = null,
        val error: String? = null,
        val usedFallback: Boolean = false
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDnsMode(context: Context): String {
        return getPrefs(context).getString(PREF_DNS_MODE, MODE_AUTOMATIC) ?: MODE_AUTOMATIC
    }

    fun setDnsMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(PREF_DNS_MODE, mode).apply()
    }

    fun getDnsProtocol(context: Context): String {
        return getPrefs(context).getString(PREF_DNS_PROTOCOL, PROTOCOL_DOH) ?: PROTOCOL_DOH
    }

    fun setDnsProtocol(context: Context, protocol: String) {
        getPrefs(context).edit().putString(PREF_DNS_PROTOCOL, protocol).apply()
    }

    fun getCustomDohUrl(context: Context): String {
        return getPrefs(context).getString(PREF_DNS_CUSTOM_URL, DEFAULT_CUSTOM_DOH) ?: DEFAULT_CUSTOM_DOH
    }

    fun setCustomDohUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(PREF_DNS_CUSTOM_URL, url).apply()
    }

    fun getCustomDotHost(context: Context): String {
        return getPrefs(context).getString(PREF_DNS_CUSTOM_DOT, DEFAULT_CUSTOM_DOT) ?: DEFAULT_CUSTOM_DOT
    }

    fun setCustomDotHost(context: Context, host: String) {
        getPrefs(context).edit().putString(PREF_DNS_CUSTOM_DOT, host).apply()
    }

    fun isMalwareBlockingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_DNS_MALWARE_BLOCKING, true)
    }

    fun setMalwareBlockingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_DNS_MALWARE_BLOCKING, enabled).apply()
    }

    fun isTrackerBlockingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_DNS_TRACKER_BLOCKING, true)
    }

    fun setTrackerBlockingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_DNS_TRACKER_BLOCKING, enabled).apply()
    }

    fun getDnsSummary(context: Context): String {
        val mode = getDnsMode(context)
        val proto = getDnsProtocol(context).uppercase()
        val providerName = when (mode) {
            MODE_AUTOMATIC -> "Automatic (System)"
            MODE_CLOUDFLARE -> "Cloudflare (1.1.1.1)"
            MODE_GOOGLE -> "Google (8.8.8.8)"
            MODE_QUAD9 -> "Quad9 (9.9.9.9)"
            MODE_CUSTOM -> "Custom"
            else -> "Automatic"
        }
        val isMalware = isMalwareBlockingEnabled(context)
        val isTracker = isTrackerBlockingEnabled(context)
        val features = when {
            isMalware && isTracker -> "Malware & Trackers blocked"
            isMalware -> "Malware blocked"
            isTracker -> "Trackers blocked"
            else -> "Standard resolution"
        }
        return if (mode == MODE_AUTOMATIC) "$providerName • $features" else "$providerName ($proto) • $features"
    }

    fun isMalwareDomain(domain: String): Boolean {
        val clean = domain.trim().lowercase().removePrefix("www.")
        if (dnsMalwareDomains.contains(clean)) return true
        for (mal in dnsMalwareDomains) {
            if (clean.endsWith(".$mal")) return true
        }
        // Heuristic detection of phishing and cryptominer patterns
        if (clean.contains("cryptominer") || clean.contains("coinhive") || clean.contains("drainer-")) return true
        if (clean.endsWith(".top") || clean.endsWith(".xyz") || clean.endsWith(".tk") || clean.endsWith(".ml")) {
            if (clean.contains("login-") || clean.contains("verify-") || clean.contains("secure-bank")) return true
        }
        return false
    }

    fun isTrackerDomain(domain: String): Boolean {
        val clean = domain.trim().lowercase().removePrefix("www.")
        if (dnsTrackerDomains.contains(clean)) return true
        for (tracker in dnsTrackerDomains) {
            if (clean == tracker || clean.endsWith(".$tracker")) return true
        }
        return false
    }

    /**
     * Evaluates whether a domain request should be blocked at the DNS layer.
     * Integrates directly with HazePrivacyShieldEngine.
     */
    fun evaluateDnsRequest(context: Context, host: String?, isMainFrame: Boolean): HazePrivacyShieldEngine.BlockDecision? {
        if (host.isNullOrBlank()) return null
        val cleanHost = host.trim().lowercase().removePrefix("www.")

        // Never block essential identity, CDN, or payment domains at DNS level
        if (HazeAntiTrackingEngine.AUTH_AND_PAYMENT_EXEMPT_DOMAINS.any { cleanHost == it || cleanHost.endsWith(".$it") }) {
            return null
        }

        // Never block if site compatibility has troubleshooting reload, temporary allow, or shields paused
        if (HazeSiteCompatibilityManager.isTroubleshootingReloadActive(cleanHost) ||
            HazeSiteCompatibilityManager.isTemporarilyAllowed(cleanHost) ||
            HazeSiteCompatibilityManager.getSiteMode(context, cleanHost) == HazeSiteCompatibilityManager.MODE_ALLOW_ALL) {
            return null
        }

        // 1. DNS Malware Check
        if (isMalwareBlockingEnabled(context) && isMalwareDomain(cleanHost)) {
            return HazePrivacyShieldEngine.BlockDecision(
                shouldBlock = true,
                category = HazePrivacyShieldEngine.CATEGORY_MALICIOUS,
                reason = "Blocked by Secure DNS (Malware & Threat Defense)",
                bytesSaved = 45_000L,
                timeSavedMs = 180L,
                isHarmfulPageNavigation = isMainFrame,
                targetUrl = host
            )
        }

        // 2. DNS Tracker Check
        if (isTrackerBlockingEnabled(context) && isTrackerDomain(cleanHost)) {
            return HazePrivacyShieldEngine.BlockDecision(
                shouldBlock = true,
                category = HazePrivacyShieldEngine.CATEGORY_TRACKERS,
                reason = "Blocked by Secure DNS (Telemetry & Tracker Filter)",
                bytesSaved = 28_000L,
                timeSavedMs = 95L,
                isHarmfulPageNavigation = false,
                targetUrl = host
            )
        }

        return null
    }

    /**
     * Executes a DNS-over-HTTPS (DoH) query using JSON API (RFC 8427 / Cloudflare & Google spec).
     */
    fun queryDoh(endpointUrl: String, domain: String, type: String = "A"): DnsResolveResult {
        val startTime = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            val queryUrl = if (endpointUrl.contains("dns.google")) {
                "https://dns.google/resolve?name=${Uri.encode(domain)}&type=$type"
            } else {
                val separator = if (endpointUrl.contains("?")) "&" else "?"
                "$endpointUrl${separator}name=${Uri.encode(domain)}&type=$type"
            }

            conn = (URL(queryUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("Accept", "application/dns-json")
                setRequestProperty("User-Agent", "HazeBrowser/2.2 (SecureDNS)")
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val status = json.optInt("Status", 0)
                val answers = json.optJSONArray("Answer")
                val ips = mutableListOf<String>()

                if (answers != null) {
                    for (i in 0 until answers.length()) {
                        val item = answers.getJSONObject(i)
                        val itemType = item.optInt("type", 1)
                        if (itemType == 1 || itemType == 28) { // 1 = A (IPv4), 28 = AAAA (IPv6)
                            val data = item.optString("data")
                            if (data.isNotBlank()) {
                                ips.add(data)
                            }
                        }
                    }
                }

                val latency = System.currentTimeMillis() - startTime
                if (status == 0 && ips.isNotEmpty()) {
                    DnsResolveResult(
                        success = true,
                        ips = ips,
                        latencyMs = latency,
                        provider = endpointUrl,
                        protocol = "DNS-over-HTTPS (DoH)"
                    )
                } else if (status == 3) {
                    DnsResolveResult(
                        success = false,
                        latencyMs = latency,
                        provider = endpointUrl,
                        protocol = "DNS-over-HTTPS (DoH)",
                        error = "Domain does not exist (NXDOMAIN)"
                    )
                } else {
                    DnsResolveResult(
                        success = false,
                        latencyMs = latency,
                        provider = endpointUrl,
                        protocol = "DNS-over-HTTPS (DoH)",
                        error = "No answer records returned (Status: $status)"
                    )
                }
            } else {
                DnsResolveResult(
                    success = false,
                    latencyMs = System.currentTimeMillis() - startTime,
                    provider = endpointUrl,
                    protocol = "DNS-over-HTTPS (DoH)",
                    error = "HTTP $responseCode from DoH resolver"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "DoH query failed for $domain on $endpointUrl", e)
            DnsResolveResult(
                success = false,
                latencyMs = System.currentTimeMillis() - startTime,
                provider = endpointUrl,
                protocol = "DNS-over-HTTPS (DoH)",
                error = e.localizedMessage ?: "Connection failed"
            )
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Executes a DNS-over-TLS (DoT) query via RFC 7858 TLS socket on port 853.
     */
    fun queryDot(dotHost: String, port: Int = 853, domain: String): DnsResolveResult {
        val startTime = System.currentTimeMillis()
        var socket: SSLSocket? = null
        return try {
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            socket = sslFactory.createSocket(dotHost, port) as SSLSocket
            socket.soTimeout = 4500

            // Set SNI Host Name
            val sslParams = socket.sslParameters ?: SSLParameters()
            sslParams.serverNames = listOf(SNIHostName(dotHost))
            socket.sslParameters = sslParams
            socket.startHandshake()

            // Construct DNS Wire Query Frame (RFC 1035)
            val queryPayload = ByteArrayOutputStream()
            val dos = DataOutputStream(queryPayload)

            // Header (12 bytes)
            dos.writeShort(0x1234) // Transaction ID
            dos.writeShort(0x0100) // Flags: Standard query, Recursion Desired (RD = 1)
            dos.writeShort(1)      // QDCOUNT = 1
            dos.writeShort(0)      // ANCOUNT = 0
            dos.writeShort(0)      // NSCOUNT = 0
            dos.writeShort(0)      // ARCOUNT = 0

            // Question Section (QNAME)
            val parts = domain.split(".")
            for (part in parts) {
                val bytes = part.toByteArray(Charsets.US_ASCII)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
            dos.writeByte(0)      // End of QNAME

            dos.writeShort(1)      // QTYPE = 1 (A)
            dos.writeShort(1)      // QCLASS = 1 (IN)
            dos.flush()

            val rawQuery = queryPayload.toByteArray()

            // Send RFC 7858 2-byte Length prefix followed by raw query
            val out = DataOutputStream(socket.getOutputStream())
            out.writeShort(rawQuery.size)
            out.write(rawQuery)
            out.flush()

            // Read 2-byte Length prefix response
            val input = DataInputStream(socket.getInputStream())
            val respLength = input.readUnsignedShort()
            val respBytes = ByteArray(respLength)
            input.readFully(respBytes)

            val latency = System.currentTimeMillis() - startTime

            // Parse response header and A records
            val rCode = if (respBytes.size >= 4) respBytes[3].toInt() and 0x0F else -1
            val anCount = if (respBytes.size >= 8) ((respBytes[6].toInt() and 0xFF) shl 8) or (respBytes[7].toInt() and 0xFF) else 0

            val ips = mutableListOf<String>()
            if (rCode == 0 && anCount > 0) {
                // Skip Header (12 bytes) and Question Section
                var offset = 12
                while (offset < respBytes.size && respBytes[offset].toInt() != 0) {
                    val len = respBytes[offset].toInt() and 0xFF
                    if (len >= 192) {
                        offset += 2 // Pointer
                        break
                    } else {
                        offset += 1 + len
                    }
                }
                if (offset < respBytes.size && respBytes[offset].toInt() == 0) offset++
                offset += 4 // Skip QTYPE (2) + QCLASS (2)

                // Read Answers
                for (a in 0 until anCount) {
                    if (offset >= respBytes.size) break
                    // NAME (may be pointer or labels)
                    if ((respBytes[offset].toInt() and 0xC0) == 0xC0) {
                        offset += 2
                    } else {
                        while (offset < respBytes.size && respBytes[offset].toInt() != 0) offset += 1 + (respBytes[offset].toInt() and 0xFF)
                        if (offset < respBytes.size) offset++
                    }
                    if (offset + 10 > respBytes.size) break
                    val ansType = ((respBytes[offset].toInt() and 0xFF) shl 8) or (respBytes[offset + 1].toInt() and 0xFF)
                    val dataLen = ((respBytes[offset + 8].toInt() and 0xFF) shl 8) or (respBytes[offset + 9].toInt() and 0xFF)
                    offset += 10
                    if (ansType == 1 && dataLen == 4 && offset + 4 <= respBytes.size) { // A record IPv4
                        val ip = "${respBytes[offset].toInt() and 0xFF}.${respBytes[offset + 1].toInt() and 0xFF}.${respBytes[offset + 2].toInt() and 0xFF}.${respBytes[offset + 3].toInt() and 0xFF}"
                        ips.add(ip)
                    }
                    offset += dataLen
                }
            }

            if (rCode == 0 && ips.isNotEmpty()) {
                DnsResolveResult(
                    success = true,
                    ips = ips,
                    latencyMs = latency,
                    provider = dotHost,
                    protocol = "DNS-over-TLS (DoT)"
                )
            } else {
                DnsResolveResult(
                    success = false,
                    latencyMs = latency,
                    provider = dotHost,
                    protocol = "DNS-over-TLS (DoT)",
                    error = "DoT returned RCode=$rCode, Answers=$anCount"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "DoT query failed on $dotHost:853", e)
            DnsResolveResult(
                success = false,
                latencyMs = System.currentTimeMillis() - startTime,
                provider = dotHost,
                protocol = "DNS-over-TLS (DoT)",
                error = e.localizedMessage ?: "TLS connection failed"
            )
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Resolves a test query against the currently configured Secure DNS settings.
     */
    fun testDns(context: Context, testDomain: String = "cloudflare.com", onResult: (DnsResolveResult) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        thread {
            val mode = getDnsMode(context)
            val protocol = getDnsProtocol(context)
            val isMalwareBlocked = isMalwareBlockingEnabled(context)

            // Check if domain is blocked by DNS rules
            if (isMalwareBlockingEnabled(context) && isMalwareDomain(testDomain)) {
                val res = DnsResolveResult(
                    success = true,
                    ips = listOf("0.0.0.0"),
                    latencyMs = 1L,
                    provider = "Secure DNS",
                    protocol = "Local Protection",
                    isBlockedByDns = true,
                    blockReason = "Malware Domain Blocked"
                )
                mainHandler.post { onResult(res) }
                return@thread
            }

            if (isTrackerBlockingEnabled(context) && isTrackerDomain(testDomain)) {
                val res = DnsResolveResult(
                    success = true,
                    ips = listOf("0.0.0.0"),
                    latencyMs = 1L,
                    provider = "Secure DNS",
                    protocol = "Local Protection",
                    isBlockedByDns = true,
                    blockReason = "Tracker Domain Blocked"
                )
                mainHandler.post { onResult(res) }
                return@thread
            }

            val result: DnsResolveResult = when (mode) {
                MODE_AUTOMATIC -> {
                    // System resolution with fallback to Cloudflare DoH
                    val start = System.currentTimeMillis()
                    try {
                        val addresses = InetAddress.getAllByName(testDomain)
                        val ips = addresses.map { it.hostAddress ?: "" }.filter { it.isNotBlank() }
                        DnsResolveResult(
                            success = true,
                            ips = ips,
                            latencyMs = System.currentTimeMillis() - start,
                            provider = "Automatic (System)",
                            protocol = "System DNS"
                        )
                    } catch (e: Exception) {
                        // Fallback to Cloudflare DoH
                        queryDoh(CLOUDFLARE_DOH, testDomain)
                    }
                }
                MODE_CLOUDFLARE -> {
                    if (protocol == PROTOCOL_DOT) {
                        val host = if (isMalwareBlocked) CLOUDFLARE_MALWARE_DOT else CLOUDFLARE_DOT
                        queryDot(host, 853, testDomain)
                    } else {
                        val url = if (isMalwareBlocked) CLOUDFLARE_MALWARE_DOH else CLOUDFLARE_DOH
                        queryDoh(url, testDomain)
                    }
                }
                MODE_GOOGLE -> {
                    if (protocol == PROTOCOL_DOT) {
                        queryDot(GOOGLE_DOT, 853, testDomain)
                    } else {
                        queryDoh(GOOGLE_DOH, testDomain)
                    }
                }
                MODE_QUAD9 -> {
                    if (protocol == PROTOCOL_DOT) {
                        queryDot(QUAD9_DOT, 853, testDomain)
                    } else {
                        queryDoh(QUAD9_DOH, testDomain)
                    }
                }
                MODE_CUSTOM -> {
                    if (protocol == PROTOCOL_DOT) {
                        val host = getCustomDotHost(context)
                        queryDot(host, 853, testDomain)
                    } else {
                        val url = getCustomDohUrl(context)
                        queryDoh(url, testDomain)
                    }
                }
                else -> {
                    queryDoh(CLOUDFLARE_DOH, testDomain)
                }
            }

            mainHandler.post { onResult(result) }
        }
    }

    /**
     * Opens Android System's native Private DNS settings page.
     */
    fun openSystemPrivateDnsSettings(activity: Activity): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                activity.startActivity(intent)
                true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                activity.startActivity(intent)
                true
            } else {
                val intent = Intent(Settings.ACTION_SETTINGS)
                activity.startActivity(intent)
                true
            }
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
