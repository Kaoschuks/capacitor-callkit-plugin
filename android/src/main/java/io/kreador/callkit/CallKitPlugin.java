package io.kreador.callkit;

import static io.kreador.callkit.Constants.EXTRA_CALL_UUID;
import static io.kreador.callkit.Constants.EXTRA_LAUNCH_ACTION;
import static io.kreador.callkit.Constants.LAUNCH_ACTION_ANSWER;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Capacitor bridge over CallKeepModule (the callkeep port). Replaces callkeep's
 * @ReactMethod surface with @PluginMethod and its event emitter with notifyListeners.
 */
@CapacitorPlugin(
    name = "CallKit",
    permissions = {
        @Permission(alias = CallKitPlugin.MICROPHONE, strings = { Manifest.permission.RECORD_AUDIO }),
        @Permission(alias = CallKitPlugin.NOTIFICATIONS, strings = { Manifest.permission.POST_NOTIFICATIONS }),
        @Permission(
            alias = CallKitPlugin.PHONE,
            strings = { Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.CALL_PHONE }
        )
    }
)
public class CallKitPlugin extends Plugin implements CallEventBus.Listener {

    private static final String TAG = "IonicCallkit";
    static final String MICROPHONE = "microphone";
    static final String NOTIFICATIONS = "notifications";
    static final String PHONE = "phone";

    private CallKeepModule module;

    private interface Action {
        void run() throws Exception;
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void load() {
        module = CallKeepModule.getInstance(getContext());
        module.setCurrentActivity(getActivity());
        CallEventBus.attach(this);
        if (getActivity() != null) {
            handleLaunchIntent(getActivity().getIntent());
        }
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        handleLaunchIntent(intent);
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        module.setCurrentActivity(getActivity());
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        CallEventBus.detach(this);
        Activity activity = getActivity();
        if (activity != null && activity.isFinishing() && !activity.isChangingConfigurations()) {
            module.setCurrentActivity(null);
            module.onHostDestroy();
        }
    }

    @Override
    public void onCallEvent(String eventName, JSObject data) {
        if ("callEnded".equals(eventName) || "callRejected".equals(eventName)) {
            if (CallConnectionService.currentConnections.isEmpty()) {
                setShowOverLockScreen(false);
            }
        }
        // Retain until a JS listener is added, so events from a cold start aren't lost.
        notifyListeners(eventName, data, true);
    }

    /** Notification taps (full-screen intent / Answer) launch the app with these extras. */
    private void handleLaunchIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_LAUNCH_ACTION)) {
            return;
        }
        String action = intent.getStringExtra(EXTRA_LAUNCH_ACTION);
        String callId = intent.getStringExtra(EXTRA_CALL_UUID);
        // Consume, so an activity recreation doesn't answer twice.
        intent.removeExtra(EXTRA_LAUNCH_ACTION);

        Log.d(TAG, "[CallKitPlugin] launch action: " + action + ", uuid: " + callId);
        setShowOverLockScreen(true);

        if (LAUNCH_ACTION_ANSWER.equals(action) && callId != null) {
            try {
                module.answerIncomingCall(callId);
            } catch (CallKeepModule.CallKeepException e) {
                Log.w(TAG, "[CallKitPlugin] answer from notification: " + e.getMessage());
                IncomingCallNotification.cancel(getContext(), callId);
            }
        }
    }

    private void setShowOverLockScreen(boolean show) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setShowWhenLocked(show);
                activity.setTurnScreenOn(show);
            }
        });
    }

    // ─── Setup / tokens ─────────────────────────────────────────────────────

    @PluginMethod
    public void setup(PluginCall call) {
        run(call, () -> {
            JSObject data = call.getData();
            JSONObject settings = new JSONObject();
            copy(data, settings, "appName", "imageName", "supportsVideo");
            JSONObject android = data.optJSONObject("android");
            if (android != null) {
                Iterator<String> keys = android.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    settings.put(key, android.get(key));
                }
            }
            module.setup(settings);
        });
    }

    @PluginMethod
    public void registerVoipToken(PluginCall call) {
        FirebaseMessaging messaging;
        try {
            messaging = FirebaseMessaging.getInstance();
        } catch (IllegalStateException e) {
            call.reject("Firebase is not initialised. Add google-services.json to your Android app.", e);
            return;
        }
        messaging
            .getToken()
            .addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) {
                    Exception e = task.getException();
                    call.reject("Could not get FCM token: " + (e != null ? e.getMessage() : "unknown error"), e);
                    return;
                }
                String token = task.getResult();
                JSObject ret = new JSObject();
                ret.put("token", token);
                call.resolve(ret);
                module.onNewToken(token);
            });
    }

    @PluginMethod
    public void unregisterVoipToken(PluginCall call) {
        try {
            FirebaseMessaging.getInstance()
                .deleteToken()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        call.resolve();
                    } else {
                        call.reject("Could not delete FCM token", task.getException());
                    }
                });
        } catch (IllegalStateException e) {
            call.reject("Firebase is not initialised.", e);
        }
    }

    // ─── Incoming ───────────────────────────────────────────────────────────

    @PluginMethod
    public void displayIncomingCall(PluginCall call) {
        String callId = required(call, "callId");
        String callerName = required(call, "callerName");
        if (callId == null || callerName == null) {
            return;
        }
        String handle = call.getString("handle", callerName);
        boolean hasVideo = Boolean.TRUE.equals(call.getBoolean("hasVideo", false));
        JSObject payload = call.getObject("payload", null);
        run(call, () -> module.reportNewIncomingCall(callId, handle, callerName, hasVideo, payload));
    }

    @PluginMethod
    public void answerCall(PluginCall call) {
        String callId = required(call, "callId");
        if (callId == null) return;
        run(call, () -> module.answerIncomingCall(callId));
    }

    @PluginMethod
    public void rejectCall(PluginCall call) {
        String callId = required(call, "callId");
        if (callId == null) return;
        run(call, () -> module.rejectCall(callId));
    }

    // ─── Outgoing ───────────────────────────────────────────────────────────

    @PluginMethod
    public void startCall(PluginCall call) {
        String callId = required(call, "callId");
        String calleeName = required(call, "calleeName");
        if (callId == null || calleeName == null) {
            return;
        }
        String handle = call.getString("handle", calleeName);
        boolean hasVideo = Boolean.TRUE.equals(call.getBoolean("hasVideo", false));
        run(call, () -> module.startCall(callId, handle, calleeName, hasVideo));
    }

    @PluginMethod
    public void setCallActive(PluginCall call) {
        String callId = required(call, "callId");
        if (callId == null) return;
        run(call, () -> module.setCurrentCallActive(callId));
    }

    @PluginMethod
    public void endCall(PluginCall call) {
        String callId = required(call, "callId");
        if (callId == null) return;
        run(call, () -> module.endCall(callId));
    }

    @PluginMethod
    public void endAllCalls(PluginCall call) {
        run(call, () -> module.endAllCalls());
    }

    @PluginMethod
    public void reportEndCall(PluginCall call) {
        String callId = required(call, "callId");
        if (callId == null) return;
        int reason = call.getInt("reason", 2);
        run(call, () -> module.reportEndCallWithUUID(callId, reason));
    }

    @PluginMethod
    public void updateDisplay(PluginCall call) {
        String callId = required(call, "callId");
        String callerName = required(call, "callerName");
        if (callId == null || callerName == null) {
            return;
        }
        String handle = call.getString("handle", callerName);
        run(call, () -> module.updateDisplay(callId, callerName, handle));
    }

    // ─── In-call controls ───────────────────────────────────────────────────

    @PluginMethod
    public void setMuted(PluginCall call) {
        String callId = required(call, "callId");
        if (callId == null) return;
        boolean muted = Boolean.TRUE.equals(call.getBoolean("muted", false));
        run(call, () -> module.setMutedCall(callId, muted));
    }

    @PluginMethod
    public void setSpeaker(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        String callId = call.getString("callId");
        run(call, () -> module.toggleAudioRouteSpeaker(callId, on));
    }

    @PluginMethod
    public void setOnHold(PluginCall call) {
        String callId = required(call, "callId");
        if (callId == null) return;
        boolean hold = Boolean.TRUE.equals(call.getBoolean("hold", false));
        run(call, () -> module.setOnHold(callId, hold));
    }

    @PluginMethod
    public void sendDTMF(PluginCall call) {
        String callId = required(call, "callId");
        String digits = required(call, "digits");
        if (callId == null || digits == null) {
            return;
        }
        run(call, () -> module.sendDTMF(callId, digits));
    }

    @PluginMethod
    public void getAudioRoutes(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            ret.put("routes", module.getAudioRoutes());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("GetAudioRoutes Error: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void setAudioRoute(PluginCall call) {
        String callId = required(call, "callId");
        String route = required(call, "route");
        if (callId == null || route == null) {
            return;
        }
        run(call, () -> module.setAudioRoute(callId, route));
    }

    // ─── Android specifics ──────────────────────────────────────────────────

    @PluginMethod
    public void setAvailable(PluginCall call) {
        boolean available = Boolean.TRUE.equals(call.getBoolean("available", true));
        run(call, () -> module.setAvailable(available));
    }

    @PluginMethod
    public void setReachable(PluginCall call) {
        run(call, () -> module.setReachable());
    }

    @PluginMethod
    public void hasPhoneAccount(PluginCall call) {
        resolveValue(call, module.hasPhoneAccount());
    }

    @PluginMethod
    public void openPhoneAccountSettings(PluginCall call) {
        run(call, () -> module.openPhoneAccounts());
    }

    @PluginMethod
    public void canUseFullScreenIntent(PluginCall call) {
        resolveValue(call, module.canUseFullScreenIntent());
    }

    @PluginMethod
    public void openFullScreenIntentSettings(PluginCall call) {
        run(call, () -> module.openFullScreenIntentSettings());
    }

    @PluginMethod
    public void backToForeground(PluginCall call) {
        run(call, () -> module.backToForeground());
    }

    @PluginMethod
    public void getActiveCalls(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("calls", module.getActiveCalls());
        call.resolve(ret);
    }

    // ─── Permissions ────────────────────────────────────────────────────────

    @Override
    @PluginMethod
    public void checkPermissions(PluginCall call) {
        call.resolve(permissionStatus());
    }

    @Override
    @PluginMethod
    public void requestPermissions(PluginCall call) {
        List<String> aliases = new ArrayList<>();
        JSArray requested = call.getArray("permissions");
        if (requested != null && requested.length() > 0) {
            try {
                aliases.addAll(requested.<String>toList());
            } catch (JSONException e) {
                call.reject("permissions must be an array of strings");
                return;
            }
        } else {
            aliases.add(MICROPHONE);
            aliases.add(NOTIFICATIONS);
            if (!module.isSelfManaged()) {
                aliases.add(PHONE);
            }
        }
        // POST_NOTIFICATIONS only exists as a runtime permission on Android 13+.
        if (Build.VERSION.SDK_INT < 33) {
            aliases.remove(NOTIFICATIONS);
        }
        if (aliases.isEmpty()) {
            call.resolve(permissionStatus());
            return;
        }
        requestPermissionForAliases(aliases.toArray(new String[0]), call, "permissionsCallback");
    }

    @PermissionCallback
    private void permissionsCallback(PluginCall call) {
        call.resolve(permissionStatus());
    }

    private JSObject permissionStatus() {
        JSObject result = new JSObject();
        result.put(MICROPHONE, getPermissionState(MICROPHONE).toString());
        if (Build.VERSION.SDK_INT < 33) {
            boolean enabled = NotificationManagerCompat.from(getContext()).areNotificationsEnabled();
            result.put(NOTIFICATIONS, (enabled ? PermissionState.GRANTED : PermissionState.DENIED).toString());
        } else {
            result.put(NOTIFICATIONS, getPermissionState(NOTIFICATIONS).toString());
        }
        result.put(PHONE, getPermissionState(PHONE).toString());
        return result;
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private void run(PluginCall call, Action action) {
        try {
            action.run();
            call.resolve();
        } catch (CallKeepModule.CallKeepException e) {
            call.reject(e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "[CallKitPlugin] " + call.getMethodName() + " failed", e);
            call.reject(e.getMessage() != null ? e.getMessage() : e.toString(), e);
        }
    }

    private String required(PluginCall call, String key) {
        String value = call.getString(key);
        if (value == null || value.isEmpty()) {
            call.reject("Missing required option: " + key);
            return null;
        }
        return value;
    }

    private void resolveValue(PluginCall call, boolean value) {
        JSObject ret = new JSObject();
        ret.put("value", value);
        call.resolve(ret);
    }

    private static void copy(JSONObject from, JSONObject to, String... keys) throws JSONException {
        for (String key : keys) {
            if (from.has(key)) {
                to.put(key, from.get(key));
            }
        }
    }
}
