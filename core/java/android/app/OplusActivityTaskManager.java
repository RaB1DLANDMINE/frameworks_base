/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package android.app;

import android.content.ComponentName;
import android.os.RemoteException;
import android.view.SurfaceControl;
import java.util.List;

/**
 * Minimal OPlus framework compatibility surface used by stock OP15 SystemUI
 * components. The real implementation lives in OxygenOS framework services.
 *
 * @hide
 */
public class OplusActivityTaskManager {
    private static final OplusActivityTaskManager INSTANCE = new OplusActivityTaskManager();

    public static OplusActivityTaskManager getInstance() {
        return INSTANCE;
    }

    public ComponentName getTopActivityComponentName() throws RemoteException {
        List<ActivityManager.RunningTaskInfo> tasks = ActivityTaskManager.getInstance().getTasks(1);
        if (!tasks.isEmpty() && tasks.get(0).topActivity != null) {
            return tasks.get(0).topActivity;
        }
        return new ComponentName("android", "android.app.Activity");
    }

    public List<ActivityManager.RunningTaskInfo> getVisibleTasks(int displayId)
            throws RemoteException {
        return ActivityTaskManager.getInstance().getTasks(10, false, false, displayId);
    }

    public boolean registerTaskInfoChangeListener(Object listener, int displayId, int flags)
            throws RemoteException {
        return true;
    }

    public boolean unregisterTaskInfoChangeListener(Object listener) throws RemoteException {
        return true;
    }

    public void registerStartingWindowObserver(Object observer) {}

    public void unregisterStartingWindowObserver(Object observer) {}

    public SurfaceControl getTaskSurface(int taskId) {
        return null;
    }
}
