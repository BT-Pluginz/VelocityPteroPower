package de.tubyoub.velocitypteropower.http;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import org.slf4j.Logger;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;

public abstract class AbstractPanelAPIClient implements PanelAPIClient {

    protected static class CacheEntry {
        final long timestamp;
        final de.tubyoub.velocitypteropower.model.ServerResourceUsage data;
        CacheEntry(long timestamp, de.tubyoub.velocitypteropower.model.ServerResourceUsage data) {
            this.timestamp = timestamp;
            this.data = data;
        }
    }

    // per-server resource usage cache
    protected final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> resourceCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<de.tubyoub.velocitypteropower.model.ServerResourceUsage>> inFlightFetches = new java.util.concurrent.ConcurrentHashMap<>();

    protected final Logger logger;
    protected final ConfigurationManager configurationManager;
    protected final ProxyServer proxyServer;
    protected final VelocityPteroPower plugin;
    protected final RateLimitTracker rateLimitTracker;

    protected final HttpClient httpClient;
    protected final ExecutorService executorService;

    /**
     * Exposes the internal HttpClient for API facade usage.
     * Intended for read-only use by integration layers.
     */
    public HttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * Constructs a base API client with common dependencies.
     *
     * @param plugin The main VelocityPteroPower plugin instance.
     */
    public AbstractPanelAPIClient(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.configurationManager = plugin.getConfigurationManager();
        this.proxyServer = plugin.getProxyServer();
        this.rateLimitTracker = plugin.getRateLimitTracker();

        int configuredThreads = configurationManager.getApiThreads();
        int servers = 0;
        try {
            java.util.Map<String, de.tubyoub.velocitypteropower.model.PteroServerInfo> map = plugin.getServerInfoMap();
            if (map != null) servers = map.size();
        } catch (Exception ignored) {}
        int computedThreads = Math.max(configuredThreads, Math.max(4, Math.min(64, servers * 2)));
        this.executorService = java.util.concurrent.Executors.newFixedThreadPool(computedThreads);
        logger.info("API executor threads: configured={}, servers={}, using={}", configuredThreads, servers, computedThreads);
        this.httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Checks if a server is online using the configured method (Velocity Ping or Panel API).
     *
     * @param serverName The name of the server as registered in Velocity.
     * @param serverId   The panel server identifier (UUID).
     * @return {@code true} if the server is considered online, {@code false} otherwise.
     */
    @Override
    public boolean isServerOnline(String serverName, String serverId) {
        try {
            var cache = plugin.getServerStateCache();
            if (cache != null && cache.isRunningFresh(serverId)) {
                ConfigurationManager.ServerCheckMethod method =
                        configurationManager.resolveCheckMethod(
                                plugin.getServerInfoMap() != null ? plugin.getServerInfoMap().get(serverName) : null);
                if (method == ConfigurationManager.ServerCheckMethod.PANEL_API
                        || method == ConfigurationManager.ServerCheckMethod.HYBRID) {
                    if (method == ConfigurationManager.ServerCheckMethod.PANEL_API) {
                        return true;
                    }
                    // HYBRID still needs ping
                    return checkOnlineViaVelocityPing(serverName);
                }
            }
        } catch (Exception ignored) {}
        ConfigurationManager.ServerCheckMethod methodOverride = null;
        try {
            var map = plugin.getServerInfoMap();
            if (map != null) {
                var info = map.get(serverName);
                if (info != null && info.getCheckMethodOverride() != null) {
                    methodOverride = info.getCheckMethodOverride();
                }
            }
        } catch (Exception ignored) {}
        return isServerOnline(serverName, serverId, methodOverride);
    }

    /**
     * Checks if a server is online using the resolved check method (global or per-server override).
     */
    public boolean isServerOnline(String serverName, String serverId,
                                  ConfigurationManager.ServerCheckMethod methodOverride) {
        ConfigurationManager.ServerCheckMethod method =
            methodOverride != null ? methodOverride : configurationManager.getServerCheckMethod();

        switch (method) {
            case VELOCITY_PING:
                return checkOnlineViaVelocityPing(serverName);
            case PANEL_API:
                return checkOnlineViaPanelApi(serverName, serverId);
            case HYBRID:
                return checkOnlineViaPanelApi(serverName, serverId)
                        && checkOnlineViaVelocityPing(serverName);
            default:
                logger.error(
                    "Unknown ServerCheckMethod: {}. Defaulting to false.",
                    method
                );
                return false;
        }
    }

    /**
     * Checks if a server is online by pinging it through Velocity.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @return {@code true} if the server responds to ping, {@code false} otherwise.
     */
    protected boolean checkOnlineViaVelocityPing(String serverName) {
        Optional<RegisteredServer> serverOptional =
            proxyServer.getServer(serverName);
        if (serverOptional.isEmpty()) {
            logger.debug(
                "Cannot perform PING check: Server '{}' not registered in Velocity.",
                serverName
            );
            return false; // Server not known to Velocity
        }

        RegisteredServer server = serverOptional.get();
        try {
            CompletableFuture<ServerPing> pingFuture = server.ping();
            ServerPing pingResult = pingFuture.get(
                configurationManager.getPingTimeout(),
                TimeUnit.MILLISECONDS
            );
            boolean online = pingResult != null;
            logger.debug(
                "Ping check for {}: {}",
                serverName,
                online ? "Success" : "Failed (No result/Timeout)"
            );
            return online;
        } catch (TimeoutException e) {
            logger.debug(
                "Ping check for {} timed out after {}ms.",
                serverName,
                configurationManager.getPingTimeout()
            );
            return false;
        } catch (ExecutionException e) {
            // Log the underlying cause if possible
            Throwable cause = e.getCause();
            logger.debug(
                "Ping check for {} failed: {}",
                serverName,
                cause != null ? cause.getMessage() : e.getMessage()
            );
            return false;
        } catch (InterruptedException e) {
            logger.warn("Ping check for {} interrupted.", serverName);
            Thread.currentThread().interrupt(); // Re-interrupt the thread
            return false;
        } catch (Exception e) { // Catch unexpected errors during ping
            logger.warn(
                "Unexpected error pinging server {}: {}",
                serverName,
                e.getMessage(),
                e
            );
            return false;
        }
    }

    /**
     * Checks if a server is online by querying the panel API.
     * This method should be implemented by each specific API client.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @param serverId   The panel server identifier (UUID).
     * @return {@code true} if the server is online according to the panel, {@code false} otherwise.
     */
    protected abstract boolean checkOnlineViaPanelApi(String serverName, String serverId);

    /**
     * Checks if a server registered in Velocity has any players connected.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @return {@code true} if the server has no players or is not found, {@code false} otherwise.
     */
    @Override
    public boolean isServerEmpty(String serverName) {
        return proxyServer.getServer(serverName)
            .map(server -> server.getPlayersConnected().isEmpty())
            .orElse(true); // Treat non-existent server as empty
    }

    /**
     * Shuts down the executor service used for API requests.
     */
    public void shutdown() {
        executorService.shutdownNow();
    }

    /**
     * Helper to fetch resource usage with per-server caching.
     */
    protected java.util.concurrent.CompletableFuture<de.tubyoub.velocitypteropower.model.ServerResourceUsage> fetchWithCache(
            String serverId,
            java.util.function.Supplier<de.tubyoub.velocitypteropower.model.ServerResourceUsage> supplier
    ) {
        int ttl = configurationManager.getResourceCacheSeconds();
        long now = System.currentTimeMillis();
        if (ttl > 0) {
            CacheEntry e = resourceCache.get(serverId);
            if (e != null) {
                long age = now - e.timestamp;
                if (age < ttl * 1000L) {
                    logger.debug("Resource cache HIT for {} (age={}ms, ttl={}s).", serverId, age, ttl);
                    return java.util.concurrent.CompletableFuture.completedFuture(e.data);
                } else {
                    logger.debug("Resource cache EXPIRED for {} (age={}ms > ttl={}s).", serverId, age, ttl);
                }
            } else {
                logger.debug("Resource cache MISS for {} (ttl={}s).", serverId, ttl);
            }
        } else {
            logger.debug("Resource cache DISABLED (ttl=0) for {}.", serverId);
        }
        CompletableFuture<de.tubyoub.velocitypteropower.model.ServerResourceUsage> existing = inFlightFetches.get(serverId);
        if (existing != null && !existing.isDone()) {
            logger.debug("Resource fetch IN-FLIGHT reuse for {}.", serverId);
            return existing;
        }
        CompletableFuture<de.tubyoub.velocitypteropower.model.ServerResourceUsage> future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                de.tubyoub.velocitypteropower.model.ServerResourceUsage data = supplier.get();
                if (ttl > 0 && data != null) {
                    resourceCache.put(serverId, new CacheEntry(System.currentTimeMillis(), data));
                    logger.debug("Resource cache STORE for {} (ttl={}s).", serverId, ttl);
                }
                return data == null ? de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable() : data;
            } catch (Exception ex) {
                logger.error("Error fetching resources for {}: {}", serverId, ex.toString());
                return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
            }
        }, executorService);
        inFlightFetches.put(serverId, future);
        future.whenComplete((r, t) -> inFlightFetches.remove(serverId, future));
        return future;
    }
}
