package com.example.haze

import org.junit.Assert.*
import org.junit.Test

class HazeProtectionEngineTest {

    @Test
    fun testAdRequestBlocking() {
        val filter = HazeContentFilter.compile(
            sequenceOf(
                "||doubleclick.net^",
                "||googleadservices.com^",
                "||googlesyndication.com^",
                "*pagead/ads.js*"
            )
        )

        // 1. DoubleClick ad request
        val adMatch1 = filter.match(
            HazeContentFilter.Request("https://ad.doubleclick.net/ddm/adi/N1234.152003", "https://news.example.com")
        )
        assertNotNull("DoubleClick ad request must be blocked", adMatch1)
        assertFalse(adMatch1!!.exception)

        // 2. Google AdServices request
        val adMatch2 = filter.match(
            HazeContentFilter.Request("https://www.googleadservices.com/pagead/conversion.js", "https://shop.example.com")
        )
        assertNotNull("Google AdServices request must be blocked", adMatch2)

        // 3. PageAd script path wildcard match
        val adMatch3 = filter.match(
            HazeContentFilter.Request("https://example.com/pagead/ads.js", "https://example.com/article")
        )
        assertNotNull("PageAd script path wildcard must be blocked", adMatch3)
    }

    @Test
    fun testTrackerAndAnalyticsBlocking() {
        val filter = HazeContentFilter.compile(
            sequenceOf(
                "||google-analytics.com^",
                "||connect.facebook.net^",
                "||hotjar.com^",
                "||clarity.ms^"
            )
        )

        // 1. Google Analytics script
        val tracker1 = filter.match(
            HazeContentFilter.Request("https://www.google-analytics.com/analytics.js", "https://site.example.com")
        )
        assertNotNull("Google Analytics script must be blocked", tracker1)

        // 2. Facebook Pixel script
        val tracker2 = filter.match(
            HazeContentFilter.Request("https://connect.facebook.net/en_US/fbevents.js", "https://store.example.com")
        )
        assertNotNull("Facebook Pixel script must be blocked", tracker2)

        // 3. Hotjar heatmaps tracking
        val tracker3 = filter.match(
            HazeContentFilter.Request("https://static.hotjar.com/c/hotjar-12345.js", "https://blog.example.com")
        )
        assertNotNull("Hotjar tracking script must be blocked", tracker3)
    }

    @Test
    fun testThirdPartyCookieAndTrackerConstraints() {
        // Third-party rule: only blocks when requested from a different top-level domain
        val filter = HazeContentFilter.compile(
            sequenceOf("||tracking.cdn.org^\$third-party")
        )

        // Same site -> ALLOWED
        val sameSiteMatch = filter.match(
            HazeContentFilter.Request("https://tracking.cdn.org/pixel.gif", "https://tracking.cdn.org/dashboard")
        )
        assertNull("First-party tracking request should not trigger third-party rule", sameSiteMatch)

        // Cross site -> BLOCKED
        val crossSiteMatch = filter.match(
            HazeContentFilter.Request("https://tracking.cdn.org/pixel.gif", "https://independent-blog.com/post")
        )
        assertNotNull("Third-party cross-site tracker must be blocked", crossSiteMatch)
    }

    @Test
    fun testExceptionRulesPrecedence() {
        val filter = HazeContentFilter.compile(
            sequenceOf(
                "||adserver.net^",
                "@@||adserver.net/essential-script.js"
            )
        )

        // Standard ad request -> BLOCKED
        val blockedMatch = filter.match(
            HazeContentFilter.Request("https://adserver.net/banner.png", "https://news.com")
        )
        assertNotNull(blockedMatch)
        assertFalse(blockedMatch!!.exception)

        // Whitelisted script -> EXCEPTION (ALLOWED)
        val exceptionMatch = filter.match(
            HazeContentFilter.Request("https://adserver.net/essential-script.js", "https://news.com")
        )
        assertNotNull(exceptionMatch)
        assertTrue("Exception rule must take precedence over blocking rule", exceptionMatch!!.exception)
    }

}
