package android.service.smartspace;

import android.app.Service;
import android.app.smartspace.SmartspaceConfig;
import android.app.smartspace.SmartspaceSessionId;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.content.Intent;
import android.os.IBinder;

import java.util.List;

/**
 * Compile-time stub of the hidden system service base class that At a Glance content providers
 * extend. The system binds to an implementation of this class and asks it for targets for each
 * UI surface ("home", "lockscreen", ...).
 *
 * Surface mirrors the Android Open Source Project class of the same name. Bodies are stubs.
 */
public abstract class SmartspaceService extends Service {

    public static final String SERVICE_INTERFACE = "android.service.smartspace.SmartspaceService";

    @Override
    public void onCreate() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public final IBinder onBind(Intent intent) {
        throw new RuntimeException("Stub!");
    }

    /** Called when a client (launcher, SystemUI) creates a session. */
    public abstract void onCreateSmartspaceSession(SmartspaceConfig config, SmartspaceSessionId sessionId);

    /** Called when a client reports an event (shown, hidden, dismissed, interaction). */
    public abstract void notifySmartspaceEvent(SmartspaceSessionId sessionId, SmartspaceTargetEvent event);

    /** Called when a client asks for fresh targets. */
    public abstract void onRequestSmartspaceUpdate(SmartspaceSessionId sessionId);

    /** Called when a client destroys its session. */
    public abstract void onDestroySmartspaceSession(SmartspaceSessionId sessionId);

    /** Sends targets to every client registered on the session. */
    public final void updateSmartspaceTargets(SmartspaceSessionId sessionId, List<SmartspaceTarget> targets) {
        throw new RuntimeException("Stub!");
    }

    /** Destroys a session. */
    public abstract void onDestroy(SmartspaceSessionId sessionId);
}
