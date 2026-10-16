package com.example.haze

import org.junit.Assert.*
import org.junit.Test

class HazeSmartOmniboxEngineTest {

    @Test
    fun testBasicMathEvaluation() {
        val item1 = HazeSmartOmniboxEngine.evaluateInstantSmartItem("25 * 40")
        assertNotNull(item1)
        assertEquals("1,000", item1?.title)
        assertEquals("Calculator", item1?.badgeText)

        val item2 = HazeSmartOmniboxEngine.evaluateInstantSmartItem("100 / 4 + 7")
        assertNotNull(item2)
        assertEquals("32", item2?.title)

        val item3 = HazeSmartOmniboxEngine.evaluateInstantSmartItem("(15 + 5) * 3")
        assertNotNull(item3)
        assertEquals("60", item3?.title)
    }

    @Test
    fun testPercentageCalculations() {
        // "15% of 850" -> 127.5
        val item1 = HazeSmartOmniboxEngine.evaluateInstantSmartItem("15% of 850")
        assertNotNull(item1)
        assertEquals("127.5", item1?.title)

        // "20% off 500" -> 400
        val item2 = HazeSmartOmniboxEngine.evaluateInstantSmartItem("20% off 500")
        assertNotNull(item2)
        assertEquals("400", item2?.title)
    }

    @Test
    fun testAdvancedMath() {
        val itemSqrt = HazeSmartOmniboxEngine.evaluateInstantSmartItem("sqrt(144)")
        assertNotNull(itemSqrt)
        assertEquals("12", itemSqrt?.title)

        val itemPower = HazeSmartOmniboxEngine.evaluateInstantSmartItem("2 ^ 8")
        assertNotNull(itemPower)
        assertEquals("256", itemPower?.title)

        val itemFactorial = HazeSmartOmniboxEngine.evaluateInstantSmartItem("5!")
        assertNotNull(itemFactorial)
        assertEquals("120", itemFactorial?.title)
    }

    @Test
    fun testCurrencyConversion() {
        val item1 = HazeSmartOmniboxEngine.evaluateInstantSmartItem("100 usd to inr")
        assertNotNull(item1)
        assertEquals("Currency", item1?.badgeText)
        assertTrue(item1?.title?.contains("INR") == true)
        assertTrue(item1?.subtitle?.contains("USD") == true)

        val item2 = HazeSmartOmniboxEngine.evaluateInstantSmartItem("$50 in eur")
        assertNotNull(item2)
        assertEquals("Currency", item2?.badgeText)
        assertTrue(item2?.title?.contains("EUR") == true)

        val item3 = HazeSmartOmniboxEngine.evaluateInstantSmartItem("100 eur to usd")
        assertNotNull(item3)
        assertTrue(item3?.title?.contains("USD") == true)
    }

    @Test
    fun testUnitConversions() {
        // Length: 10 miles to km
        val itemLength = HazeSmartOmniboxEngine.evaluateInstantSmartItem("10 miles to km")
        assertNotNull(itemLength)
        assertEquals("Unit Converter", itemLength?.badgeText)
        assertTrue(itemLength?.title?.contains("km") == true)
        assertTrue(itemLength?.title?.startsWith("16.09") == true)

        // Temperature: 100 c to f
        val itemTemp = HazeSmartOmniboxEngine.evaluateInstantSmartItem("100 c to f")
        assertNotNull(itemTemp)
        assertEquals("212 f", itemTemp?.title)

        // Mass: 5 kg to lbs
        val itemMass = HazeSmartOmniboxEngine.evaluateInstantSmartItem("5 kg to lbs")
        assertNotNull(itemMass)
        assertTrue(itemMass?.title?.startsWith("11.02") == true)

        // Data: 1 gb to mb
        val itemData = HazeSmartOmniboxEngine.evaluateInstantSmartItem("1 gb to mb")
        assertNotNull(itemData)
        assertEquals("1,024 mb", itemData?.title)
    }

    @Test
    fun testInvalidOrNonSmartQueriesReturnNull() {
        assertNull(HazeSmartOmniboxEngine.evaluateInstantSmartItem("hello world"))
        assertNull(HazeSmartOmniboxEngine.evaluateInstantSmartItem("google.com"))
        assertNull(HazeSmartOmniboxEngine.evaluateInstantSmartItem("haze://settings"))
        assertNull(HazeSmartOmniboxEngine.evaluateInstantSmartItem("https://news.ycombinator.com"))
    }
}
