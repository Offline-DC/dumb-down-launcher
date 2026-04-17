package com.offlineinc.dumbdownlauncher.weather

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlineinc.dumbdownlauncher.R
import com.offlineinc.dumbdownlauncher.quack.QuackLocationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val TAG = "WeatherViewModel"

enum class WeatherMode { LOADING, DISPLAY, ERROR }

data class HourlyForecast(
    val hour: String,
    val temp: Int,
    val iconRes: Int,
    val condition: String,
    val isNow: Boolean = false,
)

data class WeatherUiState(
    val mode: WeatherMode = WeatherMode.LOADING,
    val temp: Int = 0,
    val highTemp: Int = 0,
    val lowTemp: Int = 0,
    val condition: String = "",
    val iconRes: Int = R.drawable.ic_weather_sunny,
    val updatedAt: String = "",
    val forecasts: List<HourlyForecast> = emptyList(),
    val selectedForecastIndex: Int = 0,
    val errorMessage: String = "",
)

class WeatherViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(WeatherUiState())
    val state: StateFlow<WeatherUiState> = _state

    /**
     * Load weather using the location saved by Quack's location system.
     * Reads from QuackLocationStore — the same persisted location that
     * quack uses and that gets refreshed by the boot prewarm + periodic worker.
     */
    fun loadWeather() {
        _state.value = _state.value.copy(mode = WeatherMode.LOADING)
        viewModelScope.launch {
            try {
                val loc = readPersistedLocation()
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

    /** Read the persisted location from QuackLocationStore. */
    private fun readPersistedLocation(): Pair<Double, Double>? {
        val p = QuackLocationStore.load(getApplication()) ?: return null
        if (p.ageMs >= QuackLocationStore.STALE_MAX_AGE_MS) return null
        return p.lat to p.lng
    }

    fun moveForecastSelection(delta: Int) {
        val s = _state.value
        if (s.forecasts.isEmpty()) return
        val newIdx = (s.selectedForecastIndex + delta).coerceIn(0, s.forecasts.size - 1)
        _state.value = s.copy(selectedForecastIndex = newIdx)
    }

    private fun fetchWeatherData(latitude: Double, longitude: Double): WeatherUiState {
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=$latitude")
            append("&longitude=$longitude")
            append("&current=temperature_2m,weather_code,wind_speed_10m")
            append("&hourly=temperature_2m,weather_code")
            append("&daily=temperature_2m_max,temperature_2m_min")
            append("&temperature_unit=fahrenheit")
            append("&wind_speed_unit=mph")
            append("&forecast_days=2")
            append("&timezone=auto")
        }

        val json = URL(url).readText()
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val hourly = root.getJSONObject("hourly")
        val daily = root.getJSONObject("daily")

        val temp = current.getDouble("temperature_2m").toInt()
        val code = current.getInt("weather_code")
        val wind = current.getDouble("wind_speed_10m")

        val highTemp = daily.getJSONArray("temperature_2m_max").getDouble(0).toInt()
        val lowTemp = daily.getJSONArray("temperature_2m_min").getDouble(0).toInt()

        val condition = weatherCodeToDescription(code, wind)
        val iconRes = weatherCodeToIcon(code, wind)
        val updated = SimpleDateFormat("h:mm a", Locale.US).format(Date())

        // Parse hourly forecast
        val hourlyTimes = hourly.getJSONArray("time")
        val hourlyTemps = hourly.getJSONArray("temperature_2m")
        val hourlyCodes = hourly.getJSONArray("weather_code")

        val forecasts = mutableListOf<HourlyForecast>()
        val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        val displayFormat = SimpleDateFormat("h a", Locale.US)
        val nowTimeFormat = SimpleDateFormat("h:mm", Locale.US)

        // "Now" as the first item
        val nowLabel = nowTimeFormat.format(Date())
        forecasts.add(HourlyForecast(nowLabel, temp, iconRes, condition, isNow = true))

        // Find current hour index
        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        var startIndex = 0
        for (i in 0 until hourlyTimes.length()) {
            val timeStr = hourlyTimes.getString(i)
            val parsedTime = timeFormat.parse(timeStr)
            if (parsedTime != null) {
                val cal = Calendar.getInstance()
                cal.time = parsedTime
                if (cal.get(Calendar.HOUR_OF_DAY) == currentHour) {
                    startIndex = i + 1
                    break
                }
            }
        }

        // Next 12 hours
        for (i in startIndex until minOf(startIndex + 12, hourlyTimes.length())) {
            val time = timeFormat.parse(hourlyTimes.getString(i))
            val hourLabel = if (time != null) displayFormat.format(time) else "${i}h"
            val hourTemp = hourlyTemps.getDouble(i).toInt()
            val hourCode = hourlyCodes.getInt(i)
            val hourCondition = weatherCodeToDescription(hourCode, 0.0)
            forecasts.add(
                HourlyForecast(
                    hourLabel, hourTemp,
                    weatherCodeToIcon(hourCode, 0.0),
                    hourCondition, isNow = false,
                )
            )
        }

        return WeatherUiState(
            temp = temp,
            highTemp = highTemp,
            lowTemp = lowTemp,
            condition = condition,
            iconRes = iconRes,
            updatedAt = updated,
            forecasts = forecasts,
            selectedForecastIndex = 0,
        )
    }

    private fun friendlyError(e: Exception): String = when {
        e is java.net.UnknownHostException -> "no internet connection"
        e is java.net.SocketTimeoutException -> "connection timed out"
        e is java.net.ConnectException -> "can't reach weather service"
        else -> "something went wrong"
    }

    companion object {
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

        fun weatherCodeToIcon(code: Int, wind: Double): Int {
            if (wind > 25 && code < 45) {
                return R.drawable.ic_weather_windy
            }
            return when (code) {
                0, 1 -> R.drawable.ic_weather_sunny
                2 -> R.drawable.ic_weather_partly_cloudy
                3 -> R.drawable.ic_weather_cloudy
                45, 48 -> R.drawable.ic_weather_fog
                51, 53, 55, 61, 63, 65, 80, 81, 82 -> R.drawable.ic_weather_rain
                71, 73, 75, 77, 85, 86 -> R.drawable.ic_weather_snow
                95, 96, 99 -> R.drawable.ic_weather_thunderstorm
                else -> R.drawable.ic_weather_cloudy
            }
        }
    }
}
