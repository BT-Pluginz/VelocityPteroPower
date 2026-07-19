/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.http;

import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.VelocityPteroPower;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;


/**
 * Implementation of {@link PanelAPIClient} for interacting with the Pelican Panel API.
 * NOTE: The current implementation is the same as the PterodactylAPIClient.
 * if Pelican changes the workings of their API, this class will be updated.
 */
public class PelicanAPIClient extends AbstractPanelAPIClient {

    @Override
    public java.util.concurrent.CompletableFuture<de.tubyoub.velocitypteropower.model.ServerResourceUsage> fetchServerResources(String serverId) {
        return fetchWithCache(serverId, () -> {
            if (serverId == null || serverId.isBlank()) {
                return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
            }
            if (!rateLimitTracker.canMakeRequest()) {
                logger.warn("Rate limit reached. Skipping Pelican resources fetch for {}.", serverId);
                return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
            }
            try {
                logger.debug("Fetching Pelican resources for {} (cache ttl={}s)", serverId, configurationManager.getResourceCacheSeconds());
                // Fetch limits from server details
                String base = configurationManager.getPterodactylUrl();
                java.net.http.HttpRequest detailsReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(base + "api/client/servers/" + serverId))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + configurationManager.getPterodactylApiKey())
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();
                java.net.http.HttpResponse<String> detailsResp = httpClient.send(detailsReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                rateLimitTracker.updateRateLimitInfo(detailsResp);
                long memLimitBytes = -1L;
                long diskLimitBytes = -1L;
                double cpuLimitPercent = -1.0;
                if (detailsResp.statusCode() == 200 && detailsResp.body() != null) {
                    String djson = detailsResp.body();
                    long memMiB = extractLong(djson, "\"limits\"", "\"memory\"");
                    if (memMiB >= 0) memLimitBytes = memMiB * 1024L * 1024L; // 0 unlimited
                    long diskMiB = extractLong(djson, "\"limits\"", "\"disk\"");
                    if (diskMiB >= 0) diskLimitBytes = diskMiB * 1024L * 1024L; // 0 unlimited
                    double cpuPct = extractDouble(djson, "\"limits\"", "\"cpu\"");
                    if (cpuPct >= 0) cpuLimitPercent = cpuPct; // 0 unlimited
                }

                // Fetch live resources
                java.net.http.HttpRequest resReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(base + "api/client/servers/" + serverId + "/resources"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + configurationManager.getPterodactylApiKey())
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();
                java.net.http.HttpResponse<String> resResp = httpClient.send(resReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                rateLimitTracker.updateRateLimitInfo(resResp);
                if (resResp.statusCode() != 200 || resResp.body() == null) {
                    logger.debug("Pelican resources fetch for {} returned status {}", serverId, resResp.statusCode());
                    return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
                }
                String json = resResp.body();
                String state = extractString(json, "\"current_state\"");
                boolean suspended = extractBoolean(json, "\"is_suspended\"");
                long mem = extractLongFlat(json, "\"memory_bytes\"");
                double cpu = extractDoubleFlat(json, "\"cpu_absolute\"");
                long disk = extractLongFlat(json, "\"disk_bytes\"");
                long rx = extractLongFlat(json, "\"network_rx_bytes\"");
                long tx = extractLongFlat(json, "\"network_tx_bytes\"");
                long uptime = extractLongFlat(json, "\"uptime\"");

                logger.debug("Parsed Pelican resources for {} -> state={}, suspended={}, mem={}B/{}B, cpu={}%, disk={}B, rx={}B, tx={}B, uptime={}ms",
                        serverId, state, suspended, mem, memLimitBytes, cpu, disk, rx, tx, uptime);

                return new de.tubyoub.velocitypteropower.model.ServerResourceUsage(
                    state != null ? state : "unknown",
                    suspended,
                    mem,
                    memLimitBytes,
                    cpu,
                    cpuLimitPercent,
                    disk,
                    diskLimitBytes,
                    rx,
                    tx,
                    uptime,
                    true
                );
            } catch (Exception e) {
                logger.error("Error fetching Pelican resources for {}: {}", serverId, e.toString());
                return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
            }
        });
    }

    private String extractString(String json, String key) {
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx);
        if (colon == -1) return null;
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote == -1) return null;
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote == -1) return null;
        return json.substring(firstQuote + 1, secondQuote);
    }

    private boolean extractBoolean(String json, String key) {
        int idx = json.indexOf(key);
        if (idx == -1) return false;
        int colon = json.indexOf(':', idx);
        if (colon == -1) return false;
        int end = json.indexOf(',', colon + 1);
        String val = (end == -1 ? json.substring(colon + 1) : json.substring(colon + 1, end)).trim();
        return val.startsWith("true");
    }

    private long extractLongFlat(String json, String key) {
        try {
            int idx = json.indexOf(key);
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

    private double extractDoubleFlat(String json, String key) {
        try {
            int idx = json.indexOf(key);
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

    private long extractLong(String json, String parentKey, String childKey) {
        try {
            int p = json.indexOf(parentKey);
            if (p == -1) return 0L;
            int c = json.indexOf(childKey, p);
            if (c == -1) return 0L;
            int colon = json.indexOf(':', c);
            if (colon == -1) return 0L;
            int end = json.indexOf(',', colon + 1);
            String val = (end == -1 ? json.substring(colon + 1) : json.substring(colon + 1, end)).replaceAll("[^0-9]", "").trim();
            if (val.isEmpty()) return 0L;
            return Long.parseLong(val);
        } catch (Exception e) {
            return 0L;
        }
    }

    private double extractDouble(String json, String parentKey, String childKey) {
        try {
            int p = json.indexOf(parentKey);
            if (p == -1) return 0.0;
            int c = json.indexOf(childKey, p);
            if (c == -1) return 0.0;
            int colon = json.indexOf(':', c);
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
     * Constructs a PelicanAPIClient.
     *
     * @param plugin The main VelocityPteroPower plugin instance.
     */
    public PelicanAPIClient(VelocityPteroPower plugin) {
        super(plugin);
    }

    /**
     * Sends a power signal to a Pelican server.
     *
     * @param serverId The Pelican server identifier (UUID).
     * @param signal   The power action (START, STOP, RESTART, KILL).
     */
    @Override
    public void powerServer(String serverId, PowerSignal signal) {
        if (!rateLimitTracker.canMakeRequest()) {
            logger.warn(
                "Rate limit reached. Cannot send power signal {} to server {}.",
                signal,
                serverId
            );
            return;
        }
        rateLimitTracker.consumeOne();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configurationManager.getPterodactylUrl() + "api/client/servers/" + serverId + "/power"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + configurationManager.getPterodactylApiKey())
                .POST(HttpRequest.BodyPublishers.ofString("{\"signal\": \"" + signal.getApiSignal() + "\"}"))
                .build();

            rateLimitTracker.updateRateLimitInfo(httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            rateLimitTracker.restoreOne();
            logger.error("Error powering server.", e);
        }
    }

    /**
     * Online checks use {@link AbstractPanelAPIClient#isServerOnline(String, String)}.
     */

    /**
     * Checks server status by querying the Pelican API /resources endpoint.
     * Includes retry logic for specific HTTP/2 GOAWAY errors.
     *
     * @param serverName The name of the server (for logging).
     * @param serverId   The Pelican server identifier (UUID).
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
        if (!rateLimitTracker.canMakeRequest()) {
            logger.warn(
                "Rate limit reached. Cannot perform API status check for server {} ({}).",
                serverName,
                serverId
            );
            return false; // Cannot check status due to rate limit
        }

        int maxRetries = 3; // Max retries specifically for GOAWAY errors
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(
                        URI.create(
                            configurationManager.getPterodactylUrl() +
                            "api/client/servers/" +
                            serverId +
                            "/resources"
                        )
                    )
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header(
                        "Authorization",
                        "Bearer " + configurationManager.getPterodactylApiKey()
                    )
                    .GET()
                    .timeout(Duration.ofSeconds(10)) // Timeout for the API request
                    .build();

                // Use synchronous send here as the result is needed immediately
                HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                rateLimitTracker.updateRateLimitInfo(response); // Update rate limit info

                String responseBody = response.body();
                if (response.statusCode() == 200 && responseBody != null) {
                    // Check if the state attribute indicates running
                    // A more robust solution would parse the JSON properly
                    boolean running =
                        responseBody.contains("\"current_state\":\"running\"");
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

    public CompletableFuture<String> fetchWhitelistFile(String serverId) {
    CompletableFuture<String> future = new CompletableFuture<>();

    executorService.submit(() -> {
        try {
            String url = configurationManager.getPterodactylUrl() + "api/client/servers/" + serverId + "/files/contents?file=whitelist.json";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + configurationManager.getPterodactylApiKey())
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Update rate limit tracker
            rateLimitTracker.updateRateLimitInfo(response);

            if (response.statusCode() == 200) {
                future.complete(response.body());
            } else if (response.statusCode() == 404) {
                logger.debug("Whitelist file not found for server {}", serverId);
                future.complete("[]"); // Return empty whitelist if file doesn't exist
            } else {
                logger.error("Failed to fetch whitelist for server {}: HTTP {}", serverId, response.statusCode());
                future.completeExceptionally(new RuntimeException("HTTP Error " + response.statusCode()));
            }
        } catch (Exception e) {
            logger.error("Error fetching whitelist for server {}: {}", serverId, e.getMessage());
            future.completeExceptionally(e);
        }
    });

    return future;
}
    public boolean isApiKeyValid(String apiKey) {
        if (!rateLimitTracker.canMakeRequest()) {
            logger.warn("Rate limit reached. Cannot validate API key right now.");
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configurationManager.getPterodactylUrl() + "api/client/"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + configurationManager.getPterodactylApiKey())
                .GET()
                .build();
            HttpResponse response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            rateLimitTracker.updateRateLimitInfo(response);
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                logger.debug("API key validation failed: authentication rejected (HTTP {}).", status);
                return false;
            }
            if (status >= 500) {
                logger.warn("API key validation inconclusive: panel returned HTTP {} (server error).", status);
                return true;
            }
            return true;
        } catch (Exception e) {
            logger.warn("API key validation inconclusive: could not reach panel ({}).", e.getMessage());
        }
        return false;
    }

}
