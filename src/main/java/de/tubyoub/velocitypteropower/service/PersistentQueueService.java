/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.service;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists startup wait queues across disconnects so players can resume on rejoin.
 */
public class PersistentQueueService {

    public static final class QueueEntry {
        public final String targetServer;
        public final long enqueuedAtMs;

        public QueueEntry(String targetServer, long enqueuedAtMs) {
            this.targetServer = targetServer;
            this.enqueuedAtMs = enqueuedAtMs;
        }
    }

    private final ComponentLogger logger;
    private final Path file;
    private final Map<UUID, QueueEntry> entries = new ConcurrentHashMap<>();
    private final long ttlMs;

    public PersistentQueueService(VelocityPteroPower plugin, long ttlSeconds) {
        this.logger = plugin.getFilteredLogger();
        this.file = plugin.getDataDirectory().resolve("startup-queue.json");
        this.ttlMs = Math.max(60, ttlSeconds) * 1000L;
        load();
    }

    public void remember(UUID playerId, String targetServer) {
        entries.put(playerId, new QueueEntry(targetServer, System.currentTimeMillis()));
        save();
    }

    public void clear(UUID playerId) {
        if (entries.remove(playerId) != null) {
            save();
        }
    }

    public QueueEntry get(UUID playerId) {
        QueueEntry e = entries.get(playerId);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.enqueuedAtMs > ttlMs) {
            entries.remove(playerId);
            save();
            return null;
        }
        return e;
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Map.Entry<UUID, QueueEntry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, QueueEntry> e = it.next();
            if (now - e.getValue().enqueuedAtMs > ttlMs) {
                it.remove();
                changed = true;
            }
        }
        if (changed) save();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file).trim();
            // Expected: {"uuid":{"targetServer":"x","enqueuedAtMs":123},...}
            if (!json.startsWith("{")) return;
            int i = 1;
            while (i < json.length()) {
                while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == ',' || json.charAt(i) == '\n')) i++;
                if (i >= json.length() || json.charAt(i) == '}') break;
                if (json.charAt(i) != '"') break;
                int k1 = i + 1;
                int k2 = json.indexOf('"', k1);
                if (k2 < 0) break;
                String uuidStr = json.substring(k1, k2);
                i = json.indexOf('{', k2);
                if (i < 0) break;
                int end = json.indexOf('}', i);
                if (end < 0) break;
                String obj = json.substring(i, end + 1);
                String target = extractString(obj, "targetServer");
                long enqueued = extractLong(obj, "enqueuedAtMs");
                if (target != null) {
                    try {
                        entries.put(UUID.fromString(uuidStr), new QueueEntry(target, enqueued > 0 ? enqueued : System.currentTimeMillis()));
                    } catch (Exception ignored) {}
                }
                i = end + 1;
            }
            logger.info("Loaded {} persistent queue entr{}.", entries.size(), entries.size() == 1 ? "y" : "ies");
        } catch (Exception ex) {
            logger.warn("Failed to load startup-queue.json: {}", ex.toString());
        }
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<UUID, QueueEntry> e : entries.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append("  \"").append(e.getKey()).append("\": {")
                        .append("\"targetServer\":\"").append(escape(e.getValue().targetServer)).append("\",")
                        .append("\"enqueuedAtMs\":").append(e.getValue().enqueuedAtMs)
                        .append("}");
            }
            sb.append("\n}\n");
            Files.writeString(file, sb.toString());
        } catch (IOException ex) {
            logger.warn("Failed to save startup-queue.json: {}", ex.toString());
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractString(String json, String key) {
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        int q1 = json.indexOf('"', colon + 1);
        int q2 = json.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static long extractLong(String json, String key) {
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return -1;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return -1;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int j = i;
        while (j < json.length() && (Character.isDigit(json.charAt(j)))) j++;
        try {
            return Long.parseLong(json.substring(i, j));
        } catch (Exception e) {
            return -1;
        }
    }
}
