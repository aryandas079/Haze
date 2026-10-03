package com.example.haze

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class HazeContentFilterTest {
    @Test
    fun `known advertising request matches an ABP domain rule`() {
        val filter = HazeContentFilter.compile(sequenceOf("||doubleclick.net^"))
        val match = filter.match(HazeContentFilter.Request(
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://publisher.example/article"
        ))
        assertNotNull("ad request must be intercepted before it loads", match)
        assertFalse(match!!.exception)
    }

    @Test
    fun `third party tracker rule does not match same site requests`() {
        val filter = HazeContentFilter.compile(sequenceOf("||analytics.example^\$third-party"))
        assertNull(filter.match(HazeContentFilter.Request("https://analytics.example/pixel", "https://analytics.example/page")))
        assertNotNull(filter.match(HazeContentFilter.Request("https://analytics.example/pixel", "https://shop.example/page")))
    }

    @Test
    fun `exception takes precedence over a blocking rule`() {
        val filter = HazeContentFilter.compile(sequenceOf("||ads.example^", "@@||ads.example/allowed.js"))
        val match = filter.match(HazeContentFilter.Request("https://ads.example/allowed.js", "https://site.example"))
        assertTrue(match!!.exception)
    }
}
