package de.tubyoub.velocitypteropower.lobby;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.http.PowerSignal;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.Locale;

/**
 * Provides multi-lobby and multi-limbo selection, basic load balancing, and simple auto-scale
 * for lobby servers. All behavior is driven by configuration and designed to be additive so
 * existing single-limbo setups continue to work.
 */
public class LobbyBalancerManager {

    private volatile boolean running = false;
    private volatile ScheduledTask healthTask;

    public enum Strategy {
        ROUND_ROBIN,
        LEAST_PLAYERS,
        LEAST_CPU
    }

    private final VelocityPteroPower plugin;
    private final ProxyServer proxy;
    private final ComponentLogger logger;
    private final ConfigurationManager config;
    private final PanelAPIClient apiClient;

    private final Map<String, PteroServerInfo> managedServers;

    private final AtomicInteger rrLobbyIdx = new AtomicInteger(0);
    private final AtomicInteger rrLimboIdx = new AtomicInteger(0);

    // Track recent start attempts and cooldowns for problematic lobbies
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastStartAttempt = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> cooldownUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> emptySince = new java.util.concurrent.ConcurrentHashMap<>();

    public LobbyBalancerManager(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.proxy = plugin.getProxyServer();
        this.logger = plugin.getFilteredLogger();
        this.config = plugin.getConfigurationManager();
        this.apiClient = plugin.getApiClient();
        this.managedServers = plugin.getServerInfoMap();
    }

    // region Public API used by handlers
    public Optional<RegisteredServer> pickLobby() {
        List<String> lobbies = getConfiguredLobbiesToUse();
        if (lobbies.isEmpty()) return Optional.empty();
        List<String> online = lobbies.stream().filter(this::isReachable).collect(Collectors.toList());
        if (online.isEmpty()) return Optional.empty();
        return selectByStrategy(online, true);
    }

    public Optional<RegisteredServer> pickLimbo() {
        List<String> limbos = config.getBalancerLimbos();
        if (limbos != null && !limbos.isEmpty()) {
            List<String> online = limbos.stream().filter(this::isReachable).collect(Collectors.toList());
            if (!online.isEmpty()) {
                return selectByStrategy(online, false);
            }
        }
        return Optional.empty();
    }

    public Optional<RegisteredServer> pickHoldingServer() {
        // Prefer configured lobbies if present, else try limbos
        Optional<RegisteredServer> lobby = pickLobby();
        if (lobby.isPresent()) return lobby;
        return pickLimbo();
    }
    // endregion

    // region Scheduling
    public void startSchedulers() {
        running = true;
        int interval = Math.max(5, config.getBalancerHealthCheckInterval());
        // Cancel previous if any
        if (healthTask != null) {
            try { healthTask.cancel(); } catch (Exception ignored) {}
            healthTask = null;
        }
        healthTask = proxy.getScheduler().buildTask(plugin, this::healthAndScaleTick)
                .delay(interval, TimeUnit.SECONDS).schedule();
        logger.info("LobbyBalancer health-check scheduled every {}s.", interval);
    }

    public void stopSchedulers() {
        running = false;
        if (healthTask != null) {
            try { healthTask.cancel(); } catch (Exception ignored) {}
            healthTask = null;
        }
        logger.info("LobbyBalancer health-check stopped.");
    }

    private void reschedule() {
        if (!running) return;
        int interval = Math.max(5, config.getBalancerHealthCheckInterval());
        healthTask = proxy.getScheduler().buildTask(plugin, this::healthAndScaleTick)
                .delay(interval, TimeUnit.SECONDS).schedule();
    }

    private void healthAndScaleTick() {
        if (!running) return;
        try {
            cleanupCooldowns();
            fallbackCheckAndStartAlternate();
            ensureMinOnline();
            ensureLimboOnlineIfNeeded();
            if (config.isBalancerAutoScaleEnabled()) {
                scaleUpIfNeeded();
            }
            if (config.isBalancerScaleDownEnabled()) {
                scaleDownIfNeeded();
            }
        } catch (Exception ex) {
            logger.warn("Error during lobby balancer health-check: {}", ex.toString());
        } finally {
            reschedule();
        }
    }
    // endregion

    // region Health + scaling
    private void ensureMinOnline() {
        List<String> lobbies = getConfiguredLobbiesToUse();
        if (lobbies.isEmpty()) return;
        int minOnline = Math.max(0, config.getBalancerMinOnline());
        if (minOnline == 0) return;

        List<String> online = lobbies.stream().filter(this::isReachable).collect(Collectors.toList());
        if (online.size() >= minOnline) return;

        // Start more until min satisfied
        for (String name : lobbies) {
            if (online.contains(name)) continue;
            if (inCooldown(name)) continue;
            if (tryStart(name)) {
                online.add(name);
                if (online.size() >= minOnline) break;
            }
        }
    }

    private void scaleUpIfNeeded() {
        List<String> lobbies = getConfiguredLobbiesToUse();
        if (lobbies.isEmpty()) return;
        int maxOnline = Math.max(0, config.getBalancerMaxOnline());

        List<String> online = lobbies.stream().filter(this::isReachable).collect(Collectors.toList());
        if (maxOnline > 0 && online.size() >= maxOnline) {
            return; // cap reached
        }

        // Compute aggregate load
        int players = 0;
        for (String name : online) {
            players += proxy.getServer(name).map(s -> s.getPlayersConnected().size()).orElse(0);
        }
        int pps = Math.max(1, config.getBalancerPlayersPerServer());
        int capacity = online.size() * pps;

        double threshold = Math.min(100, Math.max(1, config.getBalancerPreStartThresholdPercent())) / 100.0;
        boolean needMoreByPlayers = players >= capacity * threshold; // start earlier to avoid bottlenecks
        boolean needMoreByCpu = false;
        if (strategy() == Strategy.LEAST_CPU) {
            // If any server CPU usage over threshold, consider scaling
            double cpuThreshold = config.getBalancerCpuScaleUpThreshold();
            for (String name : online) {
                PteroServerInfo info = managedServers.get(name);
                if (info == null) continue;
                try {
                    var usage = apiClient.fetchServerResources(info.getServerId()).get(2, java.util.concurrent.TimeUnit.SECONDS);
                    if (usage != null && usage.isAvailable() && usage.getCpuAbsolute() >= cpuThreshold) {
                        needMoreByCpu = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (needMoreByPlayers || needMoreByCpu) {
            // Find first offline lobby we can start
            for (String name : lobbies) {
                if (online.contains(name)) continue;
                if (inCooldown(name)) continue;
                if (tryStart(name)) {
                    logger.info("Auto-scaling: started lobby '{}' due to {}.", name, needMoreByCpu ? "CPU" : "player load");
                    break;
                }
            }
        }
    }

    private void ensureLimboOnlineIfNeeded() {
        if (!config.isSendToLimboOnStart()) return;
        List<String> limbos = config.getBalancerLimbos();
        if (limbos == null || limbos.isEmpty()) return;
        boolean anyOnline = limbos.stream().anyMatch(this::isReachable);
        if (anyOnline) return;
        for (String name : limbos) {
            if (inCooldown(name)) continue;
            if (tryStart(name)) {
                logger.info("Ensuring limbo online: started '{}'.", name);
                break;
            }
        }
    }

    private void scaleDownIfNeeded() {
        List<String> lobbies = getConfiguredLobbiesToUse();
        if (lobbies.isEmpty()) return;
        int minOnline = Math.max(0, config.getBalancerMinOnline());
        List<String> online = lobbies.stream().filter(this::isReachable).collect(Collectors.toList());
        if (online.size() <= minOnline) {
            emptySince.keySet().removeIf(k -> !online.contains(k));
            return;
        }

        long now = System.currentTimeMillis();
        long idleMs = config.getBalancerScaleDownIdleSeconds() * 1000L;
        String bestStop = null;
        int bestPlayers = Integer.MAX_VALUE;

        for (String name : online) {
            int players = proxy.getServer(name).map(s -> s.getPlayersConnected().size()).orElse(0);
            if (players > 0) {
                emptySince.remove(name);
                continue;
            }
            // Skip if players are waiting/transferring toward this lobby
            if (plugin.getPlayerConnectionHandler() != null
                    && plugin.getPlayerConnectionHandler().hasActiveWaiters(name)) {
                emptySince.remove(name);
                continue;
            }
            emptySince.putIfAbsent(name, now);
            Long since = emptySince.get(name);
            if (since == null || now - since < idleMs) continue;
            if (players < bestPlayers) {
                bestPlayers = players;
                bestStop = name;
            }
        }

        if (bestStop == null) return;
        if (online.size() - 1 < minOnline) return;

        PteroServerInfo info = managedServers.get(bestStop);
        if (info == null) return;
        if (plugin.getMaintenanceService() != null
                && plugin.getMaintenanceService().isServerInMaintenance(bestStop)) {
            return;
        }
        if (!plugin.getRateLimitTracker().canMakeRequest(
                de.tubyoub.velocitypteropower.util.PanelRequestPriority.BALANCER)) {
            return;
        }
        logger.info("Auto-scale down: stopping empty lobby '{}' (keeping minOnline={}).", bestStop, minOnline);
        apiClient.powerServer(info.getServerId(), PowerSignal.STOP);
        emptySince.remove(bestStop);
    }
    // endregion

    // region Helpers
    private boolean tryStart(String serverName) {
        long now = System.currentTimeMillis();
        Long until = cooldownUntil.get(serverName);
        if (until != null && until > now) {
            logger.debug("Skipping start for '{}' due to cooldown ({}s left).", serverName, (until - now) / 1000);
            return false;
        }
        PteroServerInfo info = managedServers.get(serverName);
        if (info == null) {
            logger.debug("Cannot auto-start '{}': not managed by VPP (no panel id in config).", serverName);
            return false;
        }
        if (plugin.getMaintenanceService() != null
                && plugin.getMaintenanceService().isServerInMaintenance(serverName)) {
            logger.debug("Skipping start for '{}' — maintenance mode.", serverName);
            return false;
        }
        // Throttle repeat start attempts for the same lobby to avoid spamming panel/API and logs
        Long last = lastStartAttempt.get(serverName);
        int minGapSec = Math.max(5, config.getBalancerStartFailureFallbackSeconds());
        if (last != null && (now - last) < (minGapSec * 1000L)) {
            logger.debug("Skipping start for '{}' (last attempt {}s ago).", serverName, (now - last) / 1000);
            return false;
        }
        if (!plugin.getRateLimitTracker().canMakeRequest()) {
            logger.warn("Rate limited. Cannot start lobby '{}' right now.", serverName);
            return false;
        }
        // If already online/reachable, no need to send start again
        try {
            if (apiClient.isServerOnline(serverName, info.getServerId())) {
                return true;
            }
        } catch (Exception ignored) {
            // If the check fails due to transient issues, fall through to attempt start once per throttle window
        }
        logger.info("Starting lobby '{}' ({}).", serverName, info.getServerId());
        apiClient.powerServer(info.getServerId(), PowerSignal.START);
        lastStartAttempt.put(serverName, now);
        return true;
    }

    private boolean inCooldown(String name) {
        Long until = cooldownUntil.get(name);
        return until != null && until > System.currentTimeMillis();
    }

    private void cleanupCooldowns() {
        long now = System.currentTimeMillis();
        cooldownUntil.entrySet().removeIf(e -> e.getValue() <= now);
    }

    private void fallbackCheckAndStartAlternate() {
        if (lastStartAttempt.isEmpty()) return;
        long now = System.currentTimeMillis();
        int fallbackSeconds = Math.max(10, config.getBalancerStartFailureFallbackSeconds());
        int cooldownSeconds = Math.max(10, config.getBalancerStartFailureCooldownSeconds());
        List<String> lobbies = getConfiguredLobbiesToUse();
        for (Map.Entry<String, Long> e : new ArrayList<>(lastStartAttempt.entrySet())) {
            String name = e.getKey();
            long ts = e.getValue();
            if (now - ts < fallbackSeconds * 1000L) continue;
            if (isReachable(name)) {
                lastStartAttempt.remove(name);
                continue;
            }
            // Mark cooldown for the problematic lobby
            cooldownUntil.put(name, now + cooldownSeconds * 1000L);
            lastStartAttempt.remove(name);
            logger.warn("Start fallback: '{}' did not come online within {}s. Trying a different lobby...", name, fallbackSeconds);
            // Try an alternate offline lobby not in cooldown
            for (String candidate : lobbies) {
                if (candidate.equalsIgnoreCase(name)) continue;
                if (inCooldown(candidate)) continue;
                if (isReachable(candidate)) continue; // already online; not needed here
                if (tryStart(candidate)) {
                    logger.info("Start fallback: started alternate lobby '{}' (fallback for '{}').", candidate, name);
                    break;
                }
            }
        }
    }

    private Optional<RegisteredServer> selectByStrategy(List<String> candidates, boolean lobby) {
        Strategy strategy = strategy();
        switch (strategy) {
            case LEAST_PLAYERS -> {
                String best = null;
                int bestCount = Integer.MAX_VALUE;
                for (String name : candidates) {
                    int cnt = proxy.getServer(name).map(s -> s.getPlayersConnected().size()).orElse(Integer.MAX_VALUE);
                    if (cnt < bestCount) {
                        best = name;
                        bestCount = cnt;
                    }
                }
                return best == null ? Optional.empty() : proxy.getServer(best);
            }
            case LEAST_CPU -> {
                // If resource info not available, fallback to least players
                String best = null;
                double bestCpu = Double.MAX_VALUE;
                for (String name : candidates) {
                    PteroServerInfo info = managedServers.get(name);
                    if (info == null) continue;
                    try {
                        var usage = apiClient.fetchServerResources(info.getServerId()).get(2, java.util.concurrent.TimeUnit.SECONDS);
                        if (usage != null && usage.isAvailable()) {
                            double cpu = usage.getCpuAbsolute();
                            if (cpu < bestCpu) {
                                bestCpu = cpu;
                                best = name;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (best != null) return proxy.getServer(best);
                // fallback
                return selectByStrategy(candidates, lobby, Strategy.LEAST_PLAYERS);
            }
            default -> {
                // ROUND_ROBIN
                AtomicInteger idx = lobby ? rrLobbyIdx : rrLimboIdx;
                int i = Math.floorMod(idx.getAndIncrement(), candidates.size());
                return proxy.getServer(candidates.get(i));
            }
        }
    }

    private Optional<RegisteredServer> selectByStrategy(List<String> candidates, boolean lobby, Strategy fallback) {
        try {
            switch (fallback) {
                case LEAST_PLAYERS -> {
                    String best = null;
                    int bestCount = Integer.MAX_VALUE;
                    for (String name : candidates) {
                        int cnt = proxy.getServer(name).map(s -> s.getPlayersConnected().size()).orElse(Integer.MAX_VALUE);
                        if (cnt < bestCount) {
                            best = name;
                            bestCount = cnt;
                        }
                    }
                    return best == null ? Optional.empty() : proxy.getServer(best);
                }
                default -> {
                    AtomicInteger idx = lobby ? rrLobbyIdx : rrLimboIdx;
                    int i = Math.floorMod(idx.getAndIncrement(), candidates.size());
                    return proxy.getServer(candidates.get(i));
                }
            }
        } finally {
            // no state change
        }
    }

    private boolean isReachable(String serverName) {
        // If registered in proxy and (managed and online) or (unmanaged -> assume reachable if registered)
        Optional<RegisteredServer> rs = proxy.getServer(serverName);
        if (rs.isEmpty()) return false;
        PteroServerInfo info = managedServers.get(serverName);
        if (info == null) return true; // unmanaged but registered
        try {
            return apiClient.isServerOnline(serverName, info.getServerId());
        } catch (Exception ex) {
            return false;
        }
    }

    private List<String> getConfiguredLobbiesToUse() {
        List<String> lobbies = config.getBalancerLobbies();
        if (lobbies == null) lobbies = Collections.emptyList();
        int use = Math.max(0, config.getBalancerLobbiesToUse());
        List<String> filtered = lobbies.stream()
            .filter(name -> {
                if (managedServers.containsKey(name)) {
                    return true;
                }
                logger.debug("Skipping unmanaged lobby '{}' (not in servers config).", name);
                return false;
            })
            .collect(Collectors.toList());
        if (use > 0 && use < filtered.size()) {
            return filtered.subList(0, use);
        }
        return filtered;
    }
    private Strategy strategy() {
        String name = config.getBalancerStrategyName();
        if (name == null) return Strategy.ROUND_ROBIN;
        try {
            return Strategy.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return Strategy.ROUND_ROBIN;
        }
    }
    // endregion
}
