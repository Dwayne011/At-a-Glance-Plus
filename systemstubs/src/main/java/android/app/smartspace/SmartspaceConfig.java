package android.app.smartspace;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** Compile-time stub. Describes what a client wants from a session. */
public final class SmartspaceConfig implements Parcelable {

    /** Package name of the client (launcher, SystemUI). */
    public String getPackageName() {
        throw new RuntimeException("Stub!");
    }

    /** How many targets the client wants at most. */
    public int getSmartspaceTargetCount() {
        throw new RuntimeException("Stub!");
    }

    /** "home", "lockscreen", "dream", "media_data_manager", "glanceable_hub", ... */
    public String getUiSurface() {
        throw new RuntimeException("Stub!");
    }

    public Bundle getExtras() {
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

    public static final Creator<SmartspaceConfig> CREATOR = null;

    public static final class Builder {
        public Builder(Context context, String uiSurface) {
            throw new RuntimeException("Stub!");
        }

        public Builder setSmartspaceTargetCount(int smartspaceTargetCount) {
            throw new RuntimeException("Stub!");
        }

        public Builder setExtras(Bundle extras) {
            throw new RuntimeException("Stub!");
        }

        public SmartspaceConfig build() {
            throw new RuntimeException("Stub!");
        }
    }
}
