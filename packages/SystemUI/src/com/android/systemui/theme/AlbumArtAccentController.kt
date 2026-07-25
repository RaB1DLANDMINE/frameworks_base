/*
 * Copyright (C) 2026 The Infinity-X Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.theme

import android.app.WallpaperColors
import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.UserHandle
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.settings.SystemSettings
import dagger.Lazy
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Drives [ThemeOverlayController]'s album-art accent override from the now-playing media session.
 *
 * When the user setting [SETTING_KEY] is enabled, this observes [MediaDataManager] and, whenever a
 * track is actively playing, extracts a seed color from its album art and pushes it to
 * [ThemeOverlayController.setMediaAccentColors]. When playback stops (or the setting is turned off)
 * it restores normal theming via [ThemeOverlayController.clearMediaAccentColors].
 *
 * Cost control: the setting defaults OFF, artwork -> color extraction runs on the background
 * executor, changes are debounced ([DEBOUNCE_MS]) so skipping tracks doesn't hammer the overlay
 * system, and [ThemeOverlayController] itself skips the re-theme when the album color is unchanged.
 */
@SysUISingleton
class AlbumArtAccentController
@Inject
constructor(
    private val context: Context,
    private val themeOverlayController: ThemeOverlayController,
    private val mediaDataManagerLazy: Lazy<MediaDataManager>,
    private val systemSettings: SystemSettings,
    private val userTracker: UserTracker,
    @Main private val mainExecutor: Executor,
    @Background private val bgExecutor: DelayableExecutor,
) : CoreStartable {

    // Key of the media session currently driving the accent, or null if none is playing.
    private var currentPlayingKey: String? = null
    // Whether we are currently registered as a MediaDataManager listener.
    private var listening = false
    // Handle to cancel a pending (debounced) apply/clear, so rapid changes coalesce.
    private var pendingCancel: Runnable? = null

    private val settingsObserver =
        object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // Keep all state mutations on the main thread, same as the media callbacks.
                mainExecutor.execute { updateEnabled() }
            }
        }

    private val mediaListener =
        object : MediaDataManager.Listener {
            override fun onMediaDataLoaded(
                key: String,
                oldKey: String?,
                data: MediaData,
                immediately: Boolean,
            ) {
                if (data.isPlaying == true) {
                    currentPlayingKey = key
                    val art = data.artwork
                    if (art != null) {
                        scheduleApply(art)
                    }
                } else if (key == currentPlayingKey) {
                    // The session we were tracking paused/stopped.
                    currentPlayingKey = null
                    scheduleClear()
                }
            }

            override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
                if (key == currentPlayingKey) {
                    currentPlayingKey = null
                    scheduleClear()
                }
            }
        }

    override fun start() {
        systemSettings.registerContentObserverForUserSync(
            SETTING_KEY,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        updateEnabled()
    }

    private fun isEnabled(): Boolean =
        systemSettings.getIntForUser(SETTING_KEY, 0, userTracker.userId) == 1

    private fun updateEnabled() {
        val enabled = isEnabled()
        if (enabled && !listening) {
            listening = true
            mediaDataManagerLazy.get().addListener(mediaListener)
            if (DEBUG) Log.d(TAG, "Album-art accent enabled")
        } else if (!enabled && listening) {
            listening = false
            mediaDataManagerLazy.get().removeListener(mediaListener)
            currentPlayingKey = null
            cancelPending()
            themeOverlayController.clearMediaAccentColors()
            if (DEBUG) Log.d(TAG, "Album-art accent disabled")
        }
    }

    private fun scheduleApply(artwork: Icon) {
        cancelPending()
        pendingCancel =
            bgExecutor.executeDelayed(
                {
                    val colors = artworkToWallpaperColors(artwork)
                    if (colors != null) {
                        themeOverlayController.setMediaAccentColors(colors)
                    }
                },
                DEBOUNCE_MS,
            )
    }

    private fun scheduleClear() {
        cancelPending()
        pendingCancel =
            bgExecutor.executeDelayed(
                { themeOverlayController.clearMediaAccentColors() },
                DEBOUNCE_MS,
            )
    }

    private fun cancelPending() {
        pendingCancel?.run()
        pendingCancel = null
    }

    /** Extract [WallpaperColors] from album-art [Icon]. Runs on the background executor. */
    private fun artworkToWallpaperColors(artwork: Icon): WallpaperColors? {
        return try {
            when (artwork.type) {
                Icon.TYPE_BITMAP,
                Icon.TYPE_ADAPTIVE_BITMAP -> {
                    val bitmap = artwork.bitmap
                    if (bitmap == null || bitmap.isRecycled) null
                    else WallpaperColors.fromBitmap(bitmap)
                }
                else -> {
                    val drawable = artwork.loadDrawable(context)
                    if (drawable != null) WallpaperColors.fromDrawable(drawable) else null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract colors from album art", e)
            null
        }
    }

    companion object {
        private const val TAG = "AlbumArtAccent"
        private const val DEBUG = false
        // Settings.System key (0 = off default, 1 = on). Toggle from the Settings app or via
        // `adb shell settings put system album_art_accent 1`.
        const val SETTING_KEY = "album_art_accent"
        // Debounce window so skipping through tracks doesn't re-theme the whole system repeatedly.
        private const val DEBOUNCE_MS = 800L
    }
}
