package io.kreador.callkit;

import static io.kreador.callkit.Constants.ACTION_NOTIFICATION_DECLINE;
import static io.kreador.callkit.Constants.EXTRA_CALL_UUID;
import static io.kreador.callkit.Constants.EXTRA_LAUNCH_ACTION;
import static io.kreador.callkit.Constants.LAUNCH_ACTION_ANSWER;
import static io.kreador.callkit.Constants.LAUNCH_ACTION_SHOW_INCOMING;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import org.json.JSONObject;

/**
 * Ringing UI for self-managed calls: a CallStyle notification with Answer / Decline whose
 * full-screen intent opens IncomingCallActivity over the lock screen, plus CallRinger.
 * Callkeep leaves this to JS (showIncomingCallUi), which can't work when a push wakes a killed app.
 */
public final class IncomingCallNotification {

    private static final String TAG = "IonicCallkit";
    // v2: silent channel — CallRinger plays the ringtone/vibration. Channel settings can't be
    // changed once created, so the old (sound) channel is deleted and a new id is used.
    static final String CHANNEL_ID = "ionic_callkit_incoming_v2";
    private static final String OLD_CHANNEL_ID = "ionic_callkit_incoming";

    private IncomingCallNotification() {}

    static int notificationId(String callId) {
        return ("incoming:" + callId).hashCode();
    }

    public static void show(Context context, String callId, @Nullable String callerName, @Nullable String handle, boolean hasVideo) {
        JSONObject settings = CallKeepModule.getSettings(context);
        ensureChannel(context, settings);

        String name = callerName != null && !callerName.isEmpty() ? callerName : (handle != null ? handle : "Unknown");
        Person caller = new Person.Builder().setName(name).setImportant(true).build();

        // Full-screen target: the plugin's native screen (default) or the app's own page.
        PendingIntent fullScreen = "app".equals(settings.optString("incomingCallScreen", "native"))
            ? launchPendingIntent(context, callId, LAUNCH_ACTION_SHOW_INCOMING)
            : PendingIntent.getActivity(
                context,
                (callId + "incomingScreen").hashCode(),
                IncomingCallActivity.intent(context, callId, callerName, handle, hasVideo),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        PendingIntent answer = launchPendingIntent(context, callId, LAUNCH_ACTION_ANSWER);
        PendingIntent decline = CallActionReceiver.pendingIntent(context, callId, ACTION_NOTIFICATION_DECLINE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon(context, settings.optString("notificationIcon", "")))
            .setContentTitle(name)
            .setContentText(context.getString(hasVideo ? R.string.ionic_callkit_incoming_video_call : R.string.ionic_callkit_incoming_call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer).setIsVideo(hasVideo))
            .addPerson(caller);

        Notification notification = builder.build();

        try {
            NotificationManagerCompat.from(context).notify(notificationId(callId), notification);
        } catch (SecurityException e) {
            Log.w(TAG, "[IncomingCallNotification] POST_NOTIFICATIONS not granted: " + e.getMessage());
        }
        CallRinger.start(context, callId);
    }

    public static void cancel(Context context, @Nullable String callId) {
        if (callId == null) {
            return;
        }
        NotificationManagerCompat.from(context).cancel(notificationId(callId));
        CallRinger.stop(callId);
        IncomingCallActivity.dismiss(callId);
    }

    static PendingIntent launchPendingIntent(Context context, String callId, String launchAction) {
        Intent intent = launchIntent(context, callId, launchAction);
        return PendingIntent.getActivity(
            context,
            (callId + launchAction).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    static Intent launchIntent(Context context, String callId, String launchAction) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent == null) {
            intent = new Intent();
            intent.setPackage(context.getPackageName());
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(EXTRA_LAUNCH_ACTION, launchAction);
        intent.putExtra(EXTRA_CALL_UUID, callId);
        return intent;
    }

    private static void ensureChannel(Context context, JSONObject settings) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(OLD_CHANNEL_ID) != null) {
            nm.deleteNotificationChannel(OLD_CHANNEL_ID);
        }
        if (nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        // High importance for heads-up + full-screen; silent because CallRinger rings.
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            settings.optString("incomingCallChannelName", "Incoming calls"),
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(channel);
    }

    static int smallIcon(Context context, String iconName) {
        if (iconName != null && !iconName.isEmpty()) {
            int id = context.getResources().getIdentifier(iconName, "drawable", context.getPackageName());
            if (id == 0) {
                id = context.getResources().getIdentifier(iconName, "mipmap", context.getPackageName());
            }
            if (id != 0) {
                return id;
            }
        }
        return context.getApplicationInfo().icon;
    }
}
