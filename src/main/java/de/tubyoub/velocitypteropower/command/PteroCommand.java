package de.tubyoub.velocitypteropower.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.http.PanelType;
import de.tubyoub.velocitypteropower.http.PowerSignal;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class PteroCommand implements SimpleCommand {
    private final ProxyServer proxyServer;
    private final VelocityPteroPower plugin;
    private final Logger logger;
    private final PanelAPIClient apiClient;
    public final RateLimitTracker rateLimitTracker;
    private final ConfigurationManager configurationManager;
    private final MessagesManager messages;

    private final Map<UUID, Long> pendingForceStopConfirmations = new HashMap<>();
    private static final long CONFIRMATION_TIMEOUT_MS = 30000;

    public PteroCommand(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.proxyServer = plugin.getProxyServer();
        this.logger = plugin.getFilteredLogger();
        this.apiClient = plugin.getApiClient();
        this.rateLimitTracker = plugin.getRateLimitTracker();
        this.configurationManager = plugin.getConfigurationManager();
        this.messages = plugin.getMessagesManager();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            displayHelp(sender);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start":
                if (sender.hasPermission("ptero.start")) {
                    startServer(sender, args);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "stop":
                if (sender.hasPermission("ptero.stop")) {
                    stopServer(sender, args);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "reload":
                if (sender.hasPermission("ptero.reload")) {
                    reloadConfig(sender);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "restart":
                if (sender.hasPermission("ptero.restart")) {
                    restartServer(sender, args);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "stopidle":
                if (sender.hasPermission("ptero.stopIdle")) {
                    stopIdleServers(sender);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "reloadwhitelist","whitelistreload":
                if (configurationManager.getPanelType() == PanelType.mcServerSoft) {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_MCSS_NOT_SUPPORTED));
                    break;
                }
                if (sender.hasPermission("ptero.whitelistReload")) {
                    plugin.getWhitelistManager().updateAllWhitelists();
                    sender.sendMessage(
                            messages.prefixed(MessageKey.GENERIC_SUCCESS, "message", "Whitelist reloaded"));
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "forcestopall":
                if (sender.hasPermission("ptero.forcestopall")) {
                    forceStopAll(sender, args);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "forcestartall":
                if (sender.hasPermission("ptero.forcestartall")) {
                    forceStartAll(sender, args);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "list":
                if (sender.hasPermission("ptero.list")) {
                    listServers(sender);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "info":
                if (sender.hasPermission("ptero.info")) {
                    infoServer(sender, args);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "history":
                if (sender.hasPermission("ptero.history")) {
                    showHistory(sender, args);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "queue":
                showQueue(sender);
                break;

            case "maintenance":
                if (sender.hasPermission("ptero.maintenance") || sender.hasPermission("ptero.reload")) {
                    handleMaintenance(sender, args);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "apithreads":
                if (sender.hasPermission("ptero.info")) {
                    showApiThreadsInfo(sender);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "api":
                if (sender.hasPermission("ptero.info")) {
                    showApiInfo(sender);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            default:
                // Delegate to registered addon subcommands via public API
                de.tubyoub.vpp.api.VPPApi api = de.tubyoub.vpp.api.VPPApiProvider.get();
                if (api != null) {
                    boolean handled = false;
                    try {
                        handled = api.dispatchSubcommand(sub, new de.tubyoub.velocitypteropower.api.VelocityCommandActor(sender),
                                java.util.Arrays.copyOfRange(args, 1, args.length));
                    } catch (Throwable ignored) {}
                    if (handled) return;
                }
                sender.sendMessage(
                        messages.prefixed(
                                MessageKey.COMMAND_UNKNOWN_SUBCOMMAND, "sub", sub));
                displayHelp(sender);
        }
    }

    private void stopIdleServers(CommandSource sender) {
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.isEmpty()) {
            sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_SERVERS_FOUND));
            return;
        }

        List<String> ignoreList = configurationManager.getStopAllIgnoreList();
        int stoppedCount = 0;

        for (Map.Entry<String, PteroServerInfo> entry : serverInfoMap.entrySet()) {
            String serverName = entry.getKey();
            PteroServerInfo serverInfo = entry.getValue();

            if (ignoreList.contains(serverName)) continue;

            if (proxyServer.getServer(serverName).isPresent()
                    && !proxyServer.getServer(serverName).get().getPlayersConnected().isEmpty()) {
                continue;
            }

            if (rateLimitTracker.canMakeRequest()) {
                apiClient.powerServer(serverInfo.getServerId(), PowerSignal.STOP);
                stoppedCount++;
            } else {
                sender.sendMessage(messages.prefixed(MessageKey.COMMAND_RATE_LIMIT_EXCEEDED));
                break;
            }
        }

        if (stoppedCount > 0) {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.COMMAND_STOPPING_ALL_SERVERS, "count", String.valueOf(stoppedCount)));
        }
    }

    private void forceStopAll(CommandSource sender, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) {
            // Only meaningful for players; console bypasses anyway
            if (sender instanceof com.velocitypowered.api.proxy.Player p) {
                UUID id = p.getUniqueId();
                Long at = pendingForceStopConfirmations.get(id);
                if (at != null && System.currentTimeMillis() - at < CONFIRMATION_TIMEOUT_MS) {
                    pendingForceStopConfirmations.remove(id);
                    forceStopAllServers(sender);
                } else {
                    sender.sendMessage(
                            messages.prefixed(MessageKey.GENERIC_ERROR, "message", "No pending confirmation."));
                }
            } else {
                forceStopAllServers(sender);
            }
            return;
        }

        if (sender instanceof com.velocitypowered.api.proxy.Player p) {
            pendingForceStopConfirmations.put(p.getUniqueId(), System.currentTimeMillis());
        }
        sender.sendMessage(
                messages.prefixed(
                        MessageKey.COMMAND_FORCE_STOPPING_ALL_SERVERS, "count", "ALL"));
        sender.sendMessage(
                Component.text("Type '/ptero forcestopall confirm' to confirm.")); // Plain helper
    }

    private void forceStopAllServers(CommandSource sender) {
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.isEmpty()) {
            sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_SERVERS_FOUND));
            return;
        }

        int stoppedCount = 0;
        for (Map.Entry<String, PteroServerInfo> entry : serverInfoMap.entrySet()) {
            PteroServerInfo serverInfo = entry.getValue();

            if (rateLimitTracker.canMakeRequest()) {
                apiClient.powerServer(serverInfo.getServerId(), PowerSignal.STOP);
                stoppedCount++;
            } else {
                sender.sendMessage(messages.prefixed(MessageKey.COMMAND_RATE_LIMIT_EXCEEDED));
                break;
            }
        }

        if (stoppedCount > 0) {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.COMMAND_FORCE_STOPPING_ALL_SERVERS, "count", String.valueOf(stoppedCount)));
        }
    }

    private void forceStartAll(CommandSource sender, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) {
            if (sender instanceof com.velocitypowered.api.proxy.Player p) {
                UUID id = p.getUniqueId();
                Long at = pendingForceStopConfirmations.get(id);
                if (at != null && System.currentTimeMillis() - at < CONFIRMATION_TIMEOUT_MS) {
                    pendingForceStopConfirmations.remove(id);
                    forceStartAllServers(sender);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.GENERIC_ERROR, "message", "No pending confirmation."));
                }
            } else {
                forceStartAllServers(sender);
            }
            return;
        }
        if (sender instanceof com.velocitypowered.api.proxy.Player p) {
            pendingForceStopConfirmations.put(p.getUniqueId(), System.currentTimeMillis());
        }
        // Strong warning about resource usage
        sender.sendMessage(messages.prefixed(MessageKey.COMMAND_FORCE_START_WARNING));
        sender.sendMessage(messages.prefixed(MessageKey.COMMAND_FORCE_STARTING_ALL_SERVERS, "count", "ALL"));
        sender.sendMessage(Component.text("Type '/ptero forcestartall confirm' to confirm."));
    }

    private void forceStartAllServers(CommandSource sender) {
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.isEmpty()) {
            sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_SERVERS_FOUND));
            return;
        }
        int started = 0;
        for (Map.Entry<String, PteroServerInfo> entry : serverInfoMap.entrySet()) {
            PteroServerInfo serverInfo = entry.getValue();
            if (rateLimitTracker.canMakeRequest()) {
                apiClient.powerServer(serverInfo.getServerId(), PowerSignal.START);
                plugin.recordServerStartSignalSent();
                started++;
            } else {
                sender.sendMessage(messages.prefixed(MessageKey.COMMAND_RATE_LIMIT_EXCEEDED));
                break;
            }
        }
        if (started > 0) {
            sender.sendMessage(messages.prefixed(MessageKey.COMMAND_FORCE_STARTING_ALL_SERVERS, "count", String.valueOf(started)));
        }
    }

    private void startServer(CommandSource sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.COMMAND_USAGE, "usage", "ptero start <serverName>"));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.containsKey(serverName)) {
            PteroServerInfo info = serverInfoMap.get(serverName);
            if (plugin.getMaintenanceService() != null
                    && plugin.getMaintenanceService().isServerInMaintenance(serverName)) {
                sender.sendMessage(messages.prefixed(
                        MessageKey.CONNECT_MAINTENANCE_BLOCKED,
                        "detail", plugin.getMaintenanceService().detailFor(serverName)));
                return;
            }
            if (rateLimitTracker.canMakeRequest()) {
                int maxOnline = configurationManager.getMaxOnlineServers();
                boolean hasBypass = configurationManager.isMaxOnlineAllowBypass() && sender.hasPermission("ptero.maxcap.bypass");
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
                            sender.sendMessage(messages.prefixed(MessageKey.CONNECT_MAX_ONLINE_REACHED, "max", String.valueOf(maxOnline)));
                            return;
                        }
                    }
                }
                apiClient.powerServer(info.getServerId(), PowerSignal.START);
                plugin.recordServerStartSignalSent();
            }
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.POWER_ACTION_SENT, "action", "start", "server", serverName));
        } else {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.CONNECT_UNMANAGED_SERVER, "server", serverName));
        }
    }

    private void stopServer(CommandSource sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.COMMAND_USAGE, "usage", "ptero stop <serverName>"));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.containsKey(serverName)) {
            PteroServerInfo info = serverInfoMap.get(serverName);
            if (rateLimitTracker.canMakeRequest()) {
                apiClient.powerServer(info.getServerId(), PowerSignal.STOP);
            }
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.POWER_ACTION_SENT, "action", "stop", "server", serverName));
        } else {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.CONNECT_UNMANAGED_SERVER, "server", serverName));
        }
    }

    private void restartServer(CommandSource sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.COMMAND_USAGE, "usage", "ptero restart <serverName>"));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.containsKey(serverName)) {
            PteroServerInfo info = serverInfoMap.get(serverName);
            if (rateLimitTracker.canMakeRequest()) {
                apiClient.powerServer(info.getServerId(), PowerSignal.RESTART);
            }
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.POWER_ACTION_SENT, "action", "restart", "server", serverName));
        } else {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.CONNECT_UNMANAGED_SERVER, "server", serverName));
        }
    }

    private void reloadConfig(CommandSource sender) {
        plugin.reload();
        sender.sendMessage(messages.prefixed(MessageKey.GENERIC_RELOAD_SUCCESS));
    }

    private void showHistory(CommandSource sender, String[] args) {
        var mhs = plugin.getMoveHistoryService();
        if (mhs == null || !plugin.getConfigurationManager().isMoveHistoryEnabled()) {
            sender.sendMessage(Component.text("Move history is disabled in config."));
            return;
        }
        UUID targetId = null;
        String displayName = null;
        if (args.length >= 2) {
            String nameOrUuid = args[1];
            // Try online player by name (case-insensitive)
            Optional<com.velocitypowered.api.proxy.Player> online = plugin.getProxyServer().getAllPlayers().stream()
                    .filter(p -> p.getUsername().equalsIgnoreCase(nameOrUuid)).findFirst();
            if (online.isPresent()) {
                targetId = online.get().getUniqueId();
                displayName = online.get().getUsername();
            } else {
                // Try parse UUID
                try {
                    targetId = UUID.fromString(nameOrUuid);
                    displayName = mhs.findLastKnownName(targetId).orElse(nameOrUuid);
                } catch (IllegalArgumentException ex) {
                    sender.sendMessage(Component.text("Player not found online and not a valid UUID: " + nameOrUuid));
                    return;
                }
            }
        } else if (sender instanceof com.velocitypowered.api.proxy.Player p) {
            targetId = p.getUniqueId();
            displayName = p.getUsername();
        } else {
            sender.sendMessage(Component.text("Usage: /ptero history <playerName|uuid>"));
            return;
        }

        List<de.tubyoub.velocitypteropower.service.MoveHistoryService.MoveRecord> recs = mhs.getHistory(targetId);
        Component out = de.tubyoub.velocitypteropower.service.MoveHistoryService.renderPretty(recs, Locale.ROOT);
        // Prepend small header mentioning the player
        sender.sendMessage(Component.text("History for "+displayName+":"));
        sender.sendMessage(out);
    }


    // Shows a pretty list of managed servers with status, player count, and time-to-shutdown
    private void listServers(CommandSource sender) {
        Map<String, PteroServerInfo> servers = plugin.getServerInfoMap();
        if (servers == null || servers.isEmpty()) {
            sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_SERVERS_FOUND));
            return;
        }

        // Header
        sender.sendMessage(messages.prefixed(MessageKey.COMMAND_LIST_HEADER));
        logger.debug("Executing /ptero list: {} managed server(s).", servers.size());

        long now = System.currentTimeMillis();
        Map<String, Long> deadlines = plugin.getShutdownDeadlines();

        List<String> names = new ArrayList<>(servers.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);

        boolean usePing = configurationManager.getServerCheckMethod() == de.tubyoub.velocitypteropower.manager.ConfigurationManager.ServerCheckMethod.VELOCITY_PING;

        for (String name : names) {
            PteroServerInfo info = servers.get(name);
            String id = info != null ? info.getServerId() : null;

            int players = proxyServer.getServer(name)
                    .map(s -> s.getPlayersConnected().size())
                    .orElse(0);

            // Compute TTL to display
            String ttl;
            Long endAt = deadlines.get(name);
            var alwaysOnline = configurationManager.getAlwaysOnlineList();
            boolean isAlwaysOnline = alwaysOnline != null && alwaysOnline.stream().anyMatch(s -> s.equalsIgnoreCase(name));
            if (isAlwaysOnline) {
                ttl = "—"; // always-online servers are not scheduled for shutdown
                logger.debug("TTL for {} -> always-online; showing em dash.", name);
            } else if (endAt != null && endAt > now) {
                ttl = formatDurationShort(endAt - now);
                logger.debug("TTL for {} -> active countdown: {} remaining.", name, ttl);
            } else {
                // No active countdown; if server is currently empty and has a configured timeout, show that timeout
                int cfgTimeout = info != null ? info.getTimeout() : -1;
                if (cfgTimeout >= 0 && apiClient.isServerEmpty(name)) {
                    ttl = formatDurationShort(cfgTimeout * 1000L);
                    logger.debug("TTL for {} -> empty without scheduled deadline; showing configured timeout {}.", name, ttl);
                } else {
                    ttl = "—";
                    logger.debug("TTL for {} -> no deadline and not empty (or timeout disabled); showing em dash.", name);
                }
            }

            if (usePing) {
                boolean online = false;
                try {
                    if (id != null) {
                        online = apiClient.isServerOnline(name, id);
                    }
                } catch (Exception ignored) {
                    online = false;
                }
                String status = online ? "online" : "offline";
                String statusColor = online ? "<green>" : "<red>";
                String statusFormatted = statusColor + status + statusColor.replace("<", "</");

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("server", name);
                placeholders.put("status", status);
                placeholders.put("status_color", statusColor);
                placeholders.put("status_formatted", statusFormatted);
                placeholders.put("players", String.valueOf(players));
                placeholders.put("ttl", ttl);

                logger.debug("List entry (PING) for {} -> status={}, players={}, ttl={}", name, status, players, ttl);
                sender.sendMessage(messages.prefixed(MessageKey.COMMAND_LIST_ENTRY, placeholders));
            } else {
                // Prefer cached resource info (PANEL_API). Will be instant on cache hit due to completedFuture.
                if (id == null) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("server", name);
                    placeholders.put("status", "unknown");
                    placeholders.put("status_color", "<gray>");
                    placeholders.put("status_formatted", "<gray>unknown</gray>");
                    placeholders.put("players", String.valueOf(players));
                    placeholders.put("ttl", ttl);
                    logger.debug("List entry (PANEL_API, no id) for {} -> status=unknown, players={}, ttl={}", name, players, ttl);
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_LIST_ENTRY, placeholders));
                } else {
                    apiClient.fetchServerResources(id).whenComplete((usage, throwable) -> {
                        boolean known = false;
                        boolean online = false;
                        String state = "unknown";
                        boolean suspended = false;
                        if (throwable == null && usage != null && usage.isAvailable()) {
                            state = usage.getCurrentState() == null ? "unknown" : usage.getCurrentState();
                            suspended = usage.isSuspended();
                            known = true;
                            online = "running".equalsIgnoreCase(state) && !suspended;
                        }
                        final String resolvedStatus;
                        final String statusColor;
                        if (known) {
                            resolvedStatus = online ? "online" : "offline";
                            statusColor = online ? "<green>" : "<red>";
                        } else {
                            resolvedStatus = "unknown";
                            statusColor = "<gray>";
                        }
                        final String statusFormatted = statusColor + resolvedStatus + statusColor.replace("<", "</");

                        Map<String, String> ph = new HashMap<>();
                        ph.put("server", name);
                        ph.put("status", resolvedStatus);
                        ph.put("status_color", statusColor);
                        ph.put("status_formatted", statusFormatted);
                        ph.put("players", String.valueOf(players));
                        ph.put("ttl", ttl);

                        logger.debug("List entry (PANEL_API) for {} -> state={}, suspended={}, resolvedStatus={}, players={}, ttl={}", name, state, suspended, resolvedStatus, players, ttl);
                        plugin.getProxyServer().getScheduler().buildTask(plugin, () -> sender.sendMessage(messages.prefixed(MessageKey.COMMAND_LIST_ENTRY, ph))).schedule();
                    });
                }
            }
        }
    }

    // Shows detailed resource info for a given server (Pterodactyl), or N/A if unsupported
    private void infoServer(CommandSource sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(messages.prefixed(MessageKey.COMMAND_USAGE, "usage", "ptero info <serverName>"));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> servers = plugin.getServerInfoMap();
        PteroServerInfo info = servers != null ? servers.get(serverName) : null;
        if (info == null) {
            sender.sendMessage(messages.prefixed(MessageKey.COMMAND_INFO_NOT_FOUND, "server", serverName));
            return;
        }

        logger.debug("Executing /ptero info for server '{}'", serverName);
        // Header
        sender.sendMessage(messages.prefixed(MessageKey.COMMAND_INFO_HEADER, "server", serverName));

        boolean usePing = configurationManager.getServerCheckMethod() == de.tubyoub.velocitypteropower.manager.ConfigurationManager.ServerCheckMethod.VELOCITY_PING;
        boolean canQuery = usePing || rateLimitTracker.canMakeRequest();
        boolean online = false;
        try {
            if (canQuery) online = apiClient.isServerOnline(serverName, info.getServerId());
        } catch (Exception ignored) {
            online = false;
        }
        String status = online ? "online" : (canQuery ? "offline" : "unknown");
        String statusColor = online ? "<green>" : (canQuery ? "<red>" : "<gray>");
        String statusFormatted = statusColor + status + statusColor.replace("<", "</");

        int players = proxyServer.getServer(serverName).map(s -> s.getPlayersConnected().size()).orElse(0);

        // Fetch resource usage async (only available for Pterodactyl client here)
        apiClient.fetchServerResources(info.getServerId()).whenComplete((usage, throwable) -> {
            if (throwable != null) {
                logger.debug("Failed to fetch resource usage for {}: {}", serverName, throwable.toString());
                // Fallback to N/A values
                plugin.getProxyServer().getScheduler().buildTask(plugin, () -> {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("status", status);
                    ph.put("status_color", statusColor);
                    ph.put("status_formatted", statusFormatted);
                    ph.put("players", String.valueOf(players));
                    ph.put("cpu", "<gray>n/a</gray>");
                    ph.put("memory", "<gray>n/a</gray>");
                    ph.put("disk", "<gray>n/a</gray>");
                    ph.put("rx", "n/a");
                    ph.put("tx", "n/a");
                    ph.put("uptime", "n/a");
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_INFO_BODY, ph));
                    // Append initiator info, if any
                    try {
                        java.util.UUID init = plugin.getStartInitiators().get(serverName);
                        if (init != null) {
                            String who = plugin.getProxyServer().getPlayer(init).map(p -> p.getUsername())
                                    .orElseGet(() -> {
                                        var mhs = plugin.getMoveHistoryService();
                                        if (mhs != null) return mhs.findLastKnownName(init).orElse(init.toString());
                                        return init.toString();
                                    });
                            net.kyori.adventure.text.minimessage.MiniMessage mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
                            sender.sendMessage(mm.deserialize("<gray>Started by:</gray> <yellow>" + who + "</yellow>"));
                        }
                    } catch (Exception ignored) {}
                }).schedule();
                return;
            }

            final de.tubyoub.velocitypteropower.model.ServerResourceUsage u = usage != null ? usage : de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();

            String cpuFormatted;
            String memFormatted;
            String diskFormatted;
            String rx = (u.isAvailable() && u.getNetworkRxBytes() >= 0) ? humanBytes(u.getNetworkRxBytes()) : "n/a";
            String tx = (u.isAvailable() && u.getNetworkTxBytes() >= 0) ? humanBytes(u.getNetworkTxBytes()) : "n/a";
            String uptime = (u.isAvailable() && u.getUptimeMillis() > 0) ? formatDurationShort(u.getUptimeMillis()) : "n/a";

            if (!u.isAvailable()) {
                cpuFormatted = "<gray>n/a</gray>";
                memFormatted = "<gray>n/a</gray>";
                diskFormatted = "<gray>n/a</gray>";
            } else {
                // CPU
                double usedCpu = Math.max(0.0, u.getCpuAbsolute());
                double cpuLimit = u.getCpuLimitPercent(); // 0 = unlimited, -1 unknown
                double cpuRatio = cpuLimit > 0 ? clamp01(usedCpu / cpuLimit) : clamp01(usedCpu / 100.0);
                String cpuColor = gradientColorTag(cpuRatio);
                String usedCpuStr = String.format(Locale.ROOT, "%.1f%%", usedCpu);
                String coloredCpuUsed = colorize(cpuColor, usedCpuStr);
                if (cpuLimit > 0) {
                    cpuFormatted = coloredCpuUsed + " <gray>/</gray> <yellow>" + String.format(Locale.ROOT, "%.0f%%", cpuLimit) + "</yellow>";
                } else {
                    cpuFormatted = coloredCpuUsed;
                }

                // Memory
                long memUsed = Math.max(0L, u.getMemoryBytes());
                long memLimit = u.getMemoryLimitBytes(); // 0 = unlimited, -1 unknown
                double memRatio = (memLimit > 0) ? clamp01((double) memUsed / (double) memLimit) : 0.0;
                String memColor = gradientColorTag(memRatio);
                String memUsedStr = humanBytes(memUsed);
                String coloredMemUsed = colorize(memColor, memUsedStr);
                if (memLimit > 0) {
                    memFormatted = coloredMemUsed + " <gray>/</gray> <yellow>" + humanBytes(memLimit) + "</yellow>";
                } else {
                    memFormatted = coloredMemUsed + " <gray>/</gray> <yellow>unlimited</yellow>";
                }

                // Disk
                if (u.getDiskBytes() >= 0) {
                    long diskUsed = u.getDiskBytes();
                    long diskLimit = u.getDiskLimitBytes(); // 0 = unlimited, -1 unknown
                    double diskRatio = (diskLimit > 0) ? clamp01((double) diskUsed / (double) diskLimit) : 0.0;
                    String diskColor = gradientColorTag(diskRatio);
                    String diskUsedStr = humanBytes(diskUsed);
                    String coloredDiskUsed = colorize(diskColor, diskUsedStr);
                    if (diskLimit > 0) {
                        diskFormatted = coloredDiskUsed + " <gray>/</gray> <yellow>" + humanBytes(diskLimit) + "</yellow>";
                    } else {
                        // unlimited: do not show limit
                        diskFormatted = coloredDiskUsed;
                    }
                } else {
                    diskFormatted = "<gray>n/a</gray>";
                }
            }

            Map<String, String> ph = new HashMap<>();
            ph.put("status", status);
            ph.put("status_color", statusColor);
            ph.put("status_formatted", statusFormatted);
            ph.put("players", String.valueOf(players));
            ph.put("cpu", cpuFormatted);
            ph.put("memory", memFormatted);
            ph.put("disk", diskFormatted);
            ph.put("rx", rx);
            ph.put("tx", tx);
            ph.put("uptime", uptime);

            logger.debug("Info usage for {} -> CPU={} MEM={} DISK={} RX={} TX={} UPTIME={}",
                    serverName, cpuFormatted, memFormatted, diskFormatted, rx, tx, uptime);

            plugin.getProxyServer().getScheduler().buildTask(plugin, () -> {
                sender.sendMessage(messages.prefixed(MessageKey.COMMAND_INFO_BODY, ph));
                // Append initiator info, if any
                try {
                    java.util.UUID init = plugin.getStartInitiators().get(serverName);
                    if (init != null) {
                        String who = plugin.getProxyServer().getPlayer(init).map(p -> p.getUsername())
                                .orElseGet(() -> {
                                    var mhs = plugin.getMoveHistoryService();
                                    if (mhs != null) return mhs.findLastKnownName(init).orElse(init.toString());
                                    return init.toString();
                                });
                        net.kyori.adventure.text.minimessage.MiniMessage mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
                        sender.sendMessage(mm.deserialize("<gray>Started by:</gray> <yellow>" + who + "</yellow>"));
                    }
                } catch (Exception ignored) {}
            }).schedule();
        });
    }

    private void showApiThreadsInfo(CommandSource sender) {
        int configured = configurationManager.getApiThreads();
        int servers = 0;
        try {
            Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
            if (map != null) servers = map.size();
        } catch (Exception ignored) {}
        int baseline = Math.max(4, Math.min(64, servers * 2));
        int finalThreads = Math.max(configured, baseline);
        boolean overridden = configured > baseline;

        String bodyMm =
            "<yellow>API Threads Info</yellow><newline>" +
            "  <gray>Configured (apiThreads):</gray> <green>" + configured + "</green><newline>" +
            "  <gray>Servers detected:</gray> <aqua>" + servers + "</aqua><newline>" +
            "  <gray>Auto baseline (2x servers, clamped 4..64):</gray> <gold>" + baseline + "</gold><newline>" +
            "  <gray>Using threads:</gray> <yellow>" + finalThreads + "</yellow>" +
            (overridden ? " <gray>(configured overrides baseline)</gray>" : "") + "<newline>" +
            "<gray>Tip:</gray> set <yellow>apiThreads</yellow> higher to override the auto baseline; reload/restart to apply.";

        sender.sendMessage(messages.prefixed(MessageKey.GENERIC_SUCCESS, "message", bodyMm));
    }


    private String formatDurationShort(long millis) {
        if (millis < 0) millis = 0;
        long seconds = millis / 1000;
        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long minutes = seconds / 60; seconds %= 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    private String humanBytes(long bytes) {
        final String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double b = bytes;
        int idx = 0;
        while (b >= 1024.0 && idx < units.length - 1) {
            b /= 1024.0;
            idx++;
        }
        if (idx == 0) return String.format(Locale.ROOT, "%d %s", (long)b, units[idx]);
        return String.format(Locale.ROOT, "%.2f %s", b, units[idx]);
    }

    private double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private String gradientColorTag(double ratio) {
        double r = clamp01(ratio);
        int red = (int) Math.round(255.0 * r);
        int green = (int) Math.round(255.0 * (1.0 - r));
        String hex = String.format("#%02X%02X%02X", red, green, 0);
        return "<" + hex + ">";
    }

    private String colorize(String colorTag, String text) {
        if (colorTag == null || colorTag.isEmpty()) return text;
        String close = colorTag.replace("<", "</");
        return colorTag + text + close;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();
        String current = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        // Root subcommand suggestions
        if (args.length <= 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("ptero.start")) subs.add("start");
            if (sender.hasPermission("ptero.stop")) subs.add("stop");
            if (sender.hasPermission("ptero.restart")) subs.add("restart");
            if (sender.hasPermission("ptero.list")) subs.add("list");
            if (sender.hasPermission("ptero.info")) subs.add("info");
            if (sender.hasPermission("ptero.info")) subs.add("apithreads");
            if (sender.hasPermission("ptero.info")) subs.add("api");
            if (sender.hasPermission("ptero.history")) subs.add("history");
            subs.add("queue");
            if (sender.hasPermission("ptero.maintenance") || sender.hasPermission("ptero.reload")) subs.add("maintenance");
            if (sender.hasPermission("ptero.stopIdle")) subs.add("stopidle");
            if (sender.hasPermission("ptero.whitelistReload")) subs.add("whitelistReload");
            if (sender.hasPermission("ptero.reload")) subs.add("reload");
            if (sender.hasPermission("ptero.forcestopall")) subs.add("forcestopall");
            if (sender.hasPermission("ptero.forcestartall")) subs.add("forcestartall");
            // Include addon commands
            try {
                de.tubyoub.vpp.api.VPPApi api = de.tubyoub.vpp.api.VPPApiProvider.get();
                if (api != null) {
                    for (de.tubyoub.vpp.api.AddonCommand c : api.getRegisteredCommands()) {
                        String perm = c.permission();
                        if (perm == null || perm.isBlank() || sender.hasPermission(perm)) {
                            if (c.name() != null && !c.name().isBlank()) subs.add(c.name());
                            java.util.List<String> al = c.aliases();
                            if (al != null) {
                                for (String a : al) if (a != null && !a.isBlank()) subs.add(a);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            if (current.isEmpty()) return subs;
            final String prefix = current;
            return subs.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
        }

        // Second argument suggestions (server names) or addon subcommands
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            boolean needsServer = sub.equals("start") || sub.equals("stop") || sub.equals("restart") || sub.equals("info") || sub.equals("maintenance");
            if (needsServer) {
                if ((sub.equals("start") && !sender.hasPermission("ptero.start"))
                        || (sub.equals("stop") && !sender.hasPermission("ptero.stop"))
                        || (sub.equals("restart") && !sender.hasPermission("ptero.restart"))
                        || (sub.equals("info") && !sender.hasPermission("ptero.info"))
                        || (sub.equals("maintenance") && !(sender.hasPermission("ptero.maintenance") || sender.hasPermission("ptero.reload")))) {
                    return java.util.Collections.emptyList();
                }
                String prefix = args[1];
                if (sub.equals("maintenance")) {
                    List<String> opts = new ArrayList<>();
                    opts.add("global");
                    opts.add("on");
                    opts.add("off");
                    Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
                    if (map != null) opts.addAll(map.keySet());
                    return opts.stream()
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)))
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .collect(Collectors.toList());
                }
                Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
                if (map != null && !map.isEmpty()) {
                    return map.keySet().stream()
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)))
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .collect(Collectors.toList());
                }
            } else if (sub.equals("history")) {
                if (!sender.hasPermission("ptero.history")) return java.util.Collections.emptyList();
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return plugin.getProxyServer().getAllPlayers().stream()
                        .map(p -> p.getUsername())
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .collect(Collectors.toList());
            } else if (sub.equals("forcestopall") || sub.equals("forcestartall")) {
                // Suggest the confirmation token
                if (!sender.hasPermission("ptero." + sub)) return java.util.Collections.emptyList();
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return java.util.List.of("confirm").stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
        }

        // Addon subcommand suggestions for any depth
        try {
            de.tubyoub.vpp.api.VPPApi api = de.tubyoub.vpp.api.VPPApiProvider.get();
            if (api != null && args.length >= 2) {
                String sub = args[0].toLowerCase(java.util.Locale.ROOT);
                for (de.tubyoub.vpp.api.AddonCommand c : api.getRegisteredCommands()) {
                    boolean match = false;
                    try {
                        if (c.name() != null && c.name().equalsIgnoreCase(sub)) match = true;
                        if (!match) {
                            java.util.List<String> al = c.aliases();
                            if (al != null) {
                                for (String a : al) { if (a != null && a.equalsIgnoreCase(sub)) { match = true; break; } }
                            }
                        }
                    } catch (Throwable ignored) {}
                    if (!match) continue;
                    String perm = c.permission();
                    if (perm != null && !perm.isBlank() && !sender.hasPermission(perm)) return java.util.Collections.emptyList();
                    de.tubyoub.velocitypteropower.api.VelocityCommandActor actor = new de.tubyoub.velocitypteropower.api.VelocityCommandActor(sender);
                    String[] subArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
                    java.util.List<String> sug = c.suggest(actor, subArgs);
                    if (sug != null) {
                        String prefix = args[args.length - 1];
                        return sug.stream().filter(s -> s != null && s.toLowerCase(java.util.Locale.ROOT).startsWith(prefix.toLowerCase(java.util.Locale.ROOT))).collect(java.util.stream.Collectors.toList());
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {}

        return java.util.Collections.emptyList();
    }

    private void displayHelp(CommandSource sender) {
        sender.sendMessage(messages.prefixed(MessageKey.COMMAND_HELP_HEADER));
        sender.sendMessage(Component.text("/ptero start <serverName>"));
        sender.sendMessage(Component.text("/ptero stop <serverName>"));
        sender.sendMessage(Component.text("/ptero restart <serverName>"));
        sender.sendMessage(Component.text("/ptero list"));
        sender.sendMessage(Component.text("/ptero info <serverName>"));
        sender.sendMessage(Component.text("/ptero queue"));
        sender.sendMessage(Component.text("/ptero maintenance [server|global] <on|off>"));
        sender.sendMessage(Component.text("/ptero apithreads"));
        sender.sendMessage(Component.text("/ptero api"));
        sender.sendMessage(Component.text("/ptero history [playerName|uuid]"));
        sender.sendMessage(Component.text("/ptero stopidle"));
        sender.sendMessage(Component.text("/ptero forcestopall"));
        sender.sendMessage(Component.text("/ptero forcestartall"));
        sender.sendMessage(Component.text("/ptero whitelistReload"));
        sender.sendMessage(Component.text("/ptero reload"));
        // List registered addon commands
        try {
            de.tubyoub.vpp.api.VPPApi api = de.tubyoub.vpp.api.VPPApiProvider.get();
            if (api != null) {
                java.util.Collection<de.tubyoub.vpp.api.AddonCommand> cmds = api.getRegisteredCommands();
                if (cmds != null && !cmds.isEmpty()) {
                    sender.sendMessage(Component.text("/ptero <addon> ... (registered addons):"));
                    for (de.tubyoub.vpp.api.AddonCommand c : cmds) {
                        String perm = c.permission();
                        if (perm == null || perm.isBlank() || sender.hasPermission(perm)) {
                            String name = c.name(); if (name == null) name = "unknown";
                            String desc = c.description(); if (desc == null) desc = "";
                            sender.sendMessage(Component.text(" - " + name + " - " + desc));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        sender.sendMessage(Component.text("/ptero help"));
    }

    private void showQueue(CommandSource sender) {
        var qps = plugin.getQueueProgressService();
        Map<String, Set<UUID>> waiting = plugin.getWaitingPlayers();
        boolean staff = sender.hasPermission("ptero.list") || sender.hasPermission("ptero.reload");

        if (sender instanceof com.velocitypowered.api.proxy.Player player && !staff) {
            if (qps == null) {
                sender.sendMessage(messages.prefixed(MessageKey.CONNECT_QUEUE_EMPTY));
                return;
            }
            var status = qps.findPlayerQueue(player.getUniqueId());
            if (!status.present()) {
                sender.sendMessage(messages.prefixed(MessageKey.CONNECT_QUEUE_EMPTY));
                return;
            }
            sender.sendMessage(messages.prefixed(
                    MessageKey.CONNECT_QUEUE_STATUS,
                    "server", status.server(),
                    "remaining", String.valueOf(status.remainingSeconds()),
                    "position", String.valueOf(status.position()),
                    "waiting", String.valueOf(status.waiting())));
            return;
        }

        if (waiting == null || waiting.isEmpty()) {
            sender.sendMessage(messages.prefixed(MessageKey.CONNECT_QUEUE_LIST_EMPTY));
            return;
        }
        boolean any = false;
        sender.sendMessage(messages.prefixed(MessageKey.CONNECT_QUEUE_LIST_HEADER));
        var handler = plugin.getPlayerConnectionHandler();
        for (Map.Entry<String, Set<UUID>> e : waiting.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            any = true;
            long remaining = 0;
            if (handler != null) {
                Long deadline = handler.getStartupWatcherDeadlines().get(e.getKey());
                if (deadline != null) {
                    remaining = Math.max(0, (deadline - System.currentTimeMillis()) / 1000L);
                }
            }
            sender.sendMessage(messages.prefixed(
                    MessageKey.CONNECT_QUEUE_LIST_ENTRY,
                    "server", e.getKey(),
                    "waiting", String.valueOf(e.getValue().size()),
                    "remaining", String.valueOf(remaining)));
        }
        if (!any) {
            sender.sendMessage(messages.prefixed(MessageKey.CONNECT_QUEUE_LIST_EMPTY));
        }
    }

    private void handleMaintenance(CommandSource sender, String[] args) {
        var maint = plugin.getMaintenanceService();
        if (maint == null) {
            sender.sendMessage(messages.prefixed(MessageKey.GENERIC_ERROR, "message", "Maintenance service unavailable"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Maintenance: global=" + maint.isGlobal()
                            + " servers=" + maint.getServers()));
            sender.sendMessage(messages.prefixed(
                    MessageKey.COMMAND_USAGE, "usage", "ptero maintenance [server|global] <on|off>"));
            return;
        }
        if (args.length == 2) {
            // /ptero maintenance on|off  → global
            boolean on = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
            boolean off = args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("false");
            if (!on && !off) {
                sender.sendMessage(messages.prefixed(
                        MessageKey.COMMAND_USAGE, "usage", "ptero maintenance [server|global] <on|off>"));
                return;
            }
            maint.setGlobal(on);
            sender.sendMessage(messages.prefixed(
                    MessageKey.GENERIC_SUCCESS,
                    "message", "Global maintenance " + (on ? "enabled" : "disabled")));
            return;
        }
        String target = args[1];
        boolean on = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
        boolean off = args[2].equalsIgnoreCase("off") || args[2].equalsIgnoreCase("false");
        if (!on && !off) {
            sender.sendMessage(messages.prefixed(
                    MessageKey.COMMAND_USAGE, "usage", "ptero maintenance [server|global] <on|off>"));
            return;
        }
        if (target.equalsIgnoreCase("global")) {
            maint.setGlobal(on);
            sender.sendMessage(messages.prefixed(
                    MessageKey.GENERIC_SUCCESS,
                    "message", "Global maintenance " + (on ? "enabled" : "disabled")));
            return;
        }
        maint.setServer(target, on);
        sender.sendMessage(messages.prefixed(
                MessageKey.GENERIC_SUCCESS,
                "message", "Maintenance for '" + target + "' " + (on ? "enabled" : "disabled")));
    }

    private void showApiInfo(CommandSource sender) {
        try {
            de.tubyoub.vpp.api.VPPApi api = de.tubyoub.vpp.api.VPPApiProvider.get();
            if (api == null) {
                sender.sendMessage(Component.text("[VPP] Public API not initialized."));
                return;
            }
            String version = "unknown";
            try { version = api.getApiVersion(); } catch (Throwable ignored) {}
            java.util.Collection<de.tubyoub.vpp.api.AddonCommand> cmds = java.util.Collections.emptyList();
            try { cmds = api.getRegisteredCommands(); } catch (Throwable ignored) {}
            int count = cmds != null ? cmds.size() : 0;
            boolean routing = false;
            try { routing = api.hasRoutingProvider(); } catch (Throwable ignored) {}
            boolean httpAvail = false;
            try { httpAvail = api.getPanelHttp() != null; } catch (Throwable ignored) {}
            boolean ctrlAvail = false;
            try { ctrlAvail = api.getServerControl() != null; } catch (Throwable ignored) {}
            boolean regAvail = false;
            try { regAvail = api.getServerRegistry() != null; } catch (Throwable ignored) {}

            sender.sendMessage(Component.text("VPP API version: " + version));
            sender.sendMessage(Component.text("RoutingProvider registered: " + routing));
            sender.sendMessage(Component.text("Addon commands registered: " + count));
            if (count > 0) {
                java.util.List<String> names = new java.util.ArrayList<>();
                for (de.tubyoub.vpp.api.AddonCommand c : cmds) {
                    if (c == null) continue;
                    try {
                        String n = c.name();
                        if (n != null && !n.isBlank()) names.add(n);
                    } catch (Throwable ignored) {}
                }
                java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
                sender.sendMessage(Component.text(" - " + String.join(", ", names)));
            }
            sender.sendMessage(Component.text("PanelHttpFacade available: " + httpAvail));
            sender.sendMessage(Component.text("ServerControl available: " + ctrlAvail));
            sender.sendMessage(Component.text("ServerRegistry available: " + regAvail));
        } catch (Throwable t) {
            sender.sendMessage(Component.text("Failed to read API info: " + t.getMessage()));
        }
    }
}
