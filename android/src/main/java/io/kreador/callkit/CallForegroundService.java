package io.kreador.callkit;

import static io.kreador.callkit.Constants.ACTION_NOTIFICATION_HANGUP;
import static io.kreador.callkit.Constants.EXTRA_CALLER_NAME;
import static io.kreador.callkit.Constants.EXTRA_CALL_UUID;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;

/**
 * Keeps the process and microphone alive during a call (phoneCall|microphone FGS type,
 * required on Android 14+). Callkeep runs startForeground() on the ConnectionService
 * itself; it is split out here so it can be started/stopped independently of Telecom's
 * binding and carry a CallStyle "ongoing call" notification with Hang up.
 *
 * Settings (setup android.foregroundService): channelId, channelName, notificationTitle,
 * notificationIcon. Pass `foregroundService: false` to disable.
 */
public class CallForegroundService extends Service {

    private static final String TAG = "IonicCallkit";
    private static final int NOTIFICATION_ID = -4567;

    public static void start(Context context, @Nullable String callId, @Nullable String callerName) {
        if (Boolean.FALSE.equals(CallKeepModule.getSettings(context).opt("foregroundService"))) {
            return;
        }
        Intent intent = new Intent(context, CallForegroundService.class);
        intent.putExtra(EXTRA_CALL_UUID, callId);
        intent.putExtra(EXTRA_CALLER_NAME, callerName);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            // ForegroundServiceStartNotAllowedException when started from the background on 12+.
            Log.w(TAG, "[CallForegroundService] Can't start foreground service : " + e);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, CallForegroundService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String callId = intent != null ? intent.getStringExtra(EXTRA_CALL_UUID) : null;
        String callerName = intent != null ? intent.getStringExtra(EXTRA_CALLER_NAME) : null;

        Notification notification = buildNotification(callId, callerName);

        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
        } catch (Exception e) {
            Log.w(TAG, "[CallForegroundService] startForeground failed : " + e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification(@Nullable String callId, @Nullable String callerName) {
        JSONObject settings = CallKeepModule.getSettings(this);
        JSONObject fg = settings.optJSONObject("foregroundService");
        if (fg == null) {
            fg = new JSONObject();
        }
        String channelId = fg.optString("channelId", "ionic_callkit_ongoing");

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager.getNotificationChannel(channelId) == null) {
            NotificationChannel chan = new NotificationChannel(
                channelId,
                fg.optString("channelName", "Ongoing calls"),
                NotificationManager.IMPORTANCE_LOW
            );
            chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            manager.createNotificationChannel(chan);
        }

        String iconName = fg.optString("notificationIcon", settings.optString("notificationIcon", ""));
        String title = fg.optString("notificationTitle", "Call in progress");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(IncomingCallNotification.smallIcon(this, iconName))
            .setContentTitle(title)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW);

        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            builder.setContentIntent(PendingIntent.getActivity(this, NOTIFICATION_ID, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }

        if (callId != null) {
            String name = callerName != null && !callerName.isEmpty() ? callerName : title;
            Person person = new Person.Builder().setName(name).setImportant(true).build();
            PendingIntent hangUp = CallActionReceiver.pendingIntent(this, callId, ACTION_NOTIFICATION_HANGUP);
            builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp));
        }

        return builder.build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
