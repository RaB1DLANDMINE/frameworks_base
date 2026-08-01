/*
 * Copyright (C) 2026 Project Infinity X
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
package com.android.server.wm;

import android.content.Context;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import com.android.server.NtServiceInjector;
import com.android.server.ServiceThread;

import lineageos.health.HealthInterface;

import java.util.HashSet;
import java.util.Set;

/**
 * Per-app "bypass charging" — holds the battery while a user-selected app is in the foreground,
 * then restores normal charging when that app leaves. Fully decoupled from GameSpace: it is driven
 * directly by {@link DisplayContent} focus changes on the default display.
 *
 * <p>Config (written by Settings &gt; Battery &gt; Bypass charging):
 * <ul>
 *   <li>{@code Settings.System.bypass_charge_enabled} — master on/off (default off).</li>
 *   <li>{@code Settings.Secure.bypass_charge_apps} — ':'-separated package allowlist.</li>
 * </ul>
 *
 * <p>"Bypass" is a software hold, not electrical: it puts Lineage charging-control into
 * {@link HealthInterface#MODE_LIMIT} pinned to the current battery level, so the level stays flat
 * while the app is used. On release it restores whatever charge-control config the user had before
 * (so a user-set charge limit is never clobbered).
 */
public final class BypassChargeController {

    private static final String TAG = "BypassChargeController";

    static final String KEY_ENABLED = "bypass_charge_enabled";  // Settings.System, int 0/1
    static final String KEY_APPS = "bypass_charge_apps";        // Settings.Secure, ':'-joined pkgs

    private static volatile BypassChargeController sInstance;

    private final Context mContext;
    private final Handler mHandler;

    private final Set<String> mApps = new HashSet<>();
    private String mLastPackage;

    // Bypass state + the charge-control config to restore on release.
    private boolean mHeld;
    private int mSavedLimit = 100;
    private boolean mWasChargingControlEnabled;

    private BypassChargeController(Context context) {
        mContext = context;

        final ServiceThread thread =
                new ServiceThread(TAG, Process.THREAD_PRIORITY_DEFAULT, false /*allowIo*/);
        thread.start();
        mHandler = new Handler(thread.getLooper());

        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mHandler.post(() -> {
                    reloadApps();
                    evaluate(mLastPackage);
                });
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(KEY_APPS), false, observer, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(KEY_ENABLED), false, observer, UserHandle.USER_ALL);

        mHandler.post(this::reloadApps);
    }

    public static void systemReady() {
        if (sInstance == null) {
            sInstance = new BypassChargeController(NtServiceInjector.getCtx());
            Slog.i(TAG, "BypassChargeController initialized");
        }
    }

    public static BypassChargeController get() {
        return sInstance;
    }

    /** Called from DisplayContent on every default-display focus change. */
    public void onAppFocusChanged(ActivityRecord record, Task task) {
        if (record == null || record.packageName == null) {
            return;
        }
        final String pkg = record.packageName;
        mHandler.post(() -> {
            mLastPackage = pkg;
            evaluate(pkg);
        });
    }

    private boolean masterEnabled() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                KEY_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
    }

    private void reloadApps() {
        mApps.clear();
        final String list = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                KEY_APPS, UserHandle.USER_CURRENT);
        if (!TextUtils.isEmpty(list)) {
            for (String p : list.split(":")) {
                if (!TextUtils.isEmpty(p)) {
                    mApps.add(p);
                }
            }
        }
    }

    private void evaluate(String pkg) {
        final boolean shouldHold = masterEnabled() && pkg != null && mApps.contains(pkg);
        if (shouldHold && !mHeld) {
            hold();
        } else if (!shouldHold && mHeld) {
            release();
        }
    }

    private void hold() {
        try {
            final HealthInterface health = HealthInterface.getInstance(mContext);
            mWasChargingControlEnabled = health.getEnabled();
            mSavedLimit = mWasChargingControlEnabled ? health.getLimit() : 100;
            final int level = battLevel();
            health.setMode(HealthInterface.MODE_LIMIT);
            health.setLimit(level);
            health.setEnabled(true);
            mHeld = true;
            Slog.i(TAG, "bypass ON (hold @" + level + "%)");
        } catch (Exception e) {
            Slog.w(TAG, "Failed to enable bypass", e);
        }
    }

    private void release() {
        try {
            final HealthInterface health = HealthInterface.getInstance(mContext);
            health.setMode(HealthInterface.MODE_LIMIT);
            health.setLimit(mSavedLimit);
            // Only keep charging-control on if the user had it on before we engaged.
            health.setEnabled(mWasChargingControlEnabled);
            mHeld = false;
            Slog.i(TAG, "bypass OFF (restore limit=" + mSavedLimit
                    + " ctrlEnabled=" + mWasChargingControlEnabled + ")");
        } catch (Exception e) {
            Slog.w(TAG, "Failed to disable bypass", e);
        }
    }

    private int battLevel() {
        final BatteryManager bm = mContext.getSystemService(BatteryManager.class);
        final int lvl = bm != null
                ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        return lvl > 0 ? lvl : 100;
    }
}
