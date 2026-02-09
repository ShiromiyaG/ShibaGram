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
        private const val KEY_DEFAULT_QUALITY = "default_quality"
        private const val KEY_DOWNLOAD_LOCATION = "download_location"
        private const val KEY_LAST_CHANNEL_ID = "last_channel_id"
        private const val KEY_VOLUME = "volume"
        private const val KEY_PLAYER_TYPE = "player_type"
        private const val KEY_MPV_PATH = "mpv_path"
        
        @Volatile
        private var instance: UserPreferencesRepository? = null
        
        fun getInstance(): UserPreferencesRepository {
            return instance ?: synchronized(this) {
                instance ?: UserPreferencesRepository().also { instance = it }
            }
        }
    }
    
    private val _darkTheme = MutableStateFlow(settings[KEY_DARK_THEME, false])
    val darkTheme: StateFlow<Boolean> = _darkTheme
    
    private val _viewingMode = MutableStateFlow(
        ViewingMode.entries.getOrNull(settings[KEY_VIEWING_MODE, 0]) ?: ViewingMode.GRID
    )
    val viewingMode: StateFlow<ViewingMode> = _viewingMode
    
    private val _autoPlayNext = MutableStateFlow(settings[KEY_AUTO_PLAY_NEXT, true])
    val autoPlayNext: StateFlow<Boolean> = _autoPlayNext
    
    private val _volume = MutableStateFlow(settings[KEY_VOLUME, 1f])
    val volume: StateFlow<Float> = _volume
    
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
    
    fun setVolume(vol: Float) {
        settings[KEY_VOLUME] = vol.coerceIn(0f, 1f)
        _volume.value = vol
    }
    
    var defaultQuality: String
        get() = settings[KEY_DEFAULT_QUALITY, "auto"]
        set(value) { settings[KEY_DEFAULT_QUALITY] = value }
    
    var downloadLocation: String
        get() = settings[KEY_DOWNLOAD_LOCATION, 
            System.getProperty("user.home") + "/Downloads/ShibaGram"]
        set(value) { settings[KEY_DOWNLOAD_LOCATION] = value }
    
    var lastChannelId: Long
        get() = settings[KEY_LAST_CHANNEL_ID, 0L]
        set(value) { settings[KEY_LAST_CHANNEL_ID] = value }

    var playerType: PlayerType
        get() = try {
            PlayerType.valueOf(settings[KEY_PLAYER_TYPE, PlayerType.VLC.name])
        } catch (_: Exception) { PlayerType.VLC }
        set(value) { settings[KEY_PLAYER_TYPE] = value.name }

    var mpvPath: String
        get() = settings[KEY_MPV_PATH, ""]
        set(value) { settings[KEY_MPV_PATH] = value }
}
