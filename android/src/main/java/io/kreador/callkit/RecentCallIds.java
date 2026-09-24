package io.kreador.callkit;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers call ids that recently ended, so a push delivered late or twice (FCM retries,
 * `call_ended` overtaking `incoming_call`) doesn't ring a call that is already over.
 * Pure Java so it can be unit tested.
 */
final class RecentCallIds {

    static final long TTL_MS = 5 * 60 * 1000;
    private static final int MAX_SIZE = 100;

    interface Clock {
        long now();
    }

    private final Map<String, Long> ended = new LinkedHashMap<>();
    private final Clock clock;

    RecentCallIds() {
        this(System::currentTimeMillis);
    }

    RecentCallIds(Clock clock) {
        this.clock = clock;
    }

    synchronized void markEnded(String callId) {
        if (callId == null) {
            return;
        }
        prune();
        ended.remove(callId);
        ended.put(callId, clock.now());
        if (ended.size() > MAX_SIZE) {
            Iterator<String> oldest = ended.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    synchronized boolean recentlyEnded(String callId) {
        if (callId == null) {
            return false;
        }
        prune();
        return ended.containsKey(callId);
    }

    private void prune() {
        long now = clock.now();
        Iterator<Map.Entry<String, Long>> it = ended.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > TTL_MS) {
                it.remove();
            }
        }
    }
}
