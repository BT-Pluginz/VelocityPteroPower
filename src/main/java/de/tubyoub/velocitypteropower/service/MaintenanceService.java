/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.service;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Global and per-server maintenance mode that blocks cold starts.
 */
public class MaintenanceService {

    private final VelocityPteroPower plugin;
    private final ComponentLogger logger;
    private final Path file;
    private volatile boolean global;
    private final Set<String> servers = ConcurrentHashMap.newKeySet();

    public MaintenanceService(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.file = plugin.getDataDirectory().resolve("maintenance.json");
        load();
    }

    public boolean isGlobal() {
        return global;
    }

    public boolean isServerInMaintenance(String serverName) {
        if (global) return true;
        if (serverName == null) return false;
        return servers.stream().anyMatch(s -> s.equalsIgnoreCase(serverName));
    }

    public Set<String> getServers() {
        return Collections.unmodifiableSet(servers);
    }

    public void setGlobal(boolean enabled) {
        this.global = enabled;
        save();
        logger.info("Global maintenance mode {}.", enabled ? "ENABLED" : "DISABLED");
    }

    public void setServer(String serverName, boolean enabled) {
        if (enabled) {
            servers.add(serverName);
        } else {
            servers.removeIf(s -> s.equalsIgnoreCase(serverName));
        }
        save();
        logger.info("Maintenance for '{}' {}.", serverName, enabled ? "ENABLED" : "DISABLED");
    }

    public String detailFor(String serverName) {
        if (global) return " (network)";
        if (serverName != null && isServerInMaintenance(serverName)) return " (" + serverName + ")";
        return "";
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file);
            global = json.contains("\"global\":true") || json.contains("\"global\": true");
            // naive parse of servers array
            int idx = json.indexOf("\"servers\"");
            if (idx >= 0) {
                int start = json.indexOf('[', idx);
                int end = json.indexOf(']', start);
                if (start >= 0 && end > start) {
                    String arr = json.substring(start + 1, end);
                    for (String part : arr.split(",")) {
                        String s = part.trim().replace("\"", "");
                        if (!s.isBlank()) servers.add(s);
                    }
                }
            }
            logger.info(
                    "Maintenance state loaded (global={}, servers={}).",
                    global,
                    servers.stream().sorted().collect(Collectors.joining(", ")));
        } catch (Exception ex) {
            logger.warn("Failed to load maintenance.json: {}", ex.toString());
        }
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"global\": ").append(global).append(",\n  \"servers\": [");
            boolean first = true;
            for (String s : servers) {
                if (!first) sb.append(", ");
                sb.append("\"").append(s.replace("\"", "\\\"")).append("\"");
                first = false;
            }
            sb.append("]\n}\n");
            Files.writeString(file, sb.toString());
        } catch (IOException ex) {
            logger.warn("Failed to save maintenance.json: {}", ex.toString());
        }
    }
}
