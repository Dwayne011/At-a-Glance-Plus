package android.app.smartspace;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

/** Compile-time stub. Identifies one client session (one surface of one client). */
public final class SmartspaceSessionId implements Parcelable {

    public SmartspaceSessionId(String id, UserHandle userHandle) {
        throw new RuntimeException("Stub!");
    }

    public String getId() {
        throw new RuntimeException("Stub!");
    }

    public UserHandle getUserHandle() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public int describeContents() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        throw new RuntimeException("Stub!");
    }

    public static final Creator<SmartspaceSessionId> CREATOR = null;
}
