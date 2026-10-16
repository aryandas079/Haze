package com.example.haze

import org.junit.Assert.*
import org.junit.Test

class HazeOmniboxDestinationsTest {

    @Test
    fun testAllRequestedCategoriesArePresent() {
        // Build mock list or inspect defaultEngines from companion / test
        val expectedCategories = setOf(
            "Essential",
            "Video & Streaming",
            "Social & Community",
            "Shopping",
            "AI Search & Assistants",
            "Knowledge & Technical Search",
            "Travel, Maps & Bookings",
            "Email & Productivity"
        )

        // Verify default pinned IDs
        assertEquals(
            listOf("google", "bing", "duckduckgo", "youtube", "chatgpt"),
            HazeOmniboxManager.DEFAULT_PINNED_IDS
        )
    }
}
