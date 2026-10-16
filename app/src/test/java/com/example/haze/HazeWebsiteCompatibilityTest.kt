package com.example.haze

import org.junit.Assert.*
import org.junit.Test

class HazeWebsiteCompatibilityTest {

    @Test
    fun testOAuthAndAuthEndpointsNeverUnbounced() {
        val googleOAuth = "https://accounts.google.com/o/oauth2/v2/auth?redirect_uri=https%3A%2F%2Fmyapp.com%2Fcallback&response_type=code&client_id=123"
        val unwrapped = HazeAntiTrackingEngine.unwrapBounceTrackingUrl(googleOAuth)
        assertNull("OAuth authentication flow must NEVER be unbounced or redirected away from login", unwrapped)

        val microsoftAuth = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=abc&redirect_uri=https%3A%2F%2Fportal.azure.com"
        assertNull("Microsoft login must never be unbounced", HazeAntiTrackingEngine.unwrapBounceTrackingUrl(microsoftAuth))

        val payPalCheckout = "https://www.paypal.com/checkoutnow?token=EC-12345&returnUrl=https%3A%2F%2Fstore.com%2Fdone"
        assertNull("PayPal checkout must never be unbounced", HazeAntiTrackingEngine.unwrapBounceTrackingUrl(payPalCheckout))
    }

    @Test
    fun testOAuthQueryParametersPreserved() {
        val authCallback = "https://myapp.com/oauth/callback?code=4%2F0AX4XfWh&state=xyz123&session_state=abc"
        val cleaned = HazeAntiTrackingEngine.stripTrackingParameters(authCallback)
        assertEquals("OAuth callback state, code and tokens must remain completely untouched", authCallback, cleaned)
    }

    @Test
    fun testConfirmedBounceTrackersStillUnwrapped() {
        val fbRedirect = "https://l.facebook.com/l.php?u=https%3A%2F%2Fnews.example.com%2Fstory%3Futm_source%3Dfacebook&h=AT123"
        val unwrapped = HazeAntiTrackingEngine.unwrapBounceTrackingUrl(fbRedirect)
        assertNotNull("Facebook link shim must be unwrapped", unwrapped)
        assertEquals("https://news.example.com/story", unwrapped)
    }

    @Test
    fun testCleanHostHelper() {
        assertEquals("example.com", HazeSiteCompatibilityManager.cleanHost("https://www.example.com/path?query=1"))
        assertEquals("sub.domain.org", HazeSiteCompatibilityManager.cleanHost("http://sub.domain.org:8080/"))
        assertEquals("login.live.com", HazeSiteCompatibilityManager.cleanHost("login.live.com"))
    }

    @Test
    fun testSurrogateScriptDefusing() {
        val gtmContent = HazePrivacyShieldEngine.getSurrogateScriptForUrl("https://www.googletagmanager.com/gtm.js?id=GTM-XYZ")
        assertTrue("Surrogate script must define dataLayer", gtmContent.contains("window.dataLayer"))
        assertTrue("Surrogate script must stub gtag to avoid TypeError", gtmContent.contains("window.gtag"))

        val fbContent = HazePrivacyShieldEngine.getSurrogateScriptForUrl("https://connect.facebook.net/en_US/fbevents.js")
        assertTrue("Surrogate script must stub fbq", fbContent.contains("window.fbq"))

        val hjContent = HazePrivacyShieldEngine.getSurrogateScriptForUrl("https://static.hotjar.com/c/hotjar-123.js")
        assertTrue("Surrogate script must stub hj", hjContent.contains("window.hj"))

        val clarityContent = HazePrivacyShieldEngine.getSurrogateScriptForUrl("https://www.clarity.ms/tag/xyz")
        assertTrue("Surrogate script must stub clarity", clarityContent.contains("window.clarity"))
    }

    @Test
    fun testTroubleshootingReloadLifecycle() {
        val host = "test-site.org"
        assertFalse("Initially troubleshooting should be inactive", HazeSiteCompatibilityManager.isTroubleshootingReloadActive(host))

        HazeSiteCompatibilityManager.scheduleTroubleshootingReload(host)
        assertTrue("Troubleshooting reload must be active after scheduling", HazeSiteCompatibilityManager.isTroubleshootingReloadActive(host))

        val consumed = HazeSiteCompatibilityManager.consumeTroubleshootingReload(host)
        assertTrue("Troubleshooting reload should be consumed", consumed)
        assertFalse("Troubleshooting reload should no longer be active", HazeSiteCompatibilityManager.isTroubleshootingReloadActive(host))
    }

    @Test
    fun testTemporaryAllowLifecycle() {
        val host = "temporary-allow.com"
        assertFalse(HazeSiteCompatibilityManager.isTemporarilyAllowed(host))

        HazeSiteCompatibilityManager.temporarilyAllowBlockedResources(host, 15)
        assertTrue(HazeSiteCompatibilityManager.isTemporarilyAllowed(host))

        HazeSiteCompatibilityManager.revokeTemporaryAllow(host)
        assertFalse(HazeSiteCompatibilityManager.isTemporarilyAllowed(host))
    }
}
