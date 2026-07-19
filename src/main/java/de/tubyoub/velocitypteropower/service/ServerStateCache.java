/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live panel server state cache (fed by WebSocket or HTTP polling).
 */
public class ServerStateCache {

    public enum State {
        UNKNOWN,
        OFFLINE,
        STARTING,
        RUNNING,
        STOPPING
    }

    public static final class Entry {
        public final State state;
        public final long updatedAtMs;

        public Entry(State state, long updatedAtMs) {
            this.state = state;
            this.updatedAtMs = updatedAtMs;
        }
    }

    private final Map<String, Entry> byServerId = new ConcurrentHashMap<>();
    private final long ttlMs;

    public ServerStateCache(long ttlSeconds) {
        this.ttlMs = Math.max(5, ttlSeconds) * 1000L;
    }

    public void put(String serverId, State state) {
        if (serverId == null) return;
        byServerId.put(serverId, new Entry(state, System.currentTimeMillis()));
    }

    public Entry get(String serverId) {
        if (serverId == null) return null;
        Entry e = byServerId.get(serverId);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.updatedAtMs > ttlMs) {
            return null;
        }
        return e;
    }

    public boolean isRunningFresh(String serverId) {
        Entry e = get(serverId);
        return e != null && e.state == State.RUNNING;
    }

    public void clear() {
        byServerId.clear();
    }
}
