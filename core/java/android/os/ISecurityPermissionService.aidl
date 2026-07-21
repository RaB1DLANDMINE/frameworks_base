/* //device/java/android/android/os/ISecurityPermissionService.aidl
**
** Licensed under the Apache License, Version 2.0 (the "License");
*/

package android.os;

import android.content.pm.PackagePermission;
import android.os.Bundle;

import java.util.List;
import java.util.Map;

/** @hide */
interface ISecurityPermissionService {
    boolean checkOplusPermission(String permission, int pid, int uid);
    void basicTypes(int anInt, long aLong, boolean aBoolean, float aFloat, double aDouble,
            String aString);
    int queryPermissionAsUser(String pkgName, String permissionName, int userId);
    PackagePermission queryPackagePermissionsAsUser(String pkgName, int userId);
    void updateCachedPermission(String pkgName, int userId, boolean delete);
    void writeRecommendPermissions(String recommendBody, boolean fromLocal);
    Map readRecommendPermissions(String packageName);
    long getLastUpdateTime();
    void writeRecommendPermissionsWithMode(String recommendBody, int writeMode);
    Map readRecommendPermissionsAsUser(String packageName, int userId);
    void writeIgnoredPermissions(String data, String type);
    String readIgnoredPermissions(int userId);
    void putActivityStartWhiteList(in Bundle bundle);
    void putRiskAppList(in Bundle bundle);
    Map getBackgroundLocationUsage(String pkgName, int uid);
    void updateSensitiveApp(String sensitiveApp, boolean added, int userId);
    Map getPermissionFlagsForPackageAsUser(String packageName, in String[] permissionNames,
            int userId);
    void updatePermissionFlagsForPackagesAsUser(int flagMask, in Bundle flagValues, int userId);
    Map getPrivacyLabelVersions();
    Map getPrivacyLabelInfo(String pkgName, String groupName);
    boolean isEcmRestricted(String packageName, String permissionName);
    void showEcmDialogInApp(String packageName, String permissionName);
    String checkPackagesMaps(in List<String> packageNames, in Map sigMap);
    List<String> getPartRunningElfPaths();
    boolean addExemptFromAudioRecordRestrictions(String packageName, int uid);
    void removeExemptFromAudioRecordRestrictions();
    boolean addCooperativeGames(in Bundle bundle);
    boolean removeCooperativeGames(in Bundle bundle);
    Bundle getCooperativeGames();
}
