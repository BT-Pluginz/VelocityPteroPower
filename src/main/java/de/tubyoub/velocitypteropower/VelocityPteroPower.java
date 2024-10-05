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

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.api.PanelType;
import de.tubyoub.velocitypteropower.api.PelicanAPIClient;
import de.tubyoub.velocitypteropower.api.PterodactylAPIClient;
import de.tubyoub.velocitypteropower.util.Metrics;
import de.tubyoub.velocitypteropower.util.VersionChecker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Main class for the VelocityPteroPower plugin.
 * This class handles the initialization of the plugin and the registration of commands and events.
 */
@Plugin(id = "velocity-ptero-power", name = "VelocityPteroPower", version = "0.9.2.3", authors = {"TubYoub"}, description = "A plugin for Velocity that allows you to manage your Pterodactyl/Pelican servers from the Velocity console.", url = "https://github.com/TubYoub/VelocityPteroPower")
public class VelocityPteroPower {
    private final String version = "0.9.2.3";
    private final String modrinthID = "";
    private final int pluginId = 21465;
    private final ProxyServer proxyServer;
    private final ComponentLogger logger;
    private final Path dataDirectory;
    private Map<String, PteroServerInfo> serverInfoMap;
    private final CommandManager commandManager;
    private final ConfigurationManager configurationManager;
    private PanelAPIClient apiClient;
    private final Metrics.Factory metricsFactory;
    private final Set<String> startingServers = ConcurrentHashMap.newKeySet();
    private final AtomicInteger rateLimit = new AtomicInteger(60); // Default value, will be updated
    private final AtomicInteger remainingRequests = new AtomicInteger(60); // Default value, will be updated
    private final ReentrantLock rateLimitLock = new ReentrantLock();

    /**
     * Constructor for the VelocityPteroPower class.
     *
     * @param proxy The ProxyServer instance. This is the main server instance for the Velocity proxy.
     * @param dataDirectory The path to the data directory. This is where the plugin can store and retrieve data.
     * @param commandManager The CommandManager instance. This is used to register and manage commands for the plugin.
     * @param logger The ComponentLogger instance. This is used for logging messages to the console.
     * @param metricsFactory The Metrics.Factory instance. This is used for creating metrics for the plugin.
     */
    @Inject
    public VelocityPteroPower(ProxyServer proxy, @DataDirectory Path dataDirectory,CommandManager commandManager,ComponentLogger logger, Metrics.Factory metricsFactory) {
        this.proxyServer = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.commandManager = commandManager;
        this.configurationManager = new ConfigurationManager(this);
        this.metricsFactory = metricsFactory;
    }

     /**
     * This method is called when the proxy server is initialized.
     * It logs the startup message, loads the configuration, initializes the Pterodactyl API client,
     * registers the commands and events, and checks for updates if enabled in the configuration.
     *
     * @param event the proxy initialize event
     */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info(MiniMessage.miniMessage().deserialize("<#4287f5>____   ________________________"));
        logger.info(MiniMessage.miniMessage().deserialize("<#4287f5>\\   \\ /   /\\______   \\______   \\"));
        logger.info(MiniMessage.miniMessage().deserialize("<#4287f5> \\   Y   /  |     ___/|     ___/"));
        logger.info(MiniMessage.miniMessage().deserialize("<#4287f5>  \\     /   |    |    |    |"+ "<#00ff77>         VelocityPteroPower <#6b6c6e>v" + version));
        logger.info(MiniMessage.miniMessage().deserialize("<#4287f5>   \\___/    |____|tero|____|ower" + "<#A9A9A9>     Running with Blackmagic on Velocity"));
        configurationManager.loadConfig();
        if (configurationManager.getPanelType() == PanelType.pelican) {
            logger.info("detected the pelican panel");
            this.apiClient = new PelicanAPIClient(this);
        } else {
            logger.info("detected the pterodactyl panel");
            this.apiClient = new PterodactylAPIClient(this);
        }

        commandManager.register("ptero", new PteroCommand(this));
        proxyServer.getEventManager().register(this,new ServerSwitchListener(this));

        this.serverInfoMap = configurationManager.getServerInfoMap();
        Metrics metrics = metricsFactory.make(this, pluginId);
        logger.info("VelocityPteroPower succesfully loaded");
        if (configurationManager.isCheckUpdate()){
            if (VersionChecker.isNewVersionAvailable(version)){
                logger.warn("There is a new Version of VelocityPteroPower");
            }
        }
    }

    /**
     * This method schedules a server shutdown if the server is empty.
     *
     * @param serverName the name of the server
     * @param serverID the ID of the server
     * @param timeout the timeout in seconds after which the server should be shut down if it is empty
     */
        public void scheduleServerShutdown(String serverName,String serverID, int timeout) {
            if (timeout < 0) {
                return;
            }
            logger.info("Scheduling server shutdown for " + serverName + " in " + timeout + " seconds.");
            proxyServer.getScheduler().buildTask(this, () -> {
                if (apiClient.isServerEmpty(serverName)) {
                    apiClient.powerServer(serverID, "stop");
                    logger.info("Shutting down server: " + serverName);
                }else {
                    logger.info("Shutdown cancelled for server: " + serverName + ". Players are present.");
                }
            }).delay(timeout, TimeUnit.SECONDS).schedule();
        }
     /**
     * This method is called when a player tries to connect to a server.
     * It checks if the server is online and starts it if it is not.
     * If the server is already starting, it sends a message to the player and denies the connection.
     * If the server is offline, it starts the server, sends a message to the player, denies the connection,
     * and schedules a task to check if the server is online and connect the player.
     *
     * @param event the server pre-connect event
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getOriginalServer().getServerInfo().getName();
        this.serverInfoMap = configurationManager.getServerInfoMap();
        PteroServerInfo serverInfo = serverInfoMap.get(serverName);

        if (!serverInfoMap.containsKey(serverName)) {
            logger.warn("Server '" + serverName + "' not found in configuration.");
            player.sendMessage(
                Component.text("[", NamedTextColor.WHITE)
                .append(Component.text("VPP", TextColor.color(66,135,245)))
                .append(Component.text("] Server not found in configuration: " + serverName, NamedTextColor.WHITE)));
            return;
        }
        if (apiClient.isServerOnline(serverName) && this.canMakeRequest()) {
            if (startingServers.contains(serverName)){
                startingServers.remove(serverName);
            }
            return;
        }
        if (startingServers.contains(serverName)){
            player.sendMessage(
                Component.text("[", NamedTextColor.WHITE)
                .append(Component.text("VPP", TextColor.color(66,135,245)))
                .append(Component.text("] " +  serverName +" is already starting", NamedTextColor.WHITE)));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        startingServers.add(serverName);
        apiClient.powerServer(serverInfo.getServerId(), "start");
        player.sendMessage(
                Component.text("[", NamedTextColor.WHITE)
                .append(Component.text("VPP", TextColor.color(66,135,245)))
                .append(Component.text("] Starting server: " + serverName, NamedTextColor.WHITE)));
        event.setResult(ServerPreConnectEvent.ServerResult.denied());

        proxyServer.getScheduler().buildTask(this, () -> {
            if (apiClient.isServerOnline(serverName) && this.canMakeRequest()) {
                connectPlayer(player, serverName);
            } else {
                proxyServer.getScheduler().buildTask(this, () -> checkServerAndConnectPlayer(player, serverName)).schedule();
            }
        }).delay(16, TimeUnit.SECONDS).schedule();
        }

    private void checkServerAndConnectPlayer(Player player, String serverName) {
        PteroServerInfo serverInfo = serverInfoMap.get(serverName);
        if (apiClient.isServerOnline(serverName) && this.canMakeRequest()) {
            connectPlayer(player, serverName);
        } else {
            proxyServer.getScheduler().buildTask(this, () -> checkServerAndConnectPlayer(player, serverName)).delay(configurationManager.getStartupJoinDelay(), TimeUnit.SECONDS).schedule();
        }
    }

     /**
     * This method connects a player to a server.
     * It first retrieves the server by its name. If the server is not found, it throws a RuntimeException.
     * If the player is not currently connected to any server and the target server is empty, it schedules a shutdown for the server.
     * If the player is already connected to the target server, it does nothing.
     * If the target server is online, it sends a connection request to the player and removes the server from the startingServers set.
     *
     * @param player the player to connect
     * @param serverName the name of the server
     */
    private void connectPlayer(Player player, String serverName) {
        RegisteredServer server = proxyServer.getServer(serverName).orElseThrow(() -> new RuntimeException("Server not found: " + serverName));

        if (!player.getCurrentServer().isPresent()) {
            if (apiClient.isServerEmpty(serverName)){
                this.scheduleServerShutdown(serverName, serverInfoMap.get(serverName).getServerId(),serverInfoMap.get(serverName).getTimeout());
            }
            return;
        }
        // Check if the player is already connected to the server
        if (player.getCurrentServer().get().getServerInfo().getName().equals(serverName)) {
            return;
        }

        if (apiClient.isServerOnline(serverName) && this.canMakeRequest()) {
            player.createConnectionRequest(server).fireAndForget();
            startingServers.remove(serverName);
        }
    }
    public boolean canMakeRequest() {
        rateLimitLock.lock();
        try {
            return remainingRequests.get() > 0;
        } finally {
            rateLimitLock.unlock();
        }
    }

    public void updateRateLimitInfo(HttpResponse<String> response) {
        rateLimitLock.lock();
        try {
            String limitHeader = response.headers().firstValue("x-ratelimit-limit").orElse(null);
            String remainingHeader = response.headers().firstValue("x-ratelimit-remaining").orElse(null);

            if (limitHeader != null) {
                rateLimit.set(Integer.parseInt(limitHeader));
            }
            if (remainingHeader != null) {
                remainingRequests.set(Integer.parseInt(remainingHeader));
            }
        } finally {
            rateLimitLock.unlock();
            if (configurationManager.isPrintRateLimit()) {
                logger.info("Rate limit updated: Limit: {}, Remaining: {}", rateLimit.get(), remainingRequests.get());
            }
        }
    }

    /**
     * This method reloads the configuration for the VelocityPteroPower plugin.
     * It calls the loadConfig method of the ConfigurationManager instance to reload the configuration.
     * It then updates the serverInfoMap with the new configuration.
     */
    public void reloadConfig() {
        configurationManager.loadConfig();
        this.serverInfoMap = configurationManager.getServerInfoMap();
    }
    /**
     * This method returns the map of server names to PteroServerInfo objects.
     *
     * @return the map of server names to PteroServerInfo objects
     */
    public Map<String, PteroServerInfo> getServerInfoMap() {
        return serverInfoMap;
    }

    /**
     * Returns the ProxyServer instance.
     *
     * @return the ProxyServer instance
     */
    public ProxyServer getProxyServer(){
        return proxyServer;
    }

    /**
     * Returns the ComponentLogger instance.
     *
     * @return the ComponentLogger instance
     */
    public ComponentLogger getLogger(){
        return logger;
    }

    /**
     * Returns the Path to the data directory.
     *
     * @return the Path to the data directory
     */
    public Path getDataDirectory(){
        return dataDirectory;
    }

    /**
     * Returns the PterodactylAPIClient instance.
     *
     * @return the PterodactylAPIClient instance
     */
    public PanelAPIClient getAPIClient() {
        return apiClient;
    }

    /**
     * Returns the ConfigurationManager instance.
     *
     * @return the ConfigurationManager instance
     */
    public ConfigurationManager getConfigurationManager() {
        return configurationManager;
    }
    public void onProxyShutdown(ProxyShutdownEvent event) {
        apiClient.shutdown();
        logger.info("Shutting down VelocityPteroPower... Goodbye");
    }
}