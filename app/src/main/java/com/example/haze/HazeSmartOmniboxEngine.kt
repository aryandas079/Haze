package com.example.haze

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * HazeSmartOmniboxEngine
 * Powers the intelligent omnibox with real-time:
 * 1. Math & Scientific Calculator (arithmetic, functions, powers, percentages, factorials)
 * 2. Instant Currency Converter (USD, EUR, GBP, INR, JPY, CAD, AUD, etc. with live API fallback)
 * 3. Unit Converter (Length, Mass, Temperature, Speed, Data storage, Time, Volume)
 * 4. Real-time Weather Forecast (Open-Meteo zero-key live weather preview)
 * 5. Instant Dictionary & Definition lookup
 * 6. Search Preview & Knowledge Card (Wikipedia summary preview)
 */
object HazeSmartOmniboxEngine {

    data class SmartItem(
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val target: String,
        val badgeText: String,
        val fillText: String? = null,
        val isCalculated: Boolean = false
    )

    private val executor = Executors.newFixedThreadPool(2)
    private val memoryCache = ConcurrentHashMap<String, SmartItem>()
    private val weatherCache = ConcurrentHashMap<String, Pair<Long, SmartItem>>()
    private val currencyCache = ConcurrentHashMap<String, Double>()

    // Default Fallback Exchange Rates against USD (base = 1.0 USD)
    private val DEFAULT_RATES = mapOf(
        "USD" to 1.0,
        "EUR" to 0.92,
        "GBP" to 0.79,
        "INR" to 86.50,
        "JPY" to 154.20,
        "CAD" to 1.38,
        "AUD" to 1.54,
        "CHF" to 0.88,
        "CNY" to 7.24,
        "BRL" to 5.75,
        "RUB" to 96.50,
        "AED" to 3.67,
        "SAR" to 3.75,
        "SGD" to 1.34,
        "KRW" to 1380.0,
        "SEK" to 10.60,
        "NZD" to 1.68,
        "MXN" to 19.80,
        "ZAR" to 17.65,
        "BTC" to 0.000015,
        "ETH" to 0.00038
    )

    private val CURRENCY_SYMBOLS = mapOf(
        "$" to "USD",
        "€" to "EUR",
        "£" to "GBP",
        "₹" to "INR",
        "¥" to "JPY",
        "₿" to "BTC",
        "₽" to "RUB",
        "₩" to "KRW",
        "R$" to "BRL"
    )

    init {
        currencyCache.putAll(DEFAULT_RATES)
        // Refresh exchange rates in the background asynchronously
        refreshLiveExchangeRates()
    }

    private fun safeUrlEncode(s: String): String {
        return try {
            java.net.URLEncoder.encode(s, "UTF-8")
        } catch (_: Exception) {
            s.replace(" ", "+")
        }
    }

    private fun refreshLiveExchangeRates() {
        executor.execute {
            try {
                val url = URL("https://open.er-api.com/v6/latest/USD")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val json = JSONObject(reader.readText())
                    reader.close()
                    val rates = json.optJSONObject("rates")
                    if (rates != null) {
                        val keys = rates.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            currencyCache[k.uppercase(Locale.US)] = rates.optDouble(k, 1.0)
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    /**
     * Synchronously evaluate smart query candidates that can be determined in < 1ms
     * (Calculator, Currency conversion, Unit conversion).
     */
    fun evaluateInstantSmartItem(query: String): SmartItem? {
        val trimmed = query.trim()
        if (trimmed.length < 2 || trimmed.startsWith("haze://") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return null
        }

        // 1. Math Calculation
        evaluateMath(trimmed)?.let { return it }

        // 2. Currency Conversion
        evaluateCurrency(trimmed)?.let { return it }

        // 3. Unit Conversion
        evaluateUnitConversion(trimmed)?.let { return it }

        return null
    }

    /**
     * Returns cached smart item if already resolved for this query.
     */
    fun getCachedSmartItem(query: String): SmartItem? {
        val trimmed = query.trim().lowercase(Locale.US)
        return memoryCache[trimmed]
    }

    /**
     * Asynchronously fetch rich smart answers for queries needing network lookup
     * (Live weather, Wikipedia search preview, Dictionary).
     */
    fun fetchAsyncSmartItem(context: Context, query: String, onResult: (SmartItem?) -> Unit) {
        val trimmed = query.trim()
        if (trimmed.length < 3 || trimmed.startsWith("haze://") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            onResult(null)
            return
        }

        memoryCache[trimmed.lowercase(Locale.US)]?.let {
            onResult(it)
            return
        }

        executor.execute {
            try {
                // 1. Weather
                val weather = checkAndFetchWeather(trimmed)
                if (weather != null) {
                    memoryCache[trimmed.lowercase(Locale.US)] = weather
                    onResult(weather)
                    return@execute
                }

                // 2. Dictionary / Definition
                val dict = checkAndFetchDictionary(trimmed)
                if (dict != null) {
                    memoryCache[trimmed.lowercase(Locale.US)] = dict
                    onResult(dict)
                    return@execute
                }

                // 3. Wikipedia Knowledge Search Preview
                val preview = checkAndFetchKnowledgePreview(trimmed)
                if (preview != null) {
                    memoryCache[trimmed.lowercase(Locale.US)] = preview
                    onResult(preview)
                    return@execute
                }

                onResult(null)
            } catch (_: Exception) {
                onResult(null)
            }
        }
    }

    // =========================================================================
    // 1. MATH CALCULATOR
    // =========================================================================

    private fun evaluateMath(expr: String): SmartItem? {
        val cleaned = expr.lowercase(Locale.US)
            .replace("x", "*")
            .replace("×", "*")
            .replace("÷", "/")
            .trim()

        // Match Percentage patterns like "15% of 850" or "20% off 500"
        val pctOfRegex = Regex("""^(\d+(?:\.\d+)?)\s*%\s*(?:of|off)\s*(\d+(?:\.\d+)?)$""")
        pctOfRegex.matchEntire(cleaned)?.let { m ->
            val p = m.groupValues[1].toDoubleOrNull() ?: return null
            val base = m.groupValues[2].toDoubleOrNull() ?: return null
            val isOff = cleaned.contains("off")
            val result = if (isOff) base - (p / 100.0 * base) else (p / 100.0 * base)
            val formatted = formatNumber(result)
            val sub = if (isOff) "$p% off $base = $formatted" else "$p% of $base = $formatted"
            return SmartItem(
                title = formatted,
                subtitle = sub,
                iconRes = R.drawable.ic_calculator,
                target = formatted,
                badgeText = "Calculator",
                fillText = formatted,
                isCalculated = true
            )
        }

        // Must have at least one math operator or known math function
        val hasMathOperator = cleaned.any { it in "+-*/^%" } ||
                cleaned.startsWith("sqrt(") || cleaned.startsWith("sin(") ||
                cleaned.startsWith("cos(") || cleaned.startsWith("tan(") ||
                cleaned.startsWith("log(") || cleaned.startsWith("ln(") ||
                cleaned.endsWith("!")

        if (!hasMathOperator) return null

        // Avoid pure words or search sentences
        val invalidLetters = cleaned.filter { it.isLetter() && it !in "sqrtcbsincoaglne" }
        if (invalidLetters.isNotEmpty()) return null

        val result = runCatching { MathParser.eval(cleaned) }.getOrNull() ?: return null
        if (result.isNaN() || result.isInfinite()) return null

        val formattedResult = formatNumber(result)
        return SmartItem(
            title = formattedResult,
            subtitle = "$expr = $formattedResult",
            iconRes = R.drawable.ic_calculator,
            target = formattedResult,
            badgeText = "Calculator",
            fillText = formattedResult,
            isCalculated = true
        )
    }

    // =========================================================================
    // 2. CURRENCY CONVERTER
    // =========================================================================

    private fun evaluateCurrency(query: String): SmartItem? {
        val lower = query.trim().lowercase(Locale.US)
        // Patterns: "100 usd to inr", "50 eur in usd", "$100 to inr", "100$ in inr", "1000 inr = usd"
        val regex = Regex("""^([$€£¥₹₿₽₩])?\s*(\d+(?:\.\d+)?)\s*([a-zA-Z$€£¥₹₿₽₩]+)?\s*(?:to|in|=)\s*([a-zA-Z$€£¥₹₿₽₩]+)$""")
        val m = regex.matchEntire(lower) ?: return null

        val prefixSymbol = m.groupValues[1]
        val amount = m.groupValues[2].toDoubleOrNull() ?: return null
        var fromCurr = m.groupValues[3].uppercase(Locale.US)
        var toCurr = m.groupValues[4].uppercase(Locale.US)

        if (prefixSymbol.isNotEmpty()) {
            CURRENCY_SYMBOLS[prefixSymbol]?.let { fromCurr = it }
        } else if (CURRENCY_SYMBOLS.containsKey(fromCurr)) {
            CURRENCY_SYMBOLS[fromCurr]?.let { fromCurr = it }
        }

        if (CURRENCY_SYMBOLS.containsKey(toCurr)) {
            CURRENCY_SYMBOLS[toCurr]?.let { toCurr = it }
        }

        if (fromCurr.isBlank() || toCurr.isBlank() || fromCurr == toCurr) return null

        val fromRate = currencyCache[fromCurr] ?: return null
        val toRate = currencyCache[toCurr] ?: return null

        // Rates are USD-based: 1 USD = fromRate [fromCurr] -> fromAmount in USD = amount / fromRate
        val amountInUsd = amount / fromRate
        val converted = amountInUsd * toRate
        val singleRate = toRate / fromRate

        val formattedAmount = formatNumber(amount)
        val formattedConverted = formatCurrencyOutput(converted, toCurr)
        val formattedSingle = formatCurrencyOutput(singleRate, toCurr)

        val title = "$formattedConverted $toCurr"
        val subtitle = "$formattedAmount $fromCurr = $formattedConverted $toCurr (1 $fromCurr ~ $formattedSingle $toCurr)"

        return SmartItem(
            title = title,
            subtitle = subtitle,
            iconRes = R.drawable.ic_currency,
            target = "https://www.google.com/search?q=${safeUrlEncode("$amount $fromCurr to $toCurr")}",
            badgeText = "Currency",
            fillText = "$formattedConverted $toCurr",
            isCalculated = true
        )
    }

    // =========================================================================
    // 3. UNIT CONVERTER
    // =========================================================================

    private fun evaluateUnitConversion(query: String): SmartItem? {
        val lower = query.trim().lowercase(Locale.US)
        val regex = Regex("""^(\d+(?:\.\d+)?)\s*([a-zA-Z/°]+)\s*(?:to|in|=)\s*([a-zA-Z/°]+)$""")
        val m = regex.matchEntire(lower) ?: return null

        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val fromUnit = m.groupValues[2]
        val toUnit = m.groupValues[3]

        val result = UnitConverter.convert(value, fromUnit, toUnit) ?: return null
        val formattedVal = formatNumber(value)
        val formattedRes = formatNumber(result)

        val title = "$formattedRes $toUnit"
        val subtitle = "$formattedVal $fromUnit = $formattedRes $toUnit"

        return SmartItem(
            title = title,
            subtitle = subtitle,
            iconRes = R.drawable.ic_sparkles,
            target = "https://www.google.com/search?q=${safeUrlEncode("$value $fromUnit to $toUnit")}",
            badgeText = "Unit Converter",
            fillText = "$formattedRes $toUnit",
            isCalculated = true
        )
    }

    // =========================================================================
    // 4. WEATHER (Open-Meteo Zero-Key Live API)
    // =========================================================================

    private fun checkAndFetchWeather(query: String): SmartItem? {
        val lower = query.trim().lowercase(Locale.US)
        var city: String? = null

        when {
            lower == "weather" || lower == "weather today" || lower == "temp" -> {
                city = Locale.getDefault().displayCountry.takeIf { it.isNotBlank() } ?: "London"
            }
            lower.startsWith("weather in ") -> city = lower.removePrefix("weather in ").trim()
            lower.startsWith("weather ") -> city = lower.removePrefix("weather ").trim()
            lower.endsWith(" weather") -> city = lower.removeSuffix(" weather").trim()
            lower.startsWith("temp in ") -> city = lower.removePrefix("temp in ").trim()
            lower.startsWith("temperature in ") -> city = lower.removePrefix("temperature in ").trim()
        }

        if (city.isNullOrBlank() || city.length < 2) return null

        val cacheKey = city.lowercase(Locale.US)
        val cached = weatherCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < 20 * 60 * 1000L) {
            return cached.second
        }

        // 1. Geocoding
        val geoUrl = URL("https://geocoding-api.open-meteo.com/v1/search?name=${safeUrlEncode(city)}&count=1&language=en&format=json")
        val geoConn = geoUrl.openConnection() as HttpURLConnection
        geoConn.setRequestProperty("User-Agent", "HazeBrowser/1.0 (Android; Mobile)")
        geoConn.connectTimeout = 3000
        geoConn.readTimeout = 3000
        if (geoConn.responseCode != 200) {
            geoConn.disconnect()
            return null
        }
        val geoText = geoConn.inputStream.bufferedReader().use { it.readText() }
        geoConn.disconnect()

        val geoJson = JSONObject(geoText)
        val results = geoJson.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val firstLocation = results.getJSONObject(0)
        val lat = firstLocation.optDouble("latitude")
        val lon = firstLocation.optDouble("longitude")
        val cityName = firstLocation.optString("name", city.replaceFirstChar { it.uppercase() })
        val country = firstLocation.optString("country", "")

        // 2. Current Weather Forecast
        val forecastUrl = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&temperature_unit=celsius&wind_speed_unit=kmh")
        val fConn = forecastUrl.openConnection() as HttpURLConnection
        fConn.setRequestProperty("User-Agent", "HazeBrowser/1.0 (Android; Mobile)")
        fConn.connectTimeout = 3000
        fConn.readTimeout = 3000
        if (fConn.responseCode != 200) {
            fConn.disconnect()
            return null
        }
        val fText = fConn.inputStream.bufferedReader().use { it.readText() }
        fConn.disconnect()

        val fJson = JSONObject(fText)
        val current = fJson.optJSONObject("current") ?: return null
        val temp = current.optDouble("temperature_2m", 0.0)
        val humidity = current.optInt("relative_humidity_2m", 0)
        val wind = current.optDouble("wind_speed_10m", 0.0)
        val code = current.optInt("weather_code", 0)

        val condition = getWeatherCondition(code)
        val tempF = (temp * 9.0 / 5.0 + 32.0).roundToInt()
        val tempC = temp.roundToInt()

        val locationLabel = if (country.isNotBlank()) "$cityName, $country" else cityName
        val title = "$locationLabel: ${tempC}°C / ${tempF}°F ($condition)"
        val subtitle = "Condition: $condition | Humidity: $humidity% | Wind: ${wind.roundToInt()} km/h"

        val item = SmartItem(
            title = title,
            subtitle = subtitle,
            iconRes = R.drawable.ic_cloud,
            target = "https://www.google.com/search?q=${safeUrlEncode("weather $cityName")}",
            badgeText = "Weather"
        )
        weatherCache[cacheKey] = Pair(System.currentTimeMillis(), item)
        return item
    }

    private fun getWeatherCondition(code: Int): String {
        return when (code) {
            0 -> "Clear Sky"
            1, 2 -> "Partly Cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            61, 63, 65 -> "Rain"
            71, 73, 75 -> "Snow"
            80, 81, 82 -> "Rain Showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "Cloudy"
        }
    }

    // =========================================================================
    // 5. DICTIONARY & DEFINITION
    // =========================================================================

    private fun checkAndFetchDictionary(query: String): SmartItem? {
        val lower = query.trim().lowercase(Locale.US)
        var word: String? = null
        when {
            lower.startsWith("define ") -> word = lower.removePrefix("define ").trim()
            lower.startsWith("meaning of ") -> word = lower.removePrefix("meaning of ").trim()
            lower.startsWith("definition of ") -> word = lower.removePrefix("definition of ").trim()
        }
        if (word.isNullOrBlank() || word.contains(" ") || word.length < 2) return null

        val dictUrl = URL("https://api.dictionaryapi.dev/api/v2/entries/en/${safeUrlEncode(word)}")
        val conn = dictUrl.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "HazeBrowser/1.0 (Android; Mobile)")
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        if (conn.responseCode != 200) {
            conn.disconnect()
            return null
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val array = JSONArray(text)
        if (array.length() == 0) return null
        val firstEntry = array.getJSONObject(0)
        val matchedWord = firstEntry.optString("word", word).replaceFirstChar { it.uppercase() }
        val phonetic = firstEntry.optString("phonetic", "")
        val meanings = firstEntry.optJSONArray("meanings") ?: return null
        if (meanings.length() == 0) return null
        val firstMeaning = meanings.getJSONObject(0)
        val partOfSpeech = firstMeaning.optString("partOfSpeech", "")
        val definitions = firstMeaning.optJSONArray("definitions") ?: return null
        if (definitions.length() == 0) return null
        val definition = definitions.getJSONObject(0).optString("definition", "")

        val title = "$matchedWord ${if (phonetic.isNotBlank()) "[$phonetic]" else ""} (${partOfSpeech})"
        val subtitle = definition.take(120).let { if (definition.length > 120) "$it..." else it }

        return SmartItem(
            title = title,
            subtitle = subtitle,
            iconRes = R.drawable.ic_wikipedia,
            target = "https://www.google.com/search?q=${safeUrlEncode("define $word")}",
            badgeText = "Dictionary"
        )
    }

    // =========================================================================
    // 6. SEARCH PREVIEW & KNOWLEDGE CARD (Wikipedia)
    // =========================================================================

    private fun checkAndFetchKnowledgePreview(query: String): SmartItem? {
        val lower = query.trim().lowercase(Locale.US)
        val isQuestion = lower.startsWith("who is ") || lower.startsWith("what is ") ||
                lower.startsWith("where is ") || lower.startsWith("capital of ") ||
                lower.startsWith("tell me about ")

        if (!isQuestion) return null

        var term = lower
            .removePrefix("who is ")
            .removePrefix("what is ")
            .removePrefix("where is ")
            .removePrefix("capital of ")
            .removePrefix("tell me about ")
            .trim(' ', '?')

        if (term.length < 3) return null

        val encodedTerm = safeUrlEncode(term.replace(' ', '_'))
        val wikiUrl = URL("https://en.wikipedia.org/api/rest_v1/page/summary/$encodedTerm")
        val conn = wikiUrl.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "HazeBrowser/1.0 (Android; Mobile)")
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        if (conn.responseCode != 200) {
            conn.disconnect()
            return null
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(text)
        val title = json.optString("title", "")
        val description = json.optString("description", "")
        val extract = json.optString("extract", "")
        val pageUrl = json.optJSONObject("content_urls")
            ?.optJSONObject("mobile")
            ?.optString("page", "https://en.wikipedia.org/wiki/${safeUrlEncode(title)}")
            ?: "https://en.wikipedia.org/wiki/${safeUrlEncode(title)}"

        if (title.isBlank() || extract.isBlank()) return null

        val titleLine = if (description.isNotBlank()) "$title — $description" else title
        val subLine = extract.take(120).let { if (extract.length > 120) "$it..." else it }

        return SmartItem(
            title = titleLine,
            subtitle = subLine,
            iconRes = R.drawable.ic_sparkles,
            target = pageUrl,
            badgeText = "Preview"
        )
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    private fun formatNumber(n: Double): String {
        return if (abs(n - n.roundToLong()) < 1e-9) {
            val df = DecimalFormat("#,###", DecimalFormatSymbols(Locale.US))
            df.format(n.roundToLong())
        } else {
            val df = DecimalFormat("#,##0.####", DecimalFormatSymbols(Locale.US))
            df.format(n)
        }
    }

    private fun formatCurrencyOutput(n: Double, curr: String): String {
        val df = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
        return df.format(n)
    }

    /**
     * Safe recursive descent mathematical parser & evaluator.
     */
    private object MathParser {
        fun eval(str: String): Double {
            return object : Any() {
                var pos = -1
                var ch = 0

                fun nextChar() {
                    ch = if (++pos < str.length) str[pos].code else -1
                }

                fun eat(charToEat: Int): Boolean {
                    while (ch == ' '.code) nextChar()
                    if (ch == charToEat) {
                        nextChar()
                        return true
                    }
                    return false
                }

                fun parse(): Double {
                    nextChar()
                    val x = parseExpression()
                    if (pos < str.length) throw RuntimeException("Unexpected: " + ch.toChar())
                    return x
                }

                fun parseExpression(): Double {
                    var x = parseTerm()
                    while (true) {
                        when {
                            eat('+'.code) -> x += parseTerm()
                            eat('-'.code) -> x -= parseTerm()
                            else -> return x
                        }
                    }
                }

                fun parseTerm(): Double {
                    var x = parseFactor()
                    while (true) {
                        when {
                            eat('*'.code) -> x *= parseFactor()
                            eat('/'.code) -> {
                                val d = parseFactor()
                                if (d == 0.0) throw ArithmeticException("Division by zero")
                                x /= d
                            }
                            eat('%'.code) -> x %= parseFactor()
                            else -> return x
                        }
                    }
                }

                fun parseFactor(): Double {
                    if (eat('+'.code)) return +parseFactor()
                    if (eat('-'.code)) return -parseFactor()

                    var x: Double
                    val startPos = pos
                    if (eat('('.code)) {
                        x = parseExpression()
                        eat(')'.code)
                    } else if (ch in '0'.code..'9'.code || ch == '.'.code) {
                        while (ch in '0'.code..'9'.code || ch == '.'.code) nextChar()
                        x = str.substring(startPos, pos).toDouble()
                    } else if (ch in 'a'.code..'z'.code || ch == 'π'.code) {
                        while (ch in 'a'.code..'z'.code) nextChar()
                        val func = str.substring(startPos, pos)
                        if (func == "pi" || func == "π") {
                            x = Math.PI
                        } else if (func == "e") {
                            x = Math.E
                        } else {
                            if (eat('('.code)) {
                                x = parseExpression()
                                eat(')'.code)
                            } else {
                                x = parseFactor()
                            }
                            x = when (func) {
                                "sqrt" -> sqrt(x)
                                "cbrt" -> cbrt(x)
                                "sin" -> sin(Math.toRadians(x))
                                "cos" -> cos(Math.toRadians(x))
                                "tan" -> tan(Math.toRadians(x))
                                "log" -> log10(x)
                                "ln" -> ln(x)
                                "abs" -> abs(x)
                                "round" -> round(x)
                                "floor" -> floor(x)
                                "ceil" -> ceil(x)
                                else -> throw RuntimeException("Unknown function: $func")
                            }
                        }
                    } else {
                        throw RuntimeException("Unexpected: " + ch.toChar())
                    }

                    if (eat('^'.code)) x = x.pow(parseFactor())

                    if (eat('!'.code)) {
                        val n = x.roundToInt()
                        if (n in 0..20) {
                            var fact = 1.0
                            for (i in 1..n) fact *= i
                            x = fact
                        }
                    }

                    return x
                }
            }.parse()
        }
    }

    /**
     * Unit Conversion Engine.
     */
    private object UnitConverter {
        private val LENGTH = mapOf(
            "m" to 1.0, "meter" to 1.0, "meters" to 1.0,
            "km" to 1000.0, "kilometer" to 1000.0, "kilometers" to 1000.0,
            "cm" to 0.01, "centimeter" to 0.01, "centimeters" to 0.01,
            "mm" to 0.001, "millimeter" to 0.001,
            "mi" to 1609.344, "mile" to 1609.344, "miles" to 1609.344,
            "yd" to 0.9144, "yard" to 0.9144, "yards" to 0.9144,
            "ft" to 0.3048, "foot" to 0.3048, "feet" to 0.3048,
            "in" to 0.0254, "inch" to 0.0254, "inches" to 0.0254
        )

        private val MASS = mapOf(
            "kg" to 1.0, "kilogram" to 1.0, "kilograms" to 1.0, "kgs" to 1.0,
            "g" to 0.001, "gram" to 0.001, "grams" to 0.001,
            "mg" to 0.000001, "milligram" to 0.000001,
            "lb" to 0.453592, "lbs" to 0.453592, "pound" to 0.453592, "pounds" to 0.453592,
            "oz" to 0.0283495, "ounce" to 0.0283495, "ounces" to 0.0283495
        )

        private val SPEED = mapOf(
            "m/s" to 1.0,
            "km/h" to 0.277778, "kmh" to 0.277778, "kph" to 0.277778,
            "mph" to 0.44704, "knot" to 0.514444, "knots" to 0.514444
        )

        private val DATA = mapOf(
            "b" to 1.0, "byte" to 1.0, "bytes" to 1.0,
            "kb" to 1024.0, "mb" to 1024.0 * 1024.0,
            "gb" to 1024.0 * 1024.0 * 1024.0,
            "tb" to 1024.0 * 1024.0 * 1024.0 * 1024.0
        )

        private val TIME = mapOf(
            "s" to 1.0, "sec" to 1.0, "seconds" to 1.0,
            "min" to 60.0, "mins" to 60.0, "minutes" to 60.0,
            "h" to 3600.0, "hr" to 3600.0, "hrs" to 3600.0, "hours" to 3600.0,
            "d" to 86400.0, "day" to 86400.0, "days" to 86400.0
        )

        private val VOLUME = mapOf(
            "l" to 1.0, "liter" to 1.0, "liters" to 1.0,
            "ml" to 0.001, "milliliter" to 0.001,
            "gal" to 3.78541, "gallon" to 3.78541, "gallons" to 3.78541,
            "cup" to 0.236588, "cups" to 0.236588,
            "pt" to 0.473176, "pint" to 0.473176, "pints" to 0.473176
        )

        fun convert(value: Double, from: String, to: String): Double? {
            val f = from.lowercase()
            val t = to.lowercase()

            // Temperature Special Handling
            if ((f == "c" || f == "celsius") && (t == "f" || t == "fahrenheit")) {
                return (value * 9.0 / 5.0) + 32.0
            }
            if ((f == "f" || f == "fahrenheit") && (t == "c" || t == "celsius")) {
                return (value - 32.0) * 5.0 / 9.0
            }
            if ((f == "c" || f == "celsius") && (t == "k" || t == "kelvin")) {
                return value + 273.15
            }
            if ((f == "k" || f == "kelvin") && (t == "c" || t == "celsius")) {
                return value - 273.15
            }

            // Ratio-based conversion
            listOf(LENGTH, MASS, SPEED, DATA, TIME, VOLUME).forEach { table ->
                if (table.containsKey(f) && table.containsKey(t)) {
                    val base = value * table[f]!!
                    return base / table[t]!!
                }
            }
            return null
        }
    }
}
