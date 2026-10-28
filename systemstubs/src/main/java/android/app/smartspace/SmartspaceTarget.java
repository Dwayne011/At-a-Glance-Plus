package android.app.smartspace;

import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.widget.RemoteViews;

import java.util.List;

/**
 * Compile-time stub. One card in At a Glance.
 *
 * Three ways to describe how it looks, in order of how much control the app has:
 * header/base {@link SmartspaceAction} (legacy, text + icon), {@link BaseTemplateData} (one of
 * Google's fixed layouts) and {@link RemoteViews} (an arbitrary layout, honoured by Pixel
 * Launcher and SystemUI from Android 15 QPR2 / Android 16).
 */
public final class SmartspaceTarget implements Parcelable {

    public static final int FEATURE_UNDEFINED = 0;
    public static final int FEATURE_WEATHER = 1;
    public static final int FEATURE_CALENDAR = 2;
    public static final int FEATURE_COMMUTE_TIME = 3;
    public static final int FEATURE_FLIGHT = 4;
    public static final int FEATURE_TIPS = 5;
    public static final int FEATURE_REMINDER = 6;
    public static final int FEATURE_ALARM = 7;
    public static final int FEATURE_ONBOARDING = 8;
    public static final int FEATURE_SPORTS = 9;
    public static final int FEATURE_WEATHER_ALERT = 10;
    public static final int FEATURE_CONSENT = 11;
    public static final int FEATURE_STOCK_PRICE_CHANGE = 12;
    public static final int FEATURE_SHOPPING_LIST = 13;
    public static final int FEATURE_LOYALTY_CARD = 14;
    public static final int FEATURE_MEDIA = 15;
    public static final int FEATURE_BEDTIME_ROUTINE = 16;
    public static final int FEATURE_FITNESS_TRACKING = 17;
    public static final int FEATURE_ETA_MONITORING = 18;
    public static final int FEATURE_MISSED_CALL = 19;
    public static final int FEATURE_PACKAGE_TRACKING = 20;
    public static final int FEATURE_TIMER = 21;
    public static final int FEATURE_STOPWATCH = 22;
    public static final int FEATURE_UPCOMING_ALARM = 23;

    public static final int UI_TEMPLATE_UNDEFINED = 0;
    public static final int UI_TEMPLATE_DEFAULT = 1;
    public static final int UI_TEMPLATE_SUB_IMAGE = 2;
    public static final int UI_TEMPLATE_SUB_LIST = 3;
    public static final int UI_TEMPLATE_CAROUSEL = 4;
    public static final int UI_TEMPLATE_HEAD_TO_HEAD = 5;
    public static final int UI_TEMPLATE_COMBINED_CARDS = 6;
    public static final int UI_TEMPLATE_SUB_CARD = 7;

    public String getSmartspaceTargetId() {
        throw new RuntimeException("Stub!");
    }

    public SmartspaceAction getHeaderAction() {
        throw new RuntimeException("Stub!");
    }

    public SmartspaceAction getBaseAction() {
        throw new RuntimeException("Stub!");
    }

    public long getCreationTimeMillis() {
        throw new RuntimeException("Stub!");
    }

    public long getExpiryTimeMillis() {
        throw new RuntimeException("Stub!");
    }

    public float getScore() {
        throw new RuntimeException("Stub!");
    }

    public List<SmartspaceAction> getActionChips() {
        throw new RuntimeException("Stub!");
    }

    public List<SmartspaceAction> getIconGrid() {
        throw new RuntimeException("Stub!");
    }

    public int getFeatureType() {
        throw new RuntimeException("Stub!");
    }

    public boolean isSensitive() {
        throw new RuntimeException("Stub!");
    }

    public boolean shouldShowExpanded() {
        throw new RuntimeException("Stub!");
    }

    public String getSourceNotificationKey() {
        throw new RuntimeException("Stub!");
    }

    public ComponentName getComponentName() {
        throw new RuntimeException("Stub!");
    }

    public UserHandle getUserHandle() {
        throw new RuntimeException("Stub!");
    }

    public String getAssociatedSmartspaceTargetId() {
        throw new RuntimeException("Stub!");
    }

    public Uri getSliceUri() {
        throw new RuntimeException("Stub!");
    }

    public AppWidgetProviderInfo getWidget() {
        throw new RuntimeException("Stub!");
    }

    public RemoteViews getRemoteViews() {
        throw new RuntimeException("Stub!");
    }

    public BaseTemplateData getTemplateData() {
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

    public static final Creator<SmartspaceTarget> CREATOR = null;

    public static final class Builder {
        public Builder(String smartspaceTargetId, ComponentName componentName, UserHandle userHandle) {
            throw new RuntimeException("Stub!");
        }

        public Builder setHeaderAction(SmartspaceAction headerAction) {
            throw new RuntimeException("Stub!");
        }

        public Builder setBaseAction(SmartspaceAction baseAction) {
            throw new RuntimeException("Stub!");
        }

        public Builder setCreationTimeMillis(long creationTimeMillis) {
            throw new RuntimeException("Stub!");
        }

        public Builder setExpiryTimeMillis(long expiryTimeMillis) {
            throw new RuntimeException("Stub!");
        }

        public Builder setScore(float score) {
            throw new RuntimeException("Stub!");
        }

        public Builder setActionChips(List<SmartspaceAction> actionChips) {
            throw new RuntimeException("Stub!");
        }

        public Builder setIconGrid(List<SmartspaceAction> iconGrid) {
            throw new RuntimeException("Stub!");
        }

        public Builder setFeatureType(int featureType) {
            throw new RuntimeException("Stub!");
        }

        public Builder setSensitive(boolean sensitive) {
            throw new RuntimeException("Stub!");
        }

        public Builder setShouldShowExpanded(boolean shouldShowExpanded) {
            throw new RuntimeException("Stub!");
        }

        public Builder setSourceNotificationKey(String sourceNotificationKey) {
            throw new RuntimeException("Stub!");
        }

        public Builder setAssociatedSmartspaceTargetId(String associatedSmartspaceTargetId) {
            throw new RuntimeException("Stub!");
        }

        public Builder setSliceUri(Uri sliceUri) {
            throw new RuntimeException("Stub!");
        }

        public Builder setWidget(AppWidgetProviderInfo widget) {
            throw new RuntimeException("Stub!");
        }

        public Builder setTemplateData(BaseTemplateData templateData) {
            throw new RuntimeException("Stub!");
        }

        /** Android 15 QPR2 / Android 16 and newer. When set, the other visual fields are ignored. */
        public Builder setRemoteViews(RemoteViews remoteViews) {
            throw new RuntimeException("Stub!");
        }

        public SmartspaceTarget build() {
            throw new RuntimeException("Stub!");
        }
    }
}
