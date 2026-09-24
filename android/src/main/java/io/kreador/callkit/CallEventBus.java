package io.kreador.callkit;

import com.getcapacitor.JSObject;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Replaces callkeep's RCTDeviceEventEmitter + delayedEvents queue.
 *
 * Native code (ConnectionService, FCM service, notification actions) can run while no
 * WebView / plugin instance exists, e.g. when a push wakes a killed app. Events emitted
 * then are queued in order and flushed once CallKitPlugin attaches.
 *
 * - The queue is persisted through {@link Store}, so events survive the process dying
 *   before JS loaded. Telecom drops a dead process's calls, so events restored in a new
 *   process are not replayed to listeners; they are exposed via getInitialEvents() with
 *   `restored: true` (e.g. to tell your backend a call was answered but the app died).
 * - Every event that happened before JS was listening for it — queued before the plugin
 *   attached, or delivered while JS had no listener for it yet — is also kept for
 *   getInitialEvents() until clearInitialEvents(), like callkeep's getInitialEvents.
 */
public final class CallEventBus {

    public interface Listener {
        /** @return true when JS already had a listener for this event. */
        boolean onCallEvent(String eventName, JSObject data);
    }

    /** Persistent storage for the pending queue (SharedPreferences in production). */
    public interface Store {
        String read();

        void write(String json);
    }

    public interface Clock {
        long now();
    }

    public static final class Event {

        public final String name;
        public final JSObject data;
        public final long timestamp;
        public final boolean restored;

        Event(String name, JSObject data, long timestamp, boolean restored) {
            this.name = name;
            this.data = data;
            this.timestamp = timestamp;
            this.restored = restored;
        }

        public JSObject toJSObject() {
            JSObject json = new JSObject();
            json.put("name", name);
            json.put("data", data);
            json.put("timestamp", timestamp);
            json.put("restored", restored);
            return json;
        }
    }

    // Bound the queue so a device that never opens the app can't grow it forever.
    static final int MAX_PENDING = 50;
    // Events restored from a previous process older than this are dropped.
    static final long RESTORED_MAX_AGE_MS = 5 * 60 * 1000;

    private static final List<Event> pending = new ArrayList<>();
    private static final List<Event> initial = new ArrayList<>();
    private static Listener listener;
    private static Store store;
    private static Clock clock = System::currentTimeMillis;

    private CallEventBus() {}

    /**
     * Install the persistent store. The first call in a process restores events a previous
     * process queued but never delivered.
     */
    public static synchronized void setStore(Store newStore) {
        boolean firstStore = store == null;
        store = newStore;
        if (firstStore) {
            restore();
        }
    }

    public static void emit(String eventName, JSObject data) {
        JSObject payload = data != null ? data : new JSObject();
        Listener target;
        synchronized (CallEventBus.class) {
            target = listener;
            if (target == null) {
                if (pending.size() >= MAX_PENDING) {
                    pending.remove(0);
                }
                pending.add(new Event(eventName, payload, clock.now(), false));
                persist();
                return;
            }
        }
        if (!target.onCallEvent(eventName, payload)) {
            synchronized (CallEventBus.class) {
                initial.add(new Event(eventName, payload, clock.now(), false));
            }
        }
    }

    /** Attach the listener and deliver everything queued while nobody was listening. */
    public static void attach(Listener newListener) {
        List<Event> toFlush;
        synchronized (CallEventBus.class) {
            listener = newListener;
            toFlush = new ArrayList<>(pending);
            initial.addAll(toFlush);
            pending.clear();
            persist();
        }
        for (Event event : toFlush) {
            newListener.onCallEvent(event.name, event.data);
        }
    }

    public static synchronized void detach(Listener oldListener) {
        if (listener == oldListener) {
            listener = null;
        }
    }

    /** Events that happened before JS attached (plus restored ones), oldest first. */
    public static synchronized List<Event> getInitialEvents() {
        return new ArrayList<>(initial);
    }

    public static synchronized void clearInitialEvents() {
        initial.clear();
    }

    static synchronized List<Event> pendingSnapshot() {
        return new ArrayList<>(pending);
    }

    static synchronized void setClock(Clock newClock) {
        clock = newClock;
    }

    static synchronized void reset() {
        pending.clear();
        initial.clear();
        listener = null;
        store = null;
        clock = System::currentTimeMillis;
    }

    private static void restore() {
        String json = store.read();
        if (json == null || json.isEmpty()) {
            return;
        }
        long now = clock.now();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                long timestamp = item.getLong("timestamp");
                if (now - timestamp > RESTORED_MAX_AGE_MS) {
                    continue;
                }
                JSObject data = JSObject.fromJSONObject(item.optJSONObject("data") != null ? item.getJSONObject("data") : new JSONObject());
                initial.add(new Event(item.getString("name"), data, timestamp, true));
            }
        } catch (JSONException e) {
            // Corrupt store: drop it.
        }
        store.write("[]");
    }

    private static void persist() {
        if (store == null) {
            return;
        }
        JSONArray array = new JSONArray();
        for (Event event : pending) {
            JSONObject item = new JSONObject();
            try {
                item.put("name", event.name);
                item.put("data", event.data);
                item.put("timestamp", event.timestamp);
            } catch (JSONException e) {
                continue;
            }
            array.put(item);
        }
        store.write(array.toString());
    }
}
