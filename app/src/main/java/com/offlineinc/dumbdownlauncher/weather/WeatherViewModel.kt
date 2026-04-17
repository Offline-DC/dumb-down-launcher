package com.offlineinc.dumbdownlauncher.weather

import android.app.Application
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlineinc.dumbdownlauncher.quack.QuackLocationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "WeatherViewModel"

enum class WeatherMode { LOADING, DISPLAY, ERROR }

data class WeatherUiState(
    val mode: WeatherMode = WeatherMode.LOADING,
    // Current conditions
    val temp: Int = 0,
    val highTemp: Int = 0,
    val lowTemp: Int = 0,
    val condition: String = "",
    val icon: ImageVector = Icons.Filled.WbSunny,
    // Today's summary — built from daily weather_code + precipitation
    val todaySummary: String = "",
    // Tomorrow
    val tomorrowHigh: Int = 0,
    val tomorrowLow: Int = 0,
    val tomorrowCondition: String = "",
    val tomorrowIcon: ImageVector = Icons.Filled.WbSunny,
    // Last update time (human-readable)
    val updatedAt: String = "",
    // Error
    val errorMessage: String = "",
)

class WeatherViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(WeatherUiState())
    val state: StateFlow<WeatherUiState> = _state

    /** Epoch millis of the last successful fetch — used for the 5-min staleness check. */
    private var lastFetchedAt: Long = 0L

    /**
     * Load weather using the location saved by Quack's location system.
     * Reads from QuackLocationStore — the same persisted location that
     * quack uses and that gets refreshed by the boot prewarm + periodic worker.
     */
    fun loadWeather() {
        _state.value = _state.value.copy(mode = WeatherMode.LOADING)
        viewModelScope.launch {
            try {
                val loc = QuackLocationStore.loadIfUsable(getApplication())
                if (loc == null) {
                    Log.w(TAG, "No persisted location available")
                    _state.value = _state.value.copy(
                        mode = WeatherMode.ERROR,
                        errorMessage = "no location available.\nopen quack first to set your location.",
                    )
                    return@launch
                }
                Log.d(TAG, "Fetching weather for lat=${loc.first} lng=${loc.second}")
                val weather = withContext(Dispatchers.IO) {
                    fetchWeatherData(loc.first, loc.second)
                }
                lastFetchedAt = System.currentTimeMillis()
                _state.value = weather.copy(mode = WeatherMode.DISPLAY)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch weather", e)
                _state.value = _state.value.copy(
                    mode = WeatherMode.ERROR,
                    errorMessage = friendlyError(e),
                )
            }
        }
    }

    /**
     * Silent refresh — re-fetches weather data without showing loading state.
     * Called by onResume for background updates when returning to the weather
     * screen. Skips the fetch if the last successful load was < 5 minutes ago.
     */
    fun refreshWeather() {
        if (_state.value.mode != WeatherMode.DISPLAY) return
        val elapsed = System.currentTimeMillis() - lastFetchedAt
        if (elapsed < REFRESH_COOLDOWN_MS) {
            Log.d(TAG, "refreshWeather: skipping — last fetch ${elapsed / 1000}s ago (< 5min)")
            return
        }
        viewModelScope.launch {
            try {
                val loc = QuackLocationStore.loadIfUsable(getApplication()) ?: return@launch
                Log.d(TAG, "refreshWeather: silent refresh for lat=${loc.first} lng=${loc.second}")
                val weather = withContext(Dispatchers.IO) {
                    fetchWeatherData(loc.first, loc.second)
                }
                // Only update if still on display
                if (_state.value.mode == WeatherMode.DISPLAY) {
                    lastFetchedAt = System.currentTimeMillis()
                    _state.value = weather.copy(mode = WeatherMode.DISPLAY)
                }
            } catch (e: Exception) {
                Log.w(TAG, "refreshWeather: silent fail", e)
                // Silent — don't disrupt the user
            }
        }
    }

    private fun fetchWeatherData(latitude: Double, longitude: Double): WeatherUiState {
        val urlStr = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=$latitude")
            append("&longitude=$longitude")
            append("&current=temperature_2m,weather_code,wind_speed_10m")
            append("&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_sum,wind_speed_10m_max")
            append("&temperature_unit=fahrenheit")
            append("&wind_speed_unit=mph")
            append("&forecast_days=2")
            append("&timezone=auto")
        }

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = try {
                    conn.errorStream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
                } catch (_: Exception) { "" }
                throw Exception("Weather API failed ($code): $errBody")
            }
            val json = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            return parseWeatherResponse(json)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseWeatherResponse(json: String): WeatherUiState {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")

        val temp = current.getDouble("temperature_2m").toInt()
        val code = current.getInt("weather_code")
        val wind = current.getDouble("wind_speed_10m")

        // Today (index 0)
        val highTemp = daily.getJSONArray("temperature_2m_max").getDouble(0).toInt()
        val lowTemp = daily.getJSONArray("temperature_2m_min").getDouble(0).toInt()
        val todayCode = daily.getJSONArray("weather_code").getInt(0)
        val todayPrecip = daily.getJSONArray("precipitation_sum").getDouble(0)
        val todayWind = daily.getJSONArray("wind_speed_10m_max").getDouble(0)

        // Tomorrow (index 1) — guard against API returning fewer than 2 days
        val hasTomorrow = daily.getJSONArray("temperature_2m_max").length() > 1
        val tomorrowHigh = if (hasTomorrow) daily.getJSONArray("temperature_2m_max").getDouble(1).toInt() else highTemp
        val tomorrowLow = if (hasTomorrow) daily.getJSONArray("temperature_2m_min").getDouble(1).toInt() else lowTemp
        val tomorrowCode = if (hasTomorrow) daily.getJSONArray("weather_code").getInt(1) else todayCode
        val tomorrowWind = if (hasTomorrow) daily.getJSONArray("wind_speed_10m_max").getDouble(1) else todayWind

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val updatedAt = timeFormat.format(Date())

        return WeatherUiState(
            temp = temp,
            highTemp = highTemp,
            lowTemp = lowTemp,
            condition = weatherCodeToDescription(code, wind),
            icon = weatherCodeToIcon(code, wind),
            todaySummary = buildDaySummary(todayCode, todayPrecip, todayWind, highTemp, lowTemp),
            tomorrowHigh = tomorrowHigh,
            tomorrowLow = tomorrowLow,
            tomorrowCondition = weatherCodeToDescription(tomorrowCode, tomorrowWind),
            tomorrowIcon = weatherCodeToIcon(tomorrowCode, tomorrowWind),
            updatedAt = updatedAt,
        )
    }

    /**
     * Build a short text summary for today's conditions using the daily
     * weather code, precipitation total, and wind max.
     */
    private fun buildDaySummary(code: Int, precipMm: Double, windMax: Double, high: Int, low: Int): String {
        val parts = mutableListOf<String>()

        // Main condition from daily weather code
        val mainCondition = when (code) {
            0    -> "clear skies today"
            1    -> "mostly clear today"
            2    -> "partly cloudy today"
            3    -> "overcast today"
            45, 48 -> "foggy today"
            51, 53, 55 -> "light drizzle expected"
            61   -> "light rain expected"
            63   -> "rain expected"
            65   -> "heavy rain expected"
            71   -> "light snow expected"
            73   -> "snow expected"
            75   -> "heavy snow expected"
            77   -> "snow grains expected"
            80, 81, 82 -> "rain showers expected"
            85, 86 -> "snow showers expected"
            95   -> "thunderstorms expected"
            96, 99 -> "thunderstorms with hail expected"
            else -> "mixed conditions today"
        }
        parts.add(mainCondition)

        // Precipitation detail
        if (precipMm > 0) {
            val inches = precipMm / 25.4
            if (inches >= 0.5) {
                parts.add("%.1f\" of precipitation".format(inches))
            } else if (inches >= 0.1) {
                parts.add("light precipitation")
            }
        }

        // Wind detail
        if (windMax > 25) {
            parts.add("windy, gusts up to ${windMax.toInt()} mph")
        } else if (windMax > 15) {
            parts.add("breezy")
        }

        return parts.joinToString(". ") + "."
    }

    private fun friendlyError(e: Exception): String = when {
        e is java.net.UnknownHostException -> "no internet connection"
        e is java.net.SocketTimeoutException -> "connection timed out"
        e is java.net.ConnectException -> "can't reach weather service"
        else -> "something went wrong"
    }

    companion object {
        private const val REFRESH_COOLDOWN_MS = 30 * 60 * 1000L // 30 minutes

        fun weatherCodeToDescription(code: Int, wind: Double): String {
            val windy = if (wind > 20) " & Windy" else ""
            return when (code) {
                0 -> "Clear$windy"
                1 -> "Mostly Clear$windy"
                2 -> "Partly Cloudy$windy"
                3 -> "Cloudy$windy"
                45, 48 -> "Foggy"
                51, 53, 55 -> "Drizzle"
                61, 63, 65, 80, 81, 82 -> "Rain"
                71, 73, 75, 77, 85, 86 -> "Snow"
                95, 96, 99 -> "Thunderstorm"
                else -> "Unknown"
            }
        }

        fun weatherCodeToIcon(code: Int, wind: Double): ImageVector {
            if (wind > 25 && code < 45) return Icons.Filled.Air
            return when (code) {
                0, 1 -> Icons.Filled.WbSunny
                2 -> Icons.Filled.FilterDrama       // partly cloudy
                3 -> Icons.Filled.Cloud
                45, 48 -> Icons.Filled.WbCloudy     // fog — cloudy with haze
                51, 53, 55, 61, 63, 65, 80, 81, 82 -> Icons.Filled.WaterDrop
                71, 73, 75, 77, 85, 86 -> Icons.Filled.Cloud  // snow — cloud icon
                95, 96, 99 -> Icons.Filled.Thunderstorm
                else -> Icons.Filled.Cloud
            }
        }
    }
}
