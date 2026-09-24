package io.kreador.callkit;

import static io.kreador.callkit.Constants.EXTRA_CALLER_NAME;
import static io.kreador.callkit.Constants.EXTRA_CALL_NUMBER;
import static io.kreador.callkit.Constants.EXTRA_CALL_UUID;
import static io.kreador.callkit.Constants.EXTRA_HAS_VIDEO;
import static io.kreador.callkit.Constants.LAUNCH_ACTION_SHOW_INCOMING;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.annotation.Nullable;
import java.lang.ref.WeakReference;

/**
 * Native incoming-call screen, launched by the ringing notification's full-screen intent.
 *
 * The manifest declares `showWhenLocked` / `turnScreenOn`, so Android shows it over a secure
 * lock screen from the first frame — runtime flags on the app's own activity are applied too
 * late on many devices, leaving only the notification visible. It needs no WebView, so it also
 * appears instantly on a cold start. Answer hands over to the app (over the lock screen).
 *
 * Disable with `android.incomingCallScreen: 'app'` to open the app's own call page instead.
 */
public class IncomingCallActivity extends Activity {

    private static final String TAG = "IonicCallkit";
    private static WeakReference<IncomingCallActivity> current = new WeakReference<>(null);

    private String callId;

    static Intent intent(Context context, String callId, @Nullable String callerName, @Nullable String handle, boolean hasVideo) {
        Intent intent = new Intent(context, IncomingCallActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(EXTRA_CALL_UUID, callId);
        intent.putExtra(EXTRA_CALLER_NAME, callerName);
        intent.putExtra(EXTRA_CALL_NUMBER, handle);
        intent.putExtra(EXTRA_HAS_VIDEO, hasVideo);
        return intent;
    }

    /** Close the screen when the call is answered / declined / ended elsewhere. */
    static void dismiss(@Nullable String callId) {
        IncomingCallActivity activity = current.get();
        if (activity != null && (callId == null || callId.equals(activity.callId))) {
            activity.runOnUiThread(activity::finish);
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            // Android 8.0 ignores the manifest attributes.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        setContentView(R.layout.ionic_callkit_incoming_call);
        current = new WeakReference<>(this);
        bind(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bind(intent);
    }

    @Override
    protected void onDestroy() {
        if (current.get() == this) {
            current = new WeakReference<>(null);
        }
        super.onDestroy();
    }

    private void bind(Intent intent) {
        callId = intent.getStringExtra(EXTRA_CALL_UUID);
        if (callId == null || CallConnectionService.getConnection(callId) == null) {
            // The call ended before the screen came up.
            finish();
            return;
        }
        String name = intent.getStringExtra(EXTRA_CALLER_NAME);
        String handle = intent.getStringExtra(EXTRA_CALL_NUMBER);
        boolean hasVideo = intent.getBooleanExtra(EXTRA_HAS_VIDEO, false);

        ((TextView) findViewById(R.id.ionic_callkit_app_name)).setText(appName());
        ((TextView) findViewById(R.id.ionic_callkit_caller_name)).setText(name != null && !name.isEmpty() ? name : handle);
        ((TextView) findViewById(R.id.ionic_callkit_subtitle)).setText(
            hasVideo ? R.string.ionic_callkit_incoming_video_call : R.string.ionic_callkit_incoming_call
        );
        findViewById(R.id.ionic_callkit_answer).setOnClickListener(v -> answer());
        findViewById(R.id.ionic_callkit_decline).setOnClickListener(v -> decline());
    }

    private void answer() {
        try {
            CallKeepModule.getInstance(this).answerIncomingCall(callId);
            // Hand over to the app, shown over the lock screen by CallKitPlugin.
            startActivity(IncomingCallNotification.launchIntent(this, callId, LAUNCH_ACTION_SHOW_INCOMING));
        } catch (CallKeepModule.CallKeepException e) {
            Log.w(TAG, "[IncomingCallActivity] answer: " + e.getMessage());
        }
        finish();
    }

    private void decline() {
        try {
            CallKeepModule.getInstance(this).rejectCall(callId);
        } catch (CallKeepModule.CallKeepException e) {
            Log.w(TAG, "[IncomingCallActivity] decline: " + e.getMessage());
        }
        finish();
    }

    private CharSequence appName() {
        ApplicationInfo info = getApplicationInfo();
        return info.labelRes != 0 ? getString(info.labelRes) : getPackageManager().getApplicationLabel(info);
    }
}
