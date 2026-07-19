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
 * Implementation of {@link PanelAPIClient} for interacting with the Pterodactyl Panel API.
 */
public class PterodactylAPIClient extends AbstractPanelAPIClient {

    /**
     * Constructs a PterodactylAPIClient.
     *
     * @param plugin The main VelocityPteroPower plugin instance.
     */
    public PterodactylAPIClient(VelocityPteroPower plugin){
        super(plugin);
    }

    @Override
    public java.util.concurrent.CompletableFuture<de.tubyoub.velocitypteropower.model.ServerResourceUsage> fetchServerResources(String serverId) {
        return fetchWithCache(serverId, () -> {
            if (serverId == null || serverId.isBlank()) {
                return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
            }
            if (!rateLimitTracker.canMakeRequest()) {
                logger.warn("Rate limit reached. Skipping resources fetch for {}.", serverId);
                return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
            }
            try {
                logger.debug("Fetching Pterodactyl resources for {} (cache ttl={}s)", serverId, configurationManager.getResourceCacheSeconds());

                // 1) Fetch server details for max limits: memory (MiB), disk (MiB), cpu (%)
                long limitFromDetailsBytes = -1L; // memory: -1 unknown; 0 unlimited
                long diskLimitFromDetailsBytes = -1L; // disk: -1 unknown; 0 unlimited
                double cpuLimitFromDetailsPercent = -1.0; // cpu: -1 unknown; 0 unlimited
                try {
                    java.net.http.HttpRequest detailsReq = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(configurationManager.getPterodactylUrl() + "api/client/servers/" + serverId))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + configurationManager.getPterodactylApiKey())
                        .GET()
                        .timeout(java.time.Duration.ofSeconds(10))
                        .build();
                    java.net.http.HttpResponse<String> detailsResp = httpClient.send(detailsReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                    rateLimitTracker.updateRateLimitInfo(detailsResp);
                    if (detailsResp.statusCode() == 200 && detailsResp.body() != null) {
                        String detailsJson = detailsResp.body();
                        long memMiB = extractLimitsMemoryMiB(detailsJson);
                        if (memMiB >= 0) {
                            limitFromDetailsBytes = memMiB * 1024L * 1024L; // 0 => unlimited
                        }
                        long diskMiB = extractLimitsDiskMiB(detailsJson);
                        if (diskMiB >= 0) {
                            diskLimitFromDetailsBytes = diskMiB * 1024L * 1024L; // 0 => unlimited
                        }
                        double cpuPct = extractLimitsCpuPercent(detailsJson);
                        if (cpuPct >= 0) {
                            cpuLimitFromDetailsPercent = cpuPct; // 0 => unlimited
                        }
                        logger.debug("Parsed server details for {} -> limits.memory={}MiB ({}B), limits.disk={}MiB ({}B), limits.cpu={}%%",
                                serverId, memMiB, limitFromDetailsBytes, diskMiB, diskLimitFromDetailsBytes, cpuLimitFromDetailsPercent);
                    } else {
                        logger.debug("Server details fetch for {} returned status {}", serverId, detailsResp.statusCode());
                    }
                } catch (Exception e) {
                    logger.debug("Failed to fetch/parse server details for {}: {}", serverId, e.toString());
                }

                // 2) Fetch live resources (usage + sometimes memory_limit_bytes)
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(configurationManager.getPterodactylUrl() + "api/client/servers/" + serverId + "/resources"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + configurationManager.getPterodactylApiKey())
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();

                java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                rateLimitTracker.updateRateLimitInfo(response);
                if (response.statusCode() != 200 || response.body() == null) {
                    logger.debug("Resources fetch for {} returned status {}", serverId, response.statusCode());
                    return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
                }
                String json = response.body();
                // naive parsing without external JSON library
                String state = extractString(json, "\"current_state\"");
                boolean suspended = extractBoolean(json, "\"is_suspended\"");
                long mem = extractLong(json, "\"memory_bytes\"");
                long memLimitFromResources = extractLong(json, "\"memory_limit_bytes\"");
                double cpu = extractDouble(json, "\"cpu_absolute\"");
                long disk = extractLong(json, "\"disk_bytes\"");
                long rx = extractLong(json, "\"network_rx_bytes\"");
                long tx = extractLong(json, "\"network_tx_bytes\"");
                long uptime = extractLong(json, "\"uptime\"");

                long chosenLimit;
                if (memLimitFromResources > 0) {
                    chosenLimit = memLimitFromResources;
                } else if (limitFromDetailsBytes >= 0) {
                    // Use details-derived limit when resources missing/zero
                    chosenLimit = limitFromDetailsBytes; // may be 0 (unlimited) or >0
                } else {
                    chosenLimit = 0L; // unknown -> treat as unlimited for consistency
                }

                logger.debug("Parsed resources for {} -> state={}, suspended={}, mem={}B/{}B (resources:{} details:{}), cpu={}%, disk={}B, rx={}B, tx={}B, uptime={}ms",
                        serverId, state, suspended, mem, chosenLimit, memLimitFromResources, limitFromDetailsBytes, cpu, disk, rx, tx, uptime);

                return new de.tubyoub.velocitypteropower.model.ServerResourceUsage(
                    state != null ? state : "unknown",
                    suspended,
                    mem,
                    chosenLimit,
                    cpu,
                    cpuLimitFromDetailsPercent,
                    disk,
                    diskLimitFromDetailsBytes,
                    rx,
                    tx,
                    uptime,
                    true
                );
            } catch (Exception e) {
                logger.error("Error fetching resources for {}: {}", serverId, e.toString());
                return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
            }
        });
    }

    private long extractLimitsMemoryMiB(String json) {
        try {
            int limitsIdx = json.indexOf("\"limits\"");
            int searchStart = limitsIdx >= 0 ? limitsIdx : 0;
            int memIdx = json.indexOf("\"memory\"", searchStart);
            if (memIdx == -1) return -1L;
            int colon = json.indexOf(':', memIdx);
            if (colon == -1) return -1L;
            int end = json.indexOf(',', colon + 1);
            String val = (end == -1 ? json.substring(colon + 1) : json.substring(colon + 1, end)).replaceAll("[^0-9-]", "").trim();
            if (val.isEmpty()) return -1L;
            return Long.parseLong(val);
        } catch (Exception e) {
            return -1L;
        }
    }

    private long extractLimitsDiskMiB(String json) {
        try {
            int limitsIdx = json.indexOf("\"limits\"");
            int searchStart = limitsIdx >= 0 ? limitsIdx : 0;
            int diskIdx = json.indexOf("\"disk\"", searchStart);
            if (diskIdx == -1) return -1L;
            int colon = json.indexOf(':', diskIdx);
            if (colon == -1) return -1L;
            int end = json.indexOf(',', colon + 1);
            String val = (end == -1 ? json.substring(colon + 1) : json.substring(colon + 1, end)).replaceAll("[^0-9-]", "").trim();
            if (val.isEmpty()) return -1L;
            return Long.parseLong(val);
        } catch (Exception e) {
            return -1L;
        }
    }

    private double extractLimitsCpuPercent(String json) {
        try {
            int limitsIdx = json.indexOf("\"limits\"");
            int searchStart = limitsIdx >= 0 ? limitsIdx : 0;
            int cpuIdx = json.indexOf("\"cpu\"", searchStart);
            if (cpuIdx == -1) return -1.0;
            int colon = json.indexOf(':', cpuIdx);
            if (colon == -1) return -1.0;
            int end = json.indexOf(',', colon + 1);
            String val = (end == -1 ? json.substring(colon + 1) : json.substring(colon + 1, end)).replaceAll("[^0-9.-]", "").trim();
            if (val.isEmpty()) return -1.0;
            return Double.parseDouble(val);
        } catch (Exception e) {
            return -1.0;
        }
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

    private long extractLong(String json, String key) {
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

    private double extractDouble(String json, String key) {
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

    /**
     * Sends a power signal to a Pterodactyl server.
     *
     * @param serverId The Pterodactyl server identifier (UUID).
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
        logger.debug("Fetching whitelist for server {} with ID {}", serverId, serverId);
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
        logger.debug(future.toString());
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
