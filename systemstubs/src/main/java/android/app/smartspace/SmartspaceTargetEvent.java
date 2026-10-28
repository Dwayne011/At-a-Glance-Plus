package android.app.smartspace;

import android.os.Parcel;
import android.os.Parcelable;

/** Compile-time stub. An event a client reports about a target. */
public final class SmartspaceTargetEvent implements Parcelable {

    public static final int EVENT_TARGET_INTERACTION = 1;
    public static final int EVENT_TARGET_SHOWN = 2;
    public static final int EVENT_TARGET_HIDDEN = 3;
    public static final int EVENT_TARGET_DISMISS = 4;
    public static final int EVENT_TARGET_BLOCK = 5;
    public static final int EVENT_UI_SURFACE_SHOWN = 6;
    public static final int EVENT_UI_SURFACE_HIDDEN = 7;

    public SmartspaceTarget getSmartspaceTarget() {
        throw new RuntimeException("Stub!");
    }

    public String getSmartspaceActionId() {
        throw new RuntimeException("Stub!");
    }

    public int getEventType() {
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

    public static final Creator<SmartspaceTargetEvent> CREATOR = null;

    public static final class Builder {
        public Builder(int eventType) {
            throw new RuntimeException("Stub!");
        }

        public Builder setSmartspaceTarget(SmartspaceTarget smartspaceTarget) {
            throw new RuntimeException("Stub!");
        }

        public Builder setSmartspaceActionId(String smartspaceActionId) {
            throw new RuntimeException("Stub!");
        }

        public SmartspaceTargetEvent build() {
            throw new RuntimeException("Stub!");
        }
    }
}
