package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Compile-time stand-in only: hidden from the SDK android.jar, but the AIDL-generated IBluetooth
 * (24+) references it. Pruned from the shipped jar; the guest framework's own class loads at run
 * time. The service never creates one — createBondOutOfBand refuses — so no field layout is
 * reproduced here.
 */
public final class OobData implements Parcelable {
    public static final Creator<OobData> CREATOR = new Creator<OobData>() {
        @Override public OobData createFromParcel(Parcel in) { return new OobData(); }
        @Override public OobData[] newArray(int size) { return new OobData[size]; }
    };

    public OobData() { }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { }
}
