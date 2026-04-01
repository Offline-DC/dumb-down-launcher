package com.offlineinc.dumbdownlauncher.quack

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlineinc.dumbdownlauncher.R
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "QuackViewModel"

data class QuackPost(
    val id: String,
    val body: String,
    val createdAt: String,
)

enum class QuackMode { LOADING, FEED, COMPOSE, RULES, ERROR }

data class QuackUiState(
    val mode: QuackMode = QuackMode.LOADING,
    val posts: List<QuackPost> = emptyList(),
    val selectedIndex: Int = 0,
    val composeText: String = "",
    val isSubmitting: Boolean = false,
    val submitError: String? = null,
    val errorMessage: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val postsToday: Int = 0,
    val isInitialLoad: Boolean = true,
    val hasAcceptedRules: Boolean = false,
)

class QuackViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val MAX_POSTS_PER_DAY = 3
        private const val PREFS_NAME = "quack_daily"
        private const val KEY_DATE = "post_date"
        private const val KEY_COUNT = "post_count"
        private const val RULES_PREFS = "quack_prefs"
        private const val KEY_RULES_ACCEPTED = "rules_accepted"
    }

    private val _state = MutableStateFlow(QuackUiState())
    val state: StateFlow<QuackUiState> = _state

    private var locationHelper: QuackLocationHelper? = null
    private var honkPlayer: MediaPlayer? = null
    /** true when rules screen was opened via enterCompose (should go to compose after accept) */
    private var rulesFromCompose = false

    init {
        val rulesAccepted = getApplication<Application>()
            .getSharedPreferences(RULES_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RULES_ACCEPTED, false)
        _state.value = _state.value.copy(
            postsToday = getPostsToday(),
            hasAcceptedRules = rulesAccepted,
        )
        try {
            honkPlayer = MediaPlayer.create(application, R.raw.honk)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load honk sound", e)
        }
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun getPostsToday(): Int {
        val prefs = getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDate = prefs.getString(KEY_DATE, "") ?: ""
        return if (savedDate == todayKey()) prefs.getInt(KEY_COUNT, 0) else 0
    }

    private fun incrementPostsToday() {
        val prefs = getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = todayKey()
        val savedDate = prefs.getString(KEY_DATE, "") ?: ""
        val current = if (savedDate == today) prefs.getInt(KEY_COUNT, 0) else 0
        prefs.edit()
            .putString(KEY_DATE, today)
            .putInt(KEY_COUNT, current + 1)
            .apply()
        _state.value = _state.value.copy(postsToday = current + 1)
    }

    val postsRemaining: Int
        get() = (MAX_POSTS_PER_DAY - _state.value.postsToday).coerceAtLeast(0)

    private fun playHonk() {
        try {
            honkPlayer?.let {
                if (it.isPlaying) it.seekTo(0) else it.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "honk playback failed", e)
        }
    }

    fun startLocation() {
        _state.value = _state.value.copy(mode = QuackMode.LOADING)
        locationHelper?.cancel()
        locationHelper = QuackLocationHelper(getApplication(), object : QuackLocationHelper.Callback {
            override fun onLocation(lat: Double, lng: Double) {
                _state.value = _state.value.copy(lat = lat, lng = lng)
                loadFeed()
            }
            override fun onError(reason: String) {
                _state.value = _state.value.copy(
                    mode = QuackMode.ERROR,
                    errorMessage = "location error: $reason"
                )
            }
        })
        locationHelper!!.request()
    }

    fun loadFeed() {
        val s = _state.value
        _state.value = s.copy(mode = QuackMode.LOADING)
        viewModelScope.launch {
            try {
                Log.d(TAG, "loadFeed: fetching posts at ${s.lat},${s.lng}")
                val posts = withContext(Dispatchers.IO) {
                    val arr = QuackApiClient.fetchPosts(s.lat, s.lng)
                    Log.d(TAG, "loadFeed: got ${arr.length()} posts")
                    parsePosts(arr)
                }
                _state.value = _state.value.copy(
                    mode = QuackMode.FEED,
                    posts = posts,
                    selectedIndex = 0,
                    errorMessage = "",
                    isInitialLoad = false,
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadFeed: FAILED", e)
                val friendly = friendlyError(e)
                _state.value = _state.value.copy(
                    mode = QuackMode.ERROR,
                    errorMessage = friendly,
                )
            }
        }
    }

    /** Silent refresh — updates posts without showing loading state or resetting scroll. */
    fun refreshFeed() {
        val s = _state.value
        if (s.mode != QuackMode.FEED) return
        if (s.lat == 0.0 && s.lng == 0.0) return
        viewModelScope.launch {
            try {
                val posts = withContext(Dispatchers.IO) {
                    val arr = QuackApiClient.fetchPosts(s.lat, s.lng)
                    parsePosts(arr)
                }
                // Only update if still on feed
                if (_state.value.mode == QuackMode.FEED) {
                    _state.value = _state.value.copy(posts = posts)
                }
            } catch (e: Exception) {
                Log.w(TAG, "refreshFeed: silent fail", e)
                // Silent — don't disrupt the user
            }
        }
    }

    fun moveSelection(delta: Int) {
        val s = _state.value
        val newIdx = (s.selectedIndex + delta).coerceIn(0, (s.posts.size - 1).coerceAtLeast(0))
        _state.value = s.copy(selectedIndex = newIdx)
    }

    fun enterCompose() {
        if (_state.value.hasAcceptedRules) {
            _state.value = _state.value.copy(mode = QuackMode.COMPOSE, composeText = "", submitError = null)
        } else {
            rulesFromCompose = true
            _state.value = _state.value.copy(mode = QuackMode.RULES)
        }
    }

    fun acceptRules() {
        getApplication<Application>()
            .getSharedPreferences(RULES_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RULES_ACCEPTED, true)
            .apply()
        if (rulesFromCompose) {
            rulesFromCompose = false
            _state.value = _state.value.copy(
                hasAcceptedRules = true,
                mode = QuackMode.COMPOSE,
                composeText = "",
                submitError = null,
            )
        } else {
            _state.value = _state.value.copy(
                hasAcceptedRules = true,
                mode = QuackMode.FEED,
            )
        }
    }

    fun showRules() {
        rulesFromCompose = false
        _state.value = _state.value.copy(mode = QuackMode.RULES)
    }

    fun exitRules() {
        _state.value = _state.value.copy(mode = QuackMode.FEED)
    }

    fun exitCompose() {
        _state.value = _state.value.copy(mode = QuackMode.FEED, submitError = null)
    }

    fun updateComposeText(text: String) {
        if (text.length <= 140) {
            _state.value = _state.value.copy(composeText = text)
        }
    }

    fun clearSubmitError() {
        _state.value = _state.value.copy(submitError = null)
    }

    /** Returns true if text contains a URL-like pattern. */
    private fun containsUrl(text: String): Boolean {
        val pattern = Regex(
            "(https?://|www\\.)[\\w\\-]+(\\.[\\w\\-]+)+|" +   // http(s):// or www.
            "[\\w\\-]+\\.(com|org|net|io|co|me|app|dev|xyz|info|biz|us|uk|ca|de|fr|au)(\\b|/)",
            RegexOption.IGNORE_CASE
        )
        return pattern.containsMatchIn(text)
    }

    fun submitPost() {
        val s = _state.value
        val text = s.composeText.trim()
        Log.d(TAG, "submitPost: text='$text' isSubmitting=${s.isSubmitting}")
        if (text.isEmpty() || s.isSubmitting) return

        // Block URLs
        if (containsUrl(text)) {
            _state.value = s.copy(submitError = "no links. quacks only.")
            return
        }

        // Daily limit
        if (postsRemaining <= 0) {
            _state.value = s.copy(submitError = "3 quacks used. 3 more tmmrw. quack.")
            return
        }

        _state.value = s.copy(isSubmitting = true, submitError = null)
        val deviceId = QuackDeviceId.get(getApplication())
        val phoneNumber = PairingStore(getApplication()).flipPhoneNumber
        Log.d(TAG, "submitPost: deviceId=$deviceId phoneNumber=$phoneNumber lat=${s.lat} lng=${s.lng}")

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    QuackApiClient.createPost(text, s.lat, s.lng, deviceId, phoneNumber)
                }
                Log.d(TAG, "submitPost: SUCCESS — $result")
                incrementPostsToday()
                playHonk()
                _state.value = _state.value.copy(isSubmitting = false, submitError = null)
                loadFeed()
            } catch (e: Exception) {
                Log.e(TAG, "submitPost: FAILED", e)
                val friendly = friendlyError(e)
                _state.value = _state.value.copy(
                    isSubmitting = false,
                    submitError = friendly,
                )
            }
        }
    }

    /** Retry after a feed-level error — returns to loading → fetch. */
    fun retry() {
        val s = _state.value
        if (s.lat != 0.0 || s.lng != 0.0) {
            loadFeed()
        } else {
            startLocation()
        }
    }

    private fun friendlyError(e: Exception): String = when {
        e is QuackApiClient.ApiException -> when (e.statusCode) {
            404 -> "quack service not found"
            500 -> "server error — try again"
            else -> "server error (${e.statusCode})"
        }
        e is java.net.UnknownHostException -> "no internet connection"
        e is java.net.SocketTimeoutException -> "connection timed out"
        e is java.net.ConnectException -> "can't reach server"
        e.message?.contains("FileNotFoundException") == true -> "quack service not found"
        else -> "something went wrong"
    }

    private fun parsePosts(arr: JSONArray): List<QuackPost> {
        val list = mutableListOf<QuackPost>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                QuackPost(
                    id = obj.optString("id", ""),
                    body = obj.optString("body", ""),
                    createdAt = obj.optString("created_at", ""),
                )
            )
        }
        return list
    }

    override fun onCleared() {
        super.onCleared()
        locationHelper?.cancel()
        honkPlayer?.release()
        honkPlayer = null
    }
}
