/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 *
 *  Copyright (c) TubYoub <github@tubyoub.de>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package de.tubyoub.velocitypteropower;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.api.PterodactylAPIClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This class represents a command that can be executed by a player.
 * It includes subcommands to start, stop, and reload servers.
 */
public class PteroCommand implements SimpleCommand {
    private final ProxyServer proxyServer;
    private final VelocityPteroPower plugin;
    private final Logger logger;
    private final PanelAPIClient apiClient;
    private final ConfigurationManager configurationManager;

    /**
     * Constructor for the PteroCommand class.
     * @param plugin the VelocityPteroPower plugin instance
     */
    public PteroCommand(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.proxyServer = plugin.getProxyServer();
        this.logger = plugin.getLogger();
        this.apiClient = plugin.getAPIClient();
        this.configurationManager = plugin.getConfigurationManager();
    }

    /**
     * This method is called when the command is executed.
     * It checks the subcommand and executes the corresponding action.
     *
     * @param invocation the command invocation
     */
    @Override
    public void execute(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();


        if (args.length == 0) {
            displayHelp(sender);
            return;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "start":
                if (sender.hasPermission("ptero.start")) {
                    startServer(invocation.source(), args);
                } else {
                    sender.sendMessage(getSPPPrefix().append(Component.text("You do not have permission to use this command.",TextColor.color(255,0,0))));
                }
                break;
            case "stop":
                if (sender.hasPermission("ptero.stop")) {
                    stopServer(sender, args);
                } else {
                    sender.sendMessage(getSPPPrefix().append(Component.text("You do not have permission to use this command.",TextColor.color(255,0,0))));
                }
                break;
            case "reload":
                if (sender.hasPermission("ptero.reload")) {
                    reloadConfig(sender);
                } else {
                    sender.sendMessage(getSPPPrefix().append(Component.text("You do not have permission to use this command.",TextColor.color(255,0,0))));
                }
                break;
            default:
                sender.sendMessage(getSPPPrefix().append(Component.text("Unknown subcommand: " + subCommand)));
                displayHelp(sender);
        }
    }

    /**
     * This method is called to start a server.
     *
     * @param sender the player who executed the command
     * @param args the command arguments
     */
    private void startServer(CommandSource sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(getSPPPrefix().append(Component.text("Usage: /ptero start <serverName>", NamedTextColor.RED)));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.containsKey(serverName)) {
            PteroServerInfo serverInfo = serverInfoMap.get(serverName);
            apiClient.powerServer(serverInfo.getServerId(), "start");
            sender.sendMessage(getSPPPrefix().append(Component.text("The server: "+ serverName + " is starting")));
        } else {
        }
    }

    /**
     * This method is called to stop a server.
     *
     * @param sender the player who executed the command
     * @param args the command arguments
     */
    private void stopServer(CommandSource sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(getSPPPrefix().append(Component.text("Usage: /ptero stop <serverName>", TextColor.color(66,135,245))));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.containsKey(serverName)) {
            PteroServerInfo serverInfo = serverInfoMap.get(serverName);
            apiClient.powerServer(serverInfo.getServerId(), "stop");
            sender.sendMessage(getSPPPrefix().append(Component.text("The server: "+ serverName + " is stopping")));
        } else {
        }
    }

    /**
     * This method is called to reload the configuration.
     *
     * @param sender the player who executed the command
     */
    private void reloadConfig(CommandSource sender) {
        plugin.reloadConfig();
        sender.sendMessage(getSPPPrefix().append(Component.text("Configuration reloaded.",TextColor.color(0,255,0))));
    }

    /**
     * This method is called to suggest command completions.
     *
     * @param invocation the command invocation
     * @return a list of suggested completions
     */
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

    private void displayHelp(CommandSource sender) {
        sender.sendMessage(getSPPPrefix().append(Component.text("Available commands:", NamedTextColor.GREEN)));
        sender.sendMessage(getSPPPrefix().append(Component.text("/ptero start <serverName>", TextColor.color(66,135,245))));
        sender.sendMessage(getSPPPrefix().append(Component.text("/ptero stop <serverName>", TextColor.color(66,135,245))));
        sender.sendMessage(getSPPPrefix().append(Component.text("/ptero reload", TextColor.color(66,135,245))));
        sender.sendMessage(getSPPPrefix().append(Component.text("/ptero help", TextColor.color(66,135,245))));
}

    private Component getSPPPrefix() {
        return Component.text("[", NamedTextColor.WHITE)
            .append(Component.text("VPP", TextColor.color(66,135,245)))
            .append(Component.text("] ", NamedTextColor.WHITE));
    }
}