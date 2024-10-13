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

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class listens to server switch events and disconnect events.
 * It uses the Pterodactyl API client to check if a server is empty and schedules a shutdown if it is.
 */
public class ServerSwitchListener {

    private final Logger logger;
    private final VelocityPteroPower plugin;
    private final ProxyServer proxyServer;
    private final PanelAPIClient apiClient;
    private final ConfigurationManager configurationManager;
    private final Set<String> startingServers = ConcurrentHashMap.newKeySet();
    private Map<String, PteroServerInfo> serverInfoMap;

    /**
     * Constructor for the ServerSwitchListener class.
     *
     * @param plugin the VelocityPteroPower plugin instancee
     */
    public ServerSwitchListener(VelocityPteroPower plugin){
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.proxyServer = plugin.getProxyServer();
        this.apiClient = plugin.getAPIClient();
        this.configurationManager = plugin.getConfigurationManager();
        this.serverInfoMap = configurationManager.getServerInfoMap();
    }

    /**
     * This method is called when a disconnect event occurs.
     * It checks if the server the player was on is empty and schedules a shutdown if it is.
     *
     * @param event the disconnect event
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Optional<ServerConnection> serverConnection = event.getPlayer().getCurrentServer();
        if (serverConnection.isPresent()) {
            String serverName = serverConnection.get().getServerInfo().getName();
            PteroServerInfo serverInfo = plugin.getServerInfoMap().get(serverName);
            if (serverInfo != null && apiClient.isServerEmpty(serverName)) {
                plugin.scheduleServerShutdown(serverName, serverInfo.getServerId(), serverInfo.getTimeout());
            }
        }
    }

    /**
     * This method is called when a server switch event occurs.
     * It checks if the server the player was on is empty and schedules a shutdown if it is.
     *
     * @param event the server connected event
     */
    @Subscribe
    public void onServerSwitch(ServerConnectedEvent event) {
        Optional<RegisteredServer> previousServerConnection = event.getPreviousServer();
        if (previousServerConnection.isPresent()) {
            String serverName = previousServerConnection.get().getServerInfo().getName();
            PteroServerInfo serverInfo = plugin.getServerInfoMap().get(serverName);
            if (serverInfo != null && apiClient.isServerEmpty(serverInfo.getServerId())) {
                plugin.scheduleServerShutdown(serverName, serverInfo.getServerId(), serverInfo.getTimeout());
            }
        }
    }
}