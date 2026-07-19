package de.tubyoub.velocitypteropower;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.http.*;
import de.tubyoub.velocitypteropower.command.PteroCommand;
import de.tubyoub.velocitypteropower.handler.PlayerConnectionHandler;
import de.tubyoub.velocitypteropower.lifecycle.ServerLifecycleManager;
import de.tubyoub.velocitypteropower.listener.ServerSwitchListener;
import de.tubyoub.velocitypteropower.lobby.LobbyBalancerManager;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.WhitelistManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.service.UpdateService;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import de.tubyoub.velocitypteropower.util.Metrics;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.slf4j.event.Level;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VelocityPteroPower {
    private static final String VERSION = "0.9.6-alpha";
    private static final String MODRINTH_PROJECT_ID = "1dDr5J4w";
    private static final int BSTATS_PLUGIN_ID = 21465;

    private final ProxyServer proxyServer;
    private ComponentLogger originalLogger;
    private final Path dataDirectory;
    private final CommandManager commandManager;
    private final Metrics.Factory metricsFactory;

    private ConfigurationManager configurationManager;
    private MessagesManager messagesManager;
    private WhitelistManager whitelistManager;
    private PanelAPIClient apiClient;
    private RateLimitTracker rateLimitTracker;
    private de.tubyoub.velocitypteropower.util.PanelRequestScheduler panelRequestScheduler;
    private UpdateService updateService;
    private PlayerConnectionHandler playerConnectionHandler;
    private ServerLifecycleManager serverLifecycleManager;
    private ServerSwitchListener serverSwitchListener;
    private LobbyBalancerManager lobbyBalancerManager;
    private de.tubyoub.velocitypteropower.service.LimboTrackerService limboTrackerService;
    private de.tubyoub.velocitypteropower.service.MoveHistoryService moveHistoryService;
    private de.tubyoub.velocitypteropower.service.QueueProgressService queueProgressService;
    private de.tubyoub.velocitypteropower.service.PersistentQueueService persistentQueueService;
    private de.tubyoub.velocitypteropower.service.MaintenanceService maintenanceService;
    private de.tubyoub.velocitypteropower.service.ServerStateCache serverStateCache;
    private de.tubyoub.velocitypteropower.http.PanelWebSocketService panelWebSocketService;

    private FilteredComponentLogger filteredLogger;

    private Map<String, PteroServerInfo> serverInfoMap = new ConcurrentHashMap<>();
    private final Set<String> startingServers = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<UUID>> waitingPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Long> shutdownDeadlines = new ConcurrentHashMap<>();
    private final Map<String, UUID> startInitiators = new ConcurrentHashMap<>();
    // Track when a server was marked as starting, for periodic cleanup
    private final Map<String, Long> startingServersSince = new ConcurrentHashMap<>();

    // bStats metrics instance and counters
    private Metrics metrics;
    private final java.util.concurrent.atomic.AtomicInteger serversStartedSinceBoot = new java.util.concurrent.atomic.AtomicInteger(0);

    @Inject
    public VelocityPteroPower(
            ProxyServer proxy,
            @DataDirectory Path dataDirectory,
            CommandManager commandManager,
            ComponentLogger logger,
            Metrics.Factory metricsFactory) {
        this.proxyServer = proxy;
        this.originalLogger = logger;
        this.dataDirectory = dataDirectory;
        this.commandManager = commandManager;
        this.metricsFactory = metricsFactory;

        this.filteredLogger = new FilteredComponentLogger(this.originalLogger, Level.INFO);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logStartupBanner();

        this.configurationManager = new ConfigurationManager(this);
        configurationManager.loadConfig();
        this.serverInfoMap = configurationManager.getServerInfoMap();
        this.updateLoggerLevel();

        this.whitelistManager = new WhitelistManager(proxyServer, this);

        this.messagesManager = new MessagesManager(this);
        messagesManager.loadMessages();

        this.rateLimitTracker = new RateLimitTracker(filteredLogger, configurationManager);
        this.panelRequestScheduler = new de.tubyoub.velocitypteropower.util.PanelRequestScheduler(
                this, rateLimitTracker, configurationManager);
        this.updateService = new UpdateService(filteredLogger, configurationManager, VERSION, MODRINTH_PROJECT_ID);
        initializeApiClient();
        if (this.apiClient == null) {
            filteredLogger.error("Failed to initialize Panel API Client. Plugin disabled.");
            return;
        }
        // Initialize public API and wrap client for eventing
        var eventBus = new de.tubyoub.velocitypteropower.api.DefaultVPPEventBus();
        var panelHttp = new de.tubyoub.velocitypteropower.api.PanelHttpFacadeImpl(configurationManager, ((de.tubyoub.velocitypteropower.http.AbstractPanelAPIClient) this.apiClient).getHttpClient());
        var serverControl = new de.tubyoub.velocitypteropower.api.ServerControlImpl(this);
        var serverRegistry = new de.tubyoub.velocitypteropower.api.ServerRegistryImpl(this);
        var defaultApi = new de.tubyoub.velocitypteropower.api.DefaultVPPApi(eventBus, panelHttp, serverControl, serverRegistry, this.getDataDirectory());
        de.tubyoub.vpp.api.VPPApiProvider.set(defaultApi);
        // wrap api client to fire events
        this.apiClient = new de.tubyoub.velocitypteropower.api.EventingPanelAPIClient(this, this.apiClient);
        if (!configurationManager.getPanelType().equals(PanelType.mcServerSoft)) {
            whitelistManager.initialize();
        } else {
            filteredLogger.warn("Mc Server Soft does not support whitelist fetching... disabling whitelist checking...");
        }

        this.serverLifecycleManager = new ServerLifecycleManager(proxyServer, this);
        this.playerConnectionHandler = new PlayerConnectionHandler(proxyServer, this);
        this.serverSwitchListener = new ServerSwitchListener(this, serverLifecycleManager);
        // Initialize and optionally start built-in lobby/limbo balancer (controlled solely by config)
        boolean enableBuiltin = configurationManager.isBuiltinLobbyBalancerEnabled();
        if (enableBuiltin) {
            this.lobbyBalancerManager = new LobbyBalancerManager(this);
            this.lobbyBalancerManager.startSchedulers();
            filteredLogger.info("Built-in LobbyBalancerManager ENABLED (lobbyBalancer.enableBuiltin=true).");
        } else {
            this.lobbyBalancerManager = null;
            filteredLogger.info("Built-in LobbyBalancerManager DISABLED by config (lobbyBalancer.enableBuiltin=false).");
        }
        // Initialize limbo tracking service
        this.limboTrackerService = new de.tubyoub.velocitypteropower.service.LimboTrackerService(this);
        // Schedule a light periodic sweep to drop stale records
        this.proxyServer.getScheduler().buildTask(this, () -> {
            try { if (limboTrackerService != null) limboTrackerService.sweepNow(); } catch (Exception ignored) {}
        }).delay(Math.max(10, configurationManager.getIdleShutdownCheckInterval()), java.util.concurrent.TimeUnit.SECONDS).schedule();
        
        // Initialize move history (optional, in-memory only)
        if (configurationManager.isMoveHistoryEnabled()) {
            this.moveHistoryService = new de.tubyoub.velocitypteropower.service.MoveHistoryService(this, configurationManager.getMoveHistoryMaxEntries());
            filteredLogger.info("Move history tracking is ENABLED (max {} entries/player, memory-only).", configurationManager.getMoveHistoryMaxEntries());
        } else {
            this.moveHistoryService = null;
        }

        this.maintenanceService = new de.tubyoub.velocitypteropower.service.MaintenanceService(this);
        this.persistentQueueService = new de.tubyoub.velocitypteropower.service.PersistentQueueService(
                this, configurationManager.getPersistentQueueTtlSeconds());
        this.queueProgressService = new de.tubyoub.velocitypteropower.service.QueueProgressService(this);
        this.queueProgressService.start();
        this.serverStateCache = new de.tubyoub.velocitypteropower.service.ServerStateCache(
                configurationManager.getServerStateCacheTtlSeconds());
        this.panelWebSocketService = new de.tubyoub.velocitypteropower.http.PanelWebSocketService(this, serverStateCache);
        this.panelWebSocketService.start();
        proxyServer.getScheduler().buildTask(this, () -> {
            try { if (persistentQueueService != null) persistentQueueService.purgeExpired(); } catch (Exception ignored) {}
        }).delay(60, java.util.concurrent.TimeUnit.SECONDS).schedule();
        // reschedule purge periodically
        Runnable purgeLoop = new Runnable() {
            @Override public void run() {
                try { if (persistentQueueService != null) persistentQueueService.purgeExpired(); } catch (Exception ignored) {}
                finally {
                    proxyServer.getScheduler().buildTask(VelocityPteroPower.this, this)
                            .delay(60, java.util.concurrent.TimeUnit.SECONDS).schedule();
                }
            }
        };
        proxyServer.getScheduler().buildTask(this, purgeLoop)
                .delay(120, java.util.concurrent.TimeUnit.SECONDS).schedule();
        
        // Schedule periodic enforcement of always-online servers
        this.serverLifecycleManager.scheduleAlwaysOnlineMaintenance();
        // Trigger an immediate always-online enforcement once at startup
        var always = configurationManager.getAlwaysOnlineList();
        if (always != null && !always.isEmpty()) {
            proxyServer.getScheduler().buildTask(this, () -> {
                try {
                    for (String name : always) {
                        PteroServerInfo info = serverInfoMap.get(name);
                        if (info == null) continue;
                        try {
                            if (!apiClient.isServerOnline(name, info.getServerId())) {
                                filteredLogger.info(messagesManager.mm(MessageKey.POWER_ACTION_SENT, java.util.Map.of("action", "start", "server", name)));
                                apiClient.powerServer(info.getServerId(), PowerSignal.START);
                                recordServerStartSignalSent();
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ex) {
                    filteredLogger.debug("Startup always-online start failed: {}", ex.toString());
                }
            }).schedule();
        }
        // Schedule periodic idle shutdown sweep (failsafe)
        this.serverLifecycleManager.scheduleIdleShutdownSweep();

        // Schedule periodic cleanup of potentially stuck 'startingServers' entries
        this.scheduleStartingServersSweep();

        // Periodic resource usage prefetch (warm cache) if enabled
        if (configurationManager.isResourcePrefetchEnabled()) {
            final int interval = Math.max(1, configurationManager.getResourceCacheSeconds());
            Runnable prefetchTask = new Runnable() {
                @Override public void run() {
                    try {
                        if (panelRequestScheduler != null && panelRequestScheduler.shouldPauseBackground()) {
                            filteredLogger.debug("Resource prefetch paused (rate pressure or waiters).");
                            return;
                        }
                        if (!rateLimitTracker.canMakeRequest(
                                de.tubyoub.velocitypteropower.util.PanelRequestPriority.BACKGROUND)) {
                            filteredLogger.debug("Resource prefetch skipped due to rate limit.");
                            return;
                        }
                        Map<String, PteroServerInfo> map = getServerInfoMap();
                        if (map != null && !map.isEmpty()) {
                            int staggerMs = Math.max(100, (interval * 1000) / Math.max(1, map.size()));
                            int idx = 0;
                            for (PteroServerInfo info : map.values()) {
                                if (panelRequestScheduler != null && panelRequestScheduler.shouldPauseBackground()) {
                                    filteredLogger.debug("Resource prefetch stopped early (background pause).");
                                    break;
                                }
                                if (!rateLimitTracker.canMakeRequest(
                                        de.tubyoub.velocitypteropower.util.PanelRequestPriority.BACKGROUND)) {
                                    filteredLogger.debug("Resource prefetch stopped early due to rate limit.");
                                    break;
                                }
                                final PteroServerInfo target = info;
                                proxyServer.getScheduler().buildTask(VelocityPteroPower.this, () -> {
                                    try {
                                        filteredLogger.debug("Prefetching resources for serverId={}", target.getServerId());
                                        rateLimitTracker.consumeOne();
                                        apiClient.fetchServerResources(target.getServerId());
                                    } catch (Exception ex) {
                                        rateLimitTracker.restoreOne();
                                    }
                                }).delay(idx * staggerMs, java.util.concurrent.TimeUnit.MILLISECONDS).schedule();
                                idx++;
                            }
                        }
                    } catch (Exception ex) {
                        filteredLogger.debug("Resource prefetch error: {}", ex.toString());
                    } finally {
                        proxyServer.getScheduler().buildTask(VelocityPteroPower.this, this).delay(interval, java.util.concurrent.TimeUnit.SECONDS).schedule();
                    }
                }
            };
            proxyServer.getScheduler().buildTask(this, prefetchTask).delay(interval, java.util.concurrent.TimeUnit.SECONDS).schedule();
            filteredLogger.info("Scheduled resource usage prefetch every {} seconds for {} server(s).", interval, serverInfoMap.size());
        }

        commandManager.register(
                commandManager.metaBuilder("ptero").aliases("vpp").build(), new PteroCommand(this));
        proxyServer.getEventManager().register(this, playerConnectionHandler);
        proxyServer.getEventManager().register(this, serverSwitchListener);

        // Initialize bStats metrics and register custom charts
        this.metrics = metricsFactory.make(this, BSTATS_PLUGIN_ID);
        if (this.metrics != null) {
            try {
                // Total managed servers
                this.metrics.addCustomChart(new Metrics.SingleLineChart("vpp_managed_servers", () -> {
                    try {
                        return serverInfoMap != null ? serverInfoMap.size() : 0;
                    } catch (Exception ignored) {
                        return 0;
                    }
                }));
                // Total starts since boot
                this.metrics.addCustomChart(new Metrics.SingleLineChart("vpp_total_servers_started", () -> serversStartedSinceBoot.get()));
                // Current online servers (best-effort)
                this.metrics.addCustomChart(new Metrics.SingleLineChart("vpp_current_online_servers", () -> {
                    try {
                        if (serverLifecycleManager == null) return 0;
                        int v = serverLifecycleManager.countOnlineServersExcluding(java.util.Collections.emptySet());
                        return Math.max(0, v);
                    } catch (Exception ignored) {
                        return 0;
                    }
                }));
                // Panel type breakdown
                this.metrics.addCustomChart(new Metrics.SimplePie("vpp_panel_type", () -> {
                    try {
                        return configurationManager != null && configurationManager.getPanelType() != null
                                ? configurationManager.getPanelType().name()
                                : "UNKNOWN";
                    } catch (Exception ignored) {
                        return "UNKNOWN";
                    }
                }));
            } catch (Exception ex) {
                filteredLogger.debug("Failed to register bStats custom charts: {}", ex.toString());
            }
        }
        updateService.performUpdateCheck();

        filteredLogger.info("VelocityPteroPower v{} successfully loaded.", VERSION);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        try {
            var rawList = configurationManager.getShutdownOnProxyExitList();
            if (serverInfoMap == null || rawList == null) return;

            // Clean up the list: remove null/blank entries and normalize to lowercase
            java.util.Set<String> targets = new java.util.HashSet<>();
            for (String s : rawList) {
                if (s == null) continue;
                String t = s.trim();
                if (t.isEmpty()) continue;
                targets.add(t.toLowerCase());
            }

            // If the list is empty after cleanup, treat as deactivated
            if (targets.isEmpty()) return;

            boolean all = targets.contains("all");
            int count = 0;
            for (Map.Entry<String, PteroServerInfo> e : serverInfoMap.entrySet()) {
                String name = e.getKey();
                String nameLc = name.toLowerCase();
                if (!all && !targets.contains(nameLc)) {
                    continue;
                }
                try {
                    apiClient.powerServer(e.getValue().getServerId(), PowerSignal.STOP);
                    count++;
                } catch (Exception ex) {
                    filteredLogger.warn("Failed sending stop on shutdown for {}: {}", name, ex.toString());
                }
            }
            if (count > 0) {
                filteredLogger.info(messagesManager.mm(MessageKey.COMMAND_STOPPING_ALL_SERVERS, java.util.Map.of("count", String.valueOf(count))));
            }
        } catch (Exception ex) {
            filteredLogger.warn("Error during shutdown stop sequence: {}", ex.toString());
        } finally {
            try { if (queueProgressService != null) queueProgressService.stop(); } catch (Exception ignored) {}
            try { if (panelWebSocketService != null) panelWebSocketService.stop(); } catch (Exception ignored) {}
            try { if (persistentQueueService != null) persistentQueueService.save(); } catch (Exception ignored) {}
            try { if (maintenanceService != null) maintenanceService.save(); } catch (Exception ignored) {}
            if (apiClient != null) {
                apiClient.shutdown();
            }
            try { de.tubyoub.vpp.api.VPPApiProvider.set(null); } catch (Throwable ignored) {}
            filteredLogger.info("Shutting down VelocityPteroPower... Goodbye!");
        }
    }

    public void reload() {
        filteredLogger.info("Reloading VelocityPteroPower configuration...");

        PanelType oldType = configurationManager.getPanelType();
        String oldKey = configurationManager.getPterodactylApiKey();

        configurationManager.loadConfig();
        this.updateLoggerLevel();
        messagesManager.loadMessages();
        whitelistManager.initialize();

        this.serverInfoMap = configurationManager.getServerInfoMap();

        // Re-evaluate built-in lobby balancer state based on config only
        boolean enableBuiltin = configurationManager.isBuiltinLobbyBalancerEnabled();
        if (enableBuiltin) {
            if (this.lobbyBalancerManager == null) {
                this.lobbyBalancerManager = new de.tubyoub.velocitypteropower.lobby.LobbyBalancerManager(this);
                this.lobbyBalancerManager.startSchedulers();
                filteredLogger.info("Built-in LobbyBalancerManager ENABLED after reload (lobbyBalancer.enableBuiltin=true).");
            } else {
                // Already present; ensure it is running
                try { this.lobbyBalancerManager.startSchedulers(); } catch (Throwable ignored) {}
            }
        } else {
            if (this.lobbyBalancerManager != null) {
                try { this.lobbyBalancerManager.stopSchedulers(); } catch (Throwable ignored) {}
                this.lobbyBalancerManager = null;
                filteredLogger.info("Built-in LobbyBalancerManager DISABLED by config after reload (lobbyBalancer.enableBuiltin=false).");
            }
        }

        PanelType newType = configurationManager.getPanelType();
        String newKey = configurationManager.getPterodactylApiKey();

        if (apiClient == null || oldType != newType || !Objects.equals(oldKey, newKey)) {
            filteredLogger.info("API client configuration changed. Re-initializing...");
            if (apiClient != null) apiClient.shutdown();
            initializeApiClient();
            if (apiClient == null) {
                filteredLogger.error(
                        "Failed to re-initialize Panel API Client after reload. Plugin will not function correctly.");
            } else {
                // Recreate public API wiring and eventing wrapper
                try {
                    var eventBus = new de.tubyoub.velocitypteropower.api.DefaultVPPEventBus();
                    var panelHttp = new de.tubyoub.velocitypteropower.api.PanelHttpFacadeImpl(configurationManager, ((de.tubyoub.velocitypteropower.http.AbstractPanelAPIClient) this.apiClient).getHttpClient());
                    var serverControl = new de.tubyoub.velocitypteropower.api.ServerControlImpl(this);
                    var serverRegistry = new de.tubyoub.velocitypteropower.api.ServerRegistryImpl(this);
                    var defaultApi = new de.tubyoub.velocitypteropower.api.DefaultVPPApi(eventBus, panelHttp, serverControl, serverRegistry, this.getDataDirectory());
                    de.tubyoub.vpp.api.VPPApiProvider.set(defaultApi);
                    this.apiClient = new de.tubyoub.velocitypteropower.api.EventingPanelAPIClient(this, this.apiClient);
                } catch (Throwable t) {
                    filteredLogger.warn("Failed to re-wire public API after reload: {}", t.toString());
                }
                filteredLogger.warn("API Client re-initialized. Dependent components use live references.");
            }
        } else {
            filteredLogger.info("API client configuration unchanged.");
        }

        filteredLogger.info("VelocityPteroPower configuration reloaded.");
    }

    public void updateLoggerLevel() {
        org.slf4j.event.Level configLevel = configurationManager.getLoggerLevel();
        filteredLogger.setLevel(configLevel);
    }

    private void initializeApiClient() {
        PanelType type = configurationManager.getPanelType();
        filteredLogger.info("Initializing API client for panel type: {}", type);

        switch (type) {
            case pterodactyl -> {
                filteredLogger.debug("Detected Pterodactyl Panel, creating api client...");
                this.apiClient = new PterodactylAPIClient(this);
            }
            case pelican -> {
                filteredLogger.debug("Detected Pelican Panel, creating api client...");
                this.apiClient = new PelicanAPIClient(this);
            }
            case mcServerSoft -> {
                filteredLogger.debug("Detected Mc Server Soft Panel, creating api client...");
                this.apiClient = new McServerSoftApiClient(this);
            }
            case error -> {
                logInvalidApplicationApiKeyError();
                this.apiClient = null;
                return;
            }
            default -> {
                filteredLogger.debug("No Panel type specified. Defaulting to pterodactyl Api Client...");
                this.apiClient = new PterodactylAPIClient(this);
            }
        }
        if (!apiClient.isApiKeyValid(configurationManager.getPterodactylApiKey())) {
            logInvalidApiKeyError();
            this.apiClient = null;
        }
    }

    public void recordServerStartSignalSent() {
        try {
            serversStartedSinceBoot.incrementAndGet();
        } catch (Exception ignored) {
        }
    }

    private void logStartupBanner() {
        MiniMessage mm = MiniMessage.miniMessage();
        filteredLogger.info(mm.deserialize("<#4287f5>____   ________________________"));
        filteredLogger.info(mm.deserialize("<#4287f5>\\   \\ /   /\\______   \\______   \\"));
        filteredLogger.info(mm.deserialize("<#4287f5> \\   Y   /  |     ___/|     ___/"));
        filteredLogger.info(
                mm.deserialize(
                        "<#4287f5>  \\     /   |    |    |    |"
                                + "<#00ff77>         VelocityPteroPower <#6b6c6e>v"
                                + VERSION));
        filteredLogger.info(
                mm.deserialize(
                        "<#4287f5>   \\___/    |____|tero|____|ower" + "<#A9A9A9>     Running on Velocity"));
    }

    private void logInvalidApiKeyError() {
        filteredLogger.error("=================================================");
        filteredLogger.error(" VelocityPteroPower Initialization Failed!");
        filteredLogger.error(" ");
        filteredLogger.error(" No valid API key found or configured in config.yml.");
        filteredLogger.error(" Please ensure 'pterodactyl.apiKey' is set correctly.");
        filteredLogger.error(" Key should start with 'ptlc_' (Pterodactyl Client) or 'plcn_'/'pacc_' (Pelican Client).");
        filteredLogger.error(" Application API keys ('ptla_'/'peli_') are NOT supported.");
        filteredLogger.error(" ");
        filteredLogger.error(" Plugin will be disabled.");
        filteredLogger.error("=================================================");
    }

    private void logInvalidApplicationApiKeyError() {
        filteredLogger.error("=================================================");
        filteredLogger.error(" VelocityPteroPower Initialization Failed!");
        filteredLogger.error(" ");
        filteredLogger.error(" Application API keys ('ptla_'/'peli_') are NOT supported.");
        filteredLogger.error(" Please use a Client API key: 'ptlc_' (Pterodactyl) or 'plcn_'/'pacc_' (Pelican).");
        filteredLogger.error(" ");
        filteredLogger.error(" Plugin will be disabled.");
        filteredLogger.error("=================================================");
    }

    public PlayerConnectionHandler getPlayerConnectionHandler() {
        return playerConnectionHandler;
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public FilteredComponentLogger getFilteredLogger() {
        return filteredLogger;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public ConfigurationManager getConfigurationManager() {
        return configurationManager;
    }

    public MessagesManager getMessagesManager() {
        return messagesManager;
    }

    public WhitelistManager getWhitelistManager() {
        return whitelistManager;
    }

    public PanelAPIClient getApiClient() {
        return apiClient;
    }

    public RateLimitTracker getRateLimitTracker() {
        return rateLimitTracker;
    }

    public de.tubyoub.velocitypteropower.util.PanelRequestScheduler getPanelRequestScheduler() {
        return panelRequestScheduler;
    }

    public de.tubyoub.velocitypteropower.service.QueueProgressService getQueueProgressService() {
        return queueProgressService;
    }

    public de.tubyoub.velocitypteropower.service.PersistentQueueService getPersistentQueueService() {
        return persistentQueueService;
    }

    public de.tubyoub.velocitypteropower.service.MaintenanceService getMaintenanceService() {
        return maintenanceService;
    }

    public de.tubyoub.velocitypteropower.service.ServerStateCache getServerStateCache() {
        return serverStateCache;
    }

    public ServerLifecycleManager getServerLifecycleManager() {
        return serverLifecycleManager;
    }

    public Map<String, PteroServerInfo> getServerInfoMap() {
        return serverInfoMap;
    }

    public Set<String> getStartingServers() {
        return startingServers;
    }

    public Map<String, Set<UUID>> getWaitingPlayers() {
        return waitingPlayers;
    }

    public Map<UUID, Long> getPlayerCooldowns() {
        return playerCooldowns;
    }

    public Map<String, Long> getShutdownDeadlines() {
        return shutdownDeadlines;
    }

    public LobbyBalancerManager getLobbyBalancerManager() {
        return lobbyBalancerManager;
    }

    public de.tubyoub.velocitypteropower.service.LimboTrackerService getLimboTrackerService() {
        return limboTrackerService;
    }

    public de.tubyoub.velocitypteropower.service.MoveHistoryService getMoveHistoryService() {
        return moveHistoryService;
    }

    public Map<String, UUID> getStartInitiators() {
        return startInitiators;
    }

    public Map<String, Long> getStartingServersSince() {
        return startingServersSince;
    }

    private void scheduleStartingServersSweep() {
        final int sweepIntervalSeconds = 60; // run every 60 seconds
        Runnable sweep = new Runnable() {
            @Override public void run() {
                try {
                    long now = System.currentTimeMillis();
                    var namesSnapshot = Set.copyOf(startingServers);
                    for (String name : namesSnapshot) {
                        PteroServerInfo info = serverInfoMap.get(name);
                        if (info == null) {
                            // Not managed anymore; clean up
                            startingServers.remove(name);
                            startInitiators.remove(name);
                            startingServersSince.remove(name);
                            continue;
                        }
                        boolean online = rateLimitTracker.canMakeRequest() && apiClient.isServerOnline(name, info.getServerId());
                        if (online) {
                            filteredLogger.debug("Cleanup sweep: '{}' is online. Clearing 'starting' state.", name);
                            startingServers.remove(name);
                            startInitiators.remove(name);
                            startingServersSince.remove(name);
                            continue;
                        }
                        long since = startingServersSince.getOrDefault(name, now);
                        long maxWaitSeconds = configurationManager.getStartupInitialCheckDelay()
                                + configurationManager.resolveStartupTimeout(info)
                                + 10L; // small buffer
                        // Do not clear while players are still waiting
                        if (waitingPlayers.containsKey(name)
                                && waitingPlayers.get(name) != null
                                && !waitingPlayers.get(name).isEmpty()) {
                            continue;
                        }
                        if (now - since > maxWaitSeconds * 1000L) {
                            filteredLogger.warn("Cleanup sweep: '{}' exceeded max startup window ({}s). Clearing stuck state.", name, maxWaitSeconds);
                            startingServers.remove(name);
                            startInitiators.remove(name);
                            startingServersSince.remove(name);
                        }
                    }
                } catch (Exception ex) {
                    filteredLogger.debug("Starting-servers sweep error: {}", ex.toString());
                } finally {
                    proxyServer.getScheduler().buildTask(VelocityPteroPower.this, this)
                            .delay(sweepIntervalSeconds, java.util.concurrent.TimeUnit.SECONDS)
                            .schedule();
                }
            }
        };
        proxyServer.getScheduler().buildTask(this, sweep)
                .delay(sweepIntervalSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .schedule();
        filteredLogger.info("Scheduled starting-servers cleanup sweep every {} seconds.", sweepIntervalSeconds);
    }
}