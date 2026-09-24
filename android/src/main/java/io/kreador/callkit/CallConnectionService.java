/*
 * Ported from livekit/react-native-callkeep (io.wazo.callkeep.VoiceConnectionService).
 * Copyright (c) 2016-2019 The CallKeep Authors (see the AUTHORS file)
 * SPDX-License-Identifier: ISC, MIT
 *
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package io.kreador.callkit;

import static io.kreador.callkit.Constants.ACTION_AUDIO_SESSION;
import static io.kreador.callkit.Constants.ACTION_CHECK_REACHABILITY;
import static io.kreador.callkit.Constants.ACTION_ONGOING_CALL;
import static io.kreador.callkit.Constants.ACTION_ON_CREATE_CONNECTION_FAILED;
import static io.kreador.callkit.Constants.EXTRA_CALLER_NAME;
import static io.kreador.callkit.Constants.EXTRA_CALL_NUMBER;
import static io.kreador.callkit.Constants.EXTRA_CALL_NUMBER_SCHEMA;
import static io.kreador.callkit.Constants.EXTRA_CALL_UUID;
import static io.kreador.callkit.Constants.EXTRA_DISABLE_ADD_CALL;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

// @see https://github.com/kbagchiGWC/voice-quickstart-android/blob/9a2aff7fbe0d0a5ae9457b48e9ad408740dfb968/exampleConnectionService/src/main/java/com/twilio/voice/examples/connectionservice/VoiceConnectionService.java
public class CallConnectionService extends ConnectionService {

    private static Boolean isAvailable = false;
    private static Boolean isInitialized = false;
    private static Boolean isReachable = false;
    private static Boolean canMakeMultipleCalls = true;
    private static String notReachableCallUuid;
    private static ConnectionRequest currentConnectionRequest;
    private static PhoneAccountHandle phoneAccountHandle;
    private static String TAG = "IonicCallkit";

    // Delay events sent to CallKeepModule when there is no listener available
    private static List<Bundle> delayedEvents = new ArrayList<Bundle>();

    public static Map<String, CallConnection> currentConnections = new ConcurrentHashMap<>();
    public static Boolean hasOutgoingCall = false;
    public static CallConnectionService currentConnectionService = null;

    public static Connection getConnection(String connectionId) {
        if (connectionId != null && currentConnections.containsKey(connectionId)) {
            return currentConnections.get(connectionId);
        }
        return null;
    }

    public CallConnectionService() {
        super();
        Log.d(TAG, "[CallConnectionService] Constructor");
        currentConnectionRequest = null;
        currentConnectionService = this;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Telecom can bind us before anything else in the process ran (e.g. an outgoing call
        // from the system dialer). Make sure the event receiver exists.
        CallKeepModule.getInstance(getApplicationContext());
    }

    public static void setPhoneAccountHandle(PhoneAccountHandle phoneAccountHandle) {
        CallConnectionService.phoneAccountHandle = phoneAccountHandle;
    }

    public static void setAvailable(Boolean value) {
        Log.d(TAG, "[CallConnectionService] setAvailable: " + (value ? "true" : "false"));
        if (value) {
            setInitialized(true);
        }

        isAvailable = value;
    }

    public static void setCanMakeMultipleCalls(Boolean value) {
        Log.d(TAG, "[CallConnectionService] setCanMakeMultipleCalls: " + (value ? "true" : "false"));

        CallConnectionService.canMakeMultipleCalls = value;
    }

    public static void setReachable() {
        Log.d(TAG, "[CallConnectionService] setReachable");
        isReachable = true;
        CallConnectionService.currentConnectionRequest = null;
    }

    public static void setInitialized(boolean value) {
        Log.d(TAG, "[CallConnectionService] setInitialized: " + (value ? "true" : "false"));

        isInitialized = value;
    }

    public static void deinitConnection(Context context, String connectionId) {
        Log.d(TAG, "[CallConnectionService] deinitConnection:" + connectionId);
        CallConnectionService.hasOutgoingCall = false;

        if (connectionId != null) {
            currentConnections.remove(connectionId);
        }

        if (currentConnections.isEmpty()) {
            CallForegroundService.stop(context);
        }
    }

    public static void setState(String uuid, int state) {
        Connection conn = CallConnectionService.getConnection(uuid);
        if (conn == null) {
            Log.w(TAG, "[CallConnectionService] setState ignored because no connection found, uuid: " + uuid);
            return;
        }

        switch (state) {
            case Connection.STATE_ACTIVE:
                conn.setActive();
                break;
            case Connection.STATE_DIALING:
                conn.setDialing();
                break;
            case Connection.STATE_HOLDING:
                conn.setOnHold();
                break;
            case Connection.STATE_INITIALIZING:
                conn.setInitializing();
                break;
            case Connection.STATE_RINGING:
                conn.setRinging();
                break;
        }
    }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        final Bundle extra = request.getExtras();
        Uri number = request.getAddress();
        String name = extra.getString(EXTRA_CALLER_NAME);
        String callUUID = extra.getString(EXTRA_CALL_UUID);
        Boolean isForeground = CallConnectionService.isRunning(this.getApplicationContext());
        JSONObject settings = CallKeepModule.getSettings(this);
        Integer timeout = settings.has("displayCallReachabilityTimeout")
            ? settings.optInt("displayCallReachabilityTimeout")
            : null;

        Log.d(
            TAG,
            "[CallConnectionService] onCreateIncomingConnection, name:" +
            name +
            ", number" +
            number +
            ", isForeground: " +
            isForeground +
            ", isReachable:" +
            isReachable +
            ", timeout: " +
            timeout
        );

        Connection incomingCallConnection = createConnection(request);
        if (incomingCallConnection == null) {
            return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR));
        }
        incomingCallConnection.setRinging();
        incomingCallConnection.setInitialized();

        if (timeout != null) {
            this.checkForAppReachability(callUUID, timeout);
        }

        return incomingCallConnection;
    }

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        CallConnectionService.hasOutgoingCall = true;

        Bundle extras = request.getExtras();
        String callUUID = extras.getString(EXTRA_CALL_UUID);

        if (callUUID == null || callUUID.isEmpty()) {
            callUUID = UUID.randomUUID().toString();
        }

        Log.d(TAG, "[CallConnectionService] onCreateOutgoingConnection, uuid:" + callUUID);

        if (!isInitialized && !isReachable) {
            this.notReachableCallUuid = callUUID;
            this.currentConnectionRequest = request;
            this.checkReachability();
        }

        return this.makeOutgoingCall(request, callUUID, false);
    }

    private Connection makeOutgoingCall(ConnectionRequest request, String uuid, Boolean forceWakeUp) {
        Bundle extras = request.getExtras();
        Connection outgoingCallConnection = null;
        String number = request.getAddress().getSchemeSpecificPart();
        String extrasNumber = extras.getString(EXTRA_CALL_NUMBER);
        String displayName = extras.getString(EXTRA_CALLER_NAME);
        Boolean isForeground = CallConnectionService.isRunning(this.getApplicationContext());

        Log.d(TAG, "[CallConnectionService] makeOutgoingCall, uuid:" + uuid + ", number: " + number + ", displayName:" + displayName);

        // Wakeup application if needed
        if (!isForeground || forceWakeUp) {
            Log.d(TAG, "[CallConnectionService] onCreateOutgoingConnection: Waking up application");
            this.wakeUpApplication(uuid, number, displayName);
        } else if (!this.canMakeOutgoingCall() && isReachable) {
            Log.d(TAG, "[CallConnectionService] onCreateOutgoingConnection: not available");
            return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.LOCAL));
        }

        // TODO: Hold all other calls
        if (extrasNumber == null || !extrasNumber.equals(number)) {
            extras.putString(EXTRA_CALL_UUID, uuid);
            extras.putString(EXTRA_CALLER_NAME, displayName);
            extras.putString(EXTRA_CALL_NUMBER, number);
        }

        if (!canMakeMultipleCalls) {
            Log.d(TAG, "[CallConnectionService] onCreateOutgoingConnection: disabling multi calls");
            extras.putBoolean(EXTRA_DISABLE_ADD_CALL, true);
        }

        outgoingCallConnection = createConnection(request);
        if (outgoingCallConnection == null) {
            return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR));
        }
        outgoingCallConnection.setDialing();
        outgoingCallConnection.setAudioModeIsVoip(true);
        outgoingCallConnection.setCallerDisplayName(displayName, TelecomManager.PRESENTATION_ALLOWED);

        CallForegroundService.start(this, uuid, displayName);

        // ‍️Weirdly on some Samsung phones (A50, S9...) using `setInitialized` will not display the native UI ...
        // when making a call from the native Phone application. The call will still be displayed correctly without it.
        if (!Build.MANUFACTURER.equalsIgnoreCase("Samsung")) {
            Log.d(TAG, "[CallConnectionService] onCreateOutgoingConnection: initializing connection on non-Samsung device");
            outgoingCallConnection.setInitialized();
        }

        HashMap<String, String> extrasMap = this.bundleToMap(extras);

        sendCallRequestToActivity(ACTION_ONGOING_CALL, extrasMap, true);
        sendCallRequestToActivity(ACTION_AUDIO_SESSION, extrasMap, true);

        Log.d(TAG, "[CallConnectionService] onCreateOutgoingConnection: done");

        return outgoingCallConnection;
    }

    /**
     * Callkeep starts a HeadlessJS task here. Capacitor has no headless JS, so we
     * bring the app's launch activity up instead; the ACTION_ONGOING_CALL event is
     * queued by CallEventBus and delivered when the plugin loads.
     */
    private void wakeUpApplication(String uuid, String number, String displayName) {
        Log.d(TAG, "[CallConnectionService] wakeUpApplication, uuid:" + uuid + ", number :" + number + ", displayName:" + displayName);

        // Avoid to call wake up the app again in wakeUpAfterReachabilityTimeout.
        this.currentConnectionRequest = null;

        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(launch);
            }
        } catch (Exception e) {
            Log.w(TAG, "[CallConnectionService] wakeUpApplication, error" + e.toString());
        }
    }

    private void wakeUpAfterReachabilityTimeout(ConnectionRequest request) {
        if (this.currentConnectionRequest == null) {
            return;
        }
        Bundle extras = request.getExtras();
        String number = request.getAddress().getSchemeSpecificPart();
        String displayName = extras.getString(EXTRA_CALLER_NAME);
        Log.d(TAG, "[CallConnectionService] checkReachability timeout, force wakeup, number :" + number + ", displayName: " + displayName);

        wakeUpApplication(this.notReachableCallUuid, number, displayName);

        CallConnectionService.currentConnectionRequest = null;
    }

    private void checkReachability() {
        Log.d(TAG, "[CallConnectionService] checkReachability");

        final CallConnectionService instance = this;
        sendCallRequestToActivity(ACTION_CHECK_REACHABILITY, null, true);

        new Handler(Looper.getMainLooper()).postDelayed(
            () -> instance.wakeUpAfterReachabilityTimeout(instance.currentConnectionRequest),
            2000
        );
    }

    private Boolean canMakeOutgoingCall() {
        return isAvailable;
    }

    private Connection createConnection(ConnectionRequest request) {
        Bundle extras = request.getExtras();
        if (request.getAddress() == null) {
            return null;
        }
        HashMap<String, String> extrasMap = this.bundleToMap(extras);

        String callerNumber = request.getAddress().toString();
        Log.d(TAG, "[CallConnectionService] createConnection, callerNumber:" + callerNumber);

        if (callerNumber.contains(":")) {
            //CallerNumber contains a schema which we'll separate out
            int schemaIndex = callerNumber.indexOf(":");
            String number = callerNumber.substring(schemaIndex + 1);
            String schema = callerNumber.substring(0, schemaIndex);

            extrasMap.put(EXTRA_CALL_NUMBER, number);
            extrasMap.put(EXTRA_CALL_NUMBER_SCHEMA, schema);
        } else {
            extrasMap.put(EXTRA_CALL_NUMBER, callerNumber);
        }

        CallConnection connection = new CallConnection(this, extrasMap);
        connection.setConnectionCapabilities(Connection.CAPABILITY_MUTE | Connection.CAPABILITY_SUPPORT_HOLD);

        // If the phone account is self managed, then this connection must also be self managed.
        // Callkeep asks TelecomManager.getPhoneAccount() here, which needs READ_PHONE_NUMBERS on
        // Android 13+ and crashes self-managed apps. We registered the account, so use our setting.
        if (CallKeepModule.getInstance(getApplicationContext()).isSelfManaged()) {
            Log.d(TAG, "[CallConnectionService] PhoneAccount is SELF_MANAGED, so connection will be too");
            connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        } else {
            Log.d(TAG, "[CallConnectionService] PhoneAccount is not SELF_MANAGED, so connection won't be either");
        }

        connection.setInitializing();
        connection.setExtras(extras);
        currentConnections.put(extras.getString(EXTRA_CALL_UUID), connection);

        // Get other connections for conferencing
        Map<String, CallConnection> otherConnections = new HashMap<>();
        for (Map.Entry<String, CallConnection> entry : currentConnections.entrySet()) {
            if (!(extras.getString(EXTRA_CALL_UUID).equals(entry.getKey()))) {
                otherConnections.put(entry.getKey(), entry.getValue());
            }
        }
        List<Connection> conferenceConnections = new ArrayList<Connection>(otherConnections.values());
        connection.setConferenceableConnections(conferenceConnections);

        return connection;
    }

    @Override
    public void onConference(Connection connection1, Connection connection2) {
        Log.d(TAG, "[CallConnectionService] onConference");
        super.onConference(connection1, connection2);
        CallConnection callConnection1 = (CallConnection) connection1;
        CallConnection callConnection2 = (CallConnection) connection2;

        CallConference callConference = new CallConference(phoneAccountHandle);
        callConference.addConnection(callConnection1);
        callConference.addConnection(callConnection2);

        connection1.onUnhold();
        connection2.onUnhold();

        this.addConference(callConference);
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request);
        Log.w(TAG, "[CallConnectionService] onCreateIncomingConnectionFailed: " + request);

        Bundle extras = request.getExtras();
        HashMap<String, String> extrasMap = this.bundleToMap(extras);

        String callerNumber = request.getAddress() != null ? request.getAddress().toString() : "";
        if (callerNumber.contains(":")) {
            //CallerNumber contains a schema which we'll separate out
            int schemaIndex = callerNumber.indexOf(":");
            String number = callerNumber.substring(schemaIndex + 1);
            String schema = callerNumber.substring(0, schemaIndex);

            extrasMap.put(EXTRA_CALL_NUMBER, number);
            extrasMap.put(EXTRA_CALL_NUMBER_SCHEMA, schema);
        } else {
            extrasMap.put(EXTRA_CALL_NUMBER, callerNumber);
        }

        sendCallRequestToActivity(ACTION_ON_CREATE_CONNECTION_FAILED, extrasMap, true);
    }

    // When a listener is available for `sendCallRequestToActivity`, send delayed events.
    public static void startObserving() {
        new Handler(Looper.getMainLooper()).post(() -> {
            int count = delayedEvents.size();
            Log.d(TAG, "[CallConnectionService] startObserving, event count: " + count);

            if (currentConnectionService != null) {
                for (Bundle event : delayedEvents) {
                    String action = event.getString("action");
                    HashMap attributeMap = (HashMap) event.getSerializable("attributeMap");

                    currentConnectionService.sendCallRequestToActivity(action, attributeMap, false);
                }
            }

            delayedEvents = new ArrayList<Bundle>();
        });
    }

    /*
     * Send call request to CallKeepModule
     */
    private void sendCallRequestToActivity(final String action, @Nullable final HashMap attributeMap, final boolean retry) {
        final CallConnectionService instance = this;

        Log.d(TAG, "[CallConnectionService] sendCallRequestToActivity, action:" + action);

        new Handler(Looper.getMainLooper()).post(() -> {
            Intent intent = new Intent(action);
            Bundle extras = new Bundle();
            extras.putString("action", action);

            if (attributeMap != null) {
                extras.putSerializable("attributeMap", attributeMap);
                intent.putExtras(extras);
            }

            boolean result = LocalBroadcastManager.getInstance(instance).sendBroadcast(intent);
            if (!result && retry) {
                // Event will be sent later when a listener will be available.
                delayedEvents.add(extras);
            }
        });
    }

    private HashMap<String, String> bundleToMap(Bundle extras) {
        HashMap<String, String> extrasMap = new HashMap<>();
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value != null) {
                extrasMap.put(key, value.toString());
            }
        }
        return extrasMap;
    }

    /**
     * https://stackoverflow.com/questions/5446565/android-how-do-i-check-if-activity-is-running
     *
     * @param context Context
     * @return boolean
     */
    public static boolean isRunning(Context context) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<RunningTaskInfo> tasks = activityManager.getRunningTasks(Integer.MAX_VALUE);

        for (RunningTaskInfo task : tasks) {
            if (task.baseActivity != null && context.getPackageName().equalsIgnoreCase(task.baseActivity.getPackageName())) {
                return true;
            }
        }

        Log.d(TAG, "[CallConnectionService] isRunning: no running package found.");

        return false;
    }

    private void checkForAppReachability(final String callUUID, final Integer timeout) {
        final CallConnectionService instance = this;

        new Handler(Looper.getMainLooper()).postDelayed(
            () -> {
                if (instance.isReachable) {
                    return;
                }
                Connection conn = CallConnectionService.getConnection(callUUID);
                Log.w(
                    TAG,
                    "[CallConnectionService] checkForAppReachability timeout after " +
                    timeout +
                    " ms, isReachable:" +
                    instance.isReachable +
                    ", uuid: " +
                    callUUID
                );

                if (conn == null) {
                    Log.w(TAG, "[CallConnectionService] checkForAppReachability timeout, no connection to close with uuid: " + callUUID);

                    return;
                }
                conn.onDisconnect();
            },
            timeout
        );
    }
}
