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
import android.media.AudioAttributes
import android.media.session.MediaController
import android.media.session.MediaSession
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.monet.ColorScheme
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.STATE_CLOSED
import com.android.systemui.shade.ShadeExpansionStateManager
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
    private val shadeExpansionStateManager: ShadeExpansionStateManager,
    @Main private val mainExecutor: Executor,
    @Background private val bgExecutor: DelayableExecutor,
) : CoreStartable {

    // Key of the media session currently driving the accent, or null if none is playing.
    private var currentPlayingKey: String? = null
    // Whether we are currently registered as a MediaDataManager listener.
    private var listening = false
    // Handle to cancel a pending (debounced) apply/clear, so rapid changes coalesce.
    private var pendingCancel: Runnable? = null

    // Applying the accent goes through the Material-You overlay pipeline, which commits an RRO and
    // fans out a system-wide theme change. While QuickSettings is open that fan-out reinflates the
    // panel and collapses it. So while the shade is open we DON'T re-theme: we stash the intended
    // change and instead push a local live accent to the QS glow (see LIVE_ACCENT_KEY) for a smooth
    // in-place color shift, then reconcile the real overlay once the shade closes (invisibly).
    // Main-thread state only (shade callbacks + media callbacks both hop to main).
    private var shadeOpen = false
    private var pendingApply: WallpaperColors? = null
    private var pendingClear = false
    // Last value written to LIVE_ACCENT_KEY, so we don't spam Settings with redundant binder writes.
    private var lastLiveAccentWritten = 0

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
                if (data.isPlaying == true && !isVideoMedia(data)) {
                    currentPlayingKey = key
                    val art = data.artwork
                    if (art != null) {
                        scheduleApply(art)
                    }
                } else if (key == currentPlayingKey) {
                    // The session we were tracking paused/stopped (or is a video session).
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
        // Shade open/close drives whether we re-theme now or defer. Callbacks arrive on main.
        shadeExpansionStateManager.addStateListener { state ->
            mainExecutor.execute { onPanelStateChanged(state) }
        }
        updateEnabled()
    }

    private fun onPanelStateChanged(state: Int) {
        val open = state != STATE_CLOSED
        if (open == shadeOpen) return
        shadeOpen = open
        if (!open) {
            // Shade just closed: apply the real overlay now, while nothing is visible to collapse.
            val apply = pendingApply
            when {
                apply != null -> themeOverlayController.setMediaAccentColors(apply)
                pendingClear -> themeOverlayController.clearMediaAccentColors()
            }
            pendingApply = null
            pendingClear = false
            clearLiveAccent()
        }
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
            pendingApply = null
            pendingClear = false
            clearLiveAccent()
            themeOverlayController.clearMediaAccentColors()
            if (DEBUG) Log.d(TAG, "Album-art accent disabled")
        }
    }

    private fun scheduleApply(artwork: Icon) {
        cancelPending()
        pendingCancel =
            bgExecutor.executeDelayed(
                {
                    // Color extraction is the expensive part; keep it on the bg executor, then hop
                    // to main to read shade state and mutate our fields consistently.
                    val colors = artworkToWallpaperColors(artwork) ?: return@executeDelayed
                    mainExecutor.execute { applyOrDefer(colors) }
                },
                DEBOUNCE_MS,
            )
    }

    private fun scheduleClear() {
        cancelPending()
        pendingCancel =
            bgExecutor.executeDelayed(
                { mainExecutor.execute { clearOrDefer() } },
                DEBOUNCE_MS,
            )
    }

    /** Main thread. Re-theme now if the shade is closed; otherwise defer and drive the live glow. */
    private fun applyOrDefer(colors: WallpaperColors) {
        if (shadeOpen) {
            pendingApply = colors
            pendingClear = false
            // Local, RRO-free accent for the QS glow so it cross-fades in place while open. Use the
            // same seed the overlay pipeline will use on close, so the glow and the eventual
            // system accent agree on the hue.
            writeLiveAccent(ColorScheme.getSeedColor(colors))
        } else {
            pendingApply = null
            pendingClear = false
            clearLiveAccent()
            themeOverlayController.setMediaAccentColors(colors)
        }
    }

    /** Main thread. Clear now if the shade is closed; otherwise defer and drop the live glow. */
    private fun clearOrDefer() {
        if (shadeOpen) {
            pendingApply = null
            pendingClear = true
            clearLiveAccent()
        } else {
            pendingApply = null
            pendingClear = false
            clearLiveAccent()
            themeOverlayController.clearMediaAccentColors()
        }
    }

    private fun writeLiveAccent(argb: Int) {
        if (argb == lastLiveAccentWritten) return
        lastLiveAccentWritten = argb
        Settings.System.putIntForUser(
            context.contentResolver,
            LIVE_ACCENT_KEY,
            argb,
            userTracker.userId,
        )
    }

    private fun clearLiveAccent() = writeLiveAccent(0)

    private fun cancelPending() {
        pendingCancel?.run()
        pendingCancel = null
    }

    /**
     * True if [data] is a video session (e.g. a video player) that must be skipped: driving a
     * system-wide Material-You / RRO theme change triggers a configuration change that recreates a
     * playing video's surface and kills playback (the app survives; the video dies).
     *
     * There is no reliable single signal — video players and music apps frequently use identical
     * AudioAttributes (both USAGE_MEDIA / CONTENT_TYPE_UNKNOWN, verified on-device with YouTube vs
     * Tidal). So combine two checks: an explicit MOVIE content type when present, and — the reliable
     * one — the artwork shape. Album art is square; a video session's art is the landscape video
     * thumbnail. Either signal marks it as video.
     */
    private fun isVideoMedia(data: MediaData): Boolean =
        isVideoContentType(data.token) || isVideoArtwork(data.artwork)

    /** Video only if the session explicitly declares [AudioAttributes.CONTENT_TYPE_MOVIE]. */
    private fun isVideoContentType(token: MediaSession.Token?): Boolean {
        if (token == null) return false
        return try {
            val attrs = MediaController(context, token).playbackInfo?.audioAttributes
            attrs?.contentType == AudioAttributes.CONTENT_TYPE_MOVIE
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read media session content type", e)
            false
        }
    }

    /**
     * Video if the artwork is meaningfully non-square. Album covers are square (~1:1); video
     * thumbnails are landscape (16:9 or 4:3). Failure to measure defaults to "not video" so audio
     * apps are never wrongly dropped.
     */
    private fun isVideoArtwork(artwork: Icon?): Boolean {
        if (artwork == null) return false
        val (w, h) = artworkSize(artwork) ?: return false
        if (w <= 0 || h <= 0) return false
        val ratio = maxOf(w, h).toFloat() / minOf(w, h).toFloat()
        return ratio > VIDEO_ASPECT_RATIO
    }

    /** Pixel dimensions of an artwork [Icon], or null if they can't be determined cheaply. */
    private fun artworkSize(artwork: Icon): Pair<Int, Int>? {
        return try {
            when (artwork.type) {
                Icon.TYPE_BITMAP,
                Icon.TYPE_ADAPTIVE_BITMAP -> {
                    val bitmap = artwork.bitmap
                    if (bitmap == null || bitmap.isRecycled) null
                    else Pair(bitmap.width, bitmap.height)
                }
                else -> {
                    val drawable = artwork.loadDrawable(context) ?: return null
                    Pair(drawable.intrinsicWidth, drawable.intrinsicHeight)
                }
            }
        } catch (e: Exception) {
            null
        }
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
        // Settings.System key carrying a live ARGB accent for QS while the shade is open (0 = none).
        // Consumed by QSTileViewImpl (keep in sync with QS_LIVE_ACCENT_SETTING there).
        const val LIVE_ACCENT_KEY = "qs_live_accent"
        // Debounce window so skipping through tracks doesn't re-theme the whole system repeatedly.
        private const val DEBOUNCE_MS = 800L
        // Artwork wider/taller than this (long side / short side) is treated as a video thumbnail,
        // not album art, and skipped. Album covers are ~1:1; video thumbnails are 16:9 (1.78) or
        // 4:3 (1.33). On-device: Tidal art = 1.00, YouTube = 1.33 — so 1.2 separates them safely.
        private const val VIDEO_ASPECT_RATIO = 1.2f
    }
}
