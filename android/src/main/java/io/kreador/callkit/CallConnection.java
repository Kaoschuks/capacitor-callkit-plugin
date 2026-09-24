/*
 * Ported from livekit/react-native-callkeep (io.wazo.callkeep.VoiceConnection).
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
import static io.kreador.callkit.Constants.ACTION_DID_CHANGE_AUDIO_ROUTE;
import static io.kreador.callkit.Constants.ACTION_DTMF_TONE;
import static io.kreador.callkit.Constants.ACTION_END_CALL;
import static io.kreador.callkit.Constants.ACTION_HOLD_CALL;
import static io.kreador.callkit.Constants.ACTION_MUTE_CALL;
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

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.TelecomManager;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.util.HashMap;

public class CallConnection extends Connection {

    private boolean isMuted = false;
    private boolean answered = false;
    private boolean rejected = false;
    private HashMap<String, String> handle;
    private Context context;
    private static final String TAG = "IonicCallkit";

    CallConnection(Context context, HashMap<String, String> handle) {
        super();
        this.handle = handle;
        this.context = context;

        String number = handle.get(EXTRA_CALL_NUMBER);
        String name = handle.get(EXTRA_CALLER_NAME);

        if (number != null) {
            setAddress(Uri.parse(number), TelecomManager.PRESENTATION_ALLOWED);
        }
        if (name != null && !name.equals("")) {
            setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED);
        }
    }

    public HashMap<String, String> getHandle() {
        return handle;
    }

    public boolean isAnswered() {
        return answered;
    }

    public String getCallUuid() {
        return handle.get(EXTRA_CALL_UUID);
    }

    @Override
    public void onExtrasChanged(Bundle extras) {
        super.onExtrasChanged(extras);
        HashMap attributeMap = (HashMap<String, String>) extras.getSerializable("attributeMap");
        if (attributeMap != null) {
            handle = attributeMap;
        }
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState state) {
        Log.d(TAG, "[CallConnection] onCallAudioStateChanged muted :" + (state.isMuted() ? "true" : "false"));

        // Callkeep reports the route on every audio state change (mute included); only
        // report actual route changes.
        String output = CallAudioState.audioRouteToString(state.getRoute());
        if (!output.equals(handle.get("output"))) {
            handle.put("output", output);
            sendCallRequestToActivity(ACTION_DID_CHANGE_AUDIO_ROUTE, handle);
        }

        if (state.isMuted() == this.isMuted) {
            return;
        }

        this.isMuted = state.isMuted();
        sendCallRequestToActivity(isMuted ? ACTION_MUTE_CALL : ACTION_UNMUTE_CALL, handle);
    }

    @Override
    public void onAnswer(int videoState) {
        super.onAnswer(videoState);
        Log.d(TAG, "[CallConnection] onAnswer(int) executed");

        this._onAnswer(videoState);
    }

    @Override
    public void onAnswer() {
        super.onAnswer();
        Log.d(TAG, "[CallConnection] onAnswer() executed");

        this._onAnswer(0);
    }

    @Override
    public void onPlayDtmfTone(char dtmf) {
        Log.d(TAG, "[CallConnection] Playing DTMF : " + dtmf);
        try {
            handle.put("DTMF", Character.toString(dtmf));
        } catch (Throwable exception) {
            Log.e(TAG, "[CallConnection] Handle map error", exception);
        }
        sendCallRequestToActivity(ACTION_DTMF_TONE, handle);
    }

    @Override
    public void onDisconnect() {
        super.onDisconnect();
        setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        sendCallRequestToActivity(ACTION_END_CALL, handle);
        Log.d(TAG, "[CallConnection] onDisconnect executed");
        cleanup();
    }

    public void reportDisconnect(int reason) {
        super.onDisconnect();
        switch (reason) {
            case 1:
                setDisconnected(new DisconnectCause(DisconnectCause.ERROR));
                break;
            case 2:
            case 5:
                setDisconnected(new DisconnectCause(DisconnectCause.REMOTE));
                break;
            case 3:
                setDisconnected(new DisconnectCause(DisconnectCause.BUSY));
                break;
            case 4:
                setDisconnected(new DisconnectCause(DisconnectCause.ANSWERED_ELSEWHERE));
                break;
            case 6:
                setDisconnected(new DisconnectCause(DisconnectCause.MISSED));
                break;
            default:
                // Callkeep leaves the connection un-disconnected here; Telecom
                // then keeps it alive. Treat unknown reasons as a remote hang-up.
                setDisconnected(new DisconnectCause(DisconnectCause.REMOTE));
                break;
        }
        cleanup();
    }

    @Override
    public void onAbort() {
        super.onAbort();
        setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
        sendCallRequestToActivity(ACTION_END_CALL, handle);
        Log.d(TAG, "[CallConnection] onAbort executed");
        cleanup();
    }

    @Override
    public void onHold() {
        Log.d(TAG, "[CallConnection] onHold");
        super.onHold();
        this.setOnHold();
        sendCallRequestToActivity(ACTION_HOLD_CALL, handle);
    }

    @Override
    public void onUnhold() {
        Log.d(TAG, "[CallConnection] onUnhold");
        super.onUnhold();
        sendCallRequestToActivity(ACTION_UNHOLD_CALL, handle);
        setActive();
    }

    public void onReject(int rejectReason) {
        Log.d(TAG, "[CallConnection] onReject(int) executed");

        this._onReject(rejectReason, null);
    }

    @Override
    public void onReject() {
        super.onReject();
        Log.d(TAG, "[CallConnection] onReject() executed");

        this._onReject(0, null);
    }

    @Override
    public void onReject(String replyMessage) {
        super.onReject(replyMessage);
        Log.d(TAG, "[CallConnection] onReject(String) executed");

        this._onReject(0, replyMessage);
    }

    @Override
    public void onStateChanged(int state) {
        super.onStateChanged(state);

        Log.d(TAG, "[CallConnection] onStateChanged called, state : " + state);
        // Callkeep only logs this; report it to JS as `callStateChanged`.
        handle.put("state", CallConnectionService.stateToString(state));
        sendCallRequestToActivity(ACTION_STATE_CHANGED, handle);
    }

    @Override
    public void onSilence() {
        // onSilence was added on API level 29
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }

        super.onSilence();

        sendCallRequestToActivity(ACTION_ON_SILENCE_INCOMING_CALL, handle);
        Log.d(TAG, "[CallConnection] onSilence called");
    }

    private void _onAnswer(int videoState) {
        Log.d(TAG, "[CallConnection] onAnswer called, videoState: " + videoState + ", answered: " + answered);
        // On some device (like Huawei P30 lite), both onAnswer() and onAnswer(int) are called
        // we have to trigger the callback only once
        if (answered) {
            return;
        }
        answered = true;

        setConnectionCapabilities(getConnectionCapabilities() | Connection.CAPABILITY_HOLD);
        setAudioModeIsVoip(true);
        // Callkeep leaves this to JS (setCurrentCallActive). Without it Telecom keeps
        // the call ringing, so an answered self-managed call is marked active here.
        setActive();

        IncomingCallNotification.cancel(context, getCallUuid());
        CallForegroundService.start(context, getCallUuid(), handle.get(EXTRA_CALLER_NAME));
        // If the Ionic app never comes up to handle the answered call, end it instead of
        // leaving the user in a silent "active" call.
        CallConnectionService.startReachabilityWatchdog(context, getCallUuid());

        sendCallRequestToActivity(ACTION_ANSWER_CALL, handle);
        sendCallRequestToActivity(ACTION_AUDIO_SESSION, handle);
        Log.d(TAG, "[CallConnection] onAnswer executed");
    }

    private void _onReject(int rejectReason, String replyMessage) {
        Log.d(TAG, "[CallConnection] onReject executed, rejectReason: " + rejectReason + ", replyMessage: " + replyMessage + ", rejected:" + rejected);
        if (rejected) {
            return;
        }
        rejected = true;

        setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
        sendCallRequestToActivity(ACTION_REJECT_CALL, handle);
        Log.d(TAG, "[CallConnection] onReject executed");
        cleanup();
    }

    @Override
    public void onShowIncomingCallUi() {
        Log.d(TAG, "[CallConnection] onShowIncomingCallUi");
        // Self-managed mode: callkeep only notifies JS here. When the app was woken by a push
        // there is no JS yet, so the plugin posts the ringing notification natively.
        if (CallKeepModule.getSettings(context).optBoolean("showIncomingCallNotification", true)) {
            IncomingCallNotification.show(
                context,
                getCallUuid(),
                handle.get(EXTRA_CALLER_NAME),
                handle.get(EXTRA_CALL_NUMBER),
                Boolean.parseBoolean(handle.get(EXTRA_HAS_VIDEO))
            );
        }
        sendCallRequestToActivity(ACTION_SHOW_INCOMING_CALL_UI, handle);
    }

    private void cleanup() {
        IncomingCallNotification.cancel(context, getCallUuid());
        try {
            CallConnectionService.deinitConnection(context, getCallUuid());
        } catch (Throwable exception) {
            Log.e(TAG, "[CallConnection] cleanup, handle map error", exception);
        }
        destroy();
    }

    /*
     * Send call request to CallKeepModule
     */
    private void sendCallRequestToActivity(final String action, @Nullable final HashMap attributeMap) {
        // Callkeep uses `new Handler()`, which crashes when called from a thread without
        // a Looper (Capacitor plugin calls). Always dispatch from the main looper.
        final HashMap snapshot = attributeMap != null ? new HashMap<>(attributeMap) : null;
        new Handler(Looper.getMainLooper()).post(() -> {
            Intent intent = new Intent(action);
            if (snapshot != null) {
                Bundle extras = new Bundle();
                extras.putSerializable("attributeMap", snapshot);
                intent.putExtras(extras);
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        });
    }
}
