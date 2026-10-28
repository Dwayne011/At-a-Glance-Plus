package android.app.smartspace.uitemplatedata;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

/** Compile-time stub. What happens when part of a template card is tapped. */
public final class TapAction implements Parcelable {

    public CharSequence getId() {
        throw new RuntimeException("Stub!");
    }

    public Intent getIntent() {
        throw new RuntimeException("Stub!");
    }

    public PendingIntent getPendingIntent() {
        throw new RuntimeException("Stub!");
    }

    public UserHandle getUserHandle() {
        throw new RuntimeException("Stub!");
    }

    public Bundle getExtras() {
        throw new RuntimeException("Stub!");
    }

    public boolean shouldShowOnLockscreen() {
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

    public static final Creator<TapAction> CREATOR = null;

    public static final class Builder {
        public Builder(CharSequence id) {
            throw new RuntimeException("Stub!");
        }

        public Builder setIntent(Intent intent) {
            throw new RuntimeException("Stub!");
        }

        public Builder setPendingIntent(PendingIntent pendingIntent) {
            throw new RuntimeException("Stub!");
        }

        public Builder setUserHandle(UserHandle userHandle) {
            throw new RuntimeException("Stub!");
        }

        public Builder setExtras(Bundle extras) {
            throw new RuntimeException("Stub!");
        }

        public Builder setShouldShowOnLockscreen(boolean shouldShowOnLockScreen) {
            throw new RuntimeException("Stub!");
        }

        public TapAction build() {
            throw new RuntimeException("Stub!");
        }
    }
}
