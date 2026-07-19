package de.tubyoub.velocitypteropower.handler;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.http.PowerSignal;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import de.tubyoub.vpp.api.VPPApiProvider;
import de.tubyoub.vpp.api.event.PlayerPreConnectEvent;
import de.tubyoub.vpp.api.event.PlayerPreServerSwitchEvent;
import de.tubyoub.vpp.api.routing.PlayerRouteContext;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class PlayerConnectionHandler {

    private static final Logger log = LoggerFactory.getLogger(PlayerConnectionHandler.class);
  private static final int MAX_CONNECT_RETRIES = 5;
  private static final long CONNECT_RETRY_DELAY_SECONDS = 2;

  private static final class PendingConnect {
    final String serverName;
    int retryCount;
    volatile boolean cancelled;
    final long transferStartedAt;
    int softRequeueCount;
    final boolean fromStartupWait;

    PendingConnect(String serverName, boolean fromStartupWait) {
      this.serverName = serverName;
      this.fromStartupWait = fromStartupWait;
      this.transferStartedAt = System.currentTimeMillis();
    }
  }

  private final ProxyServer proxyServer;
  private final VelocityPteroPower plugin;
  private final ComponentLogger logger;
  private final ConfigurationManager configurationManager;
  private final MessagesManager messagesManager;
  private final PanelAPIClient apiClient;
  private final RateLimitTracker rateLimitTracker;

  private final Map<String, PteroServerInfo> serverInfoMap;
  private final Set<String> startingServers;
  private final Map<String, Set<UUID>> waitingPlayers;
  private final Map<UUID, Long> playerCooldowns;
  private final Map<String, UUID> startInitiators;
  private final Map<UUID, PendingConnect> pendingConnects = new ConcurrentHashMap<>();
  private final Set<String> activeStartupWatchers = ConcurrentHashMap.newKeySet();
  private final Map<String, Long> startupWatcherDeadlines = new ConcurrentHashMap<>();
  private final Set<String> settlingServers = ConcurrentHashMap.newKeySet();

  public PlayerConnectionHandler(ProxyServer proxyServer, VelocityPteroPower plugin) {
    this.proxyServer = proxyServer;
    this.plugin = plugin;
    this.logger = plugin.getFilteredLogger();
    this.configurationManager = plugin.getConfigurationManager();
    this.messagesManager = plugin.getMessagesManager();
    this.apiClient = plugin.getApiClient();
    this.rateLimitTracker = plugin.getRateLimitTracker();
    this.serverInfoMap = plugin.getServerInfoMap();
    this.startingServers = plugin.getStartingServers();
    this.waitingPlayers = plugin.getWaitingPlayers();
    this.playerCooldowns = plugin.getPlayerCooldowns();
    this.startInitiators = plugin.getStartInitiators();
  }

  /**
   * True if players are queued waiting for this server, or a startup watcher/settle is active.
   */
  public boolean hasActiveWaiters(String serverName) {
    Set<UUID> waiting = waitingPlayers.get(serverName);
    if (waiting != null && !waiting.isEmpty()) {
      return true;
    }
    return activeStartupWatchers.contains(serverName) || settlingServers.contains(serverName);
  }

  /** Idle-stop is unsafe while waiters/watchers exist for the target. */
  public boolean isSafeToIdleStop(String serverName) {
    return !hasActiveWaiters(serverName);
  }

  public Map<String, Long> getStartupWatcherDeadlines() {
    return startupWatcherDeadlines;
  }

  public Set<String> getActiveStartupWatchers() {
    return activeStartupWatchers;
  }

  /**
   * Cancels any pending connect retries and removes the player from waiting lists.
   * Called on player disconnect.
   */
  public void cancelPendingConnect(UUID playerId) {
    PendingConnect pending = pendingConnects.remove(playerId);
    if (pending != null) {
      pending.cancelled = true;
    }
    for (Map.Entry<String, Set<UUID>> entry : waitingPlayers.entrySet()) {
      Set<UUID> waiting = entry.getValue();
      if (waiting.remove(playerId)) {
        clearStartingStateIfEmpty(entry.getKey());
      }
    }
    // Keep persistent queue entry so reconnect can resume (cleared on success / TTL)
  }

  /** Resume a persisted wait after reconnect, if any. */
  public void tryResumePersistentQueue(Player player) {
    if (!configurationManager.isPersistentQueueEnabled() || plugin.getPersistentQueueService() == null) {
      return;
    }
    var entry = plugin.getPersistentQueueService().get(player.getUniqueId());
    if (entry == null || entry.targetServer == null) {
      return;
    }
    PteroServerInfo info = serverInfoMap.get(entry.targetServer);
    if (info == null) {
      plugin.getPersistentQueueService().clear(player.getUniqueId());
      return;
    }
    logger.info(
        "Resuming persistent queue for {} → {}",
        player.getUsername(),
        entry.targetServer);
    scheduleDelayedConnect(player, entry.targetServer, info);
  }

  @Subscribe(priority = 10)
  public void onServerPreConnect(ServerPreConnectEvent event) {
    Player player = event.getPlayer();
    RegisteredServer targetServer = event.getOriginalServer();
    String serverName = targetServer.getServerInfo().getName();

    // Public API: allow addons to intercept and reroute/cancel before any internal logic
    try {
      var api = VPPApiProvider.get();
      if (api != null) {
        RegisteredServer prevServer = event.getPreviousServer();
        String prev = prevServer != null ? prevServer.getServerInfo().getName() : null;

        // 1) Consult RoutingProvider SPI (highest priority first)
        try {
          if (api.hasRoutingProvider()) {
            String reason = (prev == null ? "INITIAL" : "SWITCH");
            var ctx = new PlayerRouteContext(player.getUniqueId(), player.getUsername(), serverName, reason);
            boolean handled = api.selectRoute(ctx);
            if (handled) {
              if (ctx.isCancelled()) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                return;
              }
              String t = ctx.getTargetServer();
              if (t != null && !t.equalsIgnoreCase(serverName)) {
                proxyServer.getServer(t).ifPresentOrElse(rs -> event.setResult(ServerPreConnectEvent.ServerResult.allowed(rs)), () -> event.setResult(ServerPreConnectEvent.ServerResult.denied()));
                return;
              }
              // If handled but no override, continue with default/event flow
            }
          }
        } catch (Throwable ignored2) {}

        // 2) Event-based interception (pre-events)
        if (prev == null) {
          var evt = new PlayerPreConnectEvent(player.getUniqueId(), player.getUsername(), serverName, serverName);
          api.getEventBus().post(evt);
          if (evt.isCancelled()) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
          }
          String t = evt.getTargetServer();
          if (t != null && !t.equalsIgnoreCase(serverName)) {
            proxyServer.getServer(t).ifPresentOrElse(rs -> event.setResult(ServerPreConnectEvent.ServerResult.allowed(rs)), () -> event.setResult(ServerPreConnectEvent.ServerResult.denied()));
            return;
          }
        } else {
          var evt = new PlayerPreServerSwitchEvent(player.getUniqueId(), player.getUsername(), prev, serverName, "UNKNOWN");
          api.getEventBus().post(evt);
          if (evt.isCancelled()) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
          }
          String t = evt.getTargetServer();
          if (t != null && !t.equalsIgnoreCase(serverName)) {
            proxyServer.getServer(t).ifPresentOrElse(rs -> event.setResult(ServerPreConnectEvent.ServerResult.allowed(rs)), () -> event.setResult(ServerPreConnectEvent.ServerResult.denied()));
            return;
          }
        }
      }
    } catch (Throwable ignored) {}

    PteroServerInfo serverInfo = serverInfoMap.get(serverName);
    if (serverInfo == null) {
      handleUnmanagedServer(player, serverName);
      return;
    }

    if (!plugin.getWhitelistManager().isPlayerWhitelisted(serverName, player.getUsername())) {
      if (event.getPreviousServer() == null) {
        player.disconnect(
            messagesManager.prefixed(MessageKey.CONNECT_NOT_WHITELISTED));
        return;
      }
      // Player is trying to switch to a whitelisted server they are not allowed on.
      // Inform and deny without starting the target server or sending them to limbo.
      player.sendMessage(messagesManager.prefixed(MessageKey.CONNECT_NOT_WHITELISTED));
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      return;
    }

    String serverId = serverInfo.getServerId();

    if (isPlayerOnCooldown(player, serverName) && event.getPreviousServer() != null) {
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      return;
    }

    boolean isOnline = apiClient.isServerOnline(serverName, serverId);

    if (isOnline) {
      // If the player is already connected to the target server, do not attempt a transfer
      boolean alreadyOnTarget = player
          .getCurrentServer()
          .map(cs -> cs.getServer().getServerInfo().getName().equalsIgnoreCase(serverName))
          .orElse(false);
      if (alreadyOnTarget) {
        logger.debug("Player {} is already connected to {}. Suppressing transfer.", player.getUsername(), serverName);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        return;
      }
      startingServers.remove(serverName);
      startInitiators.remove(serverName);
      plugin.getStartingServersSince().remove(serverName);
      logger.debug("Server {} is online. Allowing connection for {}.", serverName, player.getUsername());
      return;
    }

    handleOfflineServerConnection(event, player, serverName, serverId, serverInfo);
  }

  private void handleUnmanagedServer(Player player, String serverName) {
    logger.debug("Server '{}' is not managed by VelocityPteroPower.", serverName);
    if (configurationManager.isServerNotFoundMessage()) {
      player.sendMessage(
          messagesManager.prefixed(
              MessageKey.CONNECT_UNMANAGED_SERVER, "server", serverName));
    }
  }

  private boolean isPlayerOnCooldown(Player player, String serverName) {
    long currentTime = System.currentTimeMillis();
    long lastStartTime = playerCooldowns.getOrDefault(player.getUniqueId(), 0L);
    int cooldownMillis = configurationManager.getPlayerCommandCooldown() * 1000;

    if (configurationManager.getPlayerCommandCooldown() <= 0){
        return false;
    }

    if (currentTime - lastStartTime < cooldownMillis) {
      long remainingSeconds =
          TimeUnit.MILLISECONDS.toSeconds(cooldownMillis - (currentTime - lastStartTime)) + 1;
      player.sendMessage(
          messagesManager.prefixed(
              MessageKey.COMMAND_COOLDOWN_ACTIVE, "timeout", String.valueOf(remainingSeconds)));
      logger.debug(
          "Player {} is on cooldown for starting server {}.",
          player.getUsername(),
          serverName);
      return true;
    }
    return false;
  }

  private void handleOfflineServerConnection(
      ServerPreConnectEvent event,
      Player player,
      String serverName,
      String serverId,
      PteroServerInfo serverInfo) {
    if (startingServers.contains(serverName) && event.getPreviousServer() != null) {
      UUID initiator = startInitiators.get(serverName);
      MessageKey key = (initiator != null && initiator.equals(player.getUniqueId()))
          ? MessageKey.CONNECT_SERVER_STARTING_INITIATOR
          : MessageKey.CONNECT_SERVER_STARTING;
      // If configured, route the player to a limbo instead of denying, and queue them
      if (configurationManager.isSendToLimboOnStart()) {
        try {
          if (plugin.getLobbyBalancerManager() != null) {
            Optional<RegisteredServer> limboOpt = plugin.getLobbyBalancerManager().pickLimbo();
            if (limboOpt.isPresent()) {
              RegisteredServer limbo = limboOpt.get();
              boolean alreadyOnLimbo = event.getPreviousServer().equals(limbo);
              if (!alreadyOnLimbo) {
                logger.info("Server '{}' is already starting. Redirecting {} to limbo '{}' while waiting.",
                    serverName, player.getUsername(), limbo.getServerInfo().getName());
                player.sendMessage(messagesManager.prefixed(key, "server", serverName));
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(limbo));
              } else {
                // Already on the chosen limbo; just deny the switch and keep them there
                player.sendMessage(messagesManager.prefixed(key, "server", serverName));
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
              }
              // Track limbo placement
              try { var lts = plugin.getLimboTrackerService(); if (lts != null) lts.record(player, limbo, de.tubyoub.velocitypteropower.service.LimboReason.SERVER_START_WAIT, serverName); } catch(Exception ignored) {}
              scheduleDelayedConnect(player, serverName, serverInfo);
              return;
            }
          }
        } catch (Exception ex) {
          logger.debug("Limbo selection failed in starting-branch: {}", ex.toString());
        }
      }
      // Fallback: deny the switch and queue as before
      player.sendMessage(messagesManager.prefixed(key, "server", serverName));
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      logger.debug(
          "Server {} is already starting. Denying connection for {}.",
          serverName,
          player.getUsername());
      // Queue this player for automatic connection when the server is online
      scheduleDelayedConnect(player, serverName, serverInfo);
      return;
    }

    if (!rateLimitTracker.canMakeRequest()) {
      logger.warn(
          "Cannot start server {} ({}) for {} due to rate limiting.",
          serverName,
          serverId,
          player.getUsername());
      player.sendMessage(messagesManager.prefixed(MessageKey.CONNECT_ERROR_RATE_LIMITED));
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      return;
    }

    if (plugin.getMaintenanceService() != null
        && plugin.getMaintenanceService().isServerInMaintenance(serverName)) {
      player.sendMessage(
          messagesManager.prefixed(
              MessageKey.CONNECT_MAINTENANCE_BLOCKED,
              "detail", plugin.getMaintenanceService().detailFor(serverName)));
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      return;
    }

    // Enforce maximum concurrent online servers (excluding exempt), unless bypassed
    int maxOnline = configurationManager.getMaxOnlineServers();
    boolean hasBypass = configurationManager.isMaxOnlineAllowBypass() && player.hasPermission("ptero.maxcap.bypass");
    if (maxOnline > 0 && !hasBypass) {
      java.util.Set<String> exempt = new java.util.HashSet<>(configurationManager.getMaxOnlineExemptList());
      // Optionally exclude lobbies/limbos from the cap based on config
      if (!configurationManager.isCountLobbiesInMaxOnline()) {
        java.util.List<String> lobbies = configurationManager.getBalancerLobbies();
        int use = Math.max(0, configurationManager.getBalancerLobbiesToUse());
        if (lobbies != null && !lobbies.isEmpty()) {
          if (use > 0 && use < lobbies.size()) {
            exempt.addAll(lobbies.subList(0, use));
          } else {
            exempt.addAll(lobbies);
          }
        }
      }
      if (!configurationManager.isCountLimbosInMaxOnline()) {
        java.util.List<String> limbos = configurationManager.getBalancerLimbos();
        if (limbos != null) exempt.addAll(limbos);
      }
      if (!exempt.contains(serverName)) {
        int onlineCount = plugin.getServerLifecycleManager().countOnlineServersExcluding(exempt);
        int effectiveCap = Math.max(0, maxOnline - configurationManager.getMaxOnlineReservations());
        if (onlineCount >= effectiveCap && onlineCount != -1) {
          if (configurationManager.isQueueWhenMaxOnline()) {
            player.sendMessage(
                messagesManager.prefixed(
                    MessageKey.CONNECT_CAPACITY_QUEUED, "server", serverName));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            scheduleDelayedConnect(player, serverName, serverInfo);
            return;
          }
          player.sendMessage(
              messagesManager.prefixed(
                  MessageKey.CONNECT_MAX_ONLINE_REACHED,
                  "max", String.valueOf(maxOnline)));
          event.setResult(ServerPreConnectEvent.ServerResult.denied());
          return;
        }
      }
    }

    if (!startingServers.contains(serverName)) {
      logger.info(
          "Attempting to start server '{}' ({}) for player {}",
          serverName,
          serverId,
          player.getUsername());
      startingServers.add(serverName);
      startInitiators.putIfAbsent(serverName, player.getUniqueId());
      plugin.getStartingServersSince().put(serverName, System.currentTimeMillis());
      playerCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
      apiClient.powerServer(serverId, PowerSignal.START);
      plugin.recordServerStartSignalSent();
      scheduleInitialIdleCheck(serverName, serverId);
    }

    boolean useLimbo = configurationManager.isSendToLimboOnStart();
    Optional<RegisteredServer> limboServerOpt = useLimbo ? findValidLimboServer() : Optional.empty();

    if (useLimbo && limboServerOpt.isPresent()) {
      RegisteredServer limboServer = limboServerOpt.get();
      // If the player is already on the selected limbo, avoid re-sending them there to prevent Velocity errors
      boolean alreadyOnLimbo = event.getPreviousServer() != null && event.getPreviousServer().equals(limboServer);
      if (alreadyOnLimbo) {
        logger.debug("Player {} is already on limbo '{}'. Keeping them there while '{}' starts.",
            player.getUsername(), limboServer.getServerInfo().getName(), serverName);
        // Do not change the server; just deny the switch and keep the player where they are
        {
          UUID initiator = startInitiators.get(serverName);
          MessageKey key = (initiator != null && initiator.equals(player.getUniqueId()))
              ? MessageKey.CONNECT_SERVER_STARTING_INITIATOR
              : MessageKey.CONNECT_SERVER_STARTING;
          player.sendMessage(messagesManager.prefixed(key, "server", serverName));
        }
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        scheduleDelayedConnect(player, serverName, serverInfo);
      } else {
        logger.info(
            "Redirecting player {} to limbo server '{}' while server '{}' starts.",
            player.getUsername(),
            limboServer.getServerInfo().getName(),
            serverName);
        player.sendMessage(
            messagesManager.prefixed(
                MessageKey.CONNECT_REDIRECTING_TO_LIMBO,
                "server",
                serverName,
                "limbo",
                limboServer.getServerInfo().getName()));
        event.setResult(ServerPreConnectEvent.ServerResult.allowed(limboServer));
        // Track limbo placement
        try { var lts = plugin.getLimboTrackerService(); if (lts != null) lts.record(player, limboServer, de.tubyoub.velocitypteropower.service.LimboReason.SERVER_START_WAIT, serverName); } catch(Exception ignored) {}
        scheduleDelayedConnect(player, serverName, serverInfo);
      }
    } else {
      String baseMsgKey = MessageKey.CONNECT_STARTING_SERVER_DISCONNECT.getPath();
      if (event.getPreviousServer() == null) {
        // Forced-host join case. Consult forcedHostOfflineBehavior to avoid disconnecting if possible.
        var behavior = configurationManager.getForcedHostOfflineBehavior();

        Optional<RegisteredServer> holdingOpt = Optional.empty();
        try {
          if (plugin.getLobbyBalancerManager() != null) {
            switch (behavior) {
              case LOBBY_OR_LIMBO -> {
                holdingOpt = plugin.getLobbyBalancerManager().pickHoldingServer();
              }
              case LIMBO_ONLY -> {
                holdingOpt = plugin.getLobbyBalancerManager().pickHoldingServer()
                    .filter(rs -> configurationManager.getBalancerLimbos()
                        .contains(rs.getServerInfo().getName()));
              }
              case DISCONNECT -> { /* fall through to disconnect below */ }
            }
          }
        } catch (Exception ex) {
          logger.debug("Holding selection failed: {}", ex.toString());
        }

        if (holdingOpt.isPresent()) {
          RegisteredServer holding = holdingOpt.get();
          logger.info("Forced-host: redirecting {} to holding '{}' while '{}' starts.",
              player.getUsername(), holding.getServerInfo().getName(), serverName);

          // Inform and queue
          player.sendMessage(messagesManager.prefixed(
              MessageKey.CONNECT_SERVER_STARTING, "server", serverName));

          event.setResult(ServerPreConnectEvent.ServerResult.allowed(holding));
          scheduleDelayedConnect(player, serverName, serverInfo);
          return;
        }

        logger.info(
            "Forced-host: no holding available. Disconnecting {} while '{}' starts.",
            player.getUsername(),
            serverName);
        player.disconnect(messagesManager.prefixed(baseMsgKey, "server", serverName));
      } else {
        player.sendMessage(messagesManager.prefixed(baseMsgKey, "server", serverName));
        scheduleDelayedConnect(player, serverName, serverInfo);
      }
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
    }
  }

  private Optional<RegisteredServer> findValidLimboServer() {
    // Prefer an explicit limbo for sendToLimboOnStart
    try {
      if (plugin.getLobbyBalancerManager() != null) {
        Optional<RegisteredServer> limbo = plugin.getLobbyBalancerManager().pickLimbo();
        if (limbo.isPresent()) {
          return limbo;
        }
      }
    } catch (Exception ex) {
      logger.debug("Balancer limbo selection failed: {}", ex.toString());
    }
    return Optional.empty();
  }

  private void scheduleDelayedConnect(
    Player player, String targetServerName, PteroServerInfo targetServerInfo) {
    waitingPlayers
        .computeIfAbsent(targetServerName, k -> ConcurrentHashMap.newKeySet())
        .add(player.getUniqueId());
    if (configurationManager.isPersistentQueueEnabled() && plugin.getPersistentQueueService() != null) {
      plugin.getPersistentQueueService().remember(player.getUniqueId(), targetServerName);
    }
    ensureServerStartupWatcher(targetServerName, targetServerInfo);
  }

  private void ensureServerStartupWatcher(String targetServerName, PteroServerInfo targetServerInfo) {
    if (!activeStartupWatchers.add(targetServerName)) {
      return;
    }

    long initialDelay = configurationManager.getStartupInitialCheckDelay();
    long checkInterval = configurationManager.resolvePollInterval(targetServerInfo);
    long settleSeconds = Math.max(0, targetServerInfo.getJoinDelay());
    long timeoutSeconds = configurationManager.resolveStartupTimeout(targetServerInfo);
    long deadlineMs = System.currentTimeMillis() + (initialDelay + timeoutSeconds) * 1000L;
    startupWatcherDeadlines.put(targetServerName, deadlineMs);

    proxyServer
        .getScheduler()
        .buildTask(
            plugin,
            new Runnable() {
              @Override
              public void run() {
                Set<UUID> waiting = waitingPlayers.get(targetServerName);
                if (waiting == null || waiting.isEmpty()) {
                  activeStartupWatchers.remove(targetServerName);
                  startupWatcherDeadlines.remove(targetServerName);
                  settlingServers.remove(targetServerName);
                  return;
                }

                pruneInactiveWaiters(targetServerName, waiting);
                if (waiting.isEmpty()) {
                  activeStartupWatchers.remove(targetServerName);
                  startupWatcherDeadlines.remove(targetServerName);
                  settlingServers.remove(targetServerName);
                  clearStartingStateIfEmpty(targetServerName);
                  return;
                }

                if (System.currentTimeMillis() >= deadlineMs) {
                  handleStartupTimeout(targetServerName, waiting);
                  return;
                }

                // Capacity-queued: try to send START once a slot is available
                if (!startingServers.contains(targetServerName)
                    && !apiClient.isServerOnline(targetServerName, targetServerInfo.getServerId())) {
                  if (tryStartWhenCapacityAllows(targetServerName, targetServerInfo)) {
                    logger.info(
                        "Capacity slot freed — starting '{}' for {} waiter(s).",
                        targetServerName,
                        waiting.size());
                  }
                }

                if (apiClient.isServerOnline(targetServerName, targetServerInfo.getServerId())) {
                  logger.info(
                      "Server {} is now online. Settling {}s before connecting {} waiting player(s).",
                      targetServerName,
                      settleSeconds,
                      waiting.size());
                  settlingServers.add(targetServerName);
                  Runnable connectAll = () -> {
                    settlingServers.remove(targetServerName);
                    Set<UUID> stillWaiting = waitingPlayers.get(targetServerName);
                    if (stillWaiting == null || stillWaiting.isEmpty()) {
                      activeStartupWatchers.remove(targetServerName);
                      startupWatcherDeadlines.remove(targetServerName);
                      return;
                    }
                    // Re-verify after settle
                    if (!apiClient.isServerOnline(targetServerName, targetServerInfo.getServerId())) {
                      logger.info(
                          "Server {} failed re-verify after settle. Resuming poll.",
                          targetServerName);
                      activeStartupWatchers.remove(targetServerName);
                      ensureServerStartupWatcher(targetServerName, targetServerInfo);
                      return;
                    }
                    logger.info(
                        "Server {} ready after settle. Connecting {} waiting player(s).",
                        targetServerName,
                        stillWaiting.size());
                    for (UUID playerId : new HashSet<>(stillWaiting)) {
                      proxyServer.getPlayer(playerId).ifPresent(p ->
                          connectPlayerToServer(p, targetServerName, true));
                    }
                    activeStartupWatchers.remove(targetServerName);
                    startupWatcherDeadlines.remove(targetServerName);
                  };
                  if (settleSeconds <= 0) {
                    connectAll.run();
                  } else {
                    proxyServer
                        .getScheduler()
                        .buildTask(plugin, connectAll)
                        .delay(settleSeconds, TimeUnit.SECONDS)
                        .schedule();
                  }
                  return;
                }

                long remaining = Math.max(0, (deadlineMs - System.currentTimeMillis()) / 1000L);
                logger.debug(
                    "Server {} not online yet. Rescheduling shared check ({}s remaining).",
                    targetServerName,
                    remaining);
                proxyServer
                    .getScheduler()
                    .buildTask(plugin, this)
                    .delay(checkInterval, TimeUnit.SECONDS)
                    .schedule();
              }
            })
        .delay(initialDelay, TimeUnit.SECONDS)
        .schedule();
  }

  private void handleStartupTimeout(String targetServerName, Set<UUID> waiting) {
    logger.error(
        "Server {} did not come online within the expected time. Cancelling connect tasks for {} player(s).",
        targetServerName,
        waiting.size());
    long timeoutSec = configurationManager.resolveStartupTimeout(serverInfoMap.get(targetServerName));
    for (UUID playerId : new HashSet<>(waiting)) {
      proxyServer
          .getPlayer(playerId)
          .ifPresent(
              p ->
                  p.sendMessage(
                      messagesManager.prefixed(
                          MessageKey.CONNECT_START_TIMEOUT,
                          "server", targetServerName,
                          "seconds", String.valueOf(timeoutSec))));
      pendingConnects.remove(playerId);
    }
    waiting.clear();
    activeStartupWatchers.remove(targetServerName);
    startupWatcherDeadlines.remove(targetServerName);
    settlingServers.remove(targetServerName);
    if (configurationManager.getStartupTimeoutPolicy()
        == ConfigurationManager.StartupTimeoutPolicy.KEEP_STARTING) {
      waitingPlayers.remove(targetServerName);
      // leave startingServers so players can re-queue without a full cold start
    } else {
      clearStartingStateIfEmpty(targetServerName);
    }
  }

  private boolean tryStartWhenCapacityAllows(String serverName, PteroServerInfo serverInfo) {
    if (plugin.getMaintenanceService() != null
        && plugin.getMaintenanceService().isServerInMaintenance(serverName)) {
      return false;
    }
    if (!rateLimitTracker.canMakeRequest()) {
      return false;
    }
    int maxOnline = configurationManager.getMaxOnlineServers();
    if (maxOnline > 0) {
      java.util.Set<String> exempt = new java.util.HashSet<>(configurationManager.getMaxOnlineExemptList());
      if (!configurationManager.isCountLobbiesInMaxOnline()) {
        java.util.List<String> lobbies = configurationManager.getBalancerLobbies();
        int use = Math.max(0, configurationManager.getBalancerLobbiesToUse());
        if (lobbies != null && !lobbies.isEmpty()) {
          if (use > 0 && use < lobbies.size()) exempt.addAll(lobbies.subList(0, use));
          else exempt.addAll(lobbies);
        }
      }
      if (!configurationManager.isCountLimbosInMaxOnline()) {
        java.util.List<String> limbos = configurationManager.getBalancerLimbos();
        if (limbos != null) exempt.addAll(limbos);
      }
      if (!exempt.contains(serverName)) {
        int onlineCount = plugin.getServerLifecycleManager().countOnlineServersExcluding(exempt);
        int effectiveCap = Math.max(0, maxOnline - configurationManager.getMaxOnlineReservations());
        if (onlineCount >= effectiveCap && onlineCount != -1) {
          return false;
        }
      }
    }
    if (!startingServers.add(serverName)) {
      return false;
    }
    plugin.getStartingServersSince().put(serverName, System.currentTimeMillis());
    apiClient.powerServer(serverInfo.getServerId(), PowerSignal.START);
    plugin.recordServerStartSignalSent();
    scheduleInitialIdleCheck(serverName, serverInfo.getServerId());
    return true;
  }

  private void pruneInactiveWaiters(String targetServerName, Set<UUID> waiting) {
    for (UUID playerId : new HashSet<>(waiting)) {
      Optional<Player> playerOpt = proxyServer.getPlayer(playerId);
      if (playerOpt.isEmpty()) {
        waiting.remove(playerId);
        pendingConnects.remove(playerId);
        continue;
      }
      Player player = playerOpt.get();
      if (!player.isActive() || player.getCurrentServer().isEmpty()) {
        logger.info(
            "Player {} disconnected or left limbo while waiting for {}. Cancelling connect task.",
            player.getUsername(),
            targetServerName);
        waiting.remove(playerId);
        pendingConnects.remove(playerId);
        try {
          player.sendMessage(
              messagesManager.prefixed(
                  MessageKey.CONNECT_QUEUE_CANCELLED, "server", targetServerName));
        } catch (Exception ignored) {}
        continue;
      }
      String currentName =
          player.getCurrentServer().map(cs -> cs.getServer().getServerInfo().getName()).orElse("");
      java.util.Set<String> limbos = new java.util.HashSet<>(configurationManager.getBalancerLimbos());
      java.util.Set<String> lobbies = new java.util.HashSet<>(configurationManager.getBalancerLobbies());
      boolean onHolding = limbos.contains(currentName) || lobbies.contains(currentName);
      boolean onTarget = currentName.equalsIgnoreCase(targetServerName);
      if (!onHolding && !onTarget) {
        logger.info(
            "Player {} moved to '{}' while waiting for '{}'. Not a lobby/limbo/target — cancelling their auto-connect.",
            player.getUsername(),
            currentName,
            targetServerName);
        waiting.remove(playerId);
        pendingConnects.remove(playerId);
        player.sendMessage(
            messagesManager.prefixed(
                MessageKey.CONNECT_QUEUE_CANCELLED, "server", targetServerName));
      }
    }
    if (waiting.isEmpty()) {
      clearStartingStateIfEmpty(targetServerName);
    }
  }

  private void clearStartingStateIfEmpty(String serverName) {
    Set<UUID> waiting = waitingPlayers.get(serverName);
    if (waiting != null && !waiting.isEmpty()) {
      return;
    }
    waitingPlayers.remove(serverName);
    startingServers.remove(serverName);
    plugin.getStartingServersSince().remove(serverName);
    startInitiators.remove(serverName);
  }

  private void removeWaitingPlayer(UUID playerId, String serverName) {
    Set<UUID> waiting = waitingPlayers.get(serverName);
    if (waiting != null) {
      waiting.remove(playerId);
    }
    clearStartingStateIfEmpty(serverName);
    if (plugin.getPersistentQueueService() != null) {
      plugin.getPersistentQueueService().clear(playerId);
    }
  }

  private void connectPlayerToServer(Player player, String serverName) {
    connectPlayerToServer(player, serverName, false);
  }

  private void connectPlayerToServer(Player player, String serverName, boolean fromStartupWait) {
    if (!player.isActive()) {
      cancelPendingConnect(player.getUniqueId());
      return;
    }

    PendingConnect pending =
        pendingConnects.compute(
            player.getUniqueId(),
            (id, existing) -> {
              if (existing != null
                  && !existing.cancelled
                  && existing.serverName.equals(serverName)) {
                return existing;
              }
              return new PendingConnect(serverName, fromStartupWait);
            });
    if (pending.cancelled || !pending.serverName.equals(serverName)) {
      return;
    }

    Optional<RegisteredServer> serverOpt = proxyServer.getServer(serverName);

    if (serverOpt.isEmpty()) {
      logger.error(
          "Cannot connect player {}: Server '{}' not found/registered in Velocity.",
          player.getUsername(),
          serverName);
      player.sendMessage(
          messagesManager.prefixed(
              MessageKey.CONNECT_TARGET_SERVER_NOT_FOUND, "server", serverName));
      pendingConnects.remove(player.getUniqueId());
      removeWaitingPlayer(player.getUniqueId(), serverName);
      return;
    }

    RegisteredServer targetServer = serverOpt.get();

    if (player
        .getCurrentServer()
        .map(cs -> cs.getServer().equals(targetServer))
        .orElse(false)) {
      logger.debug("Player {} is already connected to {}. No action needed.", player.getUsername(), serverName);
      pendingConnects.remove(player.getUniqueId());
      removeWaitingPlayer(player.getUniqueId(), serverName);
      return;
    }

    logger.info("Connecting player {} to server {}.", player.getUsername(), serverName);
    player.createConnectionRequest(targetServer).connect().whenComplete((result, throwable) -> {
      if (!player.isActive()) {
        cancelPendingConnect(player.getUniqueId());
        return;
      }

      PendingConnect current = pendingConnects.get(player.getUniqueId());
      if (current == null || current.cancelled) {
        return;
      }

      if (throwable != null) {
        scheduleConnectRetry(player, serverName, current, "exception: " + throwable);
        return;
      }
      if (result == null) {
        scheduleConnectRetry(player, serverName, current, "null result");
        return;
      }

      ConnectionRequestBuilder.Status status = result.getStatus();
      switch (status) {
        case SUCCESS -> {
          logger.info("{} moved to {}", player.getUsername(), serverName);
          pendingConnects.remove(player.getUniqueId());
          removeWaitingPlayer(player.getUniqueId(), serverName);
        }
        case ALREADY_CONNECTED -> {
          logger.debug("{} was already on {}", player.getUsername(), serverName);
          pendingConnects.remove(player.getUniqueId());
          removeWaitingPlayer(player.getUniqueId(), serverName);
        }
        case SERVER_DISCONNECTED -> {
          if (trySoftRequeueAfterPrematureKick(player, serverName, current)) {
            return;
          }
          logger.debug(
              "Connect status for {} to {} was SERVER_DISCONNECTED — not retrying (backend message preserved).",
              player.getUsername(),
              serverName);
          pendingConnects.remove(player.getUniqueId());
          removeWaitingPlayer(player.getUniqueId(), serverName);
        }
        case CONNECTION_IN_PROGRESS -> scheduleConnectRetry(player, serverName, current, status.name());
        default -> {
          logger.debug(
              "Connect status for {} to {} was {} — not retrying.",
              player.getUsername(),
              serverName,
              status);
          pendingConnects.remove(player.getUniqueId());
          removeWaitingPlayer(player.getUniqueId(), serverName);
        }
      }
    });
  }

  private boolean trySoftRequeueAfterPrematureKick(Player player, String serverName, PendingConnect pending) {
    if (!pending.fromStartupWait) {
      return false;
    }
    int grace = configurationManager.getSoftRequeueGraceSeconds();
    int maxSoft = configurationManager.getSoftRequeueMaxAttempts();
    if (grace <= 0 || maxSoft <= 0) {
      return false;
    }
    long ageMs = System.currentTimeMillis() - pending.transferStartedAt;
    if (ageMs > grace * 1000L) {
      return false;
    }
    if (pending.softRequeueCount >= maxSoft) {
      return false;
    }
    PteroServerInfo info = serverInfoMap.get(serverName);
    if (info == null) {
      return false;
    }
    pending.softRequeueCount++;
    pendingConnects.remove(player.getUniqueId());
    logger.info(
        "Soft-requeueing {} for {} after premature SERVER_DISCONNECTED ({}/{}).",
        player.getUsername(),
        serverName,
        pending.softRequeueCount,
        maxSoft);
    player.sendMessage(
        messagesManager.prefixed(
            MessageKey.CONNECT_SERVER_STARTING, "server", serverName));
    waitingPlayers
        .computeIfAbsent(serverName, k -> ConcurrentHashMap.newKeySet())
        .add(player.getUniqueId());
    settlingServers.add(serverName);
    long settle = Math.max(2, info.getJoinDelay());
    proxyServer
        .getScheduler()
        .buildTask(plugin, () -> {
          settlingServers.remove(serverName);
          if (!player.isActive()) {
            return;
          }
          ensureServerStartupWatcher(serverName, info);
          // If already online, watcher will settle/connect; also nudge if watcher already ended
          if (!activeStartupWatchers.contains(serverName)
              && apiClient.isServerOnline(serverName, info.getServerId())) {
            connectPlayerToServer(player, serverName, true);
          }
        })
        .delay(settle, TimeUnit.SECONDS)
        .schedule();
    return true;
  }

  private void scheduleConnectRetry(Player player, String serverName, PendingConnect pending, String reason) {
    if (!player.isActive()) {
      cancelPendingConnect(player.getUniqueId());
      return;
    }
    pending.retryCount++;
    if (pending.retryCount > MAX_CONNECT_RETRIES) {
      logger.warn(
          "Connect to {} for {} failed after {} attempts ({}). Giving up.",
          serverName,
          player.getUsername(),
          MAX_CONNECT_RETRIES,
          reason);
      pendingConnects.remove(player.getUniqueId());
      removeWaitingPlayer(player.getUniqueId(), serverName);
      return;
    }
    logger.debug(
        "Connect failed for {} to {} ({}). Retrying shortly ({}/{}).",
        player.getUsername(),
        serverName,
        reason,
        pending.retryCount,
        MAX_CONNECT_RETRIES);
    proxyServer
        .getScheduler()
        .buildTask(plugin, () -> connectPlayerToServer(player, serverName, pending.fromStartupWait))
        .delay(CONNECT_RETRY_DELAY_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .schedule();
  }

  private void scheduleInitialIdleCheck(String serverName, String serverId) {
    long idleCheckDelay = configurationManager.getIdleStartShutdownTime();
    if (idleCheckDelay < 0) return;

    long recheckInterval = Math.max(5, configurationManager.getStartupInitialCheckDelay());

    proxyServer
        .getScheduler()
        .buildTask(
            plugin,
            new Runnable() {
              @Override
              public void run() {
                if (!startingServers.contains(serverName) && !hasActiveWaiters(serverName)) {
                  logger.debug("Initial idle check for {}: Cancelled (server no longer starting).", serverName);
                  return;
                }

                if (!isSafeToIdleStop(serverName)) {
                  logger.debug(
                      "Initial idle check for {}: Waiters/watcher active. Rescheduling.",
                      serverName);
                  reschedule();
                  return;
                }

                if (!rateLimitTracker.canMakeRequest()) {
                  logger.debug("Initial idle check for {}: Rate limited. Rescheduling.", serverName);
                  reschedule();
                  return;
                }

                boolean online = apiClient.isServerOnline(serverName, serverId);
                if (online) {
                  if (apiClient.isServerEmpty(serverName) && isSafeToIdleStop(serverName)) {
                    logger.info(
                        messagesManager.raw(MessageKey.SERVER_IDLE_SHUTDOWN)
                            .replace("<server>", serverName));
                    apiClient.powerServer(serverId, PowerSignal.STOP);
                    startingServers.remove(serverName);
                  } else if (!apiClient.isServerEmpty(serverName)) {
                    logger.debug("Initial idle check for {}: Players present. Cancelling idle shutdown task.", serverName);
                    startingServers.remove(serverName);
                  } else {
                    reschedule();
                  }
                } else {
                  logger.debug("Initial idle check for {}: Server not online yet. Rescheduling.", serverName);
                  reschedule();
                }
              }

              private void reschedule() {
                proxyServer
                    .getScheduler()
                    .buildTask(plugin, this)
                    .delay(recheckInterval, TimeUnit.SECONDS)
                    .schedule();
              }
            })
        .delay(idleCheckDelay, TimeUnit.SECONDS)
        .schedule();
  }
}