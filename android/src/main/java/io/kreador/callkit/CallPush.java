package io.kreador.callkit;

import java.util.Map;

/**
 * Parses a call push's data map. Pure Java so it can be unit tested.
 *
 * Accepts the documented keys (type, callId, callerName, handle, hasVideo) and the
 * legacy iOS payload keys of this plugin (ConnectionId, Username).
 */
public final class CallPush {

    public final String type;
    public final String callId;
    public final String callerName;
    public final String handle;
    public final boolean hasVideo;

    private CallPush(String type, String callId, String callerName, String handle, boolean hasVideo) {
        this.type = type;
        this.callId = callId;
        this.callerName = callerName;
        this.handle = handle;
        this.hasVideo = hasVideo;
    }

    /** @return null when the data is not a call push. */
    public static CallPush parse(Map<String, String> data) {
        if (data == null) {
            return null;
        }
        String callId = first(data, "callId", "uuid", "ConnectionId");
        if (callId == null) {
            return null;
        }

        String type = data.get("type");
        if (type == null) {
            type = CallMessagingService.TYPE_INCOMING_CALL;
        }
        if (!CallMessagingService.TYPE_INCOMING_CALL.equals(type) && !CallMessagingService.TYPE_CALL_ENDED.equals(type)) {
            return null;
        }

        String callerName = first(data, "callerName", "name", "Username");
        String handle = first(data, "handle", "number");
        if (callerName == null) {
            callerName = handle != null ? handle : "Unknown";
        }
        if (handle == null) {
            handle = callerName;
        }

        return new CallPush(type, callId, callerName, handle, "true".equalsIgnoreCase(data.get("hasVideo")));
    }

    private static String first(Map<String, String> data, String... keys) {
        for (String key : keys) {
            String value = data.get(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
