package de.tubyoub.velocitypteropower.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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

public class PelicanAPIClient implements PanelAPIClient {
    public final Logger logger;
    public final ConfigurationManager configurationManager;
    public final ProxyServer proxyServer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VelocityPteroPower plugin;

    private final HttpClient httpClient;
    private final ExecutorService executorService;

    public PelicanAPIClient(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configurationManager = plugin.getConfigurationManager();
        this.proxyServer = plugin.getProxyServer();

        this.executorService = Executors.newFixedThreadPool(10); // Limit to 10 threads
        this.httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .build();
    }

    @Override
    public void powerServer(String serverId, String signal) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(configurationManager.getPterodactylUrl() + "api/client/servers/" + serverId + "/power"))
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

        @Override
        public boolean isServerEmpty (String serverName){
            Optional<RegisteredServer> server = proxyServer.getServer(serverName);
            return server.map(value -> value.getPlayersConnected().isEmpty()).orElse(true);
        }

        public void shutdown () {
            executorService.shutdownNow();
        }
    }

