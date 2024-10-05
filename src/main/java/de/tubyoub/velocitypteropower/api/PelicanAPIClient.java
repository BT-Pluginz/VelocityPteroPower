package de.tubyoub.velocitypteropower.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.ConfigurationManager;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
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
    public boolean isServerOnline(String serverId) {
        int retryCount = 3;
        while (retryCount > 0) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(configurationManager.getPterodactylUrl() + "api/client/servers/" + serverId + "/resources"))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + configurationManager.getPterodactylApiKey())
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                plugin.updateRateLimitInfo(response);
                String responseBody = response.body();
                if (response.statusCode() == 200) {
                    return responseBody.contains("{\"object\":\"stats\",\"attributes\":{\"current_state\":\"running\"");
                } else {
                    return false;
                }
            } catch (IOException | InterruptedException e) {
                if (e.getMessage().contains("GOAWAY")) {
                    retryCount--;
                    if (retryCount == 0) {
                        logger.error("Failed to check server status after retries: " + e.getMessage());
                        return false;
                    }
                    logger.warn("GOAWAY received, retrying... (" + retryCount + " retries left)");
                    try {
                        Thread.sleep(1000); // Wait before retrying
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    logger.error("Error checking server status: " + e.getMessage());
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isServerEmpty(String serverName) {
        Optional<RegisteredServer> server = proxyServer.getServer(serverName);
        return server.map(value -> value.getPlayersConnected().isEmpty()).orElse(true);
    }

    public void shutdown() {
        executorService.shutdownNow();
    }
}
