package com.example.haze

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.radiobutton.MaterialRadioButton
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * HazeWeatherManager
 * Manages Home Screen weather pill widget and detailed 7-day weather overlay dialog.
 * Supports:
 * - GPS-based location with fallback to IP-geolocation or custom city
 * - Celsius (°C) and Fahrenheit (°F) units
 * - Current temp, feels like, condition icon (Material monochrome B&W)
 * - 7-day daily forecast with precipitation probability, min/max temp
 * - Metrics: Precipitation, Wind Speed, UV Index, AQI (Air Quality), Humidity
 * - Custom Location and Units settings via gear button
 */
class HazeWeatherManager(
    private val activity: MainActivity,
    private val prefs: SharedPreferences
) {
    data class DailyForecast(
        val dayName: String,
        val date: String,
        val weatherCode: Int,
        val maxTemp: Double,
        val minTemp: Double,
        val precipProb: Int,
        val uvIndex: Double
    )

    data class WeatherData(
        val placeName: String,
        val isGpsLocation: Boolean,
        val isIpEstimated: Boolean = false,
        val temp: Double,
        val feelsLike: Double,
        val weatherCode: Int,
        val isDay: Boolean,
        val humidity: Int,
        val windSpeed: Double,
        val precipitation: Double,
        val uvIndex: Double,
        val aqi: Int,
        val daily: List<DailyForecast>,
        val isFahrenheit: Boolean,
        val lastUpdated: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentWeather: WeatherData? = null
    private var isFetching = false

    companion object {
        const val PREF_USE_GPS = "weather_use_gps"
        const val PREF_IS_FAHRENHEIT = "weather_is_fahrenheit"
        const val PREF_CUSTOM_CITY = "weather_custom_city"
        const val PREF_CUSTOM_LAT = "weather_custom_lat"
        const val PREF_CUSTOM_LON = "weather_custom_lon"
        const val PREF_CACHED_JSON = "weather_cached_json"
        const val PREF_CACHED_TIME = "weather_cached_time"
        const val LOCATION_PERMISSION_REQUEST_CODE = 4001
    }

    init {
        loadCachedWeather()
    }

    /**
     * Initializes weather loading and binds click listener to the top-left pill widget
     */
    fun setupWeatherWidget(
        container: LinearLayout,
        ivIcon: ImageView,
        tvTemp: TextView
    ) {
        container.setOnClickListener {
            activity.triggerStrongHaptic(it)
            showWeatherOverlay()
        }
        applyThemeToWidget(container, ivIcon, tvTemp)
        if (prefs.getBoolean(PREF_USE_GPS, true)) {
            checkAndRequestLocationPermission()
        }
        refreshWeather(force = false)
    }

    /**
     * Styles the top-left weather widget as a clean, black-and-white pill matching current theme
     */
    fun applyThemeToWidget(
        container: LinearLayout,
        ivIcon: ImageView,
        tvTemp: TextView
    ) {
        val isDark = prefs.getBoolean("is_dark", true) || activity.isPrivateMode
        val cornerRad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 22f, activity.resources.displayMetrics)

        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRad
            if (isDark) {
                setColor(Color.parseColor("#26FFFFFF"))
                setStroke(1, Color.parseColor("#38FFFFFF"))
            } else {
                setColor(Color.parseColor("#14000000"))
                setStroke(1, Color.parseColor("#1F000000"))
            }
        }
        container.background = pillBg

        val iconAndTextColor = if (isDark) Color.WHITE else Color.parseColor("#1C1B1F")
        ivIcon.clearColorFilter()
        tvTemp.setTextColor(iconAndTextColor)

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isDayFallback = hour in 6..18

        currentWeather?.let { data ->
            ivIcon.setImageResource(getWeatherIconRes(data.weatherCode, data.isDay))
            tvTemp.text = "${data.temp.roundToInt()}°"
        } ?: run {
            ivIcon.setImageResource(if (isDayFallback) R.drawable.ic_weather_sunny else R.drawable.ic_weather_clear_night)
            tvTemp.text = "--°"
        }
    }

    /**
     * Loads cached weather from preferences for instant UI rendering
     */
    private fun loadCachedWeather() {
        val cachedJson = prefs.getString(PREF_CACHED_JSON, null) ?: return
        try {
            val obj = JSONObject(cachedJson)
            val placeName = obj.optString("placeName", "Current Location")
            val isGps = obj.optBoolean("isGps", true)
            val isIpEstimated = obj.optBoolean("isIpEstimated", false)
            val temp = obj.optDouble("temp", 0.0)
            val feelsLike = obj.optDouble("feelsLike", 0.0)
            val weatherCode = obj.optInt("weatherCode", 0)
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val isDayFallback = hour in 6..18
            val isDay = obj.optBoolean("isDay", isDayFallback)
            val humidity = obj.optInt("humidity", 0)
            val windSpeed = obj.optDouble("windSpeed", 0.0)
            val precipitation = obj.optDouble("precipitation", 0.0)
            val uvIndex = obj.optDouble("uvIndex", 0.0)
            val aqi = obj.optInt("aqi", 40)
            val isFahrenheit = obj.optBoolean("isFahrenheit", false)
            val lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis())

            val dailyList = mutableListOf<DailyForecast>()
            val dailyArr = obj.optJSONArray("daily")
            if (dailyArr != null) {
                for (i in 0 until dailyArr.length()) {
                    val dObj = dailyArr.getJSONObject(i)
                    dailyList.add(
                        DailyForecast(
                            dayName = dObj.optString("dayName", "Day"),
                            date = dObj.optString("date", ""),
                            weatherCode = dObj.optInt("weatherCode", 0),
                            maxTemp = dObj.optDouble("maxTemp", 0.0),
                            minTemp = dObj.optDouble("minTemp", 0.0),
                            precipProb = dObj.optInt("precipProb", 0),
                            uvIndex = dObj.optDouble("uvIndex", 0.0)
                        )
                    )
                }
            }

            currentWeather = WeatherData(
                placeName = placeName,
                isGpsLocation = isGps,
                isIpEstimated = isIpEstimated,
                temp = temp,
                feelsLike = feelsLike,
                weatherCode = weatherCode,
                isDay = isDay,
                humidity = humidity,
                windSpeed = windSpeed,
                precipitation = precipitation,
                uvIndex = uvIndex,
                aqi = aqi,
                daily = dailyList,
                isFahrenheit = isFahrenheit,
                lastUpdated = lastUpdated
            )
        } catch (_: Exception) {}
    }

    /**
     * Saves weather data to preferences
     */
    private fun saveCachedWeather(data: WeatherData) {
        try {
            val obj = JSONObject()
            obj.put("placeName", data.placeName)
            obj.put("isGps", data.isGpsLocation)
            obj.put("isIpEstimated", data.isIpEstimated)
            obj.put("temp", data.temp)
            obj.put("feelsLike", data.feelsLike)
            obj.put("weatherCode", data.weatherCode)
            obj.put("isDay", data.isDay)
            obj.put("humidity", data.humidity)
            obj.put("windSpeed", data.windSpeed)
            obj.put("precipitation", data.precipitation)
            obj.put("uvIndex", data.uvIndex)
            obj.put("aqi", data.aqi)
            obj.put("isFahrenheit", data.isFahrenheit)
            obj.put("lastUpdated", data.lastUpdated)

            val dailyArr = org.json.JSONArray()
            for (d in data.daily) {
                val dObj = JSONObject()
                dObj.put("dayName", d.dayName)
                dObj.put("date", d.date)
                dObj.put("weatherCode", d.weatherCode)
                dObj.put("maxTemp", d.maxTemp)
                dObj.put("minTemp", d.minTemp)
                dObj.put("precipProb", d.precipProb)
                dObj.put("uvIndex", d.uvIndex)
                dailyArr.put(dObj)
            }
            obj.put("daily", dailyArr)

            prefs.edit().putString(PREF_CACHED_JSON, obj.toString()).apply()
        } catch (_: Exception) {}
    }

    /**
     * Refreshes weather data from Open-Meteo API in background
     */
    fun refreshWeather(force: Boolean = false) {
        if (isFetching) return

        // Throttle fetches: if refreshed in the last 15 minutes, skip unless forced or IP estimated
        val now = System.currentTimeMillis()
        val isIp = currentWeather?.isIpEstimated == true
        if (!force && !isIp && currentWeather != null && (now - currentWeather!!.lastUpdated) < 15 * 60 * 1000L) {
            updateWidgetUi()
            return
        }

        isFetching = true
        Thread {
            try {
                val useGps = prefs.getBoolean(PREF_USE_GPS, true)
                val isFahrenheit = prefs.getBoolean(PREF_IS_FAHRENHEIT, false)
                var lat = 0.0
                var lon = 0.0
                var placeName = "Location"
                var isRealGps = false
                var isIp = false

                if (useGps) {
                    val loc = getGpsLocation()
                    if (loc != null) {
                        lat = loc.latitude
                        lon = loc.longitude
                        placeName = resolvePlaceName(lat, lon)
                        isRealGps = true
                    } else {
                        // Fallback to IP geolocation
                        val ipLoc = getIpLocation()
                        lat = ipLoc.first
                        lon = ipLoc.second
                        placeName = ipLoc.third
                        isIp = true
                    }
                } else {
                    val customCity = prefs.getString(PREF_CUSTOM_CITY, "") ?: ""
                    val customLat = prefs.getFloat(PREF_CUSTOM_LAT, 0f).toDouble()
                    val customLon = prefs.getFloat(PREF_CUSTOM_LON, 0f).toDouble()

                    if (customLat != 0.0 && customLon != 0.0 && customCity.isNotBlank()) {
                        lat = customLat
                        lon = customLon
                        placeName = customCity
                    } else if (customCity.isNotBlank()) {
                        val geo = geocodeCity(customCity)
                        if (geo != null) {
                            lat = geo.first
                            lon = geo.second
                            placeName = geo.third
                        } else {
                            val ipLoc = getIpLocation()
                            lat = ipLoc.first
                            lon = ipLoc.second
                            placeName = ipLoc.third
                            isIp = true
                        }
                    } else {
                        val ipLoc = getIpLocation()
                        lat = ipLoc.first
                        lon = ipLoc.second
                        placeName = ipLoc.third
                        isIp = true
                    }
                }

                // Fetch Weather Forecast from Open-Meteo
                val tempUnitParam = if (isFahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" else "&wind_speed_unit=kmh"
                val forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max&timezone=auto$tempUnitParam"

                val forecastJson = fetchHttpString(forecastUrl)
                val fObj = JSONObject(forecastJson)
                val currentObj = fObj.getJSONObject("current")

                val curTemp = currentObj.optDouble("temperature_2m", 0.0)
                val curFeelsLike = currentObj.optDouble("apparent_temperature", curTemp)
                val curWeatherCode = currentObj.optInt("weather_code", 0)
                val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val isDayFallback = currentHour in 6..18
                val isDay = if (currentObj.has("is_day")) {
                    currentObj.optInt("is_day", if (isDayFallback) 1 else 0) == 1
                } else {
                    isDayFallback
                }
                val curHumidity = currentObj.optInt("relative_humidity_2m", 0)
                val curPrecip = currentObj.optDouble("precipitation", 0.0)
                val curWind = currentObj.optDouble("wind_speed_10m", 0.0)

                // Daily forecast array
                val dailyObj = fObj.optJSONObject("daily")
                val dailyList = mutableListOf<DailyForecast>()
                if (dailyObj != null) {
                    val times = dailyObj.optJSONArray("time")
                    val codes = dailyObj.optJSONArray("weather_code")
                    val maxTemps = dailyObj.optJSONArray("temperature_2m_max")
                    val minTemps = dailyObj.optJSONArray("temperature_2m_min")
                    val precips = dailyObj.optJSONArray("precipitation_probability_max")
                    val uvs = dailyObj.optJSONArray("uv_index_max")

                    val count = times?.length() ?: 0
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val dayFormat = SimpleDateFormat("EEE", Locale.US)

                    for (i in 0 until minOf(count, 7)) {
                        val dateStr = times?.optString(i) ?: ""
                        val dName = if (i == 0) {
                            "Today"
                        } else {
                            try {
                                val d = sdf.parse(dateStr)
                                if (d != null) dayFormat.format(d) else "Day"
                            } catch (_: Exception) { "Day" }
                        }

                        dailyList.add(
                            DailyForecast(
                                dayName = dName,
                                date = dateStr,
                                weatherCode = codes?.optInt(i, 0) ?: 0,
                                maxTemp = maxTemps?.optDouble(i, curTemp) ?: curTemp,
                                minTemp = minTemps?.optDouble(i, curTemp) ?: curTemp,
                                precipProb = precips?.optInt(i, 0) ?: 0,
                                uvIndex = uvs?.optDouble(i, 0.0) ?: 0.0
                            )
                        )
                    }
                }

                // Fetch Air Quality AQI
                var aqi = 35
                try {
                    val aqiUrl = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi"
                    val aqiJson = fetchHttpString(aqiUrl)
                    val aqiObj = JSONObject(aqiJson)
                    aqi = aqiObj.optJSONObject("current")?.optInt("us_aqi", 35) ?: 35
                } catch (_: Exception) {}

                val todayUv = if (isDay) (dailyList.firstOrNull()?.uvIndex ?: 0.0) else 0.0

                val data = WeatherData(
                    placeName = placeName,
                    isGpsLocation = isRealGps,
                    isIpEstimated = isIp,
                    temp = curTemp,
                    feelsLike = curFeelsLike,
                    weatherCode = curWeatherCode,
                    isDay = isDay,
                    humidity = curHumidity,
                    windSpeed = curWind,
                    precipitation = curPrecip,
                    uvIndex = todayUv,
                    aqi = aqi,
                    daily = dailyList,
                    isFahrenheit = isFahrenheit,
                    lastUpdated = System.currentTimeMillis()
                )

                currentWeather = data
                saveCachedWeather(data)

                mainHandler.post {
                    updateWidgetUi()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isFetching = false
            }
        }.start()
    }

    /**
     * Updates the home screen pill widget with latest temperature & icon
     */
    private fun updateWidgetUi() {
        val container = activity.findViewById<LinearLayout>(R.id.topLeftWeather) ?: return
        val ivIcon = activity.findViewById<ImageView>(R.id.ivWeatherIcon) ?: return
        val tvTemp = activity.findViewById<TextView>(R.id.tvWeatherTemp) ?: return
        applyThemeToWidget(container, ivIcon, tvTemp)
    }

    /**
     * Opens the detailed Weather Overview BottomSheet
     */
    fun showWeatherOverlay() {
        val data = currentWeather
        if (data == null) {
            Toast.makeText(activity, "Updating weather data...", Toast.LENGTH_SHORT).show()
            refreshWeather(force = true)
            return
        }

        val dialog = BottomSheetDialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        HazeTintManager.applyBlurBehindToWindow(dialog.window, 50)
        val isDark = prefs.getBoolean("is_dark", true) || activity.isPrivateMode
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0B57D0") ?: "#0B57D0")
        val bgColor = if (isDark) Color.parseColor("#18191A") else Color.WHITE
        val cardBgColor = if (isDark) Color.parseColor("#242526") else Color.parseColor("#F8F9FA")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#1C1B1F")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val borderColor = if (isDark) Color.parseColor("#38393A") else Color.parseColor("#E0E0E0")

        val screenHeight = activity.resources.displayMetrics.heightPixels
        val maxSheetHeight = (screenHeight * 0.85).toInt()
        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics).toInt()
            setPadding(pad, pad / 2, pad, pad)
            background = HazeTintManager.createSheetGlassDrawable(activity, isDark, 28f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxSheetHeight
            )
        }

        // Top drag handle
        val handle = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40f, activity.resources.displayMetrics).toInt(),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, activity.resources.displayMetrics).toInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt())
            }
            background = GradientDrawable().apply {
                setColor(if (isDark) Color.parseColor("#4E5052") else Color.parseColor("#D0D0D0"))
                cornerRadius = 8f
            }
        }
        rootLayout.addView(handle)

        // Header Row: Place Name (Left) + Gear Button & Close (Right)
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val locationCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val placeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val ivLoc = ImageView(activity).apply {
            setImageResource(if (data.isGpsLocation) R.drawable.ic_my_location else R.drawable.ic_map)
            setColorFilter(accentColor)
            val dp18 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, activity.resources.displayMetrics).toInt()
            layoutParams = LinearLayout.LayoutParams(dp18, dp18).apply {
                setMargins(0, 0, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, activity.resources.displayMetrics).toInt(), 0)
            }
        }
        placeRow.addView(ivLoc)

        val tvPlace = TextView(activity).apply {
            text = data.placeName
            textSize = 17f
            typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD)
            setTextColor(textColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        placeRow.addView(tvPlace)
        locationCol.addView(placeRow)

        val tvSub = TextView(activity).apply {
            text = when {
                data.isGpsLocation -> "Current Location (GPS)"
                data.isIpEstimated -> "Estimated Location (IP Network) • Tap to set city"
                else -> "Custom Location • Tap to change"
            }
            textSize = 11f
            setTextColor(subTextColor)
            val dp2 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, activity.resources.displayMetrics).toInt()
            setPadding(0, dp2, 0, 0)
        }
        locationCol.addView(tvSub)

        locationCol.isClickable = true
        locationCol.isFocusable = true
        locationCol.setOnClickListener {
            activity.triggerStrongHaptic(it)
            dialog.dismiss()
            showWeatherSettingsDialog()
        }

        headerRow.addView(locationCol)

        // Gear Icon (Settings)
        val btnGear = ImageView(activity).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(textColor)
            val dp36 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36f, activity.resources.displayMetrics).toInt()
            val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
            layoutParams = LinearLayout.LayoutParams(dp36, dp36)
            setPadding(dp8, dp8, dp8, dp8)
            isClickable = true
            isFocusable = true
            contentDescription = "Weather Settings"
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                dialog.dismiss()
                showWeatherSettingsDialog()
            }
        }
        headerRow.addView(btnGear)

        // Close Icon
        val btnClose = ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(subTextColor)
            val dp36 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36f, activity.resources.displayMetrics).toInt()
            val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
            layoutParams = LinearLayout.LayoutParams(dp36, dp36).apply {
                setMargins(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, activity.resources.displayMetrics).toInt(), 0, 0, 0)
            }
            setPadding(dp8, dp8, dp8, dp8)
            isClickable = true
            isFocusable = true
            contentDescription = "Close"
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                dialog.dismiss()
            }
        }
        headerRow.addView(btnClose)
        rootLayout.addView(headerRow)

        // Scrollable Weather Overview Content using NestedScrollView for fluid bottom sheet scrolling
        val scrollView = NestedScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)

        // 1. Hero Weather Card
        val heroCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val dp16 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics).toInt()
            val dp12 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt()
            setPadding(dp16, dp16, dp16, dp16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp12, 0, dp12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, activity.resources.displayMetrics)
                setColor(cardBgColor)
                setStroke(1, borderColor)
            }
        }

        // Hero Icon + Large Temp
        val heroRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val ivHeroIcon = ImageView(activity).apply {
            val dp56 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56f, activity.resources.displayMetrics).toInt()
            layoutParams = LinearLayout.LayoutParams(dp56, dp56).apply {
                setMargins(0, 0, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14f, activity.resources.displayMetrics).toInt(), 0)
            }
            setImageResource(getWeatherIconRes(data.weatherCode, data.isDay))
        }
        heroRow.addView(ivHeroIcon)

        val unitSymbol = if (data.isFahrenheit) "°F" else "°C"
        val tvHeroTemp = TextView(activity).apply {
            text = "${data.temp.roundToInt()}$unitSymbol"
            textSize = 42f
            typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD)
            setTextColor(textColor)
        }
        heroRow.addView(tvHeroTemp)
        heroCard.addView(heroRow)

        val tvHeroCondition = TextView(activity).apply {
            text = getWeatherDescription(data.weatherCode, data.isDay)
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(textColor)
            val dp4 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, activity.resources.displayMetrics).toInt()
            setPadding(0, dp4, 0, 0)
        }
        heroCard.addView(tvHeroCondition)

        val todayForecast = data.daily.firstOrNull()
        val highLowText = if (todayForecast != null) {
            "H: ${todayForecast.maxTemp.roundToInt()}° · L: ${todayForecast.minTemp.roundToInt()}° · Feels like ${data.feelsLike.roundToInt()}°"
        } else {
            "Feels like ${data.feelsLike.roundToInt()}°"
        }
        val tvHighLow = TextView(activity).apply {
            text = highLowText
            textSize = 12f
            setTextColor(subTextColor)
            val dp2 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, activity.resources.displayMetrics).toInt()
            setPadding(0, dp2, 0, 0)
        }
        heroCard.addView(tvHighLow)
        contentLayout.addView(heroCard)

        // 2. Metrics Grid: Precipitation, Wind Speed, UV Index, AQI, Humidity
        val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
        val metricsRow1 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val metricsRow2 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp8, 0, 0)
            }
        }

        val precipUnit = if (data.isFahrenheit) "in" else "mm"
        val windUnit = if (data.isFahrenheit) "mph" else "km/h"

        val aqiCategory = getAqiCategory(data.aqi)
        val uvCategory = getUvCategory(data.uvIndex)

        val aqiColor = when {
            data.aqi <= 50 -> Color.parseColor("#10B981")
            data.aqi <= 100 -> Color.parseColor("#F59E0B")
            data.aqi <= 150 -> Color.parseColor("#F97316")
            data.aqi <= 200 -> Color.parseColor("#EF4444")
            else -> Color.parseColor("#881337")
        }
        val uvColor = when {
            data.uvIndex <= 2 -> Color.parseColor("#10B981")
            data.uvIndex <= 5 -> Color.parseColor("#F59E0B")
            data.uvIndex <= 7 -> Color.parseColor("#F97316")
            data.uvIndex <= 10 -> Color.parseColor("#EF4444")
            else -> Color.parseColor("#7C3AED")
        }

        val cardPrecip = buildMetricCard("Precipitation", "${data.precipitation} $precipUnit", R.drawable.ic_water_drop, Color.parseColor("#0288D1"), isDark, cardBgColor, borderColor, textColor, subTextColor)
        val cardWind = buildMetricCard("Wind Speed", "${data.windSpeed.roundToInt()} $windUnit", R.drawable.ic_air, Color.parseColor("#0D9488"), isDark, cardBgColor, borderColor, textColor, subTextColor)
        val cardUv = buildMetricCard("UV Index", "${data.uvIndex.roundToInt()} ($uvCategory)", R.drawable.ic_uv_index, uvColor, isDark, cardBgColor, borderColor, textColor, subTextColor)
        val cardAqi = buildMetricCard("Air Quality", "${data.aqi} ($aqiCategory)", R.drawable.ic_air_quality, aqiColor, isDark, cardBgColor, borderColor, textColor, subTextColor)

        metricsRow1.addView(cardPrecip)
        metricsRow1.addView(cardWind)
        metricsRow2.addView(cardUv)
        metricsRow2.addView(cardAqi)

        contentLayout.addView(metricsRow1)
        contentLayout.addView(metricsRow2)

        // Humidity Single Wide Metric Card with dedicated humidity icon
        val cardHumidity = buildMetricCard("Humidity", "${data.humidity}%", R.drawable.ic_humidity, Color.parseColor("#0288D1"), isDark, cardBgColor, borderColor, textColor, subTextColor).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp8, 0, 0)
            }
        }
        contentLayout.addView(cardHumidity)

        // 3. 7-Day Daily Forecast Section
        val tvForecastHeader = TextView(activity).apply {
            text = "7-Day Forecast"
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD)
            setTextColor(textColor)
            val dp16 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics).toInt()
            val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
            setPadding(0, dp16, 0, dp8)
        }
        contentLayout.addView(tvForecastHeader)

        val forecastCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val dp12 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt()
            setPadding(dp12, dp8, dp12, dp8)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics)
                setColor(cardBgColor)
                setStroke(1, borderColor)
            }
        }

        for ((index, day) in data.daily.withIndex()) {
            val dayRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val dp10 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, activity.resources.displayMetrics).toInt()
                setPadding(0, dp10, 0, dp10)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            // Day label (e.g. Today, Mon, Tue)
            val tvDay = TextView(activity).apply {
                text = day.dayName
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60f, activity.resources.displayMetrics).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            dayRow.addView(tvDay)

            // Weather icon
            val ivDayIcon = ImageView(activity).apply {
                val dp26 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, activity.resources.displayMetrics).toInt()
                layoutParams = LinearLayout.LayoutParams(dp26, dp26)
                val isToday = index == 0
                setImageResource(getWeatherIconRes(day.weatherCode, if (isToday) data.isDay else true))
            }
            dayRow.addView(ivDayIcon)

            // Precipitation probability
            val tvPrecip = TextView(activity).apply {
                text = if (day.precipProb > 0) "${day.precipProb}%" else ""
                textSize = 11f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(Color.parseColor("#4A89F3"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50f, activity.resources.displayMetrics).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            dayRow.addView(tvPrecip)

            // Min Temp
            val tvMin = TextView(activity).apply {
                text = "${day.minTemp.roundToInt()}°"
                textSize = 13f
                setTextColor(subTextColor)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36f, activity.resources.displayMetrics).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            dayRow.addView(tvMin)

            // Temperature range bar
            val rangeBar = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, activity.resources.displayMetrics).toInt(), 1f).apply {
                    val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
                    setMargins(dp8, 0, dp8, 0)
                }
                background = GradientDrawable().apply {
                    cornerRadius = 4f
                    setColor(if (isDark) Color.parseColor("#40FFFFFF") else Color.parseColor("#33000000"))
                }
            }
            dayRow.addView(rangeBar)

            // Max Temp
            val tvMax = TextView(activity).apply {
                text = "${day.maxTemp.roundToInt()}°"
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD)
                setTextColor(textColor)
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36f, activity.resources.displayMetrics).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            dayRow.addView(tvMax)

            forecastCard.addView(dayRow)

            // Divider between days (except last)
            if (index < data.daily.size - 1) {
                val div = View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(if (isDark) Color.parseColor("#2C2D30") else Color.parseColor("#EEEEEE"))
                }
                forecastCard.addView(div)
            }
        }

        contentLayout.addView(forecastCard)

        dialog.setContentView(rootLayout)
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bs ->
            bs.setBackgroundColor(Color.TRANSPARENT)
            val behavior = BottomSheetBehavior.from(bs)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            behavior.isDraggable = true
        }
        dialog.show()
    }

    /**
     * Builds an individual metric card (e.g. UV index, AQI, Precipitation, Wind)
     */
    private fun buildMetricCard(
        label: String,
        value: String,
        iconRes: Int,
        iconTint: Int?,
        isDark: Boolean,
        cardBgColor: Int,
        borderColor: Int,
        textColor: Int,
        subTextColor: Int
    ): LinearLayout {
        val dp12 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt()
        val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
        val dp4 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, activity.resources.displayMetrics).toInt()
        val dp18 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, activity.resources.displayMetrics).toInt()
        val dp28 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, activity.resources.displayMetrics).toInt()

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp12, dp12, dp12, dp12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp4, 0, dp4, 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics)
                setColor(cardBgColor)
                setStroke(1, borderColor)
            }

            val titleRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // Soft pastel circular badge for metric icon
            val iconBadge = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp28, dp28).apply {
                    setMargins(0, 0, dp8, 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    val tint = iconTint ?: Color.parseColor("#0A56D0")
                    setColor(Color.argb(if (isDark) 45 else 25, Color.red(tint), Color.green(tint), Color.blue(tint)))
                }
            }

            val iv = ImageView(activity).apply {
                val iconSize = dp18
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                    gravity = Gravity.CENTER
                }
                setImageResource(iconRes)
                if (iconTint != null) {
                    setColorFilter(iconTint)
                }
            }
            iconBadge.addView(iv)
            titleRow.addView(iconBadge)

            val tvLbl = TextView(activity).apply {
                text = label
                textSize = 12.5f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(subTextColor)
            }
            titleRow.addView(tvLbl)
            addView(titleRow)

            val tvVal = TextView(activity).apply {
                text = value
                textSize = 16.5f
                typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD)
                setTextColor(textColor)
                setPadding(0, dp8, 0, 0)
            }
            addView(tvVal)
        }
    }

    /**
     * Dialog to configure weather location and unit (°C or °F) with Material 3 UI theme
     */
    fun showWeatherSettingsDialog() {
        val isDark = prefs.getBoolean("is_dark", true) || activity.isPrivateMode
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0B57D0") ?: "#0B57D0")
        val dialogBgColor = if (isDark) Color.parseColor("#1E1F22") else Color.parseColor("#FFFFFF")
        val cardBgColor = if (isDark) Color.parseColor("#2B2D31") else Color.parseColor("#F2F4F8")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#1C1B1F")
        val subTextColor = if (isDark) Color.parseColor("#A0A5AC") else Color.parseColor("#60646C")
        val borderColor = if (isDark) Color.parseColor("#3B3D42") else Color.parseColor("#E0E3EA")

        val dp24 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, activity.resources.displayMetrics).toInt()
        val dp16 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics).toInt()
        val dp12 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt()
        val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()

        val rootCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp24, dp24, dp24, dp16)
            background = GradientDrawable().apply {
                setColor(dialogBgColor)
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, activity.resources.displayMetrics)
                setStroke(1, borderColor)
            }
        }

        // Title Row
        val tvTitle = TextView(activity).apply {
            text = "Weather Settings"
            textSize = 20f
            typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD)
            setTextColor(textColor)
        }
        rootCard.addView(tvTitle)

        val tvSubtitle = TextView(activity).apply {
            text = "Customize temperature units and forecast location"
            textSize = 12f
            setTextColor(subTextColor)
            setPadding(0, dp8 / 2, 0, dp16)
        }
        rootCard.addView(tvSubtitle)

        val themeAccent = if (isDark) Color.parseColor("#A8C7FA") else accentColor
        val radioCheckedColor = if (isDark) Color.parseColor("#A8C7FA") else accentColor
        val radioUncheckedColor = if (isDark) Color.parseColor("#C4C7C5") else Color.parseColor("#5F6368")

        // Radio Button Tint
        val radioStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val radioColors = intArrayOf(
            radioCheckedColor,
            radioUncheckedColor
        )
        val radioTintList = ColorStateList(radioStates, radioColors)

        // 1. Temperature Unit Section Card
        val tvUnitTitle = TextView(activity).apply {
            text = "Temperature Unit"
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(themeAccent)
            setPadding(0, 0, 0, dp8 / 2)
        }
        rootCard.addView(tvUnitTitle)

        val isFahrenheitNow = prefs.getBoolean(PREF_IS_FAHRENHEIT, false)
        val unitContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp12, dp8, dp12, dp8)
            background = GradientDrawable().apply {
                setColor(cardBgColor)
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics)
                setStroke(1, borderColor)
            }
        }

        val rgUnits = RadioGroup(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val rbCelsius = MaterialRadioButton(activity).apply {
            id = View.generateViewId()
            text = "Celsius (°C)"
            setTextColor(textColor)
            buttonTintList = radioTintList
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rbFahrenheit = MaterialRadioButton(activity).apply {
            id = View.generateViewId()
            text = "Fahrenheit (°F)"
            setTextColor(textColor)
            buttonTintList = radioTintList
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        rgUnits.addView(rbCelsius)
        rgUnits.addView(rbFahrenheit)
        rgUnits.check(if (isFahrenheitNow) rbFahrenheit.id else rbCelsius.id)
        unitContainer.addView(rgUnits)
        rootCard.addView(unitContainer)

        // 2. Location Section Card
        val tvLocTitle = TextView(activity).apply {
            text = "Weather Location"
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(themeAccent)
            setPadding(0, dp16, 0, dp8 / 2)
        }
        rootCard.addView(tvLocTitle)

        val useGpsNow = prefs.getBoolean(PREF_USE_GPS, true)
        val locContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp12, dp8, dp12, dp12)
            background = GradientDrawable().apply {
                setColor(cardBgColor)
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics)
                setStroke(1, borderColor)
            }
        }

        val rgLocation = RadioGroup(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rbGps = MaterialRadioButton(activity).apply {
            id = View.generateViewId()
            text = "Use Current GPS Location"
            setTextColor(textColor)
            buttonTintList = radioTintList
        }
        val rbCustom = MaterialRadioButton(activity).apply {
            id = View.generateViewId()
            text = "Set Custom City/Place"
            setTextColor(textColor)
            buttonTintList = radioTintList
        }
        rgLocation.addView(rbGps)
        rgLocation.addView(rbCustom)
        rgLocation.check(if (useGpsNow) rbGps.id else rbCustom.id)
        locContainer.addView(rgLocation)

        val etCustomCity = EditText(activity).apply {
            hint = "Enter city name (e.g. San Francisco, Tokyo)"
            setHintTextColor(subTextColor)
            setTextColor(textColor)
            setText(prefs.getString(PREF_CUSTOM_CITY, ""))
            setSingleLine()
            textSize = 14f
            val dp10 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, activity.resources.displayMetrics).toInt()
            setPadding(dp12, dp10, dp12, dp10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp8, 0, 0)
            }
            background = GradientDrawable().apply {
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics)
                setColor(if (isDark) Color.parseColor("#383A40") else Color.WHITE)
                setStroke(1, themeAccent)
            }
            visibility = if (useGpsNow) View.GONE else View.VISIBLE
        }
        locContainer.addView(etCustomCity)
        rootCard.addView(locContainer)

        rgLocation.setOnCheckedChangeListener { _, checkedId ->
            activity.triggerStrongHaptic(rootCard)
            etCustomCity.visibility = if (checkedId == rbCustom.id) View.VISIBLE else View.GONE
            if (checkedId == rbCustom.id) etCustomCity.requestFocus()
        }

        // Action Buttons Row
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp24, 0, 0)
        }

        var dialogRef: AlertDialog? = null

        val btnCancel = TextView(activity).apply {
            text = "CANCEL"
            textSize = 13.5f
            typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD)
            setTextColor(subTextColor)
            setPadding(dp16, dp8, dp16, dp8)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                dialogRef?.dismiss()
            }
        }
        btnRow.addView(btnCancel)

        val btnSave = TextView(activity).apply {
            text = "SAVE"
            textSize = 13.5f
            typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD)
            setTextColor(if (isDark) Color.parseColor("#062E6F") else Color.WHITE)
            setPadding(dp24, dp8, dp24, dp8)
            background = GradientDrawable().apply {
                setColor(themeAccent)
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, activity.resources.displayMetrics)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                val newIsFahrenheit = rbFahrenheit.isChecked
                val newUseGps = rbGps.isChecked
                val newCity = etCustomCity.text.toString().trim()

                prefs.edit()
                    .putBoolean(PREF_IS_FAHRENHEIT, newIsFahrenheit)
                    .putBoolean(PREF_USE_GPS, newUseGps)
                    .putString(PREF_CUSTOM_CITY, newCity)
                    .remove(PREF_CUSTOM_LAT)
                    .remove(PREF_CUSTOM_LON)
                    .apply()

                if (newUseGps) {
                    checkAndRequestLocationPermission()
                }

                Toast.makeText(activity, "Updating weather...", Toast.LENGTH_SHORT).show()
                refreshWeather(force = true)
                dialogRef?.dismiss()
            }
        }
        btnRow.addView(btnSave)
        rootCard.addView(btnRow)

        dialogRef = AlertDialog.Builder(activity)
            .setView(rootCard)
            .create().apply {
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
        dialogRef.show()
    }

    /**
     * Checks and requests ACCESS_COARSE_LOCATION / ACCESS_FINE_LOCATION if needed
     */
    fun checkAndRequestLocationPermission() {
        val hasCoarse = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasCoarse && !hasFine) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * Gets GPS or Network location from Android LocationManager.
     * Evaluates fused, network, gps, and passive providers, and actively requests
     * a fresh location update if cached location is stale or unavailable.
     */
    @SuppressLint("MissingPermission")
    private fun getGpsLocation(): Location? {
        val hasCoarse = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasCoarse && !hasFine) return null

        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val candidateProviders = linkedSetOf(
            "fused",
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        try {
            lm.allProviders?.let { candidateProviders.addAll(it) }
        } catch (_: Exception) {}

        var bestLoc: Location? = null
        for (provider in candidateProviders) {
            try {
                val l = lm.getLastKnownLocation(provider) ?: continue
                if (bestLoc == null || (l.hasAccuracy() && l.accuracy < (bestLoc.accuracy.takeIf { it > 0 } ?: Float.MAX_VALUE))) {
                    bestLoc = l
                }
            } catch (_: Exception) {}
        }

        val now = System.currentTimeMillis()
        if (bestLoc != null && (now - bestLoc.time < 3 * 60 * 60 * 1000L)) {
            return bestLoc
        }

        // Actively request a single fresh location update
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            var freshLoc: Location? = null
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    freshLoc = loc
                    latch.countDown()
                    try { lm.removeUpdates(this) } catch (_: Exception) {}
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            val activeProvider = when {
                try { lm.isProviderEnabled("fused") } catch (_: Exception) { false } -> "fused"
                try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false } -> LocationManager.NETWORK_PROVIDER
                try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false } -> LocationManager.GPS_PROVIDER
                else -> candidateProviders.firstOrNull { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
            }

            if (activeProvider != null) {
                activity.runOnUiThread {
                    try {
                        lm.requestSingleUpdate(activeProvider, listener, Looper.getMainLooper())
                    } catch (_: Exception) {
                        try {
                            lm.requestLocationUpdates(activeProvider, 0L, 0f, listener, Looper.getMainLooper())
                        } catch (_: Exception) {}
                    }
                }
                latch.await(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
                activity.runOnUiThread {
                    try { lm.removeUpdates(listener) } catch (_: Exception) {}
                }
            }

            if (freshLoc != null) {
                return freshLoc
            }
        } catch (_: Exception) {}

        return bestLoc
    }

    /**
     * Resolves human readable place name using Geocoder with online reverse geocoding fallback
     */
    private fun resolvePlaceName(lat: Double, lon: Double): String {
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(activity, Locale.getDefault())
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val city = addr.locality ?: addr.subAdminArea ?: addr.adminArea
                    val country = addr.countryName
                    if (!city.isNullOrBlank()) {
                        return if (!country.isNullOrBlank()) "$city, $country" else city
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: fast online reverse-geocoding via BigDataCloud client API
        try {
            val url = "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=en"
            val json = fetchHttpString(url)
            val obj = JSONObject(json)
            val city = obj.optString("city").ifEmpty {
                obj.optString("locality").ifEmpty {
                    obj.optString("principalSubdivision")
                }
            }
            val country = obj.optString("countryName")
            if (city.isNotBlank()) {
                return if (country.isNotBlank()) "$city, $country" else city
            }
        } catch (_: Exception) {}

        return "Current Location"
    }

    /**
     * Fallback IP Geolocation when GPS is unavailable
     */
    private fun getIpLocation(): Triple<Double, Double, String> {
        try {
            val json = fetchHttpString("https://get.geojs.io/v1/ip/geo.json")
            val obj = JSONObject(json)
            val lat = obj.optString("latitude").toDoubleOrNull() ?: 40.7128
            val lon = obj.optString("longitude").toDoubleOrNull() ?: -74.0060
            val city = obj.optString("city", "New York")
            val country = obj.optString("country", "US")
            return Triple(lat, lon, "$city, $country")
        } catch (_: Exception) {}
        return Triple(40.7128, -74.0060, "New York, US")
    }

    /**
     * Geocodes city name to latitude and longitude using Open-Meteo Geocoding
     */
    private fun geocodeCity(cityName: String): Triple<Double, Double, String>? {
        try {
            val encoded = URLEncoder.encode(cityName, "UTF-8")
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json"
            val json = fetchHttpString(url)
            val obj = JSONObject(json)
            val results = obj.optJSONArray("results")
            if (results != null && results.length() > 0) {
                val item = results.getJSONObject(0)
                val lat = item.getDouble("latitude")
                val lon = item.getDouble("longitude")
                val name = item.getString("name")
                val country = item.optString("country", "")
                val fullName = if (country.isNotBlank()) "$name, $country" else name
                return Triple(lat, lon, fullName)
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Helper to make HTTP GET request and return response body as String
     */
    private fun fetchHttpString(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "HazeBrowser/1.0")
        try {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Returns Material weather vector icon based on WMO weather code and day/night status
     */
    fun getWeatherIconRes(code: Int, isDay: Boolean = true): Int {
        return when (code) {
            0 -> if (isDay) R.drawable.ic_weather_sunny else R.drawable.ic_weather_clear_night
            1, 2 -> if (isDay) R.drawable.ic_weather_partly_cloudy else R.drawable.ic_weather_partly_cloudy_night
            3 -> R.drawable.ic_weather_cloudy
            45, 48 -> R.drawable.ic_weather_fog
            51, 53, 55, 56, 57 -> R.drawable.ic_weather_rain
            61, 63, 65, 66, 67 -> R.drawable.ic_weather_rain
            71, 73, 75, 77 -> R.drawable.ic_weather_snow
            80, 81, 82 -> R.drawable.ic_weather_rain
            85, 86 -> R.drawable.ic_weather_snow
            95, 96, 99 -> R.drawable.ic_weather_thunderstorm
            else -> if (isDay) R.drawable.ic_weather_sunny else R.drawable.ic_weather_clear_night
        }
    }

    /**
     * Returns clear human-readable weather condition text
     */
    fun getWeatherDescription(code: Int, isDay: Boolean = true): String {
        return when (code) {
            0 -> if (isDay) "Clear Sky" else "Clear Night"
            1 -> if (isDay) "Mainly Clear" else "Clear Night"
            2 -> "Partly Cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51 -> "Light Drizzle"
            53 -> "Moderate Drizzle"
            55 -> "Dense Drizzle"
            61 -> "Slight Rain"
            63 -> "Moderate Rain"
            65 -> "Heavy Rain"
            71 -> "Slight Snow"
            73 -> "Moderate Snow"
            75 -> "Heavy Snow"
            77 -> "Snow Grains"
            80 -> "Slight Rain Showers"
            81 -> "Moderate Rain Showers"
            82 -> "Violent Rain Showers"
            85, 86 -> "Snow Showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with Hail"
            else -> "Cloudy"
        }
    }

    private fun getAqiCategory(aqi: Int): String {
        return when {
            aqi <= 50 -> "Good"
            aqi <= 100 -> "Moderate"
            aqi <= 150 -> "Sensitive"
            aqi <= 200 -> "Unhealthy"
            aqi <= 300 -> "Very Unhealthy"
            else -> "Hazardous"
        }
    }

    private fun getUvCategory(uv: Double): String {
        return when {
            uv <= 2.0 -> "Low"
            uv <= 5.0 -> "Moderate"
            uv <= 7.0 -> "High"
            uv <= 10.0 -> "Very High"
            else -> "Extreme"
        }
    }
}
