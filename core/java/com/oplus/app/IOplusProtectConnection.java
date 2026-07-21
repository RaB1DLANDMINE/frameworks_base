package com.oplus.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface IOplusProtectConnection extends IInterface {
    String DESCRIPTOR = "com.oplus.app.IOplusProtectConnection";

    void onError(int errorCode) throws RemoteException;

    void onSuccess() throws RemoteException;

    void onTimeout() throws RemoteException;

    class Default implements IOplusProtectConnection {
        @Override
        public void onSuccess() throws RemoteException {
        }

        @Override
        public void onError(int errorCode) throws RemoteException {
        }

        @Override
        public void onTimeout() throws RemoteException {
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }

    abstract class Stub extends Binder implements IOplusProtectConnection {
        static final int TRANSACTION_onSuccess = 1;
        static final int TRANSACTION_onError = 2;
        static final int TRANSACTION_onTimeout = 3;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IOplusProtectConnection asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IOplusProtectConnection) {
                return (IOplusProtectConnection) iin;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        public static String getDefaultTransactionName(int transactionCode) {
            switch (transactionCode) {
                case TRANSACTION_onSuccess:
                    return "onSuccess";
                case TRANSACTION_onError:
                    return "onError";
                case TRANSACTION_onTimeout:
                    return "onTimeout";
                default:
                    return null;
            }
        }

        public String getTransactionName(int transactionCode) {
            return getDefaultTransactionName(transactionCode);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= 0x00ffffff) {
                data.enforceInterface(DESCRIPTOR);
            }
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            switch (code) {
                case TRANSACTION_onSuccess:
                    onSuccess();
                    return true;
                case TRANSACTION_onError:
                    int errorCode = data.readInt();
                    data.enforceNoDataAvail();
                    onError(errorCode);
                    return true;
                case TRANSACTION_onTimeout:
                    onTimeout();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IOplusProtectConnection {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            public String getInterfaceDescriptor() {
                return DESCRIPTOR;
            }

            @Override
            public void onSuccess() throws RemoteException {
                Parcel data = Parcel.obtain(asBinder());
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_onSuccess, data, null, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }

            @Override
            public void onError(int errorCode) throws RemoteException {
                Parcel data = Parcel.obtain(asBinder());
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(errorCode);
                    mRemote.transact(TRANSACTION_onError, data, null, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }

            @Override
            public void onTimeout() throws RemoteException {
                Parcel data = Parcel.obtain(asBinder());
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_onTimeout, data, null, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }
        }

        public int getMaxTransactionId() {
            return TRANSACTION_onError;
        }
    }
}
