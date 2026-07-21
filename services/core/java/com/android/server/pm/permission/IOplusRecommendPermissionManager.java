/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.server.pm.permission;

import android.common.IOplusCommonFeature;
import android.common.OplusFeatureList;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.Map;

/**
 * OPlus recommended-permission extension entry point used by oplus-services.jar.
 *
 * @hide
 */
public interface IOplusRecommendPermissionManager extends IOplusCommonFeature {
    IOplusRecommendPermissionManager DEFAULT = new IOplusRecommendPermissionManager() {
    };

    @Override
    default OplusFeatureList.OplusIndex index() {
        return OplusFeatureList.OplusIndex.IOplusRecommendPermissionManager;
    }

    @Override
    default IOplusCommonFeature getDefault() {
        return DEFAULT;
    }

    default void dump(PrintWriter pw, String[] args) {
        pw.println("IOplusRecommendPermissionManager: default");
    }

    default void writeRecommendPermissionsWithMode(String recommendBody, int writeMode) {
    }

    default void writeRecommendPermissions(String recommendBody, boolean fromLocal) {
    }

    default Map<String, String> readRecommendPermissions(String packageName) {
        return Collections.emptyMap();
    }

    default Map<String, String> readRecommendPermissionsAsUser(String packageName, int userId) {
        return Collections.emptyMap();
    }

    default void writeIgnoredPermissions(String data, String type) {
    }

    default String readIgnoredPermissions(int userId) {
        return "";
    }

    default long getLastUpdateTime() {
        return 0L;
    }

    default Map<String, Long> getPrivacyLabelVersions() {
        return Collections.emptyMap();
    }

    default Map<String, String> getPrivacyLabelInfo(String pkgName, String groupName) {
        return Collections.emptyMap();
    }
}
