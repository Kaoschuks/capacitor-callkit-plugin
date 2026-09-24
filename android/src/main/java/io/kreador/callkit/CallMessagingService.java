package io.kreador.callkit;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.lang.reflect.Method;
import java.util.Map;
import org.json.JSONObject;

/**
 * Replaces callkeep's RNCallKeepBackgroundMessagingService (HeadlessJS). Capacitor has
 * no headless JS, so a call push is turned into a Telecom call natively — this is what
 * makes the phone ring while the app is killed.
 *
 * Expected FCM message: data-only, priority "high".
 *   { "type": "incoming_call", "callId": "...", "callerName": "...", "handle": "...", "hasVideo": "false" }
 *   { "type": "call_ended",    "callId": "..." }
 *
 * Android delivers FCM to a single FirebaseMessagingService. This one is declared with a
 * higher intent-filter priority, and forwards everything that is not a call (plus token
 * refreshes) to @capacitor/push-notifications when that plugin is installed, so both work.
 *
 * If your app has its own FirebaseMessagingService, remove this one from the merged
 * manifest (tools:node="remove") and call {@link #handleRemoteMessage} from yours.
 */
public class CallMessagingService extends FirebaseMessagingService {

    private static final String TAG = "IonicCallkit";
    private static final String CAPACITOR_PUSH_PLUGIN = "com.capacitorjs.plugins.pushnotifications.PushNotificationsPlugin";

    public static final String TYPE_INCOMING_CALL = "incoming_call";
    public static final String TYPE_CALL_ENDED = "call_ended";

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        if (handleRemoteMessage(this, message)) {
            return;
        }
        if (!forwardToCapacitorPush("sendRemoteMessage", RemoteMessage.class, message)) {
            Log.d(TAG, "[CallMessagingService] ignoring non-call message");
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        CallKeepModule.getInstance(this).onNewToken(token);
        forwardToCapacitorPush("onNewToken", String.class, token);
    }

    /** @return true if the message was a call message and was handled. */
    public static boolean handleRemoteMessage(Context context, RemoteMessage message) {
        return handleData(context, message.getData());
    }

    public static boolean handleData(Context context, Map<String, String> data) {
        CallPush push = CallPush.parse(data);
        if (push == null) {
            return false;
        }

        CallKeepModule module = CallKeepModule.getInstance(context);
        try {
            if (TYPE_CALL_ENDED.equals(push.type)) {
                module.reportRemoteEnded(push.callId);
                return true;
            }

            module.ensurePhoneAccount();
            module.reportNewIncomingCall(push.callId, push.handle, push.callerName, push.hasVideo, new JSONObject(data));
        } catch (CallKeepModule.CallKeepException e) {
            // Duplicate / late / busy pushes end up here by design.
            Log.d(TAG, "[CallMessagingService] call push not shown: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "[CallMessagingService] failed to handle call push: " + e.getMessage());
        }
        return true;
    }

    /**
     * Calls a public static method of @capacitor/push-notifications' plugin class, the same
     * ones its own MessagingService calls. Reflection keeps that plugin an optional dependency.
     *
     * @return false when the plugin is not installed or the call failed.
     */
    static boolean forwardToCapacitorPush(String methodName, Class<?> argType, Object arg) {
        try {
            Class<?> plugin = Class.forName(CAPACITOR_PUSH_PLUGIN);
            Method method = plugin.getMethod(methodName, argType);
            method.invoke(null, arg);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            Log.w(TAG, "[CallMessagingService] forwarding " + methodName + " to @capacitor/push-notifications failed: " + e);
            return false;
        }
    }
}
