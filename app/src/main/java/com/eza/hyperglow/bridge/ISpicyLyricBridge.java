package com.eza.hyperglow.bridge;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

/**
 * Java equivalent of the small oneway AIDL contract in src/main/aidl. Keeping the generated
 * contract in source makes the Termux ARM64 build independent of the host-only Android aidl
 * executable while preserving the wire format used by HyperGlow.
 */
public interface ISpicyLyricBridge extends IInterface {
    void publishState(Bundle state) throws RemoteException;
    void publishDocument(Bundle metadata, ParcelFileDescriptor document) throws RemoteException;
    void clearState(String producerId, long generation) throws RemoteException;

    abstract class Stub extends Binder implements ISpicyLyricBridge {
        private static final String DESCRIPTOR = "com.eza.hyperglow.bridge.ISpicyLyricBridge";
        private static final int TRANSACTION_publishState = IBinder.FIRST_CALL_TRANSACTION;
        private static final int TRANSACTION_publishDocument = IBinder.FIRST_CALL_TRANSACTION + 1;
        private static final int TRANSACTION_clearState = IBinder.FIRST_CALL_TRANSACTION + 2;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ISpicyLyricBridge asInterface(IBinder binder) {
            if (binder == null) return null;
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            return local instanceof ISpicyLyricBridge
                    ? (ISpicyLyricBridge) local : new Proxy(binder);
        }

        @Override public IBinder asBinder() {
            return this;
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DESCRIPTOR);
                return true;
            }
            data.enforceInterface(DESCRIPTOR);
            switch (code) {
                case TRANSACTION_publishState:
                    publishState(data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null);
                    return true;
                case TRANSACTION_publishDocument:
                    Bundle metadata = data.readInt() != 0
                            ? Bundle.CREATOR.createFromParcel(data) : null;
                    ParcelFileDescriptor document = data.readInt() != 0
                            ? ParcelFileDescriptor.CREATOR.createFromParcel(data) : null;
                    publishDocument(metadata, document);
                    return true;
                case TRANSACTION_clearState:
                    clearState(data.readString(), data.readLong());
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static final class Proxy implements ISpicyLyricBridge {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override public IBinder asBinder() {
                return remote;
            }

            @Override public void publishState(Bundle state) throws RemoteException {
                Parcel data = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    if (state == null) data.writeInt(0);
                    else { data.writeInt(1); state.writeToParcel(data, 0); }
                    remote.transact(TRANSACTION_publishState, data, null, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }

            @Override public void publishDocument(Bundle metadata, ParcelFileDescriptor document)
                    throws RemoteException {
                Parcel data = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    if (metadata == null) data.writeInt(0);
                    else { data.writeInt(1); metadata.writeToParcel(data, 0); }
                    if (document == null) data.writeInt(0);
                    else { data.writeInt(1); document.writeToParcel(data, 0); }
                    remote.transact(TRANSACTION_publishDocument, data, null, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }

            @Override public void clearState(String producerId, long generation)
                    throws RemoteException {
                Parcel data = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(producerId);
                    data.writeLong(generation);
                    remote.transact(TRANSACTION_clearState, data, null, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }
        }
    }
}
