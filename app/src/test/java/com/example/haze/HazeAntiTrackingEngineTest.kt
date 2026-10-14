package com.example.haze

import org.junit.Assert.*
import org.junit.Test

class HazeAntiTrackingEngineTest {

    @Test
    fun testUserSpecifiedExample() {
        // User requested: "example.com/?utm_source=instagram becomes: example.com/"
        val input = "example.com/?utm_source=instagram"
        val expected = "example.com/"
        val result = HazeAntiTrackingEngine.stripTrackingParameters(input)
        assertEquals("User exact specification must match", expected, result)
    }

    @Test
    fun testUtmParametersStripping() {
        val input = "https://example.com/article?utm_source=twitter&utm_medium=social&utm_campaign=spring_sale"
        val expected = "https://example.com/article"
        val result = HazeAntiTrackingEngine.stripTrackingParameters(input)
        assertEquals("All UTM parameters must be stripped cleanly", expected, result)
    }

    @Test
    fun testPreservesLegitimateQueryParameters() {
        val input = "https://example.com/search?q=open+source&page=2&utm_source=newsletter&id=99"
        val expected = "https://example.com/search?q=open+source&page=2&id=99"
        val result = HazeAntiTrackingEngine.stripTrackingParameters(input)
        assertEquals("Legitimate query parameters must be preserved", expected, result)
    }

    @Test
    fun testSocialAndAdClickIdentifiers() {
        val input = "https://example.com/product?gclid=EAIaIQobChMI&fbclid=IwAR2xyz&msclkid=abc123&igshid=def456"
        val expected = "https://example.com/product"
        val result = HazeAntiTrackingEngine.stripTrackingParameters(input)
        assertEquals("Ad and social click IDs must be stripped", expected, result)
    }

    @Test
    fun testPreservesUrlFragmentHash() {
        val input = "https://example.com/docs?utm_source=reddit#section-3"
        val expected = "https://example.com/docs#section-3"
        val result = HazeAntiTrackingEngine.stripTrackingParameters(input)
        assertEquals("URL fragment / hash must be preserved", expected, result)
    }

    @Test
    fun testNoTrackingParamsLeavesUrlIntact() {
        val input = "https://example.com/page?id=123&lang=en"
        val result = HazeAntiTrackingEngine.stripTrackingParameters(input)
        assertEquals("URL without tracking params must remain untouched", input, result)
    }

    @Test
    fun testBounceTrackingUnwrapping() {
        // Facebook redirect trampoline
        val fbRedirect = "https://l.facebook.com/l.php?u=https%3A%2F%2Fnews.example.com%2Fstory%3Futm_source%3Dfacebook&h=AT123"
        val unwrapped = HazeAntiTrackingEngine.unwrapBounceTrackingUrl(fbRedirect)
        assertNotNull("Bounce tracker must be unwrapped", unwrapped)
        assertEquals("Destination must be extracted and tracking params stripped", "https://news.example.com/story", unwrapped)

        // Generic ad network redirect
        val adRedirect = "https://click.adnetwork.com/redirect?dest=https%3A%2F%2Fshop.example.com%2Fitem%3Fid%3D45%26gclid%3Dxyz"
        val unwrappedAd = HazeAntiTrackingEngine.unwrapBounceTrackingUrl(adRedirect)
        assertNotNull("Ad network bounce redirect must be unwrapped", unwrappedAd)
        assertEquals("https://shop.example.com/item?id=45", unwrappedAd)
    }
}
