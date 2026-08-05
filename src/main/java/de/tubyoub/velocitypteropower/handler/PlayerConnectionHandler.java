package de.tubyoub.velocitypteropower.handler;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.api.PowerSignal;
import de.tubyoub.velocitypteropower.hooks.MaintenanceHook;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.service.LimboReason;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PlayerConnectionHandler {

    private static final Logger log = LoggerFactory.getLogger(PlayerConnectionHandler.class);
    private final ProxyServer proxyServer;
  private final VelocityPteroPower plugin;
  private final ComponentLogger logger;
  private final ConfigurationManager configurationManager;
  private final MessagesManager messagesManager;
  private final PanelAPIClient apiClient;
  private final RateLimitTracker rateLimitTracker;

  private final Map<String, PteroServerInfo> serverInfoMap;
  private final Set<String> startingServers;
  private final Map<UUID, Long> playerCooldowns;
  private final Map<String, UUID> startInitiators;

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
    this.playerCooldowns = plugin.getPlayerCooldowns();
    this.startInitiators = plugin.getStartInitiators();
  }

  @Subscribe(priority = 10)
  public void onServerPreConnect(ServerPreConnectEvent event) {
    Player player = event.getPlayer();
    RegisteredServer targetServer = event.getOriginalServer();
    String serverName = targetServer.getServerInfo().getName();

    PteroServerInfo serverInfo = serverInfoMap.get(serverName);
    if (serverInfo == null) {
      handleUnmanagedServer(player, serverName);
      return;
    }

    if (MaintenanceHook.isBlocked(player, serverName)) {
      logger.debug("Not starting '{}' for {} — server is under maintenance.",
              serverName, player.getUsername());
      handleMaintenanceBlocked(event, player, serverName);
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
        if (onlineCount >= maxOnline && onlineCount != -1) {
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

  private void handleMaintenanceBlocked(ServerPreConnectEvent event, Player player, String serverName) {
    MaintenanceHook.markPending(player.getUniqueId(), serverName);

    Optional<RegisteredServer> limboOpt = findValidLimboServer();
    if (limboOpt.isEmpty()) {
      return;
    }
    MaintenanceHook.consumePending(player.getUniqueId());

    RegisteredServer limbo = limboOpt.get();
    boolean alreadyOnLimbo = event.getPreviousServer() != null && event.getPreviousServer().equals(limbo);

    if (alreadyOnLimbo) {
      player.sendMessage(messagesManager.prefixed(MessageKey.CONNECT_SERVER_MAINTENANCE, "server", serverName));
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
    } else {
      if (event.getPreviousServer() != null) {
        player.sendMessage(messagesManager.prefixed(MessageKey.CONNECT_MAINTENANCE_REDIRECT, "server", serverName, "limbo", limbo.getServerInfo().getName()));
      }
      event.setResult(ServerPreConnectEvent.ServerResult.allowed(limbo));
    }

    try {
      var lts = plugin.getLimboTrackerService();
      if (lts != null) {
        lts.record(player, limbo, LimboReason.MAINTENANCE_WAIT, serverName);
      }
    } catch (Exception ignored) {}
  }

  private void scheduleDelayedConnect(
    Player player, String targetServerName, PteroServerInfo targetServerInfo) {
    Optional<ServerConnection> initialConnection = player.getCurrentServer();
    long initialDelay = configurationManager.getStartupInitialCheckDelay();
    long checkInterval = Math.max(5, targetServerInfo.getJoinDelay());

    proxyServer
        .getScheduler()
        .buildTask(
            plugin,
            new Runnable() {
              private int attempts = 0;
              private final int maxAttempts = 12;

              @Override
              public void run() {
                // Abort only if the player moved to a non-holding, non-target server while waiting
                if (initialConnection.isPresent()) {
                  String currentName = player.getCurrentServer()
                      .map(cs -> cs.getServer().getServerInfo().getName())
                      .orElse("");

                  java.util.Set<String> limbos = new java.util.HashSet<>(configurationManager.getBalancerLimbos());
                  java.util.Set<String> lobbies = new java.util.HashSet<>(configurationManager.getBalancerLobbies());

                  boolean onHolding = limbos.contains(currentName) || lobbies.contains(currentName);
                  boolean onTarget = currentName.equalsIgnoreCase(targetServerName);
                  boolean sameAsInitial = initialConnection.equals(player.getCurrentServer());

                  if (!onHolding && !onTarget && !sameAsInitial) {
                    logger.info("Player {} moved to '{}' while waiting for '{}'. Not a lobby/limbo/target — cancelling their auto-connect.",
                        player.getUsername(), currentName, targetServerName);
                    return; // Do NOT clear global starting markers here — this is a per-player cancellation only
                  }
                }
                if (!player.isActive() || player.getCurrentServer().isEmpty()) {
                  logger.info(
                      "Player {} disconnected or left limbo while waiting for {}. Cancelling connect task.",
                      player.getUsername(),
                      targetServerName);
                  startingServers.remove(targetServerName);
                  plugin.getStartingServersSince().remove(targetServerName);
                  startInitiators.remove(targetServerName);
                  return;
                }

                if (apiClient.isServerOnline(targetServerName, targetServerInfo.getServerId())) {
                  logger.info(
                      "Server {} is now online. Attempting to connect player {}.",
                      targetServerName,
                      player.getUsername());
                  connectPlayerToServer(player, targetServerName);
                } else {
                  attempts++;
                  if (attempts >= maxAttempts) {
                    logger.error(
                        "Server {} did not come online within the expected time for player {}. Cancelling connect task.",
                        targetServerName,
                        player.getUsername());
                    player.sendMessage(
                        messagesManager.prefixed(
                            MessageKey.CONNECT_START_TIMEOUT, "server", targetServerName));
                    startingServers.remove(targetServerName);
                    plugin.getStartingServersSince().remove(targetServerName);
                    startInitiators.remove(targetServerName);
                  } else {
                    logger.debug(
                        "Server {} not online yet for player {}. Rescheduling check (Attempt {}/{}).",
                        targetServerName,
                        player.getUsername(),
                        attempts,
                        maxAttempts);
                    proxyServer
                        .getScheduler()
                        .buildTask(plugin, this)
                        .delay(checkInterval, java.util.concurrent.TimeUnit.SECONDS)
                        .schedule();
                  }
                }
              }
            })
        .delay(initialDelay, TimeUnit.SECONDS)
        .schedule();
  }

  private void connectPlayerToServer(Player player, String serverName) {
    Optional<RegisteredServer> serverOpt = proxyServer.getServer(serverName);

    if (serverOpt.isEmpty()) {
      logger.error(
          "Cannot connect player {}: Server '{}' not found/registered in Velocity.",
          player.getUsername(),
          serverName);
      player.sendMessage(
          messagesManager.prefixed(
              MessageKey.CONNECT_TARGET_SERVER_NOT_FOUND, "server", serverName));
      startingServers.remove(serverName);
      plugin.getStartingServersSince().remove(serverName);
      startInitiators.remove(serverName);
      return;
    }

    RegisteredServer targetServer = serverOpt.get();

    if (player
        .getCurrentServer()
        .map(cs -> cs.getServer().equals(targetServer))
        .orElse(false)) {
      logger.debug("Player {} is already connected to {}. No action needed.", player.getUsername(), serverName);
      startingServers.remove(serverName);
      plugin.getStartingServersSince().remove(serverName);
      startInitiators.remove(serverName);
      return;
    }

    // Clear starting markers BEFORE attempting connection to avoid being re-routed to limbo by pre-connect
    startingServers.remove(serverName);
    plugin.getStartingServersSince().remove(serverName);
    startInitiators.remove(serverName);

    logger.info("Connecting player {} to server {}.", player.getUsername(), serverName);
    player.createConnectionRequest(targetServer).connect().whenComplete((result, throwable) -> {
      if (throwable != null) {
        logger.debug("Connect failed for {} to {}: {}", player.getUsername(), serverName, throwable.toString());
        // Retry once after a short delay
        proxyServer.getScheduler().buildTask(plugin, () -> connectPlayerToServer(player, serverName))
            .delay(2, java.util.concurrent.TimeUnit.SECONDS)
            .schedule();
        return;
      }
      if (result == null) {
        logger.debug("Connect returned null result for {} to {} — retrying shortly.", player.getUsername(), serverName);
        proxyServer.getScheduler().buildTask(plugin, () -> connectPlayerToServer(player, serverName))
            .delay(2, java.util.concurrent.TimeUnit.SECONDS)
            .schedule();
        return;
      }
      ConnectionRequestBuilder.Status status = result.getStatus();
      switch (status) {
        case SUCCESS -> logger.info("{} moved to {}", player.getUsername(), serverName);
        case ALREADY_CONNECTED -> logger.debug("{} was already on {}", player.getUsername(), serverName);
        default -> {
          // If denied or cancelled by event logic, retry once shortly
          logger.debug("Connect status for {} to {} was {} — retrying shortly.", player.getUsername(), serverName, status);
          proxyServer.getScheduler().buildTask(plugin, () -> connectPlayerToServer(player, serverName))
              .delay(2, java.util.concurrent.TimeUnit.SECONDS)
              .schedule();
        }
      }
    });
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
                if (!startingServers.contains(serverName)) {
                  logger.debug("Initial idle check for {}: Cancelled (server no longer starting).", serverName);
                  return;
                }

                if (!rateLimitTracker.canMakeRequest()) {
                  logger.debug("Initial idle check for {}: Rate limited. Rescheduling.", serverName);
                  reschedule();
                  return;
                }

                boolean online = apiClient.isServerOnline(serverName, serverId);
                if (online) {
                  if (apiClient.isServerEmpty(serverName)) {
                    logger.info(
                        messagesManager.raw(MessageKey.SERVER_IDLE_SHUTDOWN)
                            .replace("<server>", serverName));
                    apiClient.powerServer(serverId, PowerSignal.STOP);
                    startingServers.remove(serverName);
                  } else {
                    logger.debug("Initial idle check for {}: Players present. Cancelling idle shutdown task.", serverName);
                    startingServers.remove(serverName);
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