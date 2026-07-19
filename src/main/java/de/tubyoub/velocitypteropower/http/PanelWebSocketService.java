/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.http;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.service.ServerStateCache;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Subscribes to Pterodactyl/Pelican Wings websockets for live server state.
 * Falls back gracefully when unavailable; McServerSoft is unsupported.
 */
public class PanelWebSocketService {

    private final VelocityPteroPower plugin;
    private final ComponentLogger logger;
    private final ServerStateCache stateCache;
    private final HttpClient httpClient;
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PanelWebSocketService(VelocityPteroPower plugin, ServerStateCache stateCache) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.stateCache = stateCache;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void start() {
        if (!plugin.getConfigurationManager().isPanelWebSocketEnabled()) {
            logger.info("Panel WebSocket live state disabled by config.");
            return;
        }
        PanelType type = plugin.getConfigurationManager().getPanelType();
        if (type == PanelType.mcServerSoft || type == PanelType.error) {
            logger.info("Panel WebSocket not supported for panel type {}.", type);
            return;
        }
        running.set(true);
        scheduleRefresh();
        logger.info("Panel WebSocket live-state service started.");
    }

    public void stop() {
        running.set(false);
        for (WebSocket ws : sockets.values()) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join();
            } catch (Exception ignored) {}
        }
        sockets.clear();
        stateCache.clear();
    }

    private void scheduleRefresh() {
        if (!running.get()) return;
        plugin.getProxyServer().getScheduler()
                .buildTask(plugin, this::ensureSubscriptions)
                .delay(5, TimeUnit.SECONDS)
                .schedule();
    }

    private void ensureSubscriptions() {
        try {
            Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
            if (map == null) return;
            for (Map.Entry<String, PteroServerInfo> e : map.entrySet()) {
                String serverId = e.getValue().getServerId();
                if (serverId == null || sockets.containsKey(serverId)) continue;
                trySubscribe(e.getKey(), serverId);
            }
        } catch (Exception ex) {
            logger.debug("WebSocket ensureSubscriptions error: {}", ex.toString());
        } finally {
            if (running.get()) {
                plugin.getProxyServer().getScheduler()
                        .buildTask(plugin, this::ensureSubscriptions)
                        .delay(Math.max(30, plugin.getConfigurationManager().getPanelWebSocketRefreshSeconds()), TimeUnit.SECONDS)
                        .schedule();
            }
        }
    }

    private void trySubscribe(String serverName, String serverId) {
        try {
            String base = plugin.getConfigurationManager().getPterodactylUrl();
            String apiKey = plugin.getConfigurationManager().getPterodactylApiKey();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "api/client/servers/" + serverId + "/websocket"))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            plugin.getRateLimitTracker().consumeOne();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            plugin.getRateLimitTracker().updateRateLimitInfo(resp);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                logger.debug("WebSocket token fetch for {} failed: HTTP {}", serverName, resp.statusCode());
                return;
            }
            String body = resp.body();
            String token = extractJsonString(body, "token");
            String socket = extractJsonString(body, "socket");
            if (token == null || socket == null) {
                logger.debug("WebSocket credentials missing in response for {}.", serverName);
                return;
            }
            WebSocket.Listener listener = new WebSocket.Listener() {
                private final StringBuilder buf = new StringBuilder();

                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                    webSocket.sendText("{\"event\":\"auth\",\"args\":[\"" + token + "\"]}", true);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    buf.append(data);
                    if (last) {
                        handleMessage(serverId, buf.toString());
                        buf.setLength(0);
                    }
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    sockets.remove(serverId, webSocket);
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    logger.debug("WebSocket error for {}: {}", serverName, error.toString());
                    sockets.remove(serverId, webSocket);
                }
            };
            WebSocket ws = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(socket), listener)
                    .join();
            sockets.put(serverId, ws);
            logger.debug("Subscribed to Wings websocket for {} ({})", serverName, serverId);
        } catch (Exception ex) {
            plugin.getRateLimitTracker().restoreOne();
            logger.debug("Failed to subscribe websocket for {}: {}", serverName, ex.toString());
        }
    }

    private void handleMessage(String serverId, String message) {
        try {
            String event = extractJsonString(message, "event");
            if (event == null) return;
            if ("status".equalsIgnoreCase(event) || "auth success".equalsIgnoreCase(event)) {
                // args often contain state string
                String lower = message.toLowerCase();
                if (lower.contains("running")) {
                    stateCache.put(serverId, ServerStateCache.State.RUNNING);
                } else if (lower.contains("starting")) {
                    stateCache.put(serverId, ServerStateCache.State.STARTING);
                } else if (lower.contains("stopping")) {
                    stateCache.put(serverId, ServerStateCache.State.STOPPING);
                } else if (lower.contains("offline") || lower.contains("installing")) {
                    stateCache.put(serverId, ServerStateCache.State.OFFLINE);
                }
            } else if (message.toLowerCase().contains("\"running\"")) {
                stateCache.put(serverId, ServerStateCache.State.RUNNING);
            }
        } catch (Exception ignored) {}
    }

    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }
}
