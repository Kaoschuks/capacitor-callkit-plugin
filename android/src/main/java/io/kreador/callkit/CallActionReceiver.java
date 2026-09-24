package io.kreador.callkit;

import static io.kreador.callkit.Constants.ACTION_NOTIFICATION_DECLINE;
import static io.kreador.callkit.Constants.ACTION_NOTIFICATION_HANGUP;
import static io.kreador.callkit.Constants.EXTRA_CALL_UUID;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Handles notification buttons that must not open the app: Decline (incoming) and
 * Hang up (ongoing). Answer is a launch intent handled by CallKitPlugin, because
 * Android 12+ forbids starting activities from a broadcast receiver.
 */
public class CallActionReceiver extends BroadcastReceiver {

    private static final String TAG = "IonicCallkit";

    static PendingIntent pendingIntent(Context context, String callId, String action) {
        Intent intent = new Intent(context, CallActionReceiver.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_CALL_UUID, callId);
        return PendingIntent.getBroadcast(
            context,
            (callId + action).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String callId = intent.getStringExtra(EXTRA_CALL_UUID);
        String action = intent.getAction();
        Log.d(TAG, "[CallActionReceiver] " + action + ", uuid: " + callId);

        CallKeepModule module = CallKeepModule.getInstance(context);
        try {
            if (ACTION_NOTIFICATION_DECLINE.equals(action)) {
                module.rejectCall(callId);
            } else if (ACTION_NOTIFICATION_HANGUP.equals(action)) {
                module.endCall(callId);
            }
        } catch (CallKeepModule.CallKeepException e) {
            // Connection already gone: make sure nothing is left on screen.
            Log.w(TAG, "[CallActionReceiver] " + e.getMessage());
            IncomingCallNotification.cancel(context, callId);
            if (CallConnectionService.currentConnections.isEmpty()) {
                CallForegroundService.stop(context);
            }
        }
    }
}
