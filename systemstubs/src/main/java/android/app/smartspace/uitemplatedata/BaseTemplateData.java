package android.app.smartspace.uitemplatedata;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Compile-time stub. Describes a card using one of Google's fixed layouts
 * (see the UI_TEMPLATE_* constants on SmartspaceTarget). The launcher draws it; the app only
 * supplies text, icons and tap actions for each slot.
 */
public class BaseTemplateData implements Parcelable {

    public int getTemplateType() {
        throw new RuntimeException("Stub!");
    }

    public SubItemInfo getPrimaryItem() {
        throw new RuntimeException("Stub!");
    }

    public SubItemInfo getSubtitleItem() {
        throw new RuntimeException("Stub!");
    }

    public SubItemInfo getSubtitleSupplementalItem() {
        throw new RuntimeException("Stub!");
    }

    public SubItemInfo getSupplementalLineItem() {
        throw new RuntimeException("Stub!");
    }

    public SubItemInfo getSupplementalAlarmItem() {
        throw new RuntimeException("Stub!");
    }

    public int getLayoutWeight() {
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

    public static final Creator<BaseTemplateData> CREATOR = null;

    public static class Builder {
        public Builder(int templateType) {
            throw new RuntimeException("Stub!");
        }

        public Builder setPrimaryItem(SubItemInfo primaryItem) {
            throw new RuntimeException("Stub!");
        }

        public Builder setSubtitleItem(SubItemInfo subtitleItem) {
            throw new RuntimeException("Stub!");
        }

        public Builder setSubtitleSupplementalItem(SubItemInfo subtitleSupplementalItem) {
            throw new RuntimeException("Stub!");
        }

        public Builder setSupplementalLineItem(SubItemInfo supplementalLineItem) {
            throw new RuntimeException("Stub!");
        }

        public Builder setSupplementalAlarmItem(SubItemInfo supplementalAlarmItem) {
            throw new RuntimeException("Stub!");
        }

        public Builder setLayoutWeight(int layoutWeight) {
            throw new RuntimeException("Stub!");
        }

        public BaseTemplateData build() {
            throw new RuntimeException("Stub!");
        }
    }

    /** One slot of a template: text, icon, tap action and logging info. */
    public static final class SubItemInfo implements Parcelable {

        public Text getText() {
            throw new RuntimeException("Stub!");
        }

        public Icon getIcon() {
            throw new RuntimeException("Stub!");
        }

        public TapAction getTapAction() {
            throw new RuntimeException("Stub!");
        }

        public SubItemLoggingInfo getLoggingInfo() {
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

        public static final Creator<SubItemInfo> CREATOR = null;

        public static final class Builder {
            public Builder() {
                throw new RuntimeException("Stub!");
            }

            public Builder setText(Text text) {
                throw new RuntimeException("Stub!");
            }

            public Builder setIcon(Icon icon) {
                throw new RuntimeException("Stub!");
            }

            public Builder setTapAction(TapAction tapAction) {
                throw new RuntimeException("Stub!");
            }

            public Builder setLoggingInfo(SubItemLoggingInfo loggingInfo) {
                throw new RuntimeException("Stub!");
            }

            public SubItemInfo build() {
                throw new RuntimeException("Stub!");
            }
        }
    }

    /** Logging metadata for a slot. */
    public static final class SubItemLoggingInfo implements Parcelable {

        public int getInstanceId() {
            throw new RuntimeException("Stub!");
        }

        public int getFeatureType() {
            throw new RuntimeException("Stub!");
        }

        public CharSequence getPackageName() {
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

        public static final Creator<SubItemLoggingInfo> CREATOR = null;

        public static final class Builder {
            public Builder(int instanceId, int featureType) {
                throw new RuntimeException("Stub!");
            }

            public Builder setPackageName(CharSequence packageName) {
                throw new RuntimeException("Stub!");
            }

            public SubItemLoggingInfo build() {
                throw new RuntimeException("Stub!");
            }
        }
    }
}
