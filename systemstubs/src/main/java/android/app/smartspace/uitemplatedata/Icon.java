package android.app.smartspace.uitemplatedata;

import android.os.Parcel;
import android.os.Parcelable;

/** Compile-time stub. An icon inside a template card. */
public final class Icon implements Parcelable {

    public android.graphics.drawable.Icon getIcon() {
        throw new RuntimeException("Stub!");
    }

    public CharSequence getContentDescription() {
        throw new RuntimeException("Stub!");
    }

    public boolean shouldTint() {
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

    public static final Creator<Icon> CREATOR = null;

    public static final class Builder {
        public Builder(android.graphics.drawable.Icon icon) {
            throw new RuntimeException("Stub!");
        }

        public Builder setContentDescription(CharSequence contentDescription) {
            throw new RuntimeException("Stub!");
        }

        public Builder setShouldTint(boolean shouldTint) {
            throw new RuntimeException("Stub!");
        }

        public Icon build() {
            throw new RuntimeException("Stub!");
        }
    }
}
