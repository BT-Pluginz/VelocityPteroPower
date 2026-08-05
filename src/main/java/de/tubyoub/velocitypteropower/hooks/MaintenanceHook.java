package de.tubyoub.velocitypteropower.hooks;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import eu.kennytv.maintenance.api.Maintenance;
import eu.kennytv.maintenance.api.MaintenanceProvider;
import eu.kennytv.maintenance.api.proxy.MaintenanceProxy;
import eu.kennytv.maintenance.api.proxy.Server;
import java.util.Locale;

public final class MaintenanceHook {
    private static volatile MaintenanceProxy api;

    private MaintenanceHook() {}

    public static void init(ProxyServer proxy, FilteredComponentLogger logger) {
        api = null;
        if (!proxy.getPluginManager().isLoaded("maintenance")) return;

        Maintenance m = MaintenanceProvider.get();
        if (m instanceof MaintenanceProxy p) {
            api = p;
            logger.info("Maintenance plugin detected, hook enabled.");
        } else {
            logger.warn("Maintenance is loaded but its proxy API isn't available; hook disabled.");
        }
    }

    public static boolean isBlocked(Player player, String serverName) {
        MaintenanceProxy m = api;
        if (m == null) return false;

        boolean bypass = player.hasPermission("maintenance.admin") || player.hasPermission("maintenance.bypass") || m.getSettings().isWhitelisted(player.getUniqueId());

        if (m.isMaintenance()) return !bypass;

        Server server = m.getServer(serverName);
        if (server == null || !m.isMaintenance(server)) return false;

        return !bypass && !player.hasPermission("maintenance.singleserver.bypass." + server.getName().toLowerCase(Locale.ROOT));

    }
}