/*
 * Ported from livekit/react-native-callkeep (io.wazo.callkeep.RNCallKeepModule).
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

import static io.kreador.callkit.Constants.ACTION_ANSWER_CALL;
import static io.kreador.callkit.Constants.ACTION_AUDIO_SESSION;
import static io.kreador.callkit.Constants.ACTION_CHECK_REACHABILITY;
import static io.kreador.callkit.Constants.ACTION_DID_CHANGE_AUDIO_ROUTE;
import static io.kreador.callkit.Constants.ACTION_DTMF_TONE;
import static io.kreador.callkit.Constants.ACTION_END_CALL;
import static io.kreador.callkit.Constants.ACTION_HOLD_CALL;
import static io.kreador.callkit.Constants.ACTION_MUTE_CALL;
import static io.kreador.callkit.Constants.ACTION_ONGOING_CALL;
import static io.kreador.callkit.Constants.ACTION_ON_CREATE_CONNECTION_FAILED;
import static io.kreador.callkit.Constants.ACTION_ON_SILENCE_INCOMING_CALL;
import static io.kreador.callkit.Constants.ACTION_REJECT_CALL;
import static io.kreador.callkit.Constants.ACTION_SHOW_INCOMING_CALL_UI;
import static io.kreador.callkit.Constants.ACTION_STATE_CHANGED;
import static io.kreador.callkit.Constants.ACTION_UNHOLD_CALL;
import static io.kreador.callkit.Constants.ACTION_UNMUTE_CALL;
import static io.kreador.callkit.Constants.EXTRA_CALLER_NAME;
import static io.kreador.callkit.Constants.EXTRA_CALL_NUMBER;
import static io.kreador.callkit.Constants.EXTRA_CALL_UUID;
import static io.kreador.callkit.Constants.EXTRA_HAS_VIDEO;
import static io.kreador.callkit.Constants.EXTRA_PAYLOAD;
import static io.kreador.callkit.Constants.PREFS_NAME;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Port of RNCallKeepModule without React Native. It only needs a Context, so the
 * FCM service and notification actions can drive calls while the app / WebView is
 * not running. CallKitPlugin is a thin Capacitor bridge on top of it, and events go
 * through CallEventBus instead of RCTDeviceEventEmitter.
 *
 * Methods that callkeep silently ignores throw CallKeepException instead, so the
 * plugin can reject the JS promise with a reason.
 */
public class CallKeepModule {

    public static class CallKeepException extends RuntimeException {

        public CallKeepException(String message) {
            super(message);
        }
    }

    private static final String TAG = "IonicCallkit";
    private static CallKeepModule instance = null;
    private static JSONObject _settings;

    private static TelecomManager telecomManager;
    private static TelephonyManager telephonyManager;
    public static PhoneAccountHandle handle;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private LegacyCallStateListener legacyCallStateListener;
    private CallStateListener callStateListener;
    private boolean isReceiverRegistered = false;
    private VoiceBroadcastReceiver voiceBroadcastReceiver;
    private boolean hasActiveCall = false;
    private final RecentCallIds recentlyEnded = new RecentCallIds();

    public static synchronized CallKeepModule getInstance(Context context) {
        if (instance == null) {
            Log.d(TAG, "[CallKeepModule] getInstance");
            instance = new CallKeepModule(context.getApplicationContext());
            final SharedPreferences prefs = instance.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            CallEventBus.setStore(
                new CallEventBus.Store() {
                    @Override
                    public String read() {
                        return prefs.getString("pendingEvents", null);
                    }

                    @Override
                    public void write(String json) {
                        // commit(): the process may be killed right after an event is queued.
                        prefs.edit().putString("pendingEvents", json).commit();
                    }
                }
            );
            fetchStoredSettings(context);
            instance.registerReceiver();
            instance.initializeTelecomManager();
        }
        return instance;
    }

    public static JSONObject getSettings(@Nullable Context context) {
        if (_settings == null) {
            fetchStoredSettings(context);
        }

        return _settings != null ? _settings : new JSONObject();
    }

    private CallKeepModule(Context context) {
        Log.d(TAG, "[CallKeepModule] constructor");
        this.context = context;
    }

    public Context getContext() {
        return context;
    }

    public void setCurrentActivity(@Nullable Activity activity) {
        currentActivity = new WeakReference<>(activity);
    }

    @Nullable
    public Activity getCurrentActivity() {
        return currentActivity.get();
    }

    public boolean isSelfManaged() {
        // Callkeep defaults to managed mode; ionic-callkit defaults to self-managed.
        return getSettings(context).optBoolean("selfManaged", true);
    }

    // ─── Setup ──────────────────────────────────────────────────────────────

    public void setup(JSONObject options) {
        Log.d(TAG, "[CallKeepModule] setup : " + options);

        CallConnectionService.setAvailable(false);
        CallConnectionService.setInitialized(true);
        this.setSettings(options);

        Log.d(TAG, "[CallKeepModule] setup, self managed: " + isSelfManaged());

        CallConnectionService.setCanMakeMultipleCalls(getSettings(context).optBoolean("canMakeMultipleCalls", true));

        this.registerPhoneAccount();
        this.registerEvents();
        CallConnectionService.setAvailable(true);
    }

    public void setSettings(JSONObject options) {
        if (options == null) {
            return;
        }
        _settings = storeSettings(options);
    }

    public void registerEvents() {
        Log.d(TAG, "[CallKeepModule] registerEvents");
        CallConnectionService.setPhoneAccountHandle(handle);
        CallConnectionService.startObserving();
    }

    public void initializeTelecomManager() {
        ComponentName cName = new ComponentName(context, CallConnectionService.class);
        // Callkeep uses the app label as the handle id; a label change would then orphan the
        // registered account. Use the package name so the id is stable.
        handle = new PhoneAccountHandle(cName, context.getPackageName());
        telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    public void registerPhoneAccount() {
        Log.d(TAG, "[CallKeepModule] registerPhoneAccount");
        this.initializeTelecomManager();

        JSONObject settings = getSettings(context);
        String appName = settings.optString("appName", "");
        if (appName.isEmpty()) {
            appName = this.getApplicationName(context);
        }

        PhoneAccount.Builder builder = new PhoneAccount.Builder(handle, appName);
        int capabilities = isSelfManaged() ? PhoneAccount.CAPABILITY_SELF_MANAGED : PhoneAccount.CAPABILITY_CALL_PROVIDER;
        if (settings.optBoolean("supportsVideo", false)) {
            capabilities |= PhoneAccount.CAPABILITY_VIDEO_CALLING | PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING;
        }
        builder.setCapabilities(capabilities);

        String imageName = settings.optString("imageName", "");
        if (!imageName.isEmpty()) {
            int identifier = context.getResources().getIdentifier(imageName, "drawable", context.getPackageName());
            if (identifier != 0) {
                builder.setIcon(Icon.createWithResource(context, identifier));
            }
        }

        PhoneAccount account = builder.build();
        telecomManager.registerPhoneAccount(account);
    }

    /**
     * The phone account survives process death but not a reinstall / data wipe.
     * A push can arrive before JS ever called setup(), so register it on demand.
     */
    public void ensurePhoneAccount() {
        // Checking with getPhoneAccount() needs READ_PHONE_NUMBERS on Android 13+, which
        // self-managed apps don't hold. Registering is idempotent, so just (re)register.
        registerPhoneAccount();
        registerEvents();
    }

    // ─── Incoming ───────────────────────────────────────────────────────────

    public void reportNewIncomingCall(
        String uuid,
        String number,
        String callerName,
        boolean hasVideo,
        @Nullable JSONObject payload
    ) {
        Log.d(TAG, "[CallKeepModule] reportNewIncomingCall, uuid: " + uuid + ", number: " + number + ", callerName: " + callerName);

        this.displayIncomingCall(uuid, number, callerName, hasVideo, payload);

        // Send event to JS
        JSObject args = new JSObject();
        args.put("handle", number);
        args.put("callId", uuid);
        args.put("callerName", callerName);
        args.put("hasVideo", hasVideo);
        if (payload != null) {
            args.put("payload", payload);
        }
        sendEventToJS("incomingCall", args);
    }

    public void displayIncomingCall(String uuid, String number, String callerName, boolean hasVideo, @Nullable JSONObject payload) {
        if (CallConnectionService.getConnection(uuid) != null) {
            // Duplicate push (FCM can deliver twice): one Telecom call per call id.
            throw new CallKeepException("displayIncomingCall ignored: call " + uuid + " already exists");
        }
        if (recentlyEnded.recentlyEnded(uuid)) {
            // A late push for a call that already ended (e.g. call_ended arrived first).
            throw new CallKeepException("displayIncomingCall ignored: call " + uuid + " already ended");
        }
        if (!hasPhoneAccount()) {
            throw new CallKeepException("displayIncomingCall ignored: phone account not registered or not enabled");
        }
        if (isBusy()) {
            // Callkeep only applies canMakeMultipleCalls to outgoing calls. Refuse a second
            // incoming call too, and tell JS so the app can answer "busy" to the caller.
            JSObject args = new JSObject();
            args.put("callId", uuid);
            args.put("handle", number);
            args.put("callerName", callerName);
            args.put("error", "busy");
            sendEventToJS("incomingCallFailed", args);
            throw new CallKeepException("displayIncomingCall ignored: busy (canMakeMultipleCalls is false and a call is in progress)");
        }

        Log.d(
            TAG,
            "[CallKeepModule] displayIncomingCall, uuid: " +
            uuid +
            ", number: " +
            number +
            ", callerName: " +
            callerName +
            ", hasVideo: " +
            hasVideo +
            ", payload: " +
            payload
        );

        Bundle extras = new Bundle();
        Uri uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);

        extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, uri);
        extras.putString(EXTRA_CALLER_NAME, callerName);
        extras.putString(EXTRA_CALL_UUID, uuid);
        extras.putString(EXTRA_HAS_VIDEO, String.valueOf(hasVideo));
        if (payload != null) {
            // Callkeep stores a Bundle; the connection flattens extras to strings, so keep JSON.
            extras.putString(EXTRA_PAYLOAD, payload.toString());
        }
        this.listenToNativeCallsState();
        try {
            telecomManager.addNewIncomingCall(handle, extras);
        } catch (SecurityException e) {
            throw new CallKeepException("addNewIncomingCall refused: " + e.getMessage());
        }
    }

    public void answerIncomingCall(String uuid) {
        Log.d(TAG, "[CallKeepModule] answerIncomingCall, uuid: " + uuid);
        Connection conn = requireConnection(uuid, "answerIncomingCall");
        mainHandler.post(conn::onAnswer);
    }

    // ─── Outgoing ───────────────────────────────────────────────────────────

    public void startCall(String uuid, String number, String callerName, boolean hasVideo) {
        Log.d(TAG, "[CallKeepModule] startCall called, uuid: " + uuid + ", number: " + number + ", callerName: " + callerName);

        if (!hasPhoneAccount()) {
            throw new CallKeepException("startCall ignored: phone account not registered or not enabled");
        }
        if (!hasPermissions()) {
            throw new CallKeepException("startCall ignored: missing permissions " + String.join(", ", getRequiredPermissions()));
        }
        if (number == null) {
            throw new CallKeepException("startCall ignored: no handle");
        }
        if (isBusy()) {
            throw new CallKeepException("startCall ignored: busy (canMakeMultipleCalls is false and a call is in progress)");
        }

        Bundle extras = new Bundle();
        Uri uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);

        Bundle callExtras = new Bundle();
        callExtras.putString(EXTRA_CALLER_NAME, callerName);
        callExtras.putString(EXTRA_CALL_UUID, uuid);
        callExtras.putString(EXTRA_CALL_NUMBER, number);
        callExtras.putString(EXTRA_HAS_VIDEO, String.valueOf(hasVideo));

        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle);
        extras.putParcelable(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callExtras);

        Log.d(TAG, "[CallKeepModule] startCall, uuid: " + uuid);
        this.listenToNativeCallsState();
        try {
            telecomManager.placeCall(uri, extras);
        } catch (SecurityException e) {
            throw new CallKeepException("placeCall refused: " + e.getMessage());
        }
    }

    public void setCurrentCallActive(String uuid) {
        Log.d(TAG, "[CallKeepModule] setCurrentCallActive, uuid: " + uuid);
        Connection conn = requireConnection(uuid, "setCurrentCallActive");
        mainHandler.post(() -> {
            conn.setConnectionCapabilities(conn.getConnectionCapabilities() | Connection.CAPABILITY_HOLD);
            conn.setActive();
        });
    }

    // ─── Ending ─────────────────────────────────────────────────────────────

    public void endCall(String uuid) {
        Log.d(TAG, "[CallKeepModule] endCall called, uuid: " + uuid);
        Connection conn = requireConnection(uuid, "endCall");
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_NORMAL);
        mainHandler.post(conn::onDisconnect);
        this.stopListenToNativeCallsState();
        this.hasActiveCall = false;
        Log.d(TAG, "[CallKeepModule] endCall executed, uuid: " + uuid);
    }

    public void endAllCalls() {
        Log.d(TAG, "[CallKeepModule] endAllCalls called");
        ArrayList<CallConnection> connections = new ArrayList<>(CallConnectionService.currentConnections.values());
        mainHandler.post(() -> {
            for (Connection connectionToEnd : connections) {
                connectionToEnd.onDisconnect();
            }
        });
        this.stopListenToNativeCallsState();
        this.hasActiveCall = false;
        Log.d(TAG, "[CallKeepModule] endAllCalls executed");
    }

    public void rejectCall(String uuid) {
        Log.d(TAG, "[CallKeepModule] rejectCall, uuid: " + uuid);
        Connection conn = requireConnection(uuid, "rejectCall");
        this.stopListenToNativeCallsState();
        mainHandler.post(conn::onReject);
    }

    public void reportEndCallWithUUID(String uuid, int reason) {
        Log.d(TAG, "[CallKeepModule] reportEndCallWithUUID, uuid: " + uuid + ", reason: " + reason);
        CallConnection conn = (CallConnection) requireConnection(uuid, "reportEndCallWithUUID");
        mainHandler.post(() -> conn.reportDisconnect(reason));
        this.stopListenToNativeCallsState();
    }

    /**
     * Remote side hung up (e.g. "call_ended" push). Missed if an incoming call was never
     * answered. Unlike reportEndCallWithUUID (called by JS, which already knows), JS is
     * told via `callEnded` with the reason.
     */
    public void reportRemoteEnded(String uuid) {
        recentlyEnded.markEnded(uuid);
        CallConnection conn = (CallConnection) CallConnectionService.getConnection(uuid);
        if (conn == null) {
            IncomingCallNotification.cancel(context, uuid);
            return;
        }
        // Callkeep leaves ringing connections in STATE_NEW (setRinging() then setInitialized()),
        // so check "answered" rather than the connection state.
        boolean missed = !conn.isAnswered() && conn.getState() != Connection.STATE_DIALING;
        int reason = missed ? 6 : 2;
        reportEndCallWithUUID(uuid, reason);

        JSObject args = new JSObject();
        args.put("callId", uuid);
        args.put("reason", reason);
        sendEventToJS("callEnded", args);
    }

    /** Callkeep's onHostDestroy: end calls when the activity is finishing (no killProcess). */
    public void onHostDestroy() {
        Log.d(TAG, "[CallKeepModule] onHostDestroy called");
        endAllCalls();
    }

    // ─── In-call controls ───────────────────────────────────────────────────

    public void setOnHold(String uuid, boolean shouldHold) {
        Log.d(TAG, "[CallKeepModule] setOnHold, uuid: " + uuid + ", shouldHold: " + (shouldHold ? "true" : "false"));
        Connection conn = requireConnection(uuid, "setOnHold");
        mainHandler.post(() -> {
            if (shouldHold) {
                conn.onHold();
            } else {
                conn.onUnhold();
            }
        });
    }

    /** Callkeep's setConnectionState, with the state as a string: dialing | ringing | active | held | initializing. */
    public void setCallState(String uuid, String state) {
        final int value;
        switch (state) {
            case "dialing":
                value = Connection.STATE_DIALING;
                break;
            case "ringing":
                value = Connection.STATE_RINGING;
                break;
            case "active":
                value = Connection.STATE_ACTIVE;
                break;
            case "held":
                value = Connection.STATE_HOLDING;
                break;
            case "initializing":
                value = Connection.STATE_INITIALIZING;
                break;
            default:
                throw new CallKeepException("setCallState: unknown state '" + state + "'");
        }
        requireConnection(uuid, "setCallState");
        mainHandler.post(() -> CallConnectionService.setState(uuid, value));
    }

    public void setCanMakeMultipleCalls(boolean allow) {
        CallConnectionService.setCanMakeMultipleCalls(allow);
        // Persist so a push handled while the app is killed respects it too.
        try {
            JSONObject settings = new JSONObject(getSettings(context).toString());
            settings.put("canMakeMultipleCalls", allow);
            setSettings(settings);
        } catch (JSONException e) {
            Log.w(TAG, "[CallKeepModule] setCanMakeMultipleCalls: " + e);
        }
    }

    private boolean isBusy() {
        return !getSettings(context).optBoolean("canMakeMultipleCalls", true) && !CallConnectionService.currentConnections.isEmpty();
    }

    public void setMutedCall(String uuid, boolean shouldMute) {
        Log.d(TAG, "[CallKeepModule] setMutedCall, uuid: " + uuid + ", shouldMute: " + (shouldMute ? "true" : "false"));
        Connection conn = requireConnection(uuid, "setMutedCall");

        CallAudioState current = conn.getCallAudioState();
        int route = current != null ? current.getRoute() : CallAudioState.ROUTE_EARPIECE;
        int mask = current != null ? current.getSupportedRouteMask() : CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER;
        //if the requester wants to mute, do that. otherwise unmute
        CallAudioState newAudioState = new CallAudioState(shouldMute, route, mask);
        mainHandler.post(() -> conn.onCallAudioStateChanged(newAudioState));
    }

    /**
     * toggle audio route for speaker via connection service function.
     * With no uuid, applies to every current call.
     */
    public void toggleAudioRouteSpeaker(@Nullable String uuid, boolean routeSpeaker) {
        Log.d(TAG, "[CallKeepModule] toggleAudioRouteSpeaker, uuid: " + uuid + ", routeSpeaker: " + (routeSpeaker ? "true" : "false"));
        ArrayList<CallConnection> targets = new ArrayList<>();
        if (uuid != null) {
            targets.add((CallConnection) requireConnection(uuid, "toggleAudioRouteSpeaker"));
        } else {
            targets.addAll(CallConnectionService.currentConnections.values());
        }
        if (targets.isEmpty()) {
            throw new CallKeepException("toggleAudioRouteSpeaker ignored because there is no call");
        }
        mainHandler.post(() -> {
            for (CallConnection conn : targets) {
                conn.setAudioRoute(routeSpeaker ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_WIRED_OR_EARPIECE);
            }
        });
    }

    public void setAudioRoute(String uuid, String audioRoute) {
        CallConnection conn = (CallConnection) requireConnection(uuid, "setAudioRoute");
        final int route;
        switch (audioRoute) {
            case "Bluetooth":
                route = CallAudioState.ROUTE_BLUETOOTH;
                break;
            case "Headset":
                route = CallAudioState.ROUTE_WIRED_HEADSET;
                break;
            case "Speaker":
                route = CallAudioState.ROUTE_SPEAKER;
                break;
            default:
                route = CallAudioState.ROUTE_WIRED_OR_EARPIECE;
                break;
        }
        Log.d(TAG, "[CallKeepModule] setting audio route: " + audioRoute);
        mainHandler.post(() -> conn.setAudioRoute(route));
    }

    public JSArray getAudioRoutes() {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        JSArray devices = new JSArray();
        ArrayList<String> typeChecker = new ArrayList<>();
        AudioDeviceInfo[] audioDeviceInfo = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS + AudioManager.GET_DEVICES_OUTPUTS);
        String selectedAudioRoute = getSelectedAudioRoute(audioManager);
        for (AudioDeviceInfo device : audioDeviceInfo) {
            String type = getAudioRouteType(device.getType());
            if (type != null && !typeChecker.contains(type)) {
                JSObject deviceInfo = new JSObject();
                deviceInfo.put("name", type);
                deviceInfo.put("type", type);
                if (type.equals(selectedAudioRoute)) {
                    deviceInfo.put("selected", true);
                }
                typeChecker.add(type);
                devices.put(deviceInfo);
            }
        }
        return devices;
    }

    private String getAudioRouteType(int type) {
        switch (type) {
            case (AudioDeviceInfo.TYPE_BLUETOOTH_A2DP):
            case (AudioDeviceInfo.TYPE_BLUETOOTH_SCO):
                return "Bluetooth";
            case (AudioDeviceInfo.TYPE_WIRED_HEADPHONES):
            case (AudioDeviceInfo.TYPE_WIRED_HEADSET):
                return "Headset";
            case (AudioDeviceInfo.TYPE_BUILTIN_MIC):
                return "Phone";
            case (AudioDeviceInfo.TYPE_BUILTIN_SPEAKER):
                return "Speaker";
            default:
                return null;
        }
    }

    private String getSelectedAudioRoute(AudioManager audioManager) {
        if (audioManager.isBluetoothScoOn()) {
            return "Bluetooth";
        }
        if (audioManager.isSpeakerphoneOn()) {
            return "Speaker";
        }
        if (audioManager.isWiredHeadsetOn()) {
            return "Headset";
        }
        return "Phone";
    }

    public void sendDTMF(String uuid, String key) {
        Log.d(TAG, "[CallKeepModule] sendDTMF, uuid: " + uuid + ", key: " + key);
        Connection conn = requireConnection(uuid, "sendDTMF");
        if (key == null || key.isEmpty()) {
            throw new CallKeepException("sendDTMF ignored: no digits");
        }
        mainHandler.post(() -> {
            for (char dtmf : key.toCharArray()) {
                conn.onPlayDtmfTone(dtmf);
            }
        });
    }

    public void updateDisplay(String uuid, String displayName, String uri) {
        Log.d(TAG, "[CallKeepModule] updateDisplay, uuid: " + uuid + ", displayName: " + displayName + ", uri: " + uri);
        Connection conn = requireConnection(uuid, "updateDisplay");
        mainHandler.post(() -> {
            conn.setAddress(Uri.parse(uri), TelecomManager.PRESENTATION_ALLOWED);
            conn.setCallerDisplayName(displayName, TelecomManager.PRESENTATION_ALLOWED);
        });
    }

    public JSArray getActiveCalls() {
        JSArray calls = new JSArray();
        for (Map.Entry<String, CallConnection> entry : CallConnectionService.currentConnections.entrySet()) {
            HashMap<String, String> attrs = entry.getValue().getHandle();
            JSObject call = new JSObject();
            call.put("callId", entry.getKey());
            call.put("callerName", attrs.get(EXTRA_CALLER_NAME));
            call.put("handle", attrs.get(EXTRA_CALL_NUMBER));
            call.put("state", CallConnectionService.stateToString(entry.getValue().getState()));
            calls.put(call);
        }
        return calls;
    }

    // ─── Availability / phone account ───────────────────────────────────────

    public void setAvailable(boolean active) {
        CallConnectionService.setAvailable(active);
    }

    public void setReachable() {
        CallConnectionService.setReachable();
    }

    public void openPhoneAccounts() {
        Log.d(TAG, "[CallKeepModule] openPhoneAccounts");

        if (Build.MANUFACTURER.equalsIgnoreCase("Samsung") || Build.MANUFACTURER.equalsIgnoreCase("OnePlus")) {
            Intent intent = new Intent();
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            intent.setComponent(
                new ComponentName("com.android.server.telecom", "com.android.server.telecom.settings.EnableAccountPreferenceActivity")
            );
            try {
                context.startActivity(intent);
                return;
            } catch (Exception e) {
                Log.w(TAG, "[CallKeepModule] openPhoneAccounts vendor screen failed: " + e);
            }
        }

        openPhoneAccountSettings();
    }

    public void openPhoneAccountSettings() {
        Log.d(TAG, "[CallKeepModule] openPhoneAccountSettings");
        Intent intent = new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        context.startActivity(intent);
    }

    public boolean canUseFullScreenIntent() {
        if (Build.VERSION.SDK_INT < 34) {
            return true;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm.canUseFullScreenIntent();
    }

    public void openFullScreenIntentSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 34) {
            intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.getPackageName()));
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.getPackageName()));
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public void backToForeground() {
        String packageName = context.getPackageName();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            throw new CallKeepException("backToForeground: no launch activity");
        }
        Intent focusIntent = launch.cloneFilter();
        Activity activity = getCurrentActivity();
        boolean isOpened = activity != null;
        Log.d(TAG, "[CallKeepModule] backToForeground, app isOpened ?" + (isOpened ? "true" : "false"));

        if (isOpened) {
            focusIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(focusIntent);
        } else {
            // Callkeep ORs WindowManager flags into the Intent flags here, which has no
            // effect. Showing over the lock screen is handled by CallKitPlugin instead.
            focusIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(focusIntent);
        }
    }

    public boolean hasPhoneAccount() {
        if (telecomManager == null) {
            this.initializeTelecomManager();
        }

        if (isSelfManaged()) {
            return true;
        }

        try {
            PhoneAccount account = telecomManager.getPhoneAccount(handle);
            return hasPermissions() && account != null && account.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    public String[] getRequiredPermissions() {
        // If we're running in self managed mode we need fewer permissions.
        if (isSelfManaged()) {
            return new String[] { Manifest.permission.RECORD_AUDIO };
        }
        return new String[] {
            Build.VERSION.SDK_INT < 30 ? Manifest.permission.READ_PHONE_STATE : Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO
        };
    }

    public boolean hasPermissions() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** Called whenever a connection goes away, so late pushes for it are ignored. */
    public void markEnded(String uuid) {
        recentlyEnded.markEnded(uuid);
    }

    public void onNewToken(String token) {
        JSObject args = new JSObject();
        args.put("token", token);
        sendEventToJS("voipToken", args);
    }

    // ─── Native call state (managed calls from the SIM) ─────────────────────

    /**
     * Monitors and logs phone call activities, and shows the phone state
     */
    private class LegacyCallStateListener extends PhoneStateListener {

        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            CallKeepModule.this.onNativeCallStateChanged(state);
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class CallStateListener extends TelephonyCallback implements TelephonyCallback.CallStateListener {

        @Override
        public void onCallStateChanged(int state) {
            CallKeepModule.this.onNativeCallStateChanged(state);
        }
    }

    private void onNativeCallStateChanged(int state) {
        if (state != TelephonyManager.CALL_STATE_OFFHOOK) {
            return;
        }
        // Check if there is active call in native
        boolean isInManagedCall = this.checkIsInManagedCall();

        // Only let the JS side know if there is active app call & active native call
        if (this.hasActiveCall && isInManagedCall) {
            sendEventToJS("hasActiveCall", new JSObject());
        } else if (CallConnectionService.currentConnections.size() > 0) {
            // Will enter here for the first time to mark the app has active call
            this.hasActiveCall = true;
        }
    }

    public void stopListenToNativeCallsState() {
        Log.d(TAG, "[CallKeepModule] stopListenToNativeCallsState");
        mainHandler.post(() -> {
            if (telephonyManager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && callStateListener != null) {
                telephonyManager.unregisterTelephonyCallback(callStateListener);
                callStateListener = null;
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && legacyCallStateListener != null) {
                telephonyManager.listen(legacyCallStateListener, PhoneStateListener.LISTEN_NONE);
                legacyCallStateListener = null;
            }
        });
    }

    public void listenToNativeCallsState() {
        Log.d(TAG, "[CallKeepModule] listenToNativeCallsState");
        int permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED || telephonyManager == null) {
            return;
        }

        // Callkeep calls Looper.prepare()/Looper.loop() on the calling thread for the legacy
        // listener, which blocks that thread forever. Register on the main looper instead.
        mainHandler.post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (callStateListener == null) {
                    callStateListener = new CallStateListener();
                    telephonyManager.registerTelephonyCallback(context.getMainExecutor(), callStateListener);
                }
            } else if (legacyCallStateListener == null) {
                legacyCallStateListener = new LegacyCallStateListener();
                telephonyManager.listen(legacyCallStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            }
        });
    }

    @SuppressLint("MissingPermission") // READ_PHONE_STATE is checked just below
    public boolean checkIsInManagedCall() {
        int permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE);

        if (permissionCheck == PackageManager.PERMISSION_GRANTED && telecomManager != null) {
            return telecomManager.isInManagedCall();
        }
        return false;
    }

    // ─── Internals ──────────────────────────────────────────────────────────

    private Connection requireConnection(String uuid, String method) {
        Connection conn = CallConnectionService.getConnection(uuid);
        if (conn == null) {
            throw new CallKeepException(method + " ignored because no connection found, uuid: " + uuid);
        }
        return conn;
    }

    public void sendEventToJS(String eventName, @Nullable JSObject params) {
        Log.v(TAG, "[CallKeepModule] sendEventToJS, eventName: " + eventName + " args : " + (params != null ? params.toString() : "null"));
        CallEventBus.emit(eventName, params);
    }

    private String getApplicationName(Context appContext) {
        ApplicationInfo applicationInfo = appContext.getApplicationInfo();
        int stringId = applicationInfo.labelRes;

        if (stringId != 0) {
            return appContext.getString(stringId);
        }
        return applicationInfo.nonLocalizedLabel != null ? applicationInfo.nonLocalizedLabel.toString() : appContext.getPackageName();
    }

    protected void registerReceiver() {
        if (!isReceiverRegistered) {
            isReceiverRegistered = true;
            voiceBroadcastReceiver = new VoiceBroadcastReceiver();
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ACTION_END_CALL);
            intentFilter.addAction(ACTION_REJECT_CALL);
            intentFilter.addAction(ACTION_ANSWER_CALL);
            intentFilter.addAction(ACTION_MUTE_CALL);
            intentFilter.addAction(ACTION_UNMUTE_CALL);
            intentFilter.addAction(ACTION_DTMF_TONE);
            intentFilter.addAction(ACTION_UNHOLD_CALL);
            intentFilter.addAction(ACTION_HOLD_CALL);
            intentFilter.addAction(ACTION_ONGOING_CALL);
            intentFilter.addAction(ACTION_AUDIO_SESSION);
            intentFilter.addAction(ACTION_CHECK_REACHABILITY);
            intentFilter.addAction(ACTION_SHOW_INCOMING_CALL_UI);
            intentFilter.addAction(ACTION_ON_SILENCE_INCOMING_CALL);
            intentFilter.addAction(ACTION_ON_CREATE_CONNECTION_FAILED);
            intentFilter.addAction(ACTION_DID_CHANGE_AUDIO_ROUTE);
            intentFilter.addAction(ACTION_STATE_CHANGED);

            LocalBroadcastManager.getInstance(context).registerReceiver(voiceBroadcastReceiver, intentFilter);

            CallConnectionService.startObserving();
        }
    }

    // Store all callkeep settings in JSON
    private JSONObject storeSettings(JSONObject options) {
        SharedPreferences sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sharedPref.edit().putString("settings", options.toString()).apply();
        return options;
    }

    protected static void fetchStoredSettings(@Nullable Context fromContext) {
        if (instance == null && fromContext == null) {
            Log.w(TAG, "[CallKeepModule][fetchStoredSettings] no instance nor fromContext.");
            return;
        }
        Context context = fromContext != null ? fromContext : instance.getContext();
        _settings = new JSONObject();

        SharedPreferences sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            String jsonString = sharedPref.getString("settings", "{}");
            if (jsonString != null) {
                _settings = new JSONObject(jsonString);
            }
        } catch (JSONException e) {
            Log.w(TAG, "[CallKeepModule][fetchStoredSettings] invalid settings: " + e);
        }
    }

    @Nullable
    private static JSONObject parsePayload(@Nullable String json) {
        if (json == null) {
            return null;
        }
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            return null;
        }
    }

    private class VoiceBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            JSObject args = new JSObject();
            HashMap<String, String> attributeMap = (HashMap<String, String>) intent.getSerializableExtra("attributeMap");
            if (attributeMap == null) {
                attributeMap = new HashMap<>();
            }
            String callId = attributeMap.get(EXTRA_CALL_UUID);

            Log.d(TAG, "[CallKeepModule][onReceive] " + intent.getAction());

            switch (intent.getAction()) {
                case ACTION_END_CALL:
                    args.put("callId", callId);
                    sendEventToJS("callEnded", args);
                    break;
                case ACTION_REJECT_CALL:
                    args.put("callId", callId);
                    sendEventToJS("callRejected", args);
                    break;
                case ACTION_ANSWER_CALL:
                    args.put("callId", callId);
                    args.put("hasVideo", Boolean.valueOf(attributeMap.get(EXTRA_HAS_VIDEO)));
                    sendEventToJS("callAnswered", args);
                    break;
                case ACTION_HOLD_CALL:
                    args.put("hold", true);
                    args.put("callId", callId);
                    sendEventToJS("held", args);
                    break;
                case ACTION_UNHOLD_CALL:
                    args.put("hold", false);
                    args.put("callId", callId);
                    sendEventToJS("held", args);
                    break;
                case ACTION_MUTE_CALL:
                    args.put("muted", true);
                    args.put("callId", callId);
                    sendEventToJS("muted", args);
                    break;
                case ACTION_UNMUTE_CALL:
                    args.put("muted", false);
                    args.put("callId", callId);
                    sendEventToJS("muted", args);
                    break;
                case ACTION_DTMF_TONE:
                    args.put("digits", attributeMap.get("DTMF"));
                    args.put("callId", callId);
                    sendEventToJS("dtmf", args);
                    break;
                case ACTION_ONGOING_CALL:
                    args.put("handle", attributeMap.get(EXTRA_CALL_NUMBER));
                    args.put("callId", callId);
                    args.put("calleeName", attributeMap.get(EXTRA_CALLER_NAME));
                    sendEventToJS("callStarted", args);
                    break;
                case ACTION_AUDIO_SESSION:
                    sendEventToJS("audioSessionActivated", null);
                    break;
                case ACTION_CHECK_REACHABILITY:
                    sendEventToJS("checkReachability", null);
                    break;
                case ACTION_SHOW_INCOMING_CALL_UI:
                    args.put("handle", attributeMap.get(EXTRA_CALL_NUMBER));
                    args.put("callId", callId);
                    args.put("callerName", attributeMap.get(EXTRA_CALLER_NAME));
                    args.put("hasVideo", Boolean.valueOf(attributeMap.get(EXTRA_HAS_VIDEO)));
                    JSONObject payload = parsePayload(attributeMap.get(EXTRA_PAYLOAD));
                    if (payload != null) {
                        args.put("payload", payload);
                    }
                    sendEventToJS("showIncomingCallUi", args);
                    break;
                case ACTION_ON_SILENCE_INCOMING_CALL:
                    args.put("handle", attributeMap.get(EXTRA_CALL_NUMBER));
                    args.put("callId", callId);
                    args.put("callerName", attributeMap.get(EXTRA_CALLER_NAME));
                    sendEventToJS("silenceIncomingCall", args);
                    break;
                case ACTION_ON_CREATE_CONNECTION_FAILED:
                    args.put("handle", attributeMap.get(EXTRA_CALL_NUMBER));
                    args.put("callId", callId);
                    args.put("callerName", attributeMap.get(EXTRA_CALLER_NAME));
                    sendEventToJS("incomingCallFailed", args);
                    break;
                case ACTION_STATE_CHANGED:
                    args.put("callId", callId);
                    args.put("state", attributeMap.get("state"));
                    sendEventToJS("callStateChanged", args);
                    break;
                case ACTION_DID_CHANGE_AUDIO_ROUTE:
                    String output = attributeMap.get("output");
                    args.put("callId", callId);
                    args.put("output", output);
                    sendEventToJS("audioRouteChanged", args);

                    JSObject speaker = new JSObject();
                    speaker.put("callId", callId);
                    speaker.put("on", output != null && output.contains("SPEAKER"));
                    sendEventToJS("speakerChanged", speaker);
                    break;
            }
        }
    }
}
