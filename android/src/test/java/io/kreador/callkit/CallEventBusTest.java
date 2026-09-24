package io.kreador.callkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.getcapacitor.JSObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CallEventBusTest {

    private final List<String> received = new ArrayList<>();
    private final CallEventBus.Listener listener = (name, data) -> received.add(name + ":" + data.getString("callId"));

    @Before
    public void setUp() {
        CallEventBus.reset();
        received.clear();
    }

    @After
    public void tearDown() {
        CallEventBus.reset();
    }

    private static JSObject call(String id) {
        JSObject data = new JSObject();
        data.put("callId", id);
        return data;
    }

    @Test
    public void queuesEventsUntilAListenerAttaches() {
        CallEventBus.emit("incomingCall", call("a"));
        CallEventBus.emit("callAnswered", call("a"));

        assertTrue(received.isEmpty());
        assertEquals(2, CallEventBus.pendingSnapshot().size());

        CallEventBus.attach(listener);

        assertEquals(List.of("incomingCall:a", "callAnswered:a"), received);
        assertTrue(CallEventBus.pendingSnapshot().isEmpty());
    }

    @Test
    public void deliversDirectlyWhileAttached() {
        CallEventBus.attach(listener);
        CallEventBus.emit("callEnded", call("b"));

        assertEquals(List.of("callEnded:b"), received);
        assertTrue(CallEventBus.pendingSnapshot().isEmpty());
    }

    @Test
    public void queuesAgainAfterDetach() {
        CallEventBus.attach(listener);
        CallEventBus.detach(listener);
        CallEventBus.emit("callEnded", call("c"));

        assertTrue(received.isEmpty());
        assertEquals(1, CallEventBus.pendingSnapshot().size());
    }

    @Test
    public void detachIgnoresAStaleListener() {
        CallEventBus.Listener other = (name, data) -> {};
        CallEventBus.attach(listener);
        CallEventBus.detach(other);
        CallEventBus.emit("callEnded", call("d"));

        assertEquals(List.of("callEnded:d"), received);
    }

    @Test
    public void dropsOldestWhenQueueIsFull() {
        for (int i = 0; i < CallEventBus.MAX_PENDING + 5; i++) {
            CallEventBus.emit("incomingCall", call(String.valueOf(i)));
        }

        List<CallEventBus.Event> pending = CallEventBus.pendingSnapshot();
        assertEquals(CallEventBus.MAX_PENDING, pending.size());
        assertEquals("5", pending.get(0).data.getString("callId"));
    }

    @Test
    public void nullDataBecomesEmptyObject() {
        CallEventBus.emit("audioSessionActivated", null);
        assertEquals(0, CallEventBus.pendingSnapshot().get(0).data.length());
    }
}
