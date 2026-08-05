package de.tubyoub.velocitypteropower.hooks;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.service.LimboReason;
import de.tubyoub.velocitypteropower.service.LimboTrackerService;
import de.tubyoub.velocitypteropower.service.PlayerLimboRecord;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;

import eu.kennytv.maintenance.api.Maintenance;
import eu.kennytv.maintenance.api.MaintenanceProvider;
import eu.kennytv.maintenance.api.event.MaintenanceChangedEvent;
import eu.kennytv.maintenance.api.event.proxy.ServerMaintenanceChangedEvent;
import eu.kennytv.maintenance.api.proxy.MaintenanceProxy;
import eu.kennytv.maintenance.api.proxy.Server;
import eu.kennytv.maintenance.api.event.manager.EventListener;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MaintenanceHook {
    private static volatile MaintenanceProxy api;
    private static volatile VelocityPteroPower plugin;
    private static volatile ProxyServer proxy;
    private static volatile FilteredComponentLogger logger;
    private static volatile boolean listenersRegistered;
    private static final Map<UUID, String> PENDING_TARGETS = new ConcurrentHashMap<>();


    private MaintenanceHook() {}

    public static void init(VelocityPteroPower pluginInstance, ProxyServer proxyServer, FilteredComponentLogger log) {
        api = null;
        plugin = pluginInstance;
        proxy = proxyServer;
        logger = log;

        if (!proxy.getPluginManager().isLoaded("maintenance")) return;

        Maintenance m = MaintenanceProvider.get();
        if (!(m instanceof MaintenanceProxy p)) {
            logger.warn("Maintenance is loaded but its proxy API isn't available; hook disabled.");
            return;
        }
        api = p;

        if (!listenersRegistered) {
            p.getEventManager().registerListener(
                    new EventListener<MaintenanceChangedEvent>() {
                        @Override
                        public void onEvent(MaintenanceChangedEvent event) {
                            if (!event.isMaintenance()) retryWaiting(null);
                        }
                    },
                    MaintenanceChangedEvent.class);

            p.getEventManager().registerListener(
                    new EventListener<ServerMaintenanceChangedEvent>() {
                        @Override
                        public void onEvent(ServerMaintenanceChangedEvent event) {
                            if (!event.isMaintenance()) retryWaiting(event.getServer().getName());
                        }
                    },
                    ServerMaintenanceChangedEvent.class);
            listenersRegistered = true;
        }

        log.info("Maintenance plugin detected, hook enabled.");
    }

    public static boolean isBlocked(Player player, String serverName) {
        MaintenanceProxy m = api;
        if (m == null) return false;

        boolean bypass = player.hasPermission("maintenance.admin") || player.hasPermission("maintenance.bypass") || m.getSettings().isWhitelisted(player.getUniqueId());

        if (m.isMaintenance() && !bypass) return true;

        Server server = m.getServer(serverName);
        if (server == null || !m.isMaintenance(server)) return false;

        return !bypass && !player.hasPermission("maintenance.singleserver.bypass." + server.getName().toLowerCase(Locale.ROOT));

    }

    private static void retryWaiting(String changedServer) {
        VelocityPteroPower pl = plugin;
        ProxyServer p = proxy;
        if (pl == null || p == null) return;

        LimboTrackerService lts = pl.getLimboTrackerService();
        if (lts == null) return;

        p.getScheduler().buildTask(pl, () -> {
            List<PlayerLimboRecord> records = lts.all();
            for (PlayerLimboRecord record : records) {
                if (record.getReason() != LimboReason.MAINTENANCE_WAIT) continue;

                String target = record.getContext();
                if (target == null || target.isBlank()) continue;
                if (changedServer != null && !changedServer.equalsIgnoreCase(target)) continue;

                Optional<Player> maybePlayer = p.getPlayer(record.getPlayerId());
                if (maybePlayer.isEmpty()) {
                    lts.clearForPlayer(record.getPlayerId(), "offline during maintenance retry");
                    continue;
                }
                Player player = maybePlayer.get();

                if (isBlocked(player, target)) continue;

                Optional<RegisteredServer> maybeServer = p.getServer(target);
                if (maybeServer.isEmpty()) {
                    lts.clearForPlayer(record.getPlayerId(), "target '" + target + "' not registered");
                    continue;
                }

                if (logger != null) {
                    logger.info("Maintenance lifted for '{}', re-requesting connection for {}.", target, player.getUsername());
                }

                player.createConnectionRequest(maybeServer.get()).connect().whenComplete((result, throwable) -> {
                    if (throwable != null && logger != null) {
                        logger.debug("Maintenance retry for {} -> {} failed: {}", player.getUsername(), target, throwable.toString());
                    }
                });
            }


        }).delay(Duration.ofMillis(500)).schedule();
    }

    public static boolean isServerUnderMaintenance(String serverName) {
        MaintenanceProxy m = api;
        if (m == null) return false;
        Server server = m.getServer(serverName);
        return server != null && m.isMaintenance(server);
    }

    public static void markPending(UUID uuid, String target) {
        PENDING_TARGETS.put(uuid, target);
    }

    public static String consumePending(UUID uuid) {
        return PENDING_TARGETS.remove(uuid);
    }
}