package io.kreador.callkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class CallPushTest {

    private static Map<String, String> data(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    public void parsesDocumentedIncomingCall() {
        CallPush push = CallPush.parse(
            data("type", "incoming_call", "callId", "abc-123", "callerName", "Alice", "handle", "alice@x", "hasVideo", "true")
        );

        assertEquals(CallMessagingService.TYPE_INCOMING_CALL, push.type);
        assertEquals("abc-123", push.callId);
        assertEquals("Alice", push.callerName);
        assertEquals("alice@x", push.handle);
        assertTrue(push.hasVideo);
    }

    @Test
    public void missingTypeDefaultsToIncomingCall() {
        CallPush push = CallPush.parse(data("callId", "abc"));
        assertEquals(CallMessagingService.TYPE_INCOMING_CALL, push.type);
    }

    @Test
    public void acceptsLegacyIosKeys() {
        CallPush push = CallPush.parse(data("ConnectionId", "c-1", "Username", "Bob", "hasVideo", "false"));

        assertEquals("c-1", push.callId);
        assertEquals("Bob", push.callerName);
        assertEquals("Bob", push.handle);
        assertFalse(push.hasVideo);
    }

    @Test
    public void handleFallsBackToNameAndViceVersa() {
        assertEquals("Carol", CallPush.parse(data("callId", "1", "callerName", "Carol")).handle);
        assertEquals("+123", CallPush.parse(data("callId", "1", "handle", "+123")).callerName);
        assertEquals("Unknown", CallPush.parse(data("callId", "1")).callerName);
    }

    @Test
    public void parsesCallEnded() {
        CallPush push = CallPush.parse(data("type", "call_ended", "callId", "abc"));
        assertEquals(CallMessagingService.TYPE_CALL_ENDED, push.type);
    }

    @Test
    public void ignoresNonCallMessages() {
        assertNull(CallPush.parse(data("type", "chat", "callId", "abc")));
        assertNull(CallPush.parse(data("title", "hello")));
        assertNull(CallPush.parse(data("callId", "")));
        assertNull(CallPush.parse(null));
    }
}
