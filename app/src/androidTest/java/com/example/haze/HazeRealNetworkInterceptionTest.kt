package com.example.haze

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HazeRealNetworkInterceptionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        HazePrivacyShieldEngine.init(context)
        HazePrivacyShieldEngine.setShieldEnabled(context, true)
    }

    @Test
    fun testRealAdNetworkInterceptionAndEventPersistence() {
        val initialStats = HazePrivacyShieldEngine.getSessionStats()
        val initialTotal = HazePrivacyShieldEngine.getTotalBlocked(context)

        // Known advertising URL
        val adUrl = "https://ad.doubleclick.net/ddm/adi/N1234.152003"
        val pageUrl = "https://news.example.com/article"

        // 1. Evaluate Network Request Interception
        val decision = HazePrivacyShieldEngine.evaluateRequest(
            context = context,
            requestUrl = adUrl,
            pageUrl = pageUrl,
            isMainFrame = false,
            isPrivate = false
        )

        // 2. Verify Interception Decision
        assertTrue("Known ad request must be blocked before network fetch", decision.shouldBlock)
        assertEquals("Blocked category must be ADS", HazePrivacyShieldEngine.CATEGORY_ADS, decision.category)

        // 3. Verify Response Interception Payload
        val response = HazePrivacyShieldEngine.createBlockedResponse(adUrl)
        assertEquals(403, response.statusCode)
        assertEquals("true", response.responseHeaders["X-Haze-Blocked"])

        // 4. Verify Counter Incrementation
        val newTotal = HazePrivacyShieldEngine.getTotalBlocked(context)
        assertTrue("Total blocked counter must increment after request block", newTotal > initialTotal)

        // 5. Verify Database Event Persistence
        Thread.sleep(200) // Allow background async db executor to write
        val recentEvents = HazeDatabase.getInstance(context).getRecentBlockedEvents(10)
        val matchingEvent = recentEvents.find { it.url == adUrl }

        assertNotNull("Blocked event must appear in persistent database log", matchingEvent)
        assertEquals("ad.doubleclick.net", matchingEvent!!.domain)
        assertEquals(HazePrivacyShieldEngine.CATEGORY_ADS, matchingEvent.category)
    }

    @Test
    fun testTrackerAndAnalyticsInterception() {
        val trackerUrl = "https://www.google-analytics.com/analytics.js"
        val pageUrl = "https://store.example.com"

        val decision = HazePrivacyShieldEngine.evaluateRequest(
            context = context,
            requestUrl = trackerUrl,
            pageUrl = pageUrl,
            isMainFrame = false,
            isPrivate = false
        )

        assertTrue("Analytics tracking script must be intercepted", decision.shouldBlock)
        assertEquals(HazePrivacyShieldEngine.CATEGORY_TRACKERS, decision.category)
    }

    @Test
    fun testMalwareAndThreatDetection() {
        val malwareUrl = "https://coinhive.com/lib/coinhive.min.js"
        val pageUrl = "https://vulnerable-site.org"

        val decision = HazePrivacyShieldEngine.evaluateRequest(
            context = context,
            requestUrl = malwareUrl,
            pageUrl = pageUrl,
            isMainFrame = false,
            isPrivate = false
        )

        assertTrue("Known malware distribution endpoint must be blocked", decision.shouldBlock)
        assertEquals(HazePrivacyShieldEngine.CATEGORY_MALICIOUS, decision.category)
    }

    @Test
    fun testPhishingPageNavigationWarning() {
        val phishingUrl = "https://testsafebrowsing.appspot.com/s/phishing.html"

        val decision = HazePrivacyShieldEngine.evaluateRequest(
            context = context,
            requestUrl = phishingUrl,
            pageUrl = null,
            isMainFrame = true,
            isPrivate = false
        )

        assertTrue("Phishing page navigation must trigger security warning block", decision.shouldBlock)
        assertTrue("Main frame phishing block must trigger page warning", decision.isHarmfulPageNavigation)
        assertEquals(HazePrivacyShieldEngine.CATEGORY_PHISHING, decision.category)
    }

    @Test
    fun youtubeMediaSegmentsAreNeverBlockedByGenericAdRules() {
        assertTrue(HazePrivacyShieldEngine.isYouTubeHost("www.youtube.com"))
        assertTrue(
            HazePrivacyShieldEngine.isYouTubeMediaRequest(
                "https://r1---sn.googlevideo.com/videoplayback?mime=video%2Fmp4&range=0-1000"
            )
        )
        assertFalse(HazePrivacyShieldEngine.isYouTubeMediaRequest("https://www.youtube.com/watch?v=abc"))

        val youtubeAd = HazePrivacyShieldEngine.evaluateRequest(
            context,
            "https://googleads.g.doubleclick.net/pagead/ads?client=youtube",
            "https://www.youtube.com/watch?v=abc",
            isMainFrame = false,
            isPrivate = true
        )
        assertTrue("YouTube ad request must be blocked", youtubeAd.shouldBlock)
        assertEquals(HazePrivacyShieldEngine.CATEGORY_ADS, youtubeAd.category)

        val video = HazePrivacyShieldEngine.evaluateRequest(
            context,
            "https://r1---sn.googlevideo.com/videoplayback?mime=video%2Fmp4&range=0-1000",
            "https://www.youtube.com/watch?v=abc",
            isMainFrame = false,
            isPrivate = true
        )
        assertFalse("YouTube media segment must remain playable", video.shouldBlock)
    }
}
