/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Oplus permission cache entry used by the stock security_permission service.
 *
 * @hide
 */
public class PackagePermission implements Parcelable {
    public int mId;
    public String mPackageName;
    public long mAccept;
    public long mReject;
    public long mPrompt;
    public int mTrust;

    public PackagePermission() {
    }

    protected PackagePermission(Parcel in) {
        readFromParcel(in);
    }

    public PackagePermission copy() {
        PackagePermission copy = new PackagePermission();
        copy.mId = mId;
        copy.mPackageName = mPackageName;
        copy.mAccept = mAccept;
        copy.mReject = mReject;
        copy.mPrompt = mPrompt;
        copy.mTrust = mTrust;
        return copy;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public void readFromParcel(Parcel in) {
        mId = in.readInt();
        mPackageName = in.readString();
        mAccept = in.readLong();
        mReject = in.readLong();
        mPrompt = in.readLong();
        mTrust = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mId);
        out.writeString(mPackageName);
        out.writeLong(mAccept);
        out.writeLong(mReject);
        out.writeLong(mPrompt);
        out.writeInt(mTrust);
    }

    @Override
    public String toString() {
        return "[mPackageName=" + mPackageName
                + ", mAccept=" + mAccept
                + ", mReject=" + mReject
                + ", mPrompt=" + mPrompt
                + ", mTrust=" + mTrust + "]";
    }

    public static final Parcelable.Creator<PackagePermission> CREATOR =
            new Parcelable.Creator<PackagePermission>() {
                @Override
                public PackagePermission createFromParcel(Parcel in) {
                    return new PackagePermission(in);
                }

                @Override
                public PackagePermission[] newArray(int size) {
                    return new PackagePermission[size];
                }
            };
}
