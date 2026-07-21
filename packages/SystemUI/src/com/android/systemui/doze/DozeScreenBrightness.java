/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.doze;

import static android.os.PowerManager.GO_TO_SLEEP_REASON_TIMEOUT;

import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP;

import static java.lang.Integer.max;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.IndentingPrintWriter;
import android.view.Display;

import com.android.app.tracing.TraceUtils;
import com.android.internal.R;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.dagger.BrightnessSensor;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.doze.dagger.WrappedService;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.util.AlarmTimeout;
import com.android.systemui.util.kotlin.JavaAdapterKt;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.settings.SystemSettings;
import com.android.systemui.wallpapers.domain.interactor.WallpaperInteractor;

import kotlin.Unit;

import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Job;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import javax.inject.Inject;

/**
 * Controls the screen brightness when dozing.
 */
@DozeScope
public class DozeScreenBrightness extends BroadcastReceiver implements DozeMachine.Part,
        SensorEventListener {
    private static final boolean DEBUG_AOD_BRIGHTNESS = SystemProperties
            .getBoolean("debug.aod_brightness", false);
    protected static final String ACTION_AOD_BRIGHTNESS =
            "com.android.systemui.doze.AOD_BRIGHTNESS";
    protected static final String BRIGHTNESS_BUCKET = "brightness_bucket";
    private static final String AOD_LOW_BRIGHTNESS = "aod_low_brightness";
    private static final String AOD_HIGH_BRIGHTNESS = "aod_high_brightness";
    private static final String AOD_PICKUP_BRIGHTNESS_BOOST = "aod_pickup_brightness_boost";
    private static final long AOD_PICKUP_BRIGHTNESS_BOOST_TIMEOUT_MS = 7_000;
    private static final long AOD_PICKUP_BRIGHTNESS_REARM_DELAY_MS = 3_000;

    /**
     * Just before the screen times out from user inactivity, DisplayPowerController dims the screen
     * brightness to the lower of {@link #mScreenBrightnessDim}, or the current brightness minus
     * this amount.
     */
    private final float mScreenBrightnessMinimumDimAmount;
    private final Context mContext;
    private final DozeMachine.Service mDozeService;
    private final DozeHost mDozeHost;
    private final Handler mHandler;
    private final SensorManager mSensorManager;
    private final Sensor mPickupSensor;
    private final boolean mPickupSensorUsesTrigger;
    private final Sensor mStationarySensor;
    private final PowerManager.WakeLock mPickupBrightnessWakeLock;
    private final DisplayManager mDisplayManager;
    private final Optional<Sensor>[] mLightSensorOptional; // light sensors to use per posture
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final DozeParameters mDozeParameters;
    private final DevicePostureController mDevicePostureController;
    private final DozeLog mDozeLog;
    private final SystemSettings mSystemSettings;
    private final WallpaperInteractor mWallpaperInteractor;
    private final CoroutineScope mScope;
    private final ContentObserver mAodBrightnessObserver;
    private Job mWallpaperSupportsAmbientModeJob = null;
    private boolean mWallpaperSupportsAmbientMode;
    private final float[] mSensorToBrightness;
    private final int[] mSensorToWallpaperScrimOpacity;
    private final int[] mSensorToScrimOpacity;
    private final float mScreenBrightnessDim;

    @DevicePostureController.DevicePostureInt
    private int mDevicePosture;
    private boolean mRegistered;
    private final float mDefaultDozeBrightness;
    private boolean mPaused = false;
    private boolean mScreenOff = false;
    private int mLastSensorValue = -1;
    private DozeMachine.State mState = DozeMachine.State.UNINITIALIZED;
    private boolean mPickupBrightnessBoosted;
    private boolean mPickupSensorRegistered;
    private boolean mPickupSensorRegisteredAsListener;
    private boolean mStationarySensorRegistered;

    private final AlarmTimeout mPickupBrightnessBoostTimeout;
    private final Runnable mPickupSensorRearm = this::requestPickupSensor;

    private final TriggerEventListener mPickupListener = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
            mPickupSensorRegistered = false;
            onPickupGesture();
        }
    };

    private final TriggerEventListener mStationaryListener = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
            mStationarySensorRegistered = false;
            finishPickupBrightnessBoost();
        }
    };

    /**
     * Debug value used for emulating various display brightness buckets:
     *
     * {@code am broadcast -p com.android.systemui -a com.android.systemui.doze.AOD_BRIGHTNESS
     * --ei brightness_bucket 1}
     */
    private int mDebugBrightnessBucket = -1;

    @Inject
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public DozeScreenBrightness(
            Context context,
            @WrappedService DozeMachine.Service service,
            AsyncSensorManager sensorManager,
            @BrightnessSensor Optional<Sensor>[] lightSensorOptional,
            DozeHost host, @Main Handler handler,
            AlwaysOnDisplayPolicy alwaysOnDisplayPolicy,
            WakefulnessLifecycle wakefulnessLifecycle,
            DozeParameters dozeParameters,
            DevicePostureController devicePostureController,
            DozeLog dozeLog,
            SystemSettings systemSettings,
            DisplayManager displayManager,
            WallpaperInteractor wallpaperInteractor,
            AlarmManager alarmManager,
            @Application CoroutineScope scope
    ) {
        mContext = context;
        mDozeService = service;
        mSensorManager = sensorManager;
        Sensor pickupSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE);
        if (pickupSensor == null) {
            pickupSensor = sensorManager.getDefaultSensor(Sensor.TYPE_TILT_DETECTOR);
        }
        mPickupSensor = pickupSensor;
        mPickupSensorUsesTrigger = pickupSensor != null
                && pickupSensor.getType() == Sensor.TYPE_PICK_UP_GESTURE;
        mStationarySensor = sensorManager.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT);
        mPickupBrightnessWakeLock = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "DozeScreenBrightness:PickupBrightnessBoost");
        mPickupBrightnessWakeLock.setReferenceCounted(false);
        mDisplayManager = displayManager;
        mLightSensorOptional = lightSensorOptional;
        mDevicePostureController = devicePostureController;
        mDevicePosture = mDevicePostureController.getDevicePosture();
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mDozeParameters = dozeParameters;
        mDozeHost = host;
        mHandler = handler;
        mPickupBrightnessBoostTimeout = new AlarmTimeout(alarmManager,
                this::finishPickupBrightnessBoost, "AodPickupBrightnessBoost", mHandler);
        mDozeLog = dozeLog;
        mSystemSettings = systemSettings;
        mWallpaperInteractor = wallpaperInteractor;
        mScope = scope;

        mScreenBrightnessMinimumDimAmount = context.getResources().getFloat(
                R.dimen.config_screenBrightnessMinimumDimAmountFloat);

        mDefaultDozeBrightness = mDisplayManager.getDefaultDozeBrightness(mContext.getDisplayId());
        mScreenBrightnessDim = alwaysOnDisplayPolicy.dimBrightness;
        float[] sensorToBrightness =
                mDisplayManager.getDozeBrightnessSensorValueToBrightness(mContext.getDisplayId());
        if (sensorToBrightness == null) {
            int[] screenBrightnessArray = alwaysOnDisplayPolicy.screenBrightnessArray;
            sensorToBrightness = new float[screenBrightnessArray.length];
            for (int i = 0; i < screenBrightnessArray.length; i++) {
                sensorToBrightness[i] = BrightnessSynchronizer.brightnessIntToFloat(
                        screenBrightnessArray[i]);
            }
        }
        mSensorToBrightness = sensorToBrightness;
        mSensorToScrimOpacity = alwaysOnDisplayPolicy.dimmingScrimArray;
        mSensorToWallpaperScrimOpacity = alwaysOnDisplayPolicy.wallpaperDimmingScrimArray;

        mDevicePostureController.addCallback(mDevicePostureCallback);
        mAodBrightnessObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                if (!isPickupBrightnessBoostEnabled()) {
                    cancelPickupSensor();
                    finishPickupBrightnessBoost();
                } else {
                    requestPickupSensor();
                }
                updateBrightnessAndReady(true /* force */);
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(AOD_LOW_BRIGHTNESS), false,
                mAodBrightnessObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(AOD_HIGH_BRIGHTNESS), false,
                mAodBrightnessObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(AOD_PICKUP_BRIGHTNESS_BOOST), false,
                mAodBrightnessObserver, UserHandle.USER_ALL);
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        mState = newState;
        switch (newState) {
            case INITIALIZED:
                startListeningForWallpaperSupportsAmbientMode();
                resetBrightnessToDefault();
                break;
            case DOZE_AOD:
            case DOZE_REQUEST_PULSE:
            case DOZE_AOD_DOCKED:
            case DOZE_AOD_MINMODE:
                setLightSensorEnabled(true);
                break;
            case DOZE:
            case DOZE_SUSPEND_TRIGGERS:
                setLightSensorEnabled(false);
                resetBrightnessToDefault();
                break;
            case DOZE_AOD_PAUSED:
                setLightSensorEnabled(false);
                break;
            case FINISH:
                onDestroy();
                break;
        }
        if (newState != DozeMachine.State.FINISH) {
            setScreenOff(newState == DozeMachine.State.DOZE);
            setPaused(newState == DozeMachine.State.DOZE_AOD_PAUSED);
        }
    }

    private void onDestroy() {
        stopListeningForWallpaperSupportsAmbientMode();
        finishPickupBrightnessBoost();
        setLightSensorEnabled(false);
        mDevicePostureController.removeCallback(mDevicePostureCallback);
        mContext.getContentResolver().unregisterContentObserver(mAodBrightnessObserver);
        if (SceneContainerFlag.isEnabled()) {
            mDozeHost.setAodDimmingScrim(0f);
            mDozeHost.setAodWallpaperDimmingScrim(0f);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        TraceUtils.trace(() -> "DozeScreenBrightness.onSensorChanged" + event.values[0], () -> {
            if (Objects.equals(event.sensor, mPickupSensor)) {
                cancelPickupSensor();
                onPickupGesture();
                return Unit.INSTANCE;
            }
            if (mRegistered) {
                mLastSensorValue = (int) event.values[0];
                updateBrightnessAndReady(false /* force */);
            }
            return Unit.INSTANCE;
        });
    }

    public void updateBrightnessAndReady(boolean force) {
        if (force || mRegistered || mDebugBrightnessBucket != -1) {
            int sensorValue = mDebugBrightnessBucket == -1
                    ? mLastSensorValue : mDebugBrightnessBucket;
            boolean brightnessReady;
            float brightness = computeBrightness(sensorValue);
            brightnessReady = brightness >= 0;
            if (brightnessReady) {
                mDozeService.setDozeScreenBrightness(
                        clampToDimBrightnessForScreenOff(clampToUserSetting(brightness)));
            }

            int scrimOpacity = -1;
            int wallpaperScrimOpacity = -1;
            if (!isLightSensorPresent()) {
                // No light sensor, scrims are always transparent.
                scrimOpacity = 0;
                wallpaperScrimOpacity = 0;
            } else if (brightnessReady) {
                // Only unblank scrim once brightness is ready.
                scrimOpacity = computeScrimOpacity(sensorValue);
                wallpaperScrimOpacity = computeWallpaperScrimOpacity(sensorValue);
            }
            if (scrimOpacity >= 0) {
                mDozeHost.setAodDimmingScrim(scrimOpacity / 255f);
            }
            if (wallpaperScrimOpacity >= 0) {
                mDozeHost.setAodWallpaperDimmingScrim(wallpaperScrimOpacity / 255f);
            }
        }
    }

    public void onPickupGesture() {
        if (!isPickupBrightnessBoostEnabled()
                || mSensorToBrightness.length != 2
                || !mRegistered
                || mLastSensorValue != 0) {
            requestPickupSensor();
            return;
        }

        mPickupBrightnessBoosted = true;
        mPickupBrightnessWakeLock.acquire(AOD_PICKUP_BRIGHTNESS_BOOST_TIMEOUT_MS
                + AOD_PICKUP_BRIGHTNESS_REARM_DELAY_MS);
        updateBrightnessAndReady(true /* force */);

        mPickupBrightnessBoostTimeout.schedule(AOD_PICKUP_BRIGHTNESS_BOOST_TIMEOUT_MS,
                AlarmTimeout.MODE_RESCHEDULE_IF_SCHEDULED);
        requestStationarySensor();
    }

    private void finishPickupBrightnessBoost() {
        mPickupBrightnessBoostTimeout.cancel();
        cancelStationarySensor();
        if (mPickupBrightnessWakeLock.isHeld()) {
            mPickupBrightnessWakeLock.release();
        }
        if (mPickupBrightnessBoosted) {
            mPickupBrightnessBoosted = false;
            updateBrightnessAndReady(true /* force */);
        }
        schedulePickupSensorRearm();
    }

    private void requestPickupSensor() {
        mHandler.removeCallbacks(mPickupSensorRearm);
        if (!isPickupBrightnessBoostEnabled()
                || !mRegistered
                || mPickupBrightnessBoosted
                || mPickupSensor == null
                || mPickupSensorRegistered) {
            return;
        }
        if (mPickupSensorUsesTrigger) {
            mPickupSensorRegistered =
                    mSensorManager.requestTriggerSensor(mPickupListener, mPickupSensor);
        } else {
            mPickupSensorRegistered = mSensorManager.registerListener(this, mPickupSensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            mPickupSensorRegisteredAsListener = mPickupSensorRegistered;
        }
    }

    private void schedulePickupSensorRearm() {
        mHandler.removeCallbacks(mPickupSensorRearm);
        if (!isPickupBrightnessBoostEnabled()
                || !mRegistered
                || mPickupBrightnessBoosted
                || mPickupSensor == null
                || mPickupSensorRegistered) {
            return;
        }
        mHandler.postDelayed(mPickupSensorRearm, AOD_PICKUP_BRIGHTNESS_REARM_DELAY_MS);
    }

    private void cancelPickupSensor() {
        mHandler.removeCallbacks(mPickupSensorRearm);
        if (!mPickupSensorRegistered) {
            return;
        }
        if (mPickupSensorRegisteredAsListener) {
            mSensorManager.unregisterListener(this, mPickupSensor);
            mPickupSensorRegisteredAsListener = false;
        } else {
            mSensorManager.cancelTriggerSensor(mPickupListener, mPickupSensor);
        }
        mPickupSensorRegistered = false;
    }

    private void requestStationarySensor() {
        if (mStationarySensor == null || mStationarySensorRegistered) {
            return;
        }
        mStationarySensorRegistered =
                mSensorManager.requestTriggerSensor(mStationaryListener, mStationarySensor);
    }

    private void cancelStationarySensor() {
        if (!mStationarySensorRegistered) {
            return;
        }
        mSensorManager.cancelTriggerSensor(mStationaryListener, mStationarySensor);
        mStationarySensorRegistered = false;
    }

    private boolean lightSensorSupportsCurrentPosture() {
        return mLightSensorOptional != null
                && mDevicePosture < mLightSensorOptional.length;
    }

    private boolean isLightSensorPresent() {
        if (!lightSensorSupportsCurrentPosture()) {
            return mLightSensorOptional != null && mLightSensorOptional[0].isPresent();
        }

        return mLightSensorOptional[mDevicePosture].isPresent();
    }

    private Sensor getLightSensor() {
        if (!lightSensorSupportsCurrentPosture()) {
            return null;
        }

        return mLightSensorOptional[mDevicePosture].get();
    }

    private int computeScrimOpacity(int sensorValue) {
        if (sensorValue < 0 || sensorValue >= mSensorToScrimOpacity.length) {
            return -1;
        }
        int wallpaperScrimOpacity = -1;
        if (!SceneContainerFlag.isEnabled()) {
            if (mWallpaperSupportsAmbientMode && sensorValue
                    < mSensorToWallpaperScrimOpacity.length) {
                wallpaperScrimOpacity = mSensorToWallpaperScrimOpacity[sensorValue];
            }
        }
        return max(wallpaperScrimOpacity, mSensorToScrimOpacity[sensorValue]);
    }

    private int computeWallpaperScrimOpacity(int sensorValue) {
        if (!SceneContainerFlag.isEnabled()
                || !mWallpaperSupportsAmbientMode
                || sensorValue < 0
                || sensorValue >= mSensorToWallpaperScrimOpacity.length) {
            return -1;
        }
        return mSensorToWallpaperScrimOpacity[sensorValue];
    }

    private float computeBrightness(int sensorValue) {
        if (sensorValue < 0 || sensorValue >= mSensorToBrightness.length) {
            return -1;
        }
        int brightnessBucket = sensorValue;
        if (mSensorToBrightness.length == 2
                && sensorValue == 0
                && mPickupBrightnessBoosted
                && isPickupBrightnessBoostEnabled()) {
            brightnessBucket = 1;
        }
        float brightness = mSensorToBrightness[brightnessBucket];
        if (mSensorToBrightness.length == 2) {
            int defaultValue = BrightnessSynchronizer.brightnessFloatToInt(brightness);
            int value = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    brightnessBucket == 0 ? AOD_LOW_BRIGHTNESS : AOD_HIGH_BRIGHTNESS,
                    defaultValue,
                    UserHandle.USER_CURRENT);
            brightness = BrightnessSynchronizer.brightnessIntToFloat(
                    Math.max(1, Math.min(255, value)));
        }
        return brightness;
    }

    private boolean isPickupBrightnessBoostEnabled() {
        return Settings.System.getIntForUser(
                mContext.getContentResolver(),
                AOD_PICKUP_BRIGHTNESS_BOOST,
                0,
                UserHandle.USER_CURRENT) != 0;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void resetBrightnessToDefault() {
        mDozeService.setDozeScreenBrightness(clampToDimBrightnessForScreenOff(
                clampToUserSettingOrAutoBrightness(mDefaultDozeBrightness)));
        mDozeHost.setAodDimmingScrim(0f);
        mDozeHost.setAodWallpaperDimmingScrim(0f);
    }

    private float clampToUserSetting(float brightness) {
        int screenBrightnessModeSetting = mSystemSettings.getIntForUser(
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT);
        if (screenBrightnessModeSetting == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            return brightness;
        }

        return Math.min(brightness, getScreenBrightness());
    }

    private float clampToUserSettingOrAutoBrightness(float brightness) {
        return Math.min(brightness, getScreenBrightness());
    }

    /**
     * Gets the current screen brightness that may have been set by manually by the user
     * or by autobrightness.
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    private float getScreenBrightness() {
        return mDisplayManager.getBrightness(Display.DEFAULT_DISPLAY);
    }

    /**
     * Clamp the brightness to the dim brightness value used by PowerManagerService just before the
     * device times out and goes to sleep, if we are sleeping from a timeout. This ensures that we
     * don't raise the brightness back to the user setting before or during the screen off
     * animation.
     */
    private float clampToDimBrightnessForScreenOff(float brightness) {
        final boolean screenTurningOff =
                (mDozeParameters.shouldClampToDimBrightness()
                        || mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_GOING_TO_SLEEP)
                && mState == DozeMachine.State.INITIALIZED;
        if (screenTurningOff
                && mWakefulnessLifecycle.getLastSleepReason() == GO_TO_SLEEP_REASON_TIMEOUT) {
            return Math.max(
                    PowerManager.BRIGHTNESS_MIN,
                    // Use the lower of either the dim brightness, or the current brightness reduced
                    // by the minimum dim amount. This is the same logic used in
                    // DisplayPowerController#updatePowerState to apply a minimum dim amount.
                    Math.min(brightness - mScreenBrightnessMinimumDimAmount, mScreenBrightnessDim));
        } else {
            return brightness;
        }
    }

    private void setLightSensorEnabled(boolean enabled) {
        if (enabled && !mRegistered && isLightSensorPresent()) {
            // Wait until we get an event from the sensor until indicating ready.
            mRegistered = mSensorManager.registerListener(this, getLightSensor(),
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            mLastSensorValue = -1;
            requestPickupSensor();
        } else if (!enabled && mRegistered) {
            mSensorManager.unregisterListener(this);
            mRegistered = false;
            mLastSensorValue = -1;
            cancelPickupSensor();
            finishPickupBrightnessBoost();
            // Sensor is not enabled, hence we use the default brightness and are always ready.
        }
    }

    private void setPaused(boolean paused) {
        if (mPaused != paused) {
            mPaused = paused;
            updateBrightnessAndReady(false /* force */);
        }
    }

    private void setScreenOff(boolean screenOff) {
        if (mScreenOff != screenOff) {
            mScreenOff = screenOff;
            updateBrightnessAndReady(true /* force */);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        mDebugBrightnessBucket = intent.getIntExtra(BRIGHTNESS_BUCKET, -1);
        updateBrightnessAndReady(false /* force */);
    }

    private void startListeningForWallpaperSupportsAmbientMode() {
        if (mWallpaperSupportsAmbientModeJob != null) return;
        mWallpaperSupportsAmbientModeJob = JavaAdapterKt.collectFlow(
                mScope,
                mScope.getCoroutineContext(),
                mWallpaperInteractor.getWallpaperSupportsAmbientMode(),
                supportsAmbientMode -> mWallpaperSupportsAmbientMode = supportsAmbientMode
        );
    }

    private void stopListeningForWallpaperSupportsAmbientMode() {
        if (mWallpaperSupportsAmbientModeJob == null) return;
        mWallpaperSupportsAmbientModeJob.cancel(new CancellationException("Stop monitoring"));
        mWallpaperSupportsAmbientModeJob = null;
    }

    /** Dump current state */
    public void dump(PrintWriter pw) {
        pw.println("DozeScreenBrightness:");
        IndentingPrintWriter idpw = new IndentingPrintWriter(pw);
        idpw.increaseIndent();
        idpw.println("registered=" + mRegistered);
        idpw.println("posture=" + DevicePostureController.devicePostureToString(mDevicePosture));
        idpw.println("sensorToBrightness=" + Arrays.toString(mSensorToBrightness));
        idpw.println("sensorToScrimOpacity=" + Arrays.toString(mSensorToScrimOpacity));
        idpw.println("sensorToWallpaperScrimOpacity="
                + Arrays.toString(mSensorToWallpaperScrimOpacity));
        idpw.println("screenBrightnessDim=" + mScreenBrightnessDim);
        idpw.println("mDefaultDozeBrightness=" + mDefaultDozeBrightness);
        idpw.println("mLastSensorValue=" + mLastSensorValue);
    }

    private final DevicePostureController.Callback mDevicePostureCallback =
            new DevicePostureController.Callback() {
        @Override
        public void onPostureChanged(int posture) {
            if (mDevicePosture == posture
                    || mLightSensorOptional.length < 2
                    || posture >= mLightSensorOptional.length) {
                return;
            }
            Sensor oldSensor = mLightSensorOptional[mDevicePosture].orElse(null);
            Sensor newSensor = mLightSensorOptional[posture].orElse(null);
            if (Objects.equals(oldSensor, newSensor)) {
                mDevicePosture = posture;
                // uses the same sensor for the new posture
                return;
            }

            // cancel the previous sensor:
            if (mRegistered) {
                setLightSensorEnabled(false);
                mDevicePosture = posture;
                setLightSensorEnabled(true);
            } else {
                mDevicePosture = posture;
            }
            mDozeLog.tracePostureChanged(mDevicePosture, "DozeScreenBrightness swap "
                    + "{" + oldSensor + "} => {" + newSensor + "}, mRegistered=" + mRegistered);
        }
    };
}
