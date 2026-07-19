/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.util;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Schedules panel API work by priority lane so player starts beat prefetch under rate pressure.
 */
public class PanelRequestScheduler {

    private final VelocityPteroPower plugin;
    private final ComponentLogger logger;
    private final RateLimitTracker rateLimitTracker;
    private final ConfigurationManager configurationManager;
    private final Map<PanelRequestPriority, ConcurrentLinkedQueue<Runnable>> queues =
            new EnumMap<>(PanelRequestPriority.class);
    private final AtomicBoolean draining = new AtomicBoolean(false);

    public PanelRequestScheduler(
            VelocityPteroPower plugin,
            RateLimitTracker rateLimitTracker,
            ConfigurationManager configurationManager) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.rateLimitTracker = rateLimitTracker;
        this.configurationManager = configurationManager;
        for (PanelRequestPriority p : PanelRequestPriority.values()) {
            queues.put(p, new ConcurrentLinkedQueue<>());
        }
    }

    /** True when background work (prefetch) should pause. */
    public boolean shouldPauseBackground() {
        if (rateLimitTracker.getRemainingRequests() <= configurationManager.getRateLimitBackgroundPauseThreshold()) {
            return true;
        }
        try {
            var waiting = plugin.getWaitingPlayers();
            if (waiting != null) {
                for (var set : waiting.values()) {
                    if (set != null && !set.isEmpty()) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public boolean canDispatch(PanelRequestPriority priority) {
        if (priority == PanelRequestPriority.BACKGROUND && shouldPauseBackground()) {
            return false;
        }
        return rateLimitTracker.canMakeRequest(priority);
    }

    /**
     * Runs work immediately if allowed, otherwise queues it for later drain.
     */
    public void submit(PanelRequestPriority priority, Runnable work) {
        if (canDispatch(priority)) {
            try {
                work.run();
            } catch (Exception ex) {
                logger.debug("Panel request failed ({}): {}", priority, ex.toString());
            }
            return;
        }
        queues.get(priority).offer(work);
        logger.debug("Queued panel work at priority {} (remaining={}).", priority, rateLimitTracker.getRemainingRequests());
        scheduleDrain();
    }

    public <T> CompletableFuture<T> submitAsync(PanelRequestPriority priority, Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        submit(priority, () -> {
            try {
                future.complete(work.get());
            } catch (Exception ex) {
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    private void scheduleDrain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        long delaySec = Math.max(1, rateLimitTracker.secondsUntilReset());
        plugin.getProxyServer().getScheduler()
                .buildTask(plugin, this::drain)
                .delay(delaySec, java.util.concurrent.TimeUnit.SECONDS)
                .schedule();
    }

    private void drain() {
        try {
            for (PanelRequestPriority priority : PanelRequestPriority.values()) {
                ConcurrentLinkedQueue<Runnable> q = queues.get(priority);
                while (!q.isEmpty()) {
                    if (!canDispatch(priority)) {
                        scheduleDrain();
                        return;
                    }
                    Runnable work = q.poll();
                    if (work == null) break;
                    try {
                        work.run();
                    } catch (Exception ex) {
                        logger.debug("Drained panel work failed ({}): {}", priority, ex.toString());
                    }
                }
            }
        } finally {
            draining.set(false);
            boolean anyQueued = false;
            for (ConcurrentLinkedQueue<Runnable> q : queues.values()) {
                if (!q.isEmpty()) {
                    anyQueued = true;
                    break;
                }
            }
            if (anyQueued) {
                scheduleDrain();
            }
        }
    }
}
