package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Compile-time stand-in only: this class is hidden from the SDK android.jar, but the AIDL-generated
 * IBluetooth references it, so javac needs the symbol. It is pruned from the shipped jar (all android.*
 * classes are); at run time the guest framework's own class is what loads. The service never creates
 * one — reportActivityInfo answers null — so no field layout is reproduced here.
 */
public final class BluetoothActivityEnergyInfo implements Parcelable {
    public static final Creator<BluetoothActivityEnergyInfo> CREATOR = new Creator<BluetoothActivityEnergyInfo>() {
        @Override public BluetoothActivityEnergyInfo createFromParcel(Parcel in) {
            return new BluetoothActivityEnergyInfo();
        }
        @Override public BluetoothActivityEnergyInfo[] newArray(int size) {
            return new BluetoothActivityEnergyInfo[size];
        }
    };

    public BluetoothActivityEnergyInfo() { }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { }
}
