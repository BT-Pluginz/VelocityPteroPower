package de.tubyoub.velocitypteropower;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PteroCommand implements SimpleCommand {
    private final ProxyServer proxyServer;
    private final VelocityPteroPower plugin;

    public PteroCommand(ProxyServer proxyServer, VelocityPteroPower plugin) {
        this.proxyServer = proxyServer;
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /ptero <start|stop|reload>"));
            return;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "start":
                if (player.hasPermission("ptero.start")) {
                    startServer(player, args);
                } else {
                    player.sendMessage(Component.text("You do not have permission to use this command."));
                }
                break;
            case "stop":
                if (player.hasPermission("ptero.stop")) {
                    stopServer(player, args);
                } else {
                    player.sendMessage(Component.text("You do not have permission to use this command."));
                }
                break;
            case "reload":
                if (player.hasPermission("ptero.reload")) {
                    reloadConfig(player);
                } else {
                    player.sendMessage(Component.text("You do not have permission to use this command."));
                }
                break;
            default:
                player.sendMessage(Component.text("Unknown subcommand: " + subCommand));
        }
    }

    private void startServer(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /ptero start <serverName>"));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.containsKey(serverName)) {
            PteroServerInfo serverInfo = serverInfoMap.get(serverName);
            plugin.powerServer(serverInfo.getServerId(), "start");
            player.sendMessage(Component.text("Starting server: " + serverName));
        } else {
            player.sendMessage(Component.text("Server not found in configuration: " + serverName));
        }
    }

    private void stopServer(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /ptero stop <serverName>"));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.containsKey(serverName)) {
            PteroServerInfo serverInfo = serverInfoMap.get(serverName);
            plugin.powerServer(serverInfo.getServerId(), "stop");
            player.sendMessage(Component.text("Stopping server: " + serverName));
        } else {
            player.sendMessage(Component.text("Server not found in configuration: " + serverName));
        }
    }

    private void reloadConfig(Player player) {
        plugin.loadConfiguration();
        player.sendMessage(Component.text("Configuration reloaded."));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] currentArgs = invocation.arguments();

        if (currentArgs.length <= 1) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("start");
            suggestions.add("stop");
            suggestions.add("reload");
            return suggestions;
        } else if (currentArgs.length == 2) {
            String subCommand = currentArgs[0].toLowerCase();
            if (subCommand.equals("start") || subCommand.equals("stop")) {
                if (plugin.getServerInfoMap() != null) {
                    return plugin.getServerInfoMap().keySet().stream()
                            .filter(serverName -> serverName.startsWith(currentArgs[1]))
                            .collect(Collectors.toList());
                } else {
                    return Collections.emptyList();
                }
            }
        }
        return null;
    }
}
