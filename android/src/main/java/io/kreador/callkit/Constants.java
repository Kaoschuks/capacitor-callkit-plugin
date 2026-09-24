/*
 * Ported from livekit/react-native-callkeep (io.wazo.callkeep.Constants).
 * Copyright (c) 2016-2019 The CallKeep Authors (see the AUTHORS file)
 * SPDX-License-Identifier: ISC, MIT
 */

package io.kreador.callkit;

public class Constants {
    public static final String ACTION_ANSWER_CALL = "io.kreador.callkit.ACTION_ANSWER_CALL";
    public static final String ACTION_AUDIO_SESSION = "io.kreador.callkit.ACTION_AUDIO_SESSION";
    public static final String ACTION_CHECK_REACHABILITY = "io.kreador.callkit.ACTION_CHECK_REACHABILITY";
    public static final String ACTION_DTMF_TONE = "io.kreador.callkit.ACTION_DTMF_TONE";
    public static final String ACTION_END_CALL = "io.kreador.callkit.ACTION_END_CALL";
    public static final String ACTION_REJECT_CALL = "io.kreador.callkit.ACTION_REJECT_CALL";
    public static final String ACTION_HOLD_CALL = "io.kreador.callkit.ACTION_HOLD_CALL";
    public static final String ACTION_MUTE_CALL = "io.kreador.callkit.ACTION_MUTE_CALL";
    public static final String ACTION_ONGOING_CALL = "io.kreador.callkit.ACTION_ONGOING_CALL";
    public static final String ACTION_UNHOLD_CALL = "io.kreador.callkit.ACTION_UNHOLD_CALL";
    public static final String ACTION_UNMUTE_CALL = "io.kreador.callkit.ACTION_UNMUTE_CALL";
    public static final String ACTION_SHOW_INCOMING_CALL_UI = "io.kreador.callkit.ACTION_SHOW_INCOMING_CALL_UI";
    public static final String ACTION_ON_SILENCE_INCOMING_CALL = "io.kreador.callkit.ACTION_ON_SILENCE_INCOMING_CALL";
    public static final String ACTION_ON_CREATE_CONNECTION_FAILED = "io.kreador.callkit.ACTION_ON_CREATE_CONNECTION_FAILED";
    public static final String ACTION_DID_CHANGE_AUDIO_ROUTE = "io.kreador.callkit.ACTION_DID_CHANGE_AUDIO_ROUTE";

    // Notification actions (CallActionReceiver / launch intent)
    public static final String ACTION_NOTIFICATION_DECLINE = "io.kreador.callkit.ACTION_NOTIFICATION_DECLINE";
    public static final String ACTION_NOTIFICATION_HANGUP = "io.kreador.callkit.ACTION_NOTIFICATION_HANGUP";
    public static final String EXTRA_LAUNCH_ACTION = "io.kreador.callkit.EXTRA_LAUNCH_ACTION";
    public static final String LAUNCH_ACTION_SHOW_INCOMING = "showIncoming";
    public static final String LAUNCH_ACTION_ANSWER = "answer";

    public static final String EXTRA_CALL_NUMBER = "EXTRA_CALL_NUMBER";
    public static final String EXTRA_CALL_NUMBER_SCHEMA = "EXTRA_CALL_NUMBER_SCHEMA";
    public static final String EXTRA_CALL_UUID = "EXTRA_CALL_UUID";
    public static final String EXTRA_CALLER_NAME = "EXTRA_CALLER_NAME";
    public static final String EXTRA_HAS_VIDEO = "EXTRA_HAS_VIDEO";
    public static final String EXTRA_PAYLOAD = "EXTRA_PAYLOAD";
    // Can't use telecom.EXTRA_DISABLE_ADD_CALL ...
    public static final String EXTRA_DISABLE_ADD_CALL = "android.telecom.extra.DISABLE_ADD_CALL";

    public static final String PREFS_NAME = "ionic-callkit";
}
