/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.server;

import android.content.Context;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.internal.os.SystemServerClassLoaderFactory;

public final class OplusSecurityPermissionLifecycle extends SystemService {
    private static final String TAG = "OplusSecurityPermissionLifecycle";
    private static final String SERVICE_NAME = "security_permission";
    private static final String SERVICE_CLASS =
            "com.android.server.oplus.SecurityPermissionService";
    private static final String SERVICE_JAR = "/system/framework/oplus-services.jar";

    private Object mService;

    public OplusSecurityPermissionLifecycle(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        ensureStarted("onStart");
    }

    private void ensureStarted(String reason) {
        if (mService != null) {
            return;
        }
        try {
            Slog.w(TAG, "Starting " + SERVICE_CLASS + " from " + reason);
            if (ServiceManager.checkService(SERVICE_NAME) != null) {
                Slog.i(TAG, SERVICE_NAME + " already registered");
                return;
            }

            ClassLoader loader = SystemServerClassLoaderFactory.getOrCreateClassLoader(
                    SERVICE_JAR, getClass().getClassLoader(), false);
            Class<?> serviceClass = Class.forName(SERVICE_CLASS, true, loader);
            mService = serviceClass.getConstructor(Context.class).newInstance(getContext());
            IBinder binder = (IBinder) serviceClass.getMethod("asBinder").invoke(mService);
            publishBinderService(SERVICE_NAME, binder);
            Slog.i(TAG, "Registered " + SERVICE_CLASS);
        } catch (ClassNotFoundException e) {
            Slog.w(TAG, SERVICE_CLASS + " is not present in " + SERVICE_JAR, e);
        } catch (Throwable e) {
            Slog.wtf(TAG, "Failed to start " + SERVICE_CLASS, e);
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            ensureStarted("boot phase " + phase);
        }
        if (phase != PHASE_THIRD_PARTY_APPS_CAN_START || mService == null) {
            return;
        }

        try {
            mService.getClass().getMethod("systemRunning").invoke(mService);
            Slog.i(TAG, "Notified " + SERVICE_CLASS + " systemRunning");
        } catch (NoSuchMethodException e) {
            Slog.i(TAG, SERVICE_CLASS + " has no systemRunning hook");
        } catch (Throwable e) {
            Slog.wtf(TAG, "Failed to notify " + SERVICE_CLASS + " systemRunning", e);
        }
    }
}
