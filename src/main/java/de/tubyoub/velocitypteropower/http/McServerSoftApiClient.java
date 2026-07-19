package de.tubyoub.velocitypteropower.http;

import de.tubyoub.velocitypteropower.VelocityPteroPower;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import de.tubyoub.velocitypteropower.manager.ConfigurationManager;

public class McServerSoftApiClient extends AbstractPanelAPIClient {

    @Override
    public java.util.concurrent.CompletableFuture<de.tubyoub.velocitypteropower.model.ServerResourceUsage> fetchServerResources(String serverId) {
        return fetchWithCache(serverId, () -> {
            if (serverId == null || serverId.isBlank()) {
                return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
            }
            try {
                logger.debug("Fetching MC Server Soft resources for {} (cache ttl={}s)", serverId, configurationManager.getResourceCacheSeconds());
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(configurationManager.getPterodactylUrl() + "api/v2/servers/" + serverId + "/stats"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("apiKey", configurationManager.getPterodactylApiKey())
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();
                java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                String body = response.body();
                if (response.statusCode() != 200 || body == null) {
                    logger.debug("MCSS resources fetch for {} returned status {}", serverId, response.statusCode());
                    return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
                }
                // Parse fields under "latest"
                long memUsedMiB = extractLongUnderLatest(body, "\"memoryUsed\"");
                long memLimitMiB = extractLongUnderLatest(body, "\"memoryLimit\"");
                double cpu = extractDoubleUnderLatest(body, "\"cpu\"");
                long startDate = extractLongUnderLatest(body, "\"startDate\"");
                long uptime = startDate > 0 ? Math.max(0L, System.currentTimeMillis() - startDate) : 0L;
                long memUsedBytes = memUsedMiB * 1024L * 1024L;
                long memLimitBytes = memLimitMiB * 1024L * 1024L;
                // Disk and network stats not available => set sentinel -1 to render as N/A
                long disk = -1L;
                long rx = -1L;
                long tx = -1L;

                logger.debug("Parsed MCSS resources for {} -> mem={}B/{}B, cpu={}%, uptime={}ms (disk/net N/A)",
                        serverId, memUsedBytes, memLimitBytes, cpu, uptime);

                return new de.tubyoub.velocitypteropower.model.ServerResourceUsage(
                    "unknown",
                    false,
                    memUsedBytes,
                    memLimitBytes,
                    cpu,
                    0.0,
                    disk,
                    0L,
                    rx,
                    tx,
                    uptime,
                    true
                );
            } catch (Exception e) {
                logger.error("Error fetching MCSS resources for {}: {}", serverId, e.toString());
                return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
            }
        });
    }

    private long extractLongUnderLatest(String json, String key) {
        try {
            int latest = json.indexOf("\"latest\"");
            int idx = latest == -1 ? json.indexOf(key) : json.indexOf(key, latest);
            if (idx == -1) return 0L;
            int colon = json.indexOf(':', idx);
            if (colon == -1) return 0L;
            int end = json.indexOf(',', colon + 1);
            String val = (end == -1 ? json.substring(colon + 1) : json.substring(colon + 1, end)).replaceAll("[^0-9]", "").trim();
            if (val.isEmpty()) return 0L;
            return Long.parseLong(val);
        } catch (Exception e) {
            return 0L;
        }
    }

    private double extractDoubleUnderLatest(String json, String key) {
        try {
            int latest = json.indexOf("\"latest\"");
            int idx = latest == -1 ? json.indexOf(key) : json.indexOf(key, latest);
            if (idx == -1) return 0.0;
            int colon = json.indexOf(':', idx);
            if (colon == -1) return 0.0;
            int end = json.indexOf(',', colon + 1);
            String val = (end == -1 ? json.substring(colon + 1) : json.substring(colon + 1, end)).replaceAll("[^0-9.\\-]", "").trim();
            if (val.isEmpty()) return 0.0;
            return Double.parseDouble(val);
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * Constructs a PterodactylAPIClient.
     *
     * @param plugin The main VelocityPteroPower plugin instance.
     */
    public McServerSoftApiClient(VelocityPteroPower plugin){
        super(plugin);
    }

    /**
     * Sends a power signal to a Pterodactyl server.
     *
     * @param serverId The Pterodactyl server identifier (UUID).
     * @param signal   The power action (START, STOP, RESTART, KILL).
     */
    @Override
    public void powerServer(String serverId, PowerSignal signal) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configurationManager.getPterodactylUrl() + "api/v2/servers/" + serverId + "/execute/action"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("apiKey",  configurationManager.getPterodactylApiKey())
                .POST(HttpRequest.BodyPublishers.ofString("{\"action\": \"" + signal.getApiSignal() + "\"}"))
                .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("Error powering server.", e);
        }
    }

     /**
     * Online checks use {@link AbstractPanelAPIClient#isServerOnline(String, String)}.
     */

    /**
     * Checks server status by querying the Pterodactyl API /resources endpoint.
     * Includes retry logic for specific HTTP/2 GOAWAY errors.
     *
     * @param serverName The name of the server (for logging).
     * @param serverId   The Pterodactyl server identifier (UUID).
     * @return True if the API reports the server state as "running", false otherwise.
     */
    protected boolean checkOnlineViaPanelApi(String serverName, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            logger.error(
                "Cannot perform API check: Server ID is missing for server '{}'.",
                serverName
            );
            return false;
        }

        int maxRetries = 3; // Max retries specifically for GOAWAY errors
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(
                        URI.create(
                            configurationManager.getPterodactylUrl() +
                            "api/v2/servers/" +
                            serverId +
                            "?filter=Status"
                        )
                    )
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header(
                        "apiKey", configurationManager.getPterodactylApiKey()
                    )
                    .GET()
                    .timeout(Duration.ofSeconds(10)) // Timeout for the API request
                    .build();

                // Use synchronous send here as the result is needed immediately
                HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                String responseBody = response.body();
                if (response.statusCode() == 200 && responseBody != null) {
                    // Check if the state attribute indicates running
                    // A more robust solution would parse the JSON properly
                    boolean running =
                        responseBody.contains("\"status\":\"1\"");
                    logger.debug(
                        "API check for {} (ID {}): Status {}, State Running: {}",
                        serverName,
                        serverId,
                        response.statusCode(),
                        running
                    );
                    return running;
                } else {
                    logger.warn(
                        "API check for {} (ID {}) failed with status code: {} Body: {}",
                        serverName,
                        serverId,
                        response.statusCode(),
                        responseBody != null ? responseBody.substring(0, Math.min(responseBody.length(), 100)) + "..." : "null" // Log truncated body
                    );
                    return false; // Non-200 or null body means not running or error
                }
            } catch (IOException e) {
                // Check specifically for GOAWAY which might indicate HTTP/2 issues
                if (e.getMessage() != null && e.getMessage().contains("GOAWAY")) {
                    logger.warn(
                        "API check for {} (ID {}) received GOAWAY (Attempt {}/{}). Retrying...",
                        serverName,
                        serverId,
                        attempt,
                        maxRetries
                    );
                    if (attempt == maxRetries) {
                        logger.error(
                            "API check for {} (ID {}) failed after {} retries due to GOAWAY: {}",
                            serverName,
                            serverId,
                            maxRetries,
                            e.getMessage()
                        );
                        return false; // Failed after retries
                    }
                    try {
                        Thread.sleep(
                            1000 * attempt
                        ); // Simple exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn(
                            "API check retry sleep interrupted for {} (ID {}).",
                            serverName,
                            serverId
                        );
                        return false; // Interrupted during sleep
                    }
                    // Continue to the next iteration of the loop
                } else {
                    // Other IOExceptions
                    logger.error(
                        "API check for {} (ID {}) failed with IOException: {}",
                        serverName,
                        serverId,
                        e.getMessage(),
                        e
                    );
                    return false; // Other I/O error
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn(
                    "API check for {} (ID {}) interrupted.",
                    serverName,
                    serverId
                );
                return false;
            } catch (Exception e) { // Catch unexpected errors
                logger.error(
                    "Unexpected error during API check for {} (ID {}): {}",
                    serverName,
                    serverId,
                    e.getMessage(),
                    e
                );
                return false;
            }
        }
        // Should only be reached if all retries failed
        return false;
    }


    @Override
    public CompletableFuture<String> fetchWhitelistFile(String serverId) {
        logger.warn("MC Server Soft does not Support whitelist fetching");
        return CompletableFuture.completedFuture("[]");
    }
    public boolean isApiKeyValid(String apiKey) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configurationManager.getPterodactylUrl() + "api/v2/"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("apiKey",  configurationManager.getPterodactylApiKey())
                .GET()
                .build();
            HttpResponse response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            rateLimitTracker.updateRateLimitInfo(response);
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                logger.debug("Checking for valid ApiKey returned 401/403");
                return false;
            }else {
                return true;
            }
        } catch (Exception e) {
            logger.error("Error checking for valid ApiKey server.", e);
        }
        return false;
    }

}
