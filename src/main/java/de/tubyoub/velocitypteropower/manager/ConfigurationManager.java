/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.manager;

import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelType;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.route.Route;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.MergeRule;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * This class manages the configuration for the VelocityPteroPower plugin.
 * It loads the configuration from a YAML file and provides methods to access the configuration values.
 */

public class ConfigurationManager {

    public enum ServerCheckMethod {
        VELOCITY_PING,
        PANEL_API,
        HYBRID
    }

    public enum StartupTimeoutPolicy {
        CANCEL,
        KEEP_STARTING
    }

    public enum ForcedHostOfflineBehavior {
        DISCONNECT,
        LOBBY_OR_LIMBO,
        LIMBO_ONLY
    }

    private Path dataDirectory;
    private YamlDocument config;
    private String panelUrl;
    private String apiKey;
    private PanelType panel;
    private boolean checkUpdate;
    private boolean printRateLimit;
    private boolean serverNotFoundMessage;
    private boolean whitelistAllowBypass;
    private String languageOverride;
    private int loggerLevel;
    private int apiThreads;
    private int pingTimeout;
    private int shutdownRetryDelay;
    private int shutdownRetries;
    private int idleStartShutdownTime;
    private int playerCommandCooldown;
    private int startupInitialCheckDelay;
    private int startupPollInterval;
    private int startupTimeoutSeconds;
    private StartupTimeoutPolicy startupTimeoutPolicy = StartupTimeoutPolicy.CANCEL;
    private int softRequeueGraceSeconds;
    private int softRequeueMaxAttempts;
    private int rateLimitBackgroundPauseThreshold;
    private int rateLimitProbeFallbackSeconds;
    private int persistentQueueTtlSeconds;
    private boolean persistentQueueEnabled;
    private boolean panelWebSocketEnabled;
    private int panelWebSocketRefreshSeconds;
    private int serverStateCacheTtlSeconds;
    private int maxOnlineReservations;
    private boolean queueWhenMaxOnline;
    private int balancerScaleDownIdleSeconds;
    private boolean balancerScaleDownEnabled;
    private int whitelistCheckInterval;
    private ServerCheckMethod serverCheckMethod;
    private List<String> stopAllIgnoreList;
    private int maxOnlineServers;
    private boolean maxOnlineAllowBypass;
    private List<String> maxOnlineExemptList;
    private boolean countLobbiesInMaxOnline;
    private boolean countLimbosInMaxOnline;
    private List<String> alwaysOnlineList;
    private int alwaysOnlineCheckInterval;
    private int resourceCacheSeconds;
    private boolean resourcePrefetchEnabled;
    private int idleShutdownCheckInterval;

    // Move history
    private boolean moveHistoryEnabled;
    private int moveHistoryMaxEntries;

    // Lifecycle
    private List<String> shutdownOnProxyExitList;

    // Lobby/Limbo balancing configuration
    private List<String> balancerLobbies;
    private List<String> balancerLimbos;
    private int balancerLobbiesToUse;
    private String balancerStrategyName;
    private int balancerHealthCheckInterval;
    private boolean balancerAutoScaleEnabled;
    private int balancerMinOnline;
    private int balancerMaxOnline;
    private int balancerPlayersPerServer;
    private double balancerCpuScaleUpThreshold;
    private int balancerPreStartThresholdPercent;
    private int balancerStartFailureFallbackSeconds;
    private int balancerStartFailureCooldownSeconds;
    private boolean sendToLimboOnStart;
    private ForcedHostOfflineBehavior forcedHostOfflineBehavior = ForcedHostOfflineBehavior.DISCONNECT;
    private boolean builtinLobbyBalancerEnabled = true;

    private final VelocityPteroPower plugin;
    private final Logger logger;
    private Map<String, PteroServerInfo> serverInfoMap;

     /**
     * Constructor for the ConfigurationManager class.
     *
     * @param plugin the VelocityPteroPower plugin instance
     */
    public ConfigurationManager(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.dataDirectory = plugin.getDataDirectory();
    }

    /**
     * This method loads the configuration from a YAML file.
     * It reads the configuration values and stores them in instance variables.
     */
    public void loadConfig(){
        try{
            config = YamlDocument.create(new File(this.dataDirectory.toFile(), "config.yml"),
                                Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
                                GeneralSettings.DEFAULT,
                                LoaderSettings.builder().setAutoUpdate(true).build(),
                                DumperSettings.DEFAULT,
                                UpdaterSettings.builder().setVersioning(new BasicVersioning("fileversion"))
                                        .setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS)
                                        .setMergeRule(MergeRule.MAPPINGS, true)
                                        .setMergeRule(MergeRule.MAPPING_AT_SECTION, false)
                                        .setMergeRule(MergeRule.SECTION_AT_MAPPING, false)
                                        .addIgnoredRoute("5", "servers", '.')
                                        .addIgnoredRoute("6", "servers", '.')
                                        .addIgnoredRoute("7", "servers", '.')
                                        .addIgnoredRoute("8", "servers", '.')
                                        .addIgnoredRoute("9", "servers", '.')
                                        .addIgnoredRoute("10", "servers", '.')
                                        .addIgnoredRoute("11", "servers", '.')
                                        .addIgnoredRoute("12", "servers", '.')
                                        .addIgnoredRoute("13", "servers", '.')
                                        .addIgnoredRoute("14", "servers", '.')
                                        .addIgnoredRoute("15", "servers", '.')
                                        .addIgnoredRoute("15", "profiles", '.')
                                        .build());


            checkUpdate = (boolean) config.get("checkUpdate", true);
            printRateLimit = (boolean) config.get("printRateLimit", false);
            serverNotFoundMessage = (boolean) config.get("serverNotFoundMessage", false);
            whitelistAllowBypass = (boolean) config.get("whitelistAllowBypass", true);
            languageOverride = config.getString("languageOverride", "auto");

            loggerLevel = (int) config.get("loggerLevel", 20);
            pingTimeout = (int) config.get("pingTimeout", 1000);
            apiThreads = (int) config.get("apiThreads", 10);
            shutdownRetryDelay = (int) config.get("shutdownRetryDelay", 30);
            shutdownRetries = (int) config.get("shutdownRetries", 3);
            idleStartShutdownTime = (int) config.get("idleStartShutdownTime", 300);
            playerCommandCooldown = (int) config.get("playerStartCooldown", 10);
            startupInitialCheckDelay = (int) config.get("startupInitialCheckDelay", 10);
            startupPollInterval = (int) config.get("startupPollInterval", 5);
            startupTimeoutSeconds = (int) config.get("startupTimeoutSeconds", 180);
            softRequeueGraceSeconds = (int) config.get("softRequeueGraceSeconds", 30);
            softRequeueMaxAttempts = (int) config.get("softRequeueMaxAttempts", 3);
            rateLimitBackgroundPauseThreshold = (int) config.get("rateLimitBackgroundPauseThreshold", 5);
            rateLimitProbeFallbackSeconds = (int) config.get("rateLimitProbeFallbackSeconds", 60);
            persistentQueueEnabled = (boolean) config.get("persistentQueueEnabled", true);
            persistentQueueTtlSeconds = (int) config.get("persistentQueueTtlSeconds", 900);
            panelWebSocketEnabled = (boolean) config.get("panelWebSocketEnabled", true);
            panelWebSocketRefreshSeconds = (int) config.get("panelWebSocketRefreshSeconds", 120);
            serverStateCacheTtlSeconds = (int) config.get("serverStateCacheTtlSeconds", 30);
            maxOnlineReservations = (int) config.get("maxOnlineReservations", 0);
            queueWhenMaxOnline = (boolean) config.get("queueWhenMaxOnline", true);
            String timeoutPolicyStr = config.getString("startupTimeoutPolicy", "CANCEL");
            try {
                startupTimeoutPolicy = StartupTimeoutPolicy.valueOf(timeoutPolicyStr.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid startupTimeoutPolicy '{}'. Using CANCEL.", timeoutPolicyStr);
                startupTimeoutPolicy = StartupTimeoutPolicy.CANCEL;
            }
            whitelistCheckInterval = (int) config.get("whitelistCheckInterval", 10);
            maxOnlineServers = (int) config.get("maxOnlineServers", 0);
            maxOnlineAllowBypass = (boolean) config.get("maxOnlineAllowBypass", true);
            maxOnlineExemptList = config.getStringList("maxOnlineExempt");
            countLobbiesInMaxOnline = (boolean) config.get("countLobbiesInMaxOnline", false);
            countLimbosInMaxOnline = (boolean) config.get("countLimbosInMaxOnline", false);
            alwaysOnlineList = config.getStringList("alwaysOnline");
            alwaysOnlineCheckInterval = (int) config.get("alwaysOnlineCheckInterval", 60);
            resourceCacheSeconds = (int) config.get("resourceCacheSeconds", 10);
            resourcePrefetchEnabled = (boolean) config.get("resourcePrefetchEnabled", true);
            idleShutdownCheckInterval = (int) config.get("idleShutdownCheckInterval", 60);

            // Move history section
            Section mh = config.getSection("moveHistory");
            if (mh != null) {
                moveHistoryEnabled = mh.getBoolean("enabled", false);
                moveHistoryMaxEntries = mh.getInt("maxEntriesPerPlayer", 50);
            } else {
                moveHistoryEnabled = false;
                moveHistoryMaxEntries = 50;
            }

            // Lifecycle section
            Section lifecycle = config.getSection("lifecycle");
            if (lifecycle != null) {
                shutdownOnProxyExitList = lifecycle.getStringList("shutdownOnProxyExit");
            } else {
                shutdownOnProxyExitList = java.util.Collections.emptyList();
            }

            // Lobby/Limbo balancer section (all optional, sensible defaults)
            Section lb = config.getSection("lobbyBalancer");
            if (lb != null) {
                balancerLobbies = lb.getStringList("lobbies");
                balancerLimbos = lb.getStringList("limbos");
                balancerLobbiesToUse = lb.getInt("lobbiesToUse", 0);
                String strat = lb.getString("strategy", "ROUND_ROBIN");
                balancerStrategyName = strat != null ? strat : "ROUND_ROBIN";
                balancerHealthCheckInterval = lb.getInt("healthCheckInterval", 15);
                balancerAutoScaleEnabled = lb.getBoolean("autoScaleEnabled", true);
                balancerMinOnline = lb.getInt("minOnline", 1);
                balancerMaxOnline = lb.getInt("maxOnline", 0);
                balancerPlayersPerServer = lb.getInt("playersPerServer", 80);
                try {
                    balancerCpuScaleUpThreshold = lb.getDouble("cpuScaleUpThreshold", 85.0);
                } catch (Exception ex) {
                    balancerCpuScaleUpThreshold = 85.0;
                }
                balancerPreStartThresholdPercent = lb.getInt("preStartThresholdPercent", 80);
                balancerStartFailureFallbackSeconds = lb.getInt("startFailureFallbackSeconds", 60);
                balancerStartFailureCooldownSeconds = lb.getInt("startFailureCooldownSeconds", 120);
                sendToLimboOnStart = lb.getBoolean("sendToLimboOnStart", false);
                balancerScaleDownEnabled = lb.getBoolean("scaleDownEnabled", true);
                balancerScaleDownIdleSeconds = lb.getInt("scaleDownIdleSeconds", 300);
                String fho = lb.getString("forcedHostOfflineBehavior", "DISCONNECT");
                try {
                    forcedHostOfflineBehavior = ForcedHostOfflineBehavior.valueOf(fho.toUpperCase(Locale.ROOT));
                } catch (Exception ex) {
                    forcedHostOfflineBehavior = ForcedHostOfflineBehavior.LOBBY_OR_LIMBO;
                    logger.warn("Invalid forcedHostOfflineBehavior '{}' in config. Defaulting to LOBBY_OR_LIMBO.", fho);
                }
                builtinLobbyBalancerEnabled = lb.getBoolean("enableBuiltin", true);
            } else {
                // defaults
                balancerLobbies = Collections.emptyList();
                balancerLimbos = Collections.emptyList();
                balancerLobbiesToUse = 0;
                balancerStrategyName = "ROUND_ROBIN";
                balancerHealthCheckInterval = 15;
                balancerAutoScaleEnabled = true;
                balancerMinOnline = 1;
                balancerMaxOnline = 0;
                balancerPlayersPerServer = 80;
                balancerCpuScaleUpThreshold = 85.0;
                balancerPreStartThresholdPercent = 80;
                balancerStartFailureFallbackSeconds = 60;
                balancerStartFailureCooldownSeconds = 120;
                sendToLimboOnStart = false;
                balancerScaleDownEnabled = true;
                balancerScaleDownIdleSeconds = 300;
                forcedHostOfflineBehavior = ForcedHostOfflineBehavior.LOBBY_OR_LIMBO;
                builtinLobbyBalancerEnabled = true;
            }

            // Migrate legacy 'limboServer' to 'lobbyBalancer.limbos'
            try {
                String legacyLimbo = config.getString("limboServer");
                if (legacyLimbo != null && !legacyLimbo.isBlank() && !"changeMe".equalsIgnoreCase(legacyLimbo)) {
                    Section lbSec = config.getSection("lobbyBalancer");
                    if (lbSec == null) {
                        config.set("lobbyBalancer.limbos", java.util.List.of(legacyLimbo));
                    } else {
                        java.util.List<String> limbos = lbSec.getStringList("limbos");
                        if (limbos == null) limbos = new java.util.ArrayList<>();
                        boolean exists = false;
                        for (String s : limbos) { if (s.equalsIgnoreCase(legacyLimbo)) { exists = true; break; } }
                        if (!exists) limbos.add(0, legacyLimbo);
                        config.set("lobbyBalancer.limbos", limbos);
                    }
                    config.remove(Route.fromString("limboServer"));
                    config.save();
                    logger.info("Migrated legacy 'limboServer' to 'lobbyBalancer.limbos' and removed the old key.");
                }
            } catch (Exception ignored) {}

            stopAllIgnoreList = config.getStringList("stopIdleIgnore");

            String checkMethodStr = config.getString("serverStatusCheckMethod", "VELOCITY_PING");
            try {
                this.serverCheckMethod = ServerCheckMethod.valueOf(checkMethodStr.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                logger.error("Invalid serverStatusCheckMethod '{}' in config. Using default 'VELOCITY_PING'.", checkMethodStr);
                this.serverCheckMethod = ServerCheckMethod.VELOCITY_PING;
            }

            Section pterodactylSection = config.getSection("pterodactyl");
            Map<String, Object> pterodactyl = new HashMap<>();
            if (pterodactylSection != null) {
                for (Object keyObj : pterodactylSection.getKeys()) {
                    String key = String.valueOf(keyObj);
                    Route route = Route.fromString(key);
                    Object value = pterodactylSection.get(route);
                    pterodactyl.put(key, value);
                }
            }
            panelUrl = (String) pterodactyl.get("url");
            if (panelUrl != null && !panelUrl.endsWith("/")) {
                panelUrl += "/";
            }
            Object apiKeyObj = pterodactyl.get("apiKey");
            apiKey = coerceToString(apiKeyObj);
            if (apiKeyObj != null && !(apiKeyObj instanceof String)) {
                config.set("pterodactyl.apiKey", apiKey);
                config.save();
                logger.info("Coerced pterodactyl.apiKey to string and saved config (quote API keys in YAML).");
            }
            panel = detectPanelType(apiKey);


            Section serversSection = config.getSection("servers");
                if (serversSection != null) {
                    boolean reloading = this.serverInfoMap != null && !this.serverInfoMap.isEmpty();
                    serverInfoMap = processServerSection(serversSection, reloading);
                    validateBalancerEntries();
                } else {
                    logger.error("Servers section not found in configuration.");
                }
                } catch (IOException e) {
                    logger.error("Error creating/loading configuration: " + e.getMessage());
                }
            }

    /**
     * This method processes the server section of the configuration.
     * It creates a map of server names to PteroServerInfo objects.
     *
     * @param serversSection the server section of the configuration
     * @return a map of server names to PteroServerInfo objects
     */
    public Map<String, PteroServerInfo> processServerSection(Section serversSection) {
        return processServerSection(serversSection, false);
    }

    public Map<String, PteroServerInfo> processServerSection(Section serversSection, boolean reloading) {
            Map<String, PteroServerInfo> serverInfoMap = new HashMap<>();
            for (Object keyObj : serversSection.getKeys()) {
                String key = String.valueOf(keyObj);
                Route route = Route.fromString(key);
                Object serverInfoDataObj = serversSection.get(route);
                if (serverInfoDataObj instanceof Section) {
                    Section serverInfoDataSection = (Section) serverInfoDataObj;
                    try {
                        Object idObj = serverInfoDataSection.get("id");
                        if (idObj == null) {
                            throw new IllegalArgumentException("Missing 'id' for server '" + key + "'");
                        }

                        if (!(idObj instanceof String)) {
                            // YAML likely parsed an unquoted ID (e.g., 91e62747) as a number (scientific notation),
                            // which can overflow to Infinity. We cannot recover the original text.
                            throw new IllegalArgumentException("Invalid type for 'id' (" + idObj.getClass().getSimpleName() + ") at path 'servers." + key + ".id'. YAML parsed an unquoted value as a number. Edit your config and quote the ID, e.g.: id: \"91e62747\" then restart/reload.");
                        }

                        String id = ((String) idObj).trim();
                        if (id.equalsIgnoreCase("infinity") || id.equalsIgnoreCase("nan") || id.isEmpty()) {
                            throw new IllegalArgumentException("Invalid 'id' value '" + id + "' for server '" + key + "'. Quote the ID in YAML, e.g.: id: \"91e62747\"");
                        }

                        // Basic sanity check: IDs are typically short alphanumeric (pterodactyl short uuid)
                        if (!id.matches("^[A-Za-z0-9_-]{4,64}$")) {
                            logger.warn("Suspicious server id '{}' for server '{}'. Expected alphanumeric/underscore/dash. Continuing anyway.", id, key);
                        }

                        if (!Objects.equals(id, "1234abcd")){
                            int timeout = serverInfoDataSection.getInt("timeout", -1);
                            int startupJoinDelay = serverInfoDataSection.getInt("startupJoinDelay", 10);
                            boolean whitelist = serverInfoDataSection.getBoolean("whitelist", false);
                            Integer pollOverride = serverInfoDataSection.contains("startupPollInterval")
                                    ? serverInfoDataSection.getInt("startupPollInterval")
                                    : null;
                            Integer timeoutOverride = serverInfoDataSection.contains("startupTimeoutSeconds")
                                    ? serverInfoDataSection.getInt("startupTimeoutSeconds")
                                    : null;
                            ServerCheckMethod methodOverride = null;
                            if (serverInfoDataSection.contains("serverStatusCheckMethod")) {
                                try {
                                    methodOverride = ServerCheckMethod.valueOf(
                                            serverInfoDataSection.getString("serverStatusCheckMethod").toUpperCase(Locale.ROOT));
                                } catch (Exception ignored) {}
                            }
                            String profile = serverInfoDataSection.contains("profile")
                                    ? serverInfoDataSection.getString("profile")
                                    : null;

                            // Apply profile defaults when present (field overrides win)
                            if (profile != null && !profile.isBlank() && config != null) {
                                Section profiles = config.getSection("profiles");
                                if (profiles != null) {
                                    Section profileSec = profiles.getSection(profile);
                                    if (profileSec != null) {
                                        if (!serverInfoDataSection.contains("timeout") && profileSec.contains("timeout")) {
                                            timeout = profileSec.getInt("timeout");
                                        }
                                        if (!serverInfoDataSection.contains("startupJoinDelay") && profileSec.contains("startupJoinDelay")) {
                                            startupJoinDelay = profileSec.getInt("startupJoinDelay");
                                        }
                                        if (!serverInfoDataSection.contains("whitelist") && profileSec.contains("whitelist")) {
                                            whitelist = profileSec.getBoolean("whitelist");
                                        }
                                        if (pollOverride == null && profileSec.contains("startupPollInterval")) {
                                            pollOverride = profileSec.getInt("startupPollInterval");
                                        }
                                        if (timeoutOverride == null && profileSec.contains("startupTimeoutSeconds")) {
                                            timeoutOverride = profileSec.getInt("startupTimeoutSeconds");
                                        }
                                        if (methodOverride == null && profileSec.contains("serverStatusCheckMethod")) {
                                            try {
                                                methodOverride = ServerCheckMethod.valueOf(
                                                        profileSec.getString("serverStatusCheckMethod").toUpperCase(Locale.ROOT));
                                            } catch (Exception ignored) {}
                                        }
                                    }
                                }
                            }

                            serverInfoMap.put(key, new PteroServerInfo(
                                    id, timeout, startupJoinDelay, whitelist,
                                    pollOverride, timeoutOverride, methodOverride, profile));
                            if (reloading) {
                                logger.debug("Registered Server: {} successfully", id);
                            } else {
                                logger.info("Registered Server: " + id + " successfully");
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Error processing server '" + key + "': " + e.getMessage());
                    }
                }
            }
            return serverInfoMap;
        }

    private String coerceToString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s.trim();
        }
        return String.valueOf(value).trim();
    }

    private void validateBalancerEntries() {
        if (serverInfoMap == null) {
            return;
        }
        java.util.Set<String> managed = serverInfoMap.keySet();
        validateBalancerList(balancerLobbies, "lobbies", managed);
        validateBalancerList(balancerLimbos, "limbos", managed);
    }

    private void validateBalancerList(List<String> entries, String section, java.util.Set<String> managed) {
        if (entries == null) {
            return;
        }
        for (String name : entries) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (!managed.contains(name)) {
                logger.warn(
                    "lobbyBalancer.{} entry '{}' is not configured under servers (no panel id). It will be ignored.",
                    section,
                    name);
            }
        }
    }

    private PanelType detectPanelType(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
          return PanelType.pterodactyl;
        }

        String prefix = apiKey.split("_")[0];
        return switch (prefix) {
            case "ptlc" -> {
                logger.debug("Detected pterodactyl panel from apiKey");
                yield PanelType.pterodactyl;
            }
            case "plcn", "pacc"-> {
                logger.debug("Detected pelican panel from apiKey");
                yield PanelType.pelican;
            }
            case "ptla", "peli" -> {
                logger.debug("Detected Application Api Key please change this to a client key");
                yield PanelType.error;
            }
            default -> {
                logger.info("API Key has no recognized prefix, assuming mcServerSoft.");
                yield PanelType.mcServerSoft;
            }
        };
    }

    /**
     * This method returns the map of server names to PteroServerInfo objects.
     *
     * @return the map of server names to PteroServerInfo objects
     */
    public Map<String, PteroServerInfo> getServerInfoMap() {
        return serverInfoMap;
    }

    // Balancer getters
    public List<String> getBalancerLobbies() { return balancerLobbies == null ? Collections.emptyList() : balancerLobbies; }
    public List<String> getBalancerLimbos() { return balancerLimbos == null ? Collections.emptyList() : balancerLimbos; }
    public int getBalancerLobbiesToUse() { return balancerLobbiesToUse; }
    public String getBalancerStrategyName() { return balancerStrategyName == null ? "ROUND_ROBIN" : balancerStrategyName; }
    public int getBalancerHealthCheckInterval() { return balancerHealthCheckInterval <= 0 ? 15 : balancerHealthCheckInterval; }
    public boolean isBalancerAutoScaleEnabled() { return balancerAutoScaleEnabled; }
    public int getBalancerMinOnline() { return Math.max(0, balancerMinOnline); }
    public int getBalancerMaxOnline() { return Math.max(0, balancerMaxOnline); }
    public int getBalancerPlayersPerServer() { return Math.max(1, balancerPlayersPerServer); }
    public double getBalancerCpuScaleUpThreshold() { return balancerCpuScaleUpThreshold <= 0 ? 85.0 : balancerCpuScaleUpThreshold; }
    public int getBalancerPreStartThresholdPercent() { return balancerPreStartThresholdPercent <= 0 ? 80 : Math.min(100, balancerPreStartThresholdPercent); }
    public int getBalancerStartFailureFallbackSeconds() { return balancerStartFailureFallbackSeconds <= 0 ? 60 : balancerStartFailureFallbackSeconds; }
    public int getBalancerStartFailureCooldownSeconds() { return balancerStartFailureCooldownSeconds <= 0 ? 120 : balancerStartFailureCooldownSeconds; }
    public boolean isSendToLimboOnStart() { return sendToLimboOnStart; }
    public ForcedHostOfflineBehavior getForcedHostOfflineBehavior() { return forcedHostOfflineBehavior; }
    public boolean isBuiltinLobbyBalancerEnabled() { return builtinLobbyBalancerEnabled; }

    /**
     * This method returns the Pterodactyl URL.
     *
     * @return the Pterodactyl URL
     */
    public String getPterodactylUrl() {
        return panelUrl;
    }

    /**
     * This method returns the Pterodactyl API key.
     *
     * @return the Pterodactyl API key
     */
    public String getPterodactylApiKey() {
        return apiKey;
    }

    /**
     * This method returns whether to check for updates.
     *
     * @return true if updates should be checked, false otherwise
     */
    public boolean isCheckUpdate() {
        return checkUpdate;
    }

    public boolean isServerNotFoundMessage() {
        return serverNotFoundMessage;
    }

    public boolean isWhitelistAllowBypass() {
        return whitelistAllowBypass;
    }

    public PanelType getPanelType(){
        return panel;
    }

    public Level getLoggerLevel() {
        try {
            return Level.intToLevel(loggerLevel);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid logger level: {}. Defaulting to INFO.", loggerLevel);
            return Level.INFO;
        }
    }

    public int getApiThreads() {
        return apiThreads;
    }

    public boolean isPrintRateLimit() {
        return printRateLimit;
    }

    public String getLanguageOverride() {
        return languageOverride;
    }

    public int getPingTimeout() {
        return pingTimeout;
    }

    public int getShutdownRetries() {
        return shutdownRetries;
    }

    public int getShutdownRetryDelay(){
        return shutdownRetryDelay;
    }

    public int getPlayerCommandCooldown() {
        return playerCommandCooldown;
    }
    public int getIdleStartShutdownTime(){
        return idleStartShutdownTime;
    }

    public  int getStartupInitialCheckDelay(){
        return startupInitialCheckDelay;
    }

    public int getStartupPollInterval() {
        return Math.max(1, startupPollInterval);
    }

    public int getStartupTimeoutSeconds() {
        return Math.max(30, startupTimeoutSeconds);
    }

    public StartupTimeoutPolicy getStartupTimeoutPolicy() {
        return startupTimeoutPolicy == null ? StartupTimeoutPolicy.CANCEL : startupTimeoutPolicy;
    }

    public int getSoftRequeueGraceSeconds() {
        return Math.max(0, softRequeueGraceSeconds);
    }

    public int getSoftRequeueMaxAttempts() {
        return Math.max(0, softRequeueMaxAttempts);
    }

    public int getRateLimitBackgroundPauseThreshold() {
        return Math.max(0, rateLimitBackgroundPauseThreshold);
    }

    public int getRateLimitProbeFallbackSeconds() {
        return Math.max(10, rateLimitProbeFallbackSeconds);
    }

    public boolean isPersistentQueueEnabled() {
        return persistentQueueEnabled;
    }

    public int getPersistentQueueTtlSeconds() {
        return Math.max(60, persistentQueueTtlSeconds);
    }

    public boolean isPanelWebSocketEnabled() {
        return panelWebSocketEnabled;
    }

    public int getPanelWebSocketRefreshSeconds() {
        return Math.max(30, panelWebSocketRefreshSeconds);
    }

    public int getServerStateCacheTtlSeconds() {
        return Math.max(5, serverStateCacheTtlSeconds);
    }

    public int getMaxOnlineReservations() {
        return Math.max(0, maxOnlineReservations);
    }

    public boolean isQueueWhenMaxOnline() {
        return queueWhenMaxOnline;
    }

    public boolean isBalancerScaleDownEnabled() {
        return balancerScaleDownEnabled;
    }

    public int getBalancerScaleDownIdleSeconds() {
        return Math.max(30, balancerScaleDownIdleSeconds);
    }

    /** Effective poll interval for a server (per-server override or global). */
    public int resolvePollInterval(PteroServerInfo info) {
        if (info != null && info.getPollIntervalSeconds() != null) {
            return Math.max(1, info.getPollIntervalSeconds());
        }
        return getStartupPollInterval();
    }

    /** Effective startup timeout for a server (per-server override or global). */
    public int resolveStartupTimeout(PteroServerInfo info) {
        if (info != null && info.getStartupTimeoutSeconds() != null) {
            return Math.max(30, info.getStartupTimeoutSeconds());
        }
        return getStartupTimeoutSeconds();
    }

    /** Effective check method for a server (per-server override or global). */
    public ServerCheckMethod resolveCheckMethod(PteroServerInfo info) {
        if (info != null && info.getCheckMethodOverride() != null) {
            return info.getCheckMethodOverride();
        }
        return getServerCheckMethod();
    }

    public int getWhitelistCheckInterval(){
        return whitelistCheckInterval;
    }


    public List<String> getStopAllIgnoreList() {
        return stopAllIgnoreList;
    }

    public ServerCheckMethod getServerCheckMethod() {
        return serverCheckMethod;
    }
    
    public int getMaxOnlineServers() {
        return maxOnlineServers;
    }

    public boolean isMaxOnlineAllowBypass() {
        return maxOnlineAllowBypass;
    }

    public java.util.List<String> getMaxOnlineExemptList() {
        return maxOnlineExemptList == null ? java.util.Collections.emptyList() : maxOnlineExemptList;
    }

    public boolean isCountLobbiesInMaxOnline() { return countLobbiesInMaxOnline; }
    public boolean isCountLimbosInMaxOnline() { return countLimbosInMaxOnline; }

    public java.util.List<String> getAlwaysOnlineList() {
        return alwaysOnlineList == null ? java.util.Collections.emptyList() : alwaysOnlineList;
    }

    public int getAlwaysOnlineCheckInterval() {
        return alwaysOnlineCheckInterval;
    }

    public int getResourceCacheSeconds() {
        return Math.max(0, resourceCacheSeconds);
    }

    public boolean isResourcePrefetchEnabled() {
        return resourcePrefetchEnabled;
    }

    public int getIdleShutdownCheckInterval() {
        return Math.max(0, idleShutdownCheckInterval);
    }

    public boolean isMoveHistoryEnabled() { return moveHistoryEnabled; }
    public int getMoveHistoryMaxEntries() { return Math.max(1, moveHistoryMaxEntries); }

    public java.util.List<String> getShutdownOnProxyExitList() {
        return shutdownOnProxyExitList == null ? java.util.Collections.emptyList() : shutdownOnProxyExitList;
    }
}