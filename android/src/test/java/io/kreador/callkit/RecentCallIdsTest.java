package io.kreador.callkit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecentCallIdsTest {

    private long now = 0;
    private final RecentCallIds ids = new RecentCallIds(() -> now);

    @Test
    public void remembersEndedCallsForTheTtl() {
        ids.markEnded("a");
        assertTrue(ids.recentlyEnded("a"));
        assertFalse(ids.recentlyEnded("b"));

        now = RecentCallIds.TTL_MS;
        assertTrue(ids.recentlyEnded("a"));
        now = RecentCallIds.TTL_MS + 1;
        assertFalse(ids.recentlyEnded("a"));
    }

    @Test
    public void markingAgainRefreshesTheTimestamp() {
        ids.markEnded("a");
        now = RecentCallIds.TTL_MS;
        ids.markEnded("a");
        now = RecentCallIds.TTL_MS * 2;
        assertTrue(ids.recentlyEnded("a"));
    }

    @Test
    public void staysBoundedAndIgnoresNull() {
        for (int i = 0; i < 150; i++) {
            ids.markEnded("c" + i);
        }
        assertFalse(ids.recentlyEnded("c0"));
        assertTrue(ids.recentlyEnded("c149"));
        ids.markEnded(null);
        assertFalse(ids.recentlyEnded(null));
    }
}
