/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.server.am;

import android.common.IOplusCommonFeature;
import android.common.OplusFeatureList;
import android.content.pm.PackagePermission;
import android.os.Bundle;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.Map;

/**
 * OPlus security permission extension entry point used by oplus-services.jar.
 *
 * @hide
 */
public interface IOplusSecurityPermissionManager extends IOplusCommonFeature {
    IOplusSecurityPermissionManager DEFAULT = new IOplusSecurityPermissionManager() {
    };

    @Override
    default OplusFeatureList.OplusIndex index() {
        return OplusFeatureList.OplusIndex.IOplusSecurityPermissionManager;
    }

    @Override
    default IOplusCommonFeature getDefault() {
        return DEFAULT;
    }

    default boolean checkOplusPermission(String permission, int pid, int uid) {
        return true;
    }

    default int queryPermissionAsUser(String pkgName, String permissionName, int userId) {
        return 0;
    }

    default PackagePermission queryPackagePermissionsAsUser(String pkgName, int userId) {
        PackagePermission permission = new PackagePermission();
        permission.mPackageName = pkgName;
        return permission;
    }

    default void updateCachedPermission(String pkgName, int userId, boolean delete) {
    }

    default void dump(PrintWriter pw, String[] args) {
        pw.println("IOplusSecurityPermissionManager: default");
    }

    default void putActivityStartWhiteList(Bundle bundle) {
    }

    default void putRiskAppList(Bundle bundle) {
    }

    default Map getPermissionFlagsForPackageAsUser(
            String packageName, String[] permissionNames, int userId) {
        return Collections.emptyMap();
    }

    default void updatePermissionFlagsForPackagesAsUser(
            int flagMask, Bundle flagValues, int userId) {
    }

    default boolean isEcmRestricted(String packageName, String permissionName) {
        return false;
    }

    default void showEcmDialogInApp(String packageName, String permissionName) {
    }

    default boolean addExemptFromAudioRecordRestrictions(String packageName, int uid) {
        return false;
    }

    default void removeExemptFromAudioRecordRestrictions() {
    }

    default boolean addCooperativeGames(Bundle bundle) {
        return false;
    }

    default boolean removeCooperativeGames(Bundle bundle) {
        return false;
    }

    default Bundle getCooperativeGames() {
        return Bundle.EMPTY;
    }
}
