package com.shirou.shibagram.data.preferences

import com.russhwolf.settings.Settings
import com.russhwolf.settings.get
import com.russhwolf.settings.set
import com.shirou.shibagram.domain.model.PlayerType
import com.shirou.shibagram.domain.model.ViewingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * User preferences repository using multiplatform-settings.
 * Ported from Android ShibaGram app's DataStore preferences.
 */
class UserPreferencesRepository {
    private val settings: Settings = Settings()
    
    companion object {
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_VIEWING_MODE = "viewing_mode"
        private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
        private const val KEY_DOWNLOAD_LOCATION = "download_location"
        private const val KEY_PLAYER_TYPE = "player_type"
        private const val KEY_MAX_CACHE_SIZE_GB = "max_cache_size_gb"
        
        @Volatile
        private var instance: UserPreferencesRepository? = null
        
        fun getInstance(): UserPreferencesRepository {
            return instance ?: synchronized(this) {
                instance ?: UserPreferencesRepository().also { instance = it }
            }
        }
    }
    
    private val _darkTheme = MutableStateFlow(settings[KEY_DARK_THEME, true])
    val darkTheme: StateFlow<Boolean> = _darkTheme
    
    private val _viewingMode = MutableStateFlow(
        ViewingMode.entries.getOrNull(settings[KEY_VIEWING_MODE, 0]) ?: ViewingMode.GRID
    )
    val viewingMode: StateFlow<ViewingMode> = _viewingMode
    
    private val _autoPlayNext = MutableStateFlow(settings[KEY_AUTO_PLAY_NEXT, true])
    val autoPlayNext: StateFlow<Boolean> = _autoPlayNext
    
    fun setDarkTheme(enabled: Boolean) {
        settings[KEY_DARK_THEME] = enabled
        _darkTheme.value = enabled
    }
    
    fun setViewingMode(mode: ViewingMode) {
        settings[KEY_VIEWING_MODE] = mode.ordinal
        _viewingMode.value = mode
    }
    
    fun setAutoPlayNext(enabled: Boolean) {
        settings[KEY_AUTO_PLAY_NEXT] = enabled
        _autoPlayNext.value = enabled
    }
    
    var downloadLocation: String
        get() = settings[KEY_DOWNLOAD_LOCATION,
            java.nio.file.Paths.get(System.getProperty("user.home"), "Downloads", "ShibaGram").toString()]
        set(value) { settings[KEY_DOWNLOAD_LOCATION] = value }
    
    var playerType: PlayerType
        get() = try {
            PlayerType.valueOf(settings[KEY_PLAYER_TYPE, PlayerType.MPV.name])
        } catch (_: Exception) { PlayerType.MPV }
        set(value) { settings[KEY_PLAYER_TYPE] = value.name }

    var maxCacheSizeGb: Float
        get() = settings[KEY_MAX_CACHE_SIZE_GB, 2f]
        set(value) { settings[KEY_MAX_CACHE_SIZE_GB] = value }
}
