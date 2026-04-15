package com.offlineinc.dumbdownlauncher.quack

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlineinc.dumbdownlauncher.R
import com.offlineinc.dumbdownlauncher.launcher.PhoneNumberReader
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

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

    /**
     * Returns the epoch millis of the most recent 6am in local time.
     * If it's currently before 6am, returns yesterday's 6am.
     * The quack limit resets at 6am each day.
     */
    private fun currentWindowStart(): Long {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < 6) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        cal.set(Calendar.HOUR_OF_DAY, 6)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getPostsToday(): Int {
        val prefs = getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedWindow = try {
            prefs.getLong(KEY_DATE, 0L)
        } catch (_: ClassCastException) {
            // Migration: old versions stored this as a String date. Clear it.
            prefs.edit().remove(KEY_DATE).apply()
            0L
        }
        return if (savedWindow == currentWindowStart()) prefs.getInt(KEY_COUNT, 0) else 0
    }

    private fun incrementPostsToday() {
        val prefs = getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val window = currentWindowStart()
        val savedWindow = try {
            prefs.getLong(KEY_DATE, 0L)
        } catch (_: ClassCastException) {
            prefs.edit().remove(KEY_DATE).apply()
            0L
        }
        val current = if (savedWindow == window) prefs.getInt(KEY_COUNT, 0) else 0
        prefs.edit()
            .putLong(KEY_DATE, window)
            .putInt(KEY_COUNT, current + 1)
            .apply()
        _state.value = _state.value.copy(postsToday = current + 1)
    }

    val postsRemaining: Int
        get() = (MAX_POSTS_PER_DAY - _state.value.postsToday).coerceAtLeast(0)

    /**
     * Backend returned 429 — force local count to MAX so the "X/3 quacks left
     * today" indicator matches reality. Fixes the case where local prefs got
     * out of sync (app data cleared, reinstall, etc.).
     */
    private fun syncPostsTodayToMax() {
        val prefs = getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_DATE, currentWindowStart())
            .putInt(KEY_COUNT, MAX_POSTS_PER_DAY)
            .apply()
        _state.value = _state.value.copy(postsToday = MAX_POSTS_PER_DAY)
    }

    private fun playHonk() {
        try {
            honkPlayer?.let {
                if (it.isPlaying) it.seekTo(0) else it.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "honk playback failed", e)
        }
    }

    /**
     * Called when the user first enters the quack screen (or regrants
     * location permission). Uses whatever coarse location was prewarmed at
     * boot — never blocks on a fresh fix.
     */
    fun startLocation() {
        loadFeed()
    }

    /**
     * Called when the user presses the "refresh" soft key on the feed.
     * Forces a fresh coarse-location read (blocks up to 30s while the radio
     * gets a fix) and then reloads the feed against it. This is the only
     * path that invalidates the 30-min location cache.
     */
    fun refreshFromUser() {
        _state.value = _state.value.copy(mode = QuackMode.LOADING)
        viewModelScope.launch {
            try {
                Log.d(TAG, "refreshFromUser: forcing fresh location read")
                val posts = withContext(Dispatchers.IO) {
                    val loc = QuackLocationReader.forceRefresh(getApplication())
                    if (loc == null) {
                        Log.w(TAG, "refreshFromUser: no fix — server will return empty feed")
                    } else {
                        Log.d(TAG, "refreshFromUser: got lat=${loc.first} lng=${loc.second}")
                    }
                    val arr = QuackApiClient.fetchPosts(loc?.first, loc?.second)
                    Log.d(TAG, "refreshFromUser: got ${arr.length()} posts")
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
                Log.e(TAG, "refreshFromUser: FAILED", e)
                _state.value = _state.value.copy(
                    mode = QuackMode.ERROR,
                    errorMessage = friendlyError(e),
                )
            }
        }
    }

    fun loadFeed() {
        _state.value = _state.value.copy(mode = QuackMode.LOADING)
        viewModelScope.launch {
            try {
                Log.d(TAG, "loadFeed: fetching posts (using cached/in-flight location)")
                val posts = withContext(Dispatchers.IO) {
                    // Prefer cache; if nothing's cached but the boot-time
                    // prewarm is still running, piggyback on it (keeps the
                    // LOADING mode up rather than flashing an empty feed).
                    // If the prewarm already finished without a fix and the
                    // cache is empty, kick off a fresh read and wait on it.
                    var loc = QuackLocationReader.readCached()
                    if (loc == null) {
                        if (QuackLocationReader.isFetching()) {
                            Log.d(TAG, "loadFeed: prewarm in flight — awaiting up to 60s")
                            loc = QuackLocationReader.awaitInflight(60_000L)
                        } else {
                            Log.d(TAG, "loadFeed: no cache and no prewarm — kicking one off and waiting")
                            QuackLocationReader.prewarm(getApplication())
                            loc = QuackLocationReader.awaitInflight(60_000L)
                        }
                    }
                    if (loc == null) {
                        Log.w(TAG, "loadFeed: no location fix — server will return empty feed")
                    } else {
                        Log.d(TAG, "loadFeed: using lat=${loc.first} lng=${loc.second}")
                    }
                    val arr = QuackApiClient.fetchPosts(loc?.first, loc?.second)
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
        if (_state.value.mode != QuackMode.FEED) return
        viewModelScope.launch {
            try {
                val posts = withContext(Dispatchers.IO) {
                    val loc = QuackLocationReader.readCached()
                    val arr = QuackApiClient.fetchPosts(loc?.first, loc?.second)
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
            _state.value = s.copy(submitError = "3 quacks used. try again tomorrow. quack.")
            return
        }

        _state.value = s.copy(isSubmitting = true, submitError = null)
        val deviceId = QuackDeviceId.get(getApplication())
        // Prefer the paired flip phone number (set at pairing time, E.164-normalized).
        // Fall back to reading directly from the SIM for unpaired devices so every
        // quack carries a phone_number — backend tags/notifies on this field.
        val phoneNumber = PairingStore(getApplication()).flipPhoneNumber
            ?.takeIf { it.isNotBlank() }
            ?: try {
                PhoneNumberReader.read(getApplication()).first?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.w(TAG, "submitPost: SIM phone-number read failed", e)
                null
            }
        // Total UTC offset in minutes (includes DST). Backend uses this to find
        // when 6am was in the user's local timezone for the daily reset.
        val utcOffsetMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        Log.d(TAG, "submitPost: deviceId=$deviceId phoneNumber=$phoneNumber utcOffset=${utcOffsetMinutes}min")

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val loc = QuackLocationReader.readCached()
                    if (loc == null) {
                        Log.w(TAG, "submitPost: no cached location — posting without coords (server will reject)")
                    } else {
                        Log.d(TAG, "submitPost: using cached lat=${loc.first} lng=${loc.second}")
                    }
                    QuackApiClient.createPost(text, deviceId, phoneNumber, utcOffsetMinutes, loc?.first, loc?.second)
                }
                Log.d(TAG, "submitPost: SUCCESS — $result")
                incrementPostsToday()
                playHonk()
                _state.value = _state.value.copy(isSubmitting = false, submitError = null)
                loadFeed()
            } catch (e: Exception) {
                Log.e(TAG, "submitPost: FAILED", e)
                // Map API errors to inline compose-screen messages rather than full-screen errors
                val msg = when {
                    e is QuackApiClient.ApiException && e.statusCode == 429 -> {
                        // Backend is the source of truth — sync local count so the
                        // "X/3 quacks left today" indicator matches reality.
                        syncPostsTodayToMax()
                        "3 quacks used. try again tomorrow. quack."
                    }
                    e is QuackApiClient.ApiException && e.statusCode == 422 ->
                        "no links. quacks only."
                    else -> friendlyError(e)
                }
                _state.value = _state.value.copy(
                    isSubmitting = false,
                    submitError = msg,
                )
            }
        }
    }

    /** Retry after a feed-level error — just re-fetch (location is server-side). */
    fun retry() {
        loadFeed()
    }

    private fun friendlyError(e: Exception): String = when {
        e is QuackApiClient.ApiException -> when (e.statusCode) {
            404 -> "quack service not found"
            422 -> "no links. quacks only."
            429 -> "3 quacks used. try again tomorrow. quack."
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
        honkPlayer?.release()
        honkPlayer = null
    }
}
