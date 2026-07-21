package com.android.server.wm;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;

import com.oplus.app.IOplusAccessControlManager;
import com.oplus.app.IOplusAccessControlObserver;
import com.oplus.app.OplusAccessControlInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OplusAccessControlManagerService extends IOplusAccessControlManager.Stub {
    private static final String TAG = "OplusAccessControlManagerService";

    private static final String TYPE_ENCRYPT = "type_encrypt";
    private static final String TYPE_HIDE = "type_hide";
    private static final String KEY_ENABLED_PREFIX = "oplus_access_control_enabled_";
    private static final String KEY_APPS_PREFIX = "oplus_access_control_apps_";
    private static final String KEY_PASS_PREFIX = "oplus_access_control_pass_";

    private final Context mContext;
    private final Object mLock = new Object();
    private final ArrayMap<Integer, UserState> mUserStates = new ArrayMap<>();
    private final RemoteCallbackList<IOplusAccessControlObserver> mObservers =
            new RemoteCallbackList<>();

    public OplusAccessControlManagerService(Context context) {
        mContext = context;
    }

    public void onSystemReady() {
        final Uri encryptUri = Settings.Secure.getUriFor(KEY_ENABLED_PREFIX + TYPE_ENCRYPT);
        final Uri hideUri = Settings.Secure.getUriFor(KEY_ENABLED_PREFIX + TYPE_HIDE);
        mContext.getContentResolver().registerContentObserver(encryptUri, false,
                new SettingsObserver(new Handler()));
        mContext.getContentResolver().registerContentObserver(hideUri, false,
                new SettingsObserver(new Handler()));
        Slog.i(TAG, "OplusAccessControlManagerService ready");
    }

    @Override
    public void setAccessControlAppsInfo(String type, Map accessControlInfo, int userId) {
        final String normalizedType = normalizeType(type);
        if (normalizedType == null) {
            Slog.w(TAG, "setAccessControlAppsInfo type mismatch: " + type);
            return;
        }
        final HashMap<String, Integer> apps = sanitizeMap(accessControlInfo);
        synchronized (mLock) {
            getUserStateLocked(userId).apps.put(normalizedType, apps);
            persistAppsLocked(normalizedType, userId, apps);
        }
        notifyAccessControlStateChanged(normalizedType, apps, userId);
    }

    @Override
    public Map getAccessControlAppsInfo(String type, int userId) {
        final String normalizedType = normalizeType(type);
        if (normalizedType == null) {
            Slog.w(TAG, "getAccessControlAppsInfo type mismatch: " + type);
            return new HashMap<String, Integer>();
        }
        synchronized (mLock) {
            final HashMap<String, Integer> apps = getUserStateLocked(userId).apps.get(normalizedType);
            return apps == null ? new HashMap<String, Integer>() : new HashMap<>(apps);
        }
    }

    @Override
    public void setAccessControlEnabled(String type, boolean enable, int userId) {
        final String normalizedType = normalizeType(type);
        if (normalizedType == null) {
            Slog.w(TAG, "setAccessControlEnabled type mismatch: " + type);
            return;
        }
        synchronized (mLock) {
            getUserStateLocked(userId).enabled.put(normalizedType, enable);
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    KEY_ENABLED_PREFIX + normalizedType, enable ? 1 : 0, userId);
        }
        notifyAccessControlEnableChanged(normalizedType, enable, userId);
    }

    @Override
    public boolean getAccessControlEnabled(String type, int userId) {
        final String normalizedType = normalizeType(type);
        if (normalizedType == null) {
            Slog.w(TAG, "getAccessControlEnabled type mismatch: " + type);
            return false;
        }
        synchronized (mLock) {
            final Boolean enabled = getUserStateLocked(userId).enabled.get(normalizedType);
            return enabled != null && enabled;
        }
    }

    @Override
    public void addEncryptPass(String packageName, int windowMode, int userId) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        synchronized (mLock) {
            final UserState state = getUserStateLocked(userId);
            state.encryptPassPackages.add(packageName);
            persistPassPackagesLocked(userId, state.encryptPassPackages);
        }
    }

    @Override
    public boolean isEncryptPass(String packageName, int userId) {
        if (packageName == null) {
            return false;
        }
        synchronized (mLock) {
            return getUserStateLocked(userId).encryptPassPackages.contains(packageName);
        }
    }

    @Override
    public boolean isEncryptedPackage(String packageName, int userId) {
        if (packageName == null) {
            return false;
        }
        synchronized (mLock) {
            final Integer value = getUserStateLocked(userId).apps.get(TYPE_ENCRYPT).get(packageName);
            return value != null && value != 0;
        }
    }

    @Override
    public boolean registerAccessControlObserver(String type, IOplusAccessControlObserver observer) {
        final String normalizedType = normalizeType(type);
        if (normalizedType == null || observer == null) {
            return false;
        }
        return mObservers.register(observer, normalizedType);
    }

    @Override
    public boolean unregisterAccessControlObserver(String type, IOplusAccessControlObserver observer) {
        if (observer == null) {
            return false;
        }
        return mObservers.unregister(observer);
    }

    @Override
    public void updateRusList(int type, List<String> addList, List<String> deleteList) {
        Slog.d(TAG, "updateRusList type=" + type);
    }

    private UserState getUserStateLocked(int userId) {
        UserState state = mUserStates.get(userId);
        if (state == null) {
            state = new UserState(userId);
            state.enabled.put(TYPE_ENCRYPT, Settings.Secure.getIntForUser(
                    mContext.getContentResolver(), KEY_ENABLED_PREFIX + TYPE_ENCRYPT, 0, userId) != 0);
            state.enabled.put(TYPE_HIDE, Settings.Secure.getIntForUser(
                    mContext.getContentResolver(), KEY_ENABLED_PREFIX + TYPE_HIDE, 0, userId) != 0);
            state.apps.put(TYPE_ENCRYPT, readApps(TYPE_ENCRYPT, userId));
            state.apps.put(TYPE_HIDE, readApps(TYPE_HIDE, userId));
            state.encryptPassPackages.addAll(readStringSet(KEY_PASS_PREFIX, userId));
            mUserStates.put(userId, state);
        }
        return state;
    }

    private HashMap<String, Integer> readApps(String type, int userId) {
        final HashMap<String, Integer> result = new HashMap<>();
        final String raw = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), KEY_APPS_PREFIX + type, userId);
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        final String[] entries = raw.split(";");
        for (String entry : entries) {
            final int split = entry.lastIndexOf('=');
            if (split <= 0 || split == entry.length() - 1) {
                continue;
            }
            try {
                result.put(entry.substring(0, split), Integer.parseInt(entry.substring(split + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private Set<String> readStringSet(String prefix, int userId) {
        final HashSet<String> result = new HashSet<>();
        final String raw = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), prefix + userId, userId);
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String entry : raw.split(";")) {
            if (!entry.isEmpty()) {
                result.add(entry);
            }
        }
        return result;
    }

    private void persistAppsLocked(String type, int userId, Map<String, Integer> apps) {
        final StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Integer> entry : apps.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                KEY_APPS_PREFIX + type, builder.toString(), userId);
    }

    private void persistPassPackagesLocked(int userId, Set<String> packages) {
        final StringBuilder builder = new StringBuilder();
        for (String packageName : packages) {
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(packageName);
        }
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                KEY_PASS_PREFIX + userId, builder.toString(), userId);
    }

    private void notifyAccessControlStateChanged(String type, Map<String, Integer> apps, int userId) {
        final int count = mObservers.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                if (!type.equals(mObservers.getBroadcastCookie(i))) {
                    continue;
                }
                for (Map.Entry<String, Integer> entry : apps.entrySet()) {
                    final OplusAccessControlInfo info = new OplusAccessControlInfo();
                    info.mName = entry.getKey();
                    info.userId = userId;
                    info.isEncrypted = TYPE_ENCRYPT.equals(type) && entry.getValue() != 0;
                    info.isHideIcon = TYPE_HIDE.equals(type) && entry.getValue() != 0;
                    try {
                        if (TYPE_HIDE.equals(type)) {
                            mObservers.getBroadcastItem(i).onHideStateChange(info);
                        } else {
                            mObservers.getBroadcastItem(i).onEncryptStateChange(info);
                        }
                    } catch (RemoteException ignored) {
                    }
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
    }

    private void notifyAccessControlEnableChanged(String type, boolean enabled, int userId) {
        final int count = mObservers.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                if (!type.equals(mObservers.getBroadcastCookie(i))) {
                    continue;
                }
                final OplusAccessControlInfo info = new OplusAccessControlInfo();
                info.userId = userId;
                info.isEncrypted = TYPE_ENCRYPT.equals(type) && enabled;
                info.isHideIcon = TYPE_HIDE.equals(type) && enabled;
                try {
                    if (TYPE_HIDE.equals(type)) {
                        mObservers.getBroadcastItem(i).onHideEnableChange(enabled);
                    } else {
                        mObservers.getBroadcastItem(i).onEncryptEnableChange(enabled);
                    }
                } catch (RemoteException ignored) {
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
    }

    private static String normalizeType(String type) {
        if (TYPE_ENCRYPT.equals(type) || "type_encrypt_ignore_enable".equals(type)) {
            return TYPE_ENCRYPT;
        }
        if (TYPE_HIDE.equals(type) || "type_hide_ignore_enable".equals(type)) {
            return TYPE_HIDE;
        }
        return null;
    }

    private static HashMap<String, Integer> sanitizeMap(Map accessControlInfo) {
        final HashMap<String, Integer> result = new HashMap<>();
        if (accessControlInfo == null) {
            return result;
        }
        for (Object item : accessControlInfo.entrySet()) {
            if (!(item instanceof Map.Entry)) {
                continue;
            }
            final Map.Entry entry = (Map.Entry) item;
            if (entry.getKey() instanceof String && entry.getValue() instanceof Integer) {
                result.put((String) entry.getKey(), (Integer) entry.getValue());
            }
        }
        return result;
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mLock) {
                mUserStates.clear();
            }
        }
    }

    private static final class UserState {
        final int userId;
        final ArrayMap<String, Boolean> enabled = new ArrayMap<>();
        final ArrayMap<String, HashMap<String, Integer>> apps = new ArrayMap<>();
        final Set<String> encryptPassPackages = new HashSet<>();

        UserState(int userId) {
            this.userId = userId;
        }
    }
}
