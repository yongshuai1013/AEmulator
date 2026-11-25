package android.bluetooth.le;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Compile-time stand-in only: hidden from the SDK android.jar, but the AIDL-generated
 * IPeriodicAdvertisingCallback references it. Pruned from the shipped jar; the guest framework's
 * own class loads at run time. The service never creates one — periodic sync is refused in
 * registerSync — so no field layout is reproduced here.
 */
public final class PeriodicAdvertisingReport implements Parcelable {
    public static final Creator<PeriodicAdvertisingReport> CREATOR = new Creator<PeriodicAdvertisingReport>() {
        @Override public PeriodicAdvertisingReport createFromParcel(Parcel in) { return new PeriodicAdvertisingReport(); }
        @Override public PeriodicAdvertisingReport[] newArray(int size) { return new PeriodicAdvertisingReport[size]; }
    };

    public PeriodicAdvertisingReport() { }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { }
}
