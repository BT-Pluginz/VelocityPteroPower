package de.tubyoub.velocitypteropower.api;

import java.util.concurrent.CompletableFuture;

public interface PanelAPIClient {
    void powerServer(String serverId, String signal);
    boolean isServerOnline(String serverId);
    boolean isServerEmpty(String serverName);
    void shutdown();
}
