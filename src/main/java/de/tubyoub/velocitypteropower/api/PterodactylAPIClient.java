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

package de.tubyoub.velocitypteropower.api;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * This class provides methods to interact with the Pterodactyl API.
 * It includes methods to power a server, check if a server is online, and check if a server is empty.
 */

public class PterodactylAPIClient implements PanelAPIClient{
    public final Logger logger;
    public final ConfigurationManager configurationManager;
    public final ProxyServer proxyServer;
    private final VelocityPteroPower plugin;

    private final HttpClient httpClient;
    private final ExecutorService executorService;


    /**
     * Constructor for the PterodactylAPIClient class.
     * It initializes the logger, configuration manager, and proxy server from the provided plugin instance.
     *
     * @param plugin the VelocityPteroPower plugin instance
     */

    public PterodactylAPIClient(VelocityPteroPower plugin){
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configurationManager = plugin.getConfigurationManager();
        this.proxyServer = plugin.getProxyServer();

        this.executorService = Executors.newFixedThreadPool(configurationManager.getApiThreads());
        this.httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .build();
    }

    /**
     * This method sends a power signal to a server.
     *
     * @param serverId the ID of the server
     * @param signal the power signal to send
     */
    @Override
    public void powerServer(String serverId, String signal) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configurationManager.getPterodactylUrl() + "api/client/servers/" + serverId + "/power"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + configurationManager.getPterodactylApiKey())
                .POST(HttpRequest.BodyPublishers.ofString("{\"signal\": \"" + signal + "\"}"))
                .build();

            plugin.updateRateLimitInfo(httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            logger.error("Error powering server.", e);
        }
    }


    /**
     * This method checks if a server is online.
     *
     * @param serverName the ID of the server
     * @return true if the server is online, false otherwise
     */
    @Override
    public boolean isServerOnline(String serverName) {
        Optional<RegisteredServer> serverOptional = proxyServer.getServer(serverName);
        if (serverOptional.isPresent()) {
            RegisteredServer server = serverOptional.get();
            try {
                CompletableFuture<ServerPing> pingFuture = server.ping();
                ServerPing pingResult = pingFuture.get(configurationManager.getPingTimeout(), TimeUnit.MILLISECONDS);
                return pingResult != null;
            } catch (NullPointerException npe) {
                return false;
            } catch (Exception e) {
                logger.debug("Error pinging server {}: {}", serverName, e.getMessage());
                return false;
            }
        } else {
            logger.error("Server not found: {} Check if the server name match in the configs", serverName);
            return false;
        }
    }

    /**
     * This method checks if a server is online.
     *
     * @param serverName the name of the server
     * @return true if the server is online, false otherwise
     */
    @Override
    public boolean isServerEmpty(String serverName) {
        Optional<RegisteredServer> server = proxyServer.getServer(serverName);
        return server.map(value -> value.getPlayersConnected().isEmpty()).orElse(true);
    }

    public void shutdown() {
        executorService.shutdownNow();
    }
}
