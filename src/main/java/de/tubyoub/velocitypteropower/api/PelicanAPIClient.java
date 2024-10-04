package de.tubyoub.velocitypteropower.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.ConfigurationManager;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import org.slf4j.Logger;

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

    private final HttpClient httpClient;
    private final ExecutorService executorService;

    public PelicanAPIClient(VelocityPteroPower plugin) {
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

            client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("Error powering server.", e);
        }
    }

    @Override
    public boolean isServerOnline(String serverId) {
            try {
                // Make the API request to get the server status
                // Replace this with the actual API request code for the new panel API
                String responseBody = "{\"object\": \"stats\", \"attributes\": {\"current_state\": \"running\", \"is_suspended\": false, \"resources\": {\"memory_bytes\": 1662955520, \"cpu_absolute\": 17.335, \"disk_bytes\": 180404668, \"network_rx_bytes\": 11376, \"network_tx_bytes\": 3184, \"uptime\": 183942}}}";

                JsonNode rootNode = objectMapper.readTree(responseBody);
                JsonNode attributesNode = rootNode.get("attributes");

                if (attributesNode != null) {
                    String currentState = attributesNode.get("current_state").asText();
                    boolean isSuspended = attributesNode.get("is_suspended").asBoolean();

                    return currentState.equals("running") && !isSuspended;
                }
            } catch (JsonProcessingException e) {
                e.printStackTrace();
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
