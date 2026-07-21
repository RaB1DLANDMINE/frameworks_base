/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package android.app;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import com.oplus.app.IOplusProtectConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal OPlus framework compatibility surface used by stock OP15 SystemUI
 * components. The real implementation lives in OxygenOS framework services.
 *
 * @hide
 */
public class OplusActivityManager {
    private static final OplusActivityManager INSTANCE = new OplusActivityManager();

    public OplusActivityManager() {}

    public static OplusActivityManager getInstance() {
        return INSTANCE;
    }

    public static List getFilteredTasks(int maxNum, boolean filterOnlyVisibleRecents) {
        return ActivityTaskManager.getInstance().getTasks(maxNum, filterOnlyVisibleRecents);
    }

    public void addBackgroundRestrictedInfo(String callerPkg, List targetPkgList) {}

    public void addPreventIndulgeList(List packageNames) {}

    public List getAllTopAppInfos() {
        return new ArrayList();
    }

    public List getAllTopApps() {
        return new ArrayList();
    }

    public Bundle getConfigInfo(String configName, int flag, int userId) throws RemoteException {
        return null;
    }

    public List<String> getGlobalPkgWhiteList(int type) throws RemoteException {
        return new ArrayList<>();
    }

    public List<String> getGlobalProcessWhiteList() throws RemoteException {
        return new ArrayList<>();
    }

    public List<String> getStageProtectListFromPkg(String callerPkg, int type)
            throws RemoteException {
        return new ArrayList<>();
    }

    public List<String> getStageProtectListFromPkgAsUser(String callerPkg, int type, int userId)
            throws RemoteException {
        return new ArrayList<>();
    }

    public List<String> getStageProtectList(int type) throws RemoteException {
        return new ArrayList<>();
    }

    public List<String> getStageProtectListAsUser(int type, int userId) throws RemoteException {
        return new ArrayList<>();
    }

    public void addStageProtectInfo(
            String callerPkg,
            String pkg,
            List<String> processList,
            String reason,
            long timeout,
            Object connection)
            throws RemoteException {}

    public void addStageProtectInfo(
            String callerPkg,
            String pkg,
            List<String> processList,
            String reason,
            long timeout,
            IOplusProtectConnection connection)
            throws RemoteException {}

    public void removeStageProtectInfo(String pkg, String callerPkg) throws RemoteException {}

    public List getTaskPkgList(int taskId) {
        List<String> packages = new ArrayList<>();
        for (ActivityManager.RunningTaskInfo task : ActivityTaskManager.getInstance().getTasks(10)) {
            if (task.taskId == taskId && task.topActivity != null) {
                packages.add(task.topActivity.getPackageName());
                break;
            }
        }
        return packages;
    }

    public ComponentName getTopActivityComponentName() {
        List<ActivityManager.RunningTaskInfo> tasks = ActivityTaskManager.getInstance().getTasks(1);
        if (!tasks.isEmpty() && tasks.get(0).topActivity != null) {
            return tasks.get(0).topActivity;
        }
        return new ComponentName("android", "android.app.Activity");
    }

    public void handleAppForNotification(String pkg, int uid, int action) {}

    public void handleAppFromControlCenter(String packageName, int uid) {}

    public boolean isAppCallRefuseMode() {
        return false;
    }

    public boolean putConfigInfo(String configName, Bundle bundle, int flag, int userId)
            throws RemoteException {
        return true;
    }

    public boolean requestDeviceFolded(int folded, boolean enableSecDisplay) {
        return false;
    }

    public void setAllowLaunchApps(List packageNames) {}

    public void setAppCallRefuseMode(boolean enabled) {}

    public void setAppStartMonitorController(Object controller) {}

    public void setChildSpaceMode(boolean enabled) {}

    public void setPreventIndulgeController(Object controller) {}

    public void startActivity(Intent intent) {}
}
