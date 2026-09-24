package io.kreador.callkit;

import com.getcapacitor.JSObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Replaces callkeep's RCTDeviceEventEmitter + delayedEvents queue.
 *
 * Native code (ConnectionService, FCM service, notification actions) can run
 * while no WebView / plugin instance exists, e.g. when a push wakes a killed app.
 * Events emitted then are queued and flushed once CallKitPlugin attaches.
 */
public final class CallEventBus {

    public interface Listener {
        void onCallEvent(String eventName, JSObject data);
    }

    public static final class Event {

        public final String name;
        public final JSObject data;

        Event(String name, JSObject data) {
            this.name = name;
            this.data = data;
        }
    }

    // Bound the queue so a device that never opens the app can't grow it forever.
    static final int MAX_PENDING = 50;

    private static final List<Event> pending = new ArrayList<>();
    private static Listener listener;

    private CallEventBus() {}

    public static void emit(String eventName, JSObject data) {
        Listener target;
        synchronized (CallEventBus.class) {
            target = listener;
            if (target == null) {
                if (pending.size() >= MAX_PENDING) {
                    pending.remove(0);
                }
                pending.add(new Event(eventName, data != null ? data : new JSObject()));
                return;
            }
        }
        target.onCallEvent(eventName, data != null ? data : new JSObject());
    }

    /** Attach the listener and deliver everything queued while nobody was listening. */
    public static void attach(Listener newListener) {
        List<Event> toFlush;
        synchronized (CallEventBus.class) {
            listener = newListener;
            toFlush = new ArrayList<>(pending);
            pending.clear();
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

    static synchronized List<Event> pendingSnapshot() {
        return new ArrayList<>(pending);
    }

    static synchronized void reset() {
        pending.clear();
        listener = null;
    }
}
