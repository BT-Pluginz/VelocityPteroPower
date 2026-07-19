package de.tubyoub.velocitypteropower.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.lifecycle.ServerLifecycleManager;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import de.tubyoub.vpp.api.VPPApiProvider;
import de.tubyoub.vpp.api.event.PlayerPostConnectEvent;
import de.tubyoub.vpp.api.event.PlayerDisconnectEvent;

import java.util.concurrent.TimeUnit;

public class ServerSwitchListener {

  private final VelocityPteroPower plugin;
  private final ComponentLogger logger;
  private final MessagesManager messages;
  private final ServerLifecycleManager serverLifecycleManager;

  public ServerSwitchListener(VelocityPteroPower plugin, ServerLifecycleManager serverLifecycleManager) {
    this.plugin = plugin;
    this.logger = plugin.getFilteredLogger();
    this.messages = plugin.getMessagesManager();
    this.serverLifecycleManager = serverLifecycleManager;
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    Player player = event.getPlayer();
    // Cancel any pending auto-connect retries for this player
    try {
      if (plugin.getPlayerConnectionHandler() != null) {
        plugin.getPlayerConnectionHandler().cancelPendingConnect(player.getUniqueId());
      }
    } catch (Exception ignored) {}

    // Cleanup limbo record on disconnect
    try { var lts = plugin.getLimboTrackerService(); if (lts != null) lts.clearForPlayer(player.getUniqueId(), "disconnect"); } catch (Exception ignored) {}

    // Record move history: current -> DISCONNECT
    try {
      var mhs = plugin.getMoveHistoryService();
      if (mhs != null) {
        String from = player.getCurrentServer().map(sc -> sc.getServer().getServerInfo().getName()).orElse("-");
        mhs.record(player.getUniqueId(), player.getUsername(), from, "DISCONNECT");
      }
    } catch (Exception ignored) {}

    // Fire public API PlayerDisconnectEvent
    try {
      var api = VPPApiProvider.get();
      if (api != null) {
        String last = player.getCurrentServer().map(sc -> sc.getServer().getServerInfo().getName()).orElse(null);
        api.getEventBus().post(new PlayerDisconnectEvent(player.getUniqueId(), player.getUsername(), last));
      }
    } catch (Throwable ignored) {}

    player.getCurrentServer()
        .ifPresent(
            serverConnection -> {
              String serverName = serverConnection.getServer().getServerInfo().getName();
              PteroServerInfo serverInfo = plugin.getServerInfoMap().get(serverName);

              if (serverInfo != null) {
                plugin.getProxyServer()
                    .getScheduler()
                    .buildTask(plugin, () -> serverLifecycleManager.checkAndScheduleShutdownIfNeeded(serverName, serverInfo))
                    .delay(500, TimeUnit.MILLISECONDS)
                    .schedule();
              }
            });
  }

  @Subscribe
  public void onServerSwitch(ServerConnectedEvent event) {
    RegisteredServer newServer = event.getServer();
    String newServerName = newServer.getServerInfo().getName();

    // Fire public API PlayerPostConnectEvent
    try {
      var api = VPPApiProvider.get();
      if (api != null) {
        api.getEventBus().post(new PlayerPostConnectEvent(event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), newServerName));
      }
    } catch (Throwable ignored) {}

    // Record move history: previous -> new
    try {
      var mhs = plugin.getMoveHistoryService();
      if (mhs != null) {
        String from = event.getPreviousServer().map(ps -> ps.getServerInfo().getName()).orElse("-");
        mhs.record(event.getPlayer(), from, newServerName);
      }
    } catch (Exception ignored) {}

    // Record if player joined a limbo by themselves (no prior record)
    try {
      var cfg = plugin.getConfigurationManager();
      var lts = plugin.getLimboTrackerService();
      if (cfg != null && lts != null) {
        boolean joinedLimbo = cfg.getBalancerLimbos() != null && cfg.getBalancerLimbos().contains(newServerName);
        if (joinedLimbo) {
          if (lts.get(event.getPlayer().getUniqueId()).isEmpty()) {
            lts.recordSelfMove(event.getPlayer(), newServer);
          }
        }
      }
    } catch (Exception ignored) {}

    serverLifecycleManager.cancelScheduledShutdown(newServerName, "player " + event.getPlayer().getUsername() + " joined");

    // Resume persistent start-wait when landing on lobby/limbo after reconnect
    try {
      var cfg = plugin.getConfigurationManager();
      var pch = plugin.getPlayerConnectionHandler();
      if (cfg != null && pch != null) {
        boolean onHolding =
            (cfg.getBalancerLimbos() != null && cfg.getBalancerLimbos().contains(newServerName))
                || (cfg.getBalancerLobbies() != null && cfg.getBalancerLobbies().contains(newServerName));
        if (onHolding) {
          pch.tryResumePersistentQueue(event.getPlayer());
        }
      }
    } catch (Exception ignored) {}

    event
        .getPreviousServer()
        .ifPresent(
            previousServer -> {
              String prevName = previousServer.getServerInfo().getName();
              PteroServerInfo prevInfo = plugin.getServerInfoMap().get(prevName);

              // If leaving a limbo, clear record
              try {
                var cfg = plugin.getConfigurationManager();
                var lts = plugin.getLimboTrackerService();
                if (cfg != null && lts != null) {
                  boolean wasLimbo = cfg.getBalancerLimbos() != null && cfg.getBalancerLimbos().contains(prevName);
                  if (wasLimbo && !prevName.equalsIgnoreCase(newServerName)) {
                    lts.clearForPlayer(event.getPlayer().getUniqueId(), "left limbo -> " + newServerName);
                  }
                }
              } catch (Exception ignored) {}

              if (prevInfo != null) {
                plugin.getProxyServer()
                    .getScheduler()
                    .buildTask(plugin, () -> serverLifecycleManager.checkAndScheduleShutdownIfNeeded(prevName, prevInfo))
                    .delay(500, TimeUnit.MILLISECONDS)
                    .schedule();
              }
            });
  }
}