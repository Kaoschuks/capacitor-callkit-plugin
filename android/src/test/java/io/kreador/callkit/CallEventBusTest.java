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
    /** Plugin attached but JS hasn't added listeners yet (e.g. WebView still loading). */
    private final CallEventBus.Listener notListening = (name, data) -> false;

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
        CallEventBus.Listener other = (name, data) -> true;
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

    /** In-memory stand-in for SharedPreferences. */
    private static final class MemoryStore implements CallEventBus.Store {

        String json;

        @Override
        public String read() {
            return json;
        }

        @Override
        public void write(String json) {
            this.json = json;
        }
    }

    @Test
    public void pendingEventsArePersistedInOrderAndClearedOnceDelivered() throws Exception {
        MemoryStore store = new MemoryStore();
        CallEventBus.setStore(store);
        CallEventBus.emit("incomingCall", call("p1"));
        CallEventBus.emit("callAnswered", call("p1"));
        CallEventBus.emit("audioSessionActivated", call("p1"));

        org.json.JSONArray saved = new org.json.JSONArray(store.json);
        assertEquals(3, saved.length());
        assertEquals("incomingCall", saved.getJSONObject(0).getString("name"));
        assertEquals("audioSessionActivated", saved.getJSONObject(2).getString("name"));

        CallEventBus.attach(listener);
        assertEquals("[]", store.json);
    }

    @Test
    public void eventsDeliveredBeforeAttachAreKeptAsInitialEventsUntilCleared() {
        CallEventBus.emit("incomingCall", call("i1"));
        CallEventBus.emit("callAnswered", call("i1"));
        CallEventBus.attach(listener);
        CallEventBus.emit("callEnded", call("i1"));

        List<CallEventBus.Event> initial = CallEventBus.getInitialEvents();
        assertEquals(2, initial.size());
        assertEquals("incomingCall", initial.get(0).name);
        assertEquals("callAnswered", initial.get(1).name);
        assertTrue(!initial.get(0).restored);

        CallEventBus.clearInitialEvents();
        assertTrue(CallEventBus.getInitialEvents().isEmpty());
    }

    @Test
    public void eventsDeliveredWhileJsIsNotListeningAreInitialEvents() {
        CallEventBus.attach(notListening);
        CallEventBus.emit("callAnswered", call("cold"));
        CallEventBus.detach(notListening);
        CallEventBus.attach(listener);
        CallEventBus.emit("callEnded", call("cold"));

        List<CallEventBus.Event> initial = CallEventBus.getInitialEvents();
        assertEquals(1, initial.size());
        assertEquals("callAnswered", initial.get(0).name);
    }

    @Test
    public void eventsFromADeadProcessAreRestoredButNotReplayed() {
        // Previous process queued an answer, then died before JS attached.
        MemoryStore store = new MemoryStore();
        CallEventBus.setClock(() -> 1_000_000L);
        CallEventBus.setStore(store);
        CallEventBus.emit("incomingCall", call("d1"));
        CallEventBus.emit("callAnswered", call("d1"));
        String persisted = store.json;

        // New process, 30 s later.
        CallEventBus.reset();
        CallEventBus.setClock(() -> 1_030_000L);
        MemoryStore reopened = new MemoryStore();
        reopened.json = persisted;
        CallEventBus.setStore(reopened);
        CallEventBus.attach(listener);

        assertTrue("restored events must not reach listeners", received.isEmpty());
        List<CallEventBus.Event> initial = CallEventBus.getInitialEvents();
        assertEquals(2, initial.size());
        assertTrue(initial.get(0).restored);
        assertEquals("d1", initial.get(1).data.getString("callId"));
        assertEquals(1_000_000L, initial.get(0).timestamp);
        assertEquals("[]", reopened.json);
    }

    @Test
    public void staleRestoredEventsAreDropped() {
        MemoryStore store = new MemoryStore();
        CallEventBus.setClock(() -> 0L);
        CallEventBus.setStore(store);
        CallEventBus.emit("incomingCall", call("old"));
        String persisted = store.json;

        CallEventBus.reset();
        CallEventBus.setClock(() -> CallEventBus.RESTORED_MAX_AGE_MS + 1);
        MemoryStore reopened = new MemoryStore();
        reopened.json = persisted;
        CallEventBus.setStore(reopened);

        assertTrue(CallEventBus.getInitialEvents().isEmpty());
    }

    @Test
    public void corruptStoreIsIgnored() {
        MemoryStore store = new MemoryStore();
        store.json = "not json";
        CallEventBus.setStore(store);
        assertTrue(CallEventBus.getInitialEvents().isEmpty());
        assertEquals("[]", store.json);
    }

    @Test
    public void nullDataBecomesEmptyObject() {
        CallEventBus.emit("audioSessionActivated", null);
        assertEquals(0, CallEventBus.pendingSnapshot().get(0).data.length());
    }
}
