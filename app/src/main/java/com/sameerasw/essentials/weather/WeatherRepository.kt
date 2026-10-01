package com.sameerasw.essentials.weather

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import com.sameerasw.essentials.weather.model.WeatherError
import com.sameerasw.essentials.weather.model.WeatherSnapshot
import com.sameerasw.essentials.weather.model.WeatherState
import com.sameerasw.essentials.weather.overcast.OvercastWeather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object WeatherRepository {
    const val REFRESH_INTERVAL_MINUTES = 30

    private const val CACHE_FILE = "weather_cache.json"
    private const val MIN_INTERVAL_MS = 2 * 60_000L

    private val gson = Gson()
    private val mutex = Mutex()
    private val _state = MutableStateFlow(WeatherState())
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    private var cache = WeatherCache()
    private var loaded = false

    @Keep
    private data class WeatherCache(
        val snapshot: WeatherSnapshot? = null,
        val notifiedAlertIds: Set<String>? = null,
    )

    suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            cache = withContext(Dispatchers.IO) {
                try {
                    File(context.applicationContext.cacheDir, CACHE_FILE).takeIf { it.exists() }?.readText()
                        ?.let { gson.fromJson(it, WeatherCache::class.java) } ?: WeatherCache()
                } catch (_: Exception) {
                    WeatherCache()
                }
            }
            loaded = true
            _state.update { it.copy(snapshot = cache.snapshot) }
        }
    }

    fun isStale(): Boolean {
        val snapshot = _state.value.snapshot ?: return true
        return System.currentTimeMillis() - snapshot.updatedAt >= REFRESH_INTERVAL_MINUTES * 60_000L
    }

    // Returns true when newer data was stored. A forced refresh asks Overcast to fetch first.
    suspend fun refresh(context: Context, force: Boolean = false): Boolean {
        val app = context.applicationContext
        ensureLoaded(app)
        return mutex.withLock {
            val current = _state.value.snapshot
            if (!force && current != null && System.currentTimeMillis() - current.updatedAt < MIN_INTERVAL_MS) {
                return@withLock false
            }
            if (!OvercastWeather.isInstalled(app)) {
                _state.update { it.copy(error = WeatherError.OvercastMissing, loading = false) }
                return@withLock false
            }
            if (!OvercastWeather.hasPermission(app)) {
                _state.update { it.copy(error = WeatherError.OvercastPermission, loading = false) }
                return@withLock false
            }
            _state.update { it.copy(loading = true) }
            if (force) OvercastWeather.requestRefresh(app)
            val snapshot = OvercastWeather.fetch(app)
            if (snapshot == null) {
                _state.update { it.copy(error = WeatherError.NoLocation, loading = false) }
                return@withLock false
            }
            val changed = snapshot.updatedAt != current?.updatedAt
            cache = cache.copy(snapshot = snapshot)
            persist(app)
            _state.value = WeatherState(snapshot = snapshot)
            changed
        }
    }

    // Returns the ids that haven't been announced yet and records them as announced.
    suspend fun takeUnnotifiedAlerts(context: Context, ids: Collection<String>): Set<String> {
        ensureLoaded(context)
        return mutex.withLock {
            val notified = cache.notifiedAlertIds.orEmpty()
            val fresh = ids.filterNot { it in notified }.toSet()
            if (fresh.isNotEmpty()) {
                val activeIds = _state.value.snapshot?.alerts?.map { it.id }?.toSet().orEmpty()
                cache = cache.copy(notifiedAlertIds = (notified.filter { it in activeIds } + fresh).toSet())
                persist(context.applicationContext)
            }
            fresh
        }
    }

    private suspend fun persist(context: Context) = withContext(Dispatchers.IO) {
        try {
            File(context.cacheDir, CACHE_FILE).writeText(gson.toJson(cache))
        } catch (_: Exception) {
        }
    }
}
