/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import net.kyori.adventure.text.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Periodic action-bar progress for players waiting on server startups.
 */
public class QueueProgressService {

    private final VelocityPteroPower plugin;
    private final ProxyServer proxy;
    private final MessagesManager messages;
    private volatile boolean running;

    public QueueProgressService(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.proxy = plugin.getProxyServer();
        this.messages = plugin.getMessagesManager();
    }

    public void start() {
        running = true;
        scheduleNext();
    }

    public void stop() {
        running = false;
    }

    private void scheduleNext() {
        if (!running) return;
        proxy.getScheduler()
                .buildTask(plugin, this::tick)
                .delay(5, TimeUnit.SECONDS)
                .schedule();
    }

    private void tick() {
        try {
            Map<String, Set<UUID>> waiting = plugin.getWaitingPlayers();
            if (waiting == null || waiting.isEmpty()) {
                return;
            }
            var handler = plugin.getPlayerConnectionHandler();
            Map<String, Long> deadlines = handler != null ? handler.getStartupWatcherDeadlines() : Map.of();

            for (Map.Entry<String, Set<UUID>> entry : waiting.entrySet()) {
                String server = entry.getKey();
                Set<UUID> waiters = entry.getValue();
                if (waiters == null || waiters.isEmpty()) continue;

                long remaining = 0;
                Long deadline = deadlines.get(server);
                if (deadline != null) {
                    remaining = Math.max(0, (deadline - System.currentTimeMillis()) / 1000L);
                }
                int count = waiters.size();
                Component bar = messages.prefixed(
                        MessageKey.CONNECT_QUEUE_PROGRESS,
                        "server", server,
                        "remaining", String.valueOf(remaining),
                        "waiting", String.valueOf(count));

                for (UUID id : waiters) {
                    proxy.getPlayer(id).ifPresent(p -> p.sendActionBar(bar));
                }
            }
        } catch (Exception ex) {
            plugin.getFilteredLogger().debug("Queue progress tick error: {}", ex.toString());
        } finally {
            scheduleNext();
        }
    }

    public OptionalQueueStatus findPlayerQueue(UUID playerId) {
        Map<String, Set<UUID>> waiting = plugin.getWaitingPlayers();
        if (waiting == null) return OptionalQueueStatus.none();
        for (Map.Entry<String, Set<UUID>> e : waiting.entrySet()) {
            Set<UUID> set = e.getValue();
            if (set == null || !set.contains(playerId)) continue;
            int position = 1;
            for (UUID id : set) {
                if (id.equals(playerId)) break;
                position++;
            }
            long remaining = 0;
            var handler = plugin.getPlayerConnectionHandler();
            if (handler != null) {
                Long deadline = handler.getStartupWatcherDeadlines().get(e.getKey());
                if (deadline != null) {
                    remaining = Math.max(0, (deadline - System.currentTimeMillis()) / 1000L);
                }
            }
            return new OptionalQueueStatus(e.getKey(), position, set.size(), remaining);
        }
        return OptionalQueueStatus.none();
    }

    public record OptionalQueueStatus(String server, int position, int waiting, long remainingSeconds) {
        public boolean present() {
            return server != null;
        }

        public static OptionalQueueStatus none() {
            return new OptionalQueueStatus(null, 0, 0, 0);
        }
    }
}
