package de.tubyoub.velocitypteropower.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.hooks.MaintenanceHook;
import de.tubyoub.velocitypteropower.lifecycle.ServerLifecycleManager;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.service.LimboReason;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

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
    // Cleanup limbo record on disconnect
    try { var lts = plugin.getLimboTrackerService(); if (lts != null) lts.clearForPlayer(player.getUniqueId(), "disconnect"); MaintenanceHook.consumePending(player.getUniqueId()); } catch (Exception ignored) {}

    // Record move history: current -> DISCONNECT
    try {
      var mhs = plugin.getMoveHistoryService();
      if (mhs != null) {
        String from = player.getCurrentServer().map(sc -> sc.getServer().getServerInfo().getName()).orElse("-");
        mhs.record(player.getUniqueId(), player.getUsername(), from, "DISCONNECT");
      }
    } catch (Exception ignored) {}

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
            String pendingTarget = MaintenanceHook.consumePending(event.getPlayer().getUniqueId());
            String previousName = event.getPreviousServer()
                    .map(ps -> ps.getServerInfo().getName())
                    .orElse(null);

            if (pendingTarget != null) {
              lts.record(event.getPlayer(),newServer, LimboReason.MAINTENANCE_WAIT, pendingTarget);
            } else if (previousName != null && MaintenanceHook.isServerUnderMaintenance(previousName)) {
              lts.record(event.getPlayer(), newServer, LimboReason.MAINTENANCE_WAIT, previousName);
            } else {
              lts.recordSelfMove(event.getPlayer(), newServer);
            }
          }
        }
      }
    } catch (Exception ignored) {}

    serverLifecycleManager.cancelScheduledShutdown(newServerName, "player " + event.getPlayer().getUsername() + " joined");

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

  @Subscribe
  public void onServerPostConnect(ServerPostConnectEvent event) {
    if (event.getPreviousServer() != null) return;

    try {
      var lts = plugin.getLimboTrackerService();
      if (lts == null) return;

      lts.get(event.getPlayer().getUniqueId()).ifPresent(record -> {
        if (record.getReason() != LimboReason.MAINTENANCE_WAIT) return;

        String target = record.getContext();
        if (target == null || target.isBlank()) return;

        event.getPlayer().sendMessage(messages.prefixed(MessageKey.CONNECT_MAINTENANCE_REDIRECT, "server", target, "limbo", record.getLimboServer()));
      });
    } catch (Exception ignored) {}
  }

}