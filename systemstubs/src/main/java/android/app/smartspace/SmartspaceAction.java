package android.app.smartspace;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

/**
 * Compile-time stub. The legacy (pre-template) description of a card: title, subtitle, icon and
 * what happens on tap. Still read by the lock screen and always-on display, so every target
 * carries one even when template data or RemoteViews are also set.
 */
public final class SmartspaceAction implements Parcelable {

    public String getId() {
        throw new RuntimeException("Stub!");
    }

    public Icon getIcon() {
        throw new RuntimeException("Stub!");
    }

    public CharSequence getTitle() {
        throw new RuntimeException("Stub!");
    }

    public CharSequence getSubtitle() {
        throw new RuntimeException("Stub!");
    }

    public CharSequence getContentDescription() {
        throw new RuntimeException("Stub!");
    }

    public PendingIntent getPendingIntent() {
        throw new RuntimeException("Stub!");
    }

    public Intent getIntent() {
        throw new RuntimeException("Stub!");
    }

    public UserHandle getUserHandle() {
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
    public void writeToParcel(Parcel out, int flags) {
        throw new RuntimeException("Stub!");
    }

    public static final Creator<SmartspaceAction> CREATOR = null;

    public static final class Builder {
        public Builder(String id, String title) {
            throw new RuntimeException("Stub!");
        }

        public Builder setIcon(Icon icon) {
            throw new RuntimeException("Stub!");
        }

        public Builder setSubtitle(CharSequence subtitle) {
            throw new RuntimeException("Stub!");
        }

        public Builder setContentDescription(CharSequence contentDescription) {
            throw new RuntimeException("Stub!");
        }

        public Builder setPendingIntent(PendingIntent pendingIntent) {
            throw new RuntimeException("Stub!");
        }

        public Builder setUserHandle(UserHandle userHandle) {
            throw new RuntimeException("Stub!");
        }

        public Builder setIntent(Intent intent) {
            throw new RuntimeException("Stub!");
        }

        public Builder setExtras(Bundle extras) {
            throw new RuntimeException("Stub!");
        }

        public SmartspaceAction build() {
            throw new RuntimeException("Stub!");
        }
    }
}
