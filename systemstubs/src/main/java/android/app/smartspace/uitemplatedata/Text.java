package android.app.smartspace.uitemplatedata;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/** Compile-time stub. A text run inside a template card. */
public final class Text implements Parcelable {

    public CharSequence getText() {
        throw new RuntimeException("Stub!");
    }

    public TextUtils.TruncateAt getTruncateAtType() {
        throw new RuntimeException("Stub!");
    }

    public int getMaxLines() {
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

    public static final Creator<Text> CREATOR = null;

    public static final class Builder {
        public Builder(CharSequence text) {
            throw new RuntimeException("Stub!");
        }

        public Builder setTruncateAtType(TextUtils.TruncateAt truncateAtType) {
            throw new RuntimeException("Stub!");
        }

        public Builder setMaxLines(int maxLines) {
            throw new RuntimeException("Stub!");
        }

        public Text build() {
            throw new RuntimeException("Stub!");
        }
    }
}
