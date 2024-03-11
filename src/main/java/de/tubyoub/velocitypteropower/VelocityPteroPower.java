package de.tubyoub.velocitypteropower;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.route.Route;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import org.yaml.snakeyaml.Yaml;

import com.google.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

@Plugin(id = "velocity-ptero-power", name = "VelocityPteroPower", version = "1.0", authors = {"TubYoub"})
public class VelocityPteroPower {
    private final ProxyServer proxyServer;
    private final Logger logger;
    private Path dataDirectory;
    private YamlDocument config;
    private String pterodactylUrl;
    private String pterodactylApiKey;
    private boolean checkUpdate;
    private int startTimeout;
    private int startupJoinTimeout;
    private int startupJoinDelay;
    private int startupJoinPingInterval;
    private Map<String, PteroServerInfo> serverInfoMap;
    private final CommandManager commandManager;

    @Inject
    public VelocityPteroPower(ProxyServer proxy, @DataDirectory Path dataDirectory,CommandManager commandManager,Logger logger) {
        this.proxyServer = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.serverInfoMap = new HashMap<>();
        this.commandManager = commandManager;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        commandManager.register("ptero", new PteroCommand(proxyServer, this));
        loadConfiguration();
        logger.info("VelocityPteroPower succesfully loaded");
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getOriginalServer().getServerInfo().getName();
        PteroServerInfo serverInfo = serverInfoMap.get(serverName);


        if (!serverInfoMap.containsKey(serverName)) {
            logger.warn("Server '" + serverName + "' not found in configuration.");
            return;
        }


        if (isServerOnline(serverInfo.getServerId())) {
            logger.info("Server '" + serverName + "' is already online, canceling connection attempt.");
            return;
        }
        if(!isServerOnline(serverInfo.getServerId())){
            powerServer(serverInfo.getServerId(), "start");
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }


        proxyServer.getScheduler().buildTask(this, () -> {
            connectPlayer(player, serverName);
        }).delay(startupJoinDelay+startupJoinTimeout, TimeUnit.SECONDS).schedule();
    }

        void loadConfiguration() {
        try {
            config = YamlDocument.create(new File(this.dataDirectory.toFile(), "config.yml"),
                    Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.DEFAULT,
                    UpdaterSettings.builder().setVersioning(new BasicVersioning("fileversion"))
                            .setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS).build());


            checkUpdate = (boolean) config.get("checkUpdate");
            startTimeout = (int) config.get("startTimeout");

            Section startupJoinSection = config.getSection("startupJoin");
            Map<String, Object> startupJoin = new HashMap<>();
            if (startupJoinSection != null) {
                for (Object keyObj : startupJoinSection.getKeys()) {
                    String key = (String) keyObj;
                    Route route = Route.fromString(key);
                    Object value = startupJoinSection.get(route);
                    startupJoin.put(key, value);
                }
            }
            startupJoinTimeout = (int) startupJoin.get("timeout");
            startupJoinDelay = (int) startupJoin.get("joinDelay");
            startupJoinPingInterval = (int) startupJoin.get("pingInterval");

            Section pterodactylSection = config.getSection("pterodactyl");
            Map<String, Object> pterodactyl = new HashMap<>();
            if (pterodactylSection != null) {
                for (Object keyObj : pterodactylSection.getKeys()) {
                    String key = (String) keyObj;
                    Route route = Route.fromString(key);
                    Object value = pterodactylSection.get(route);
                    pterodactyl.put(key, value);
                }
            }
            pterodactylUrl = (String) pterodactyl.get("url");
            if (!pterodactylUrl.endsWith("/")) {
                pterodactylUrl += "/";
            }
            pterodactylApiKey = (String) pterodactyl.get("apiKey");


            Section serversSection = config.getSection("servers");
            if (serversSection != null) {
                serverInfoMap = processServerSection(serversSection);
            } else {
                logger.error("Servers section not found in configuration.");
            }
        } catch (IOException e) {
            logger.error("Error creating/loading configuration: " + e.getMessage());
        }
    }

    private Map<String, PteroServerInfo> processServerSection(Section serversSection) {
        Map<String, PteroServerInfo> serverInfoMap = new HashMap<>();
        for (Object keyObj : serversSection.getKeys()) {
            String key = (String) keyObj;
            Route route = Route.fromString(key);
            Object serverInfoDataObj = serversSection.get(route);
            if (serverInfoDataObj instanceof Section) {
                Section serverInfoDataSection = (Section) serverInfoDataObj;
                Map<String, Object> serverInfoData = new HashMap<>();
                for (Object dataKeyObj : serverInfoDataSection.getKeys()) {
                    String dataKey = (String) dataKeyObj;
                    Route dataRoute = Route.fromString(dataKey);
                    Object value = serverInfoDataSection.get(dataRoute);
                    serverInfoData.put(dataKey, value);
                }
                try {
                    String id = (String) serverInfoData.get("id");
                    int timeout = (int) serverInfoData.getOrDefault("timeout", -1);
                    serverInfoMap.put(key, new PteroServerInfo(id, timeout, startupJoinDelay));
                    logger.info("Registered Server: " + id + " successfully");
                } catch (Exception e) {
                    logger.warn("Error processing server '" + key + "': " + e.getMessage());
                }
            }
        }
        return serverInfoMap;
    }
    private Map<String, PteroServerInfo> processServerMap(Map<String, Object> serversMap) {
        Map<String, PteroServerInfo> serverInfoMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : serversMap.entrySet()) {
            String serverName = entry.getKey();
            Map<String, Object> serverInfoData = (Map<String, Object>) entry.getValue();
            if (!(serverInfoData instanceof Map)) {
                throw new RuntimeException("Unexpected value type for key '" + serverName + "': " + serverInfoData.getClass().getName()); // Removed unnecessary variable
            }
            String id = (String) serverInfoData.get("id");
            int timeout = (int) serverInfoData.getOrDefault("timeout", -1);
            PteroServerInfo serverInfo = new PteroServerInfo(id, timeout, startupJoinDelay);
            serverInfoMap.put(serverName, serverInfo);
        }
        return serverInfoMap;
    }

    private void loadServerInfo() {
        if (config != null) {
            Map<String, Object> servers = (Map<String, Object>) config.getMapList("servers");
            if (servers != null) {
                for (Map.Entry<String, Object> entry : servers.entrySet()) {
                    String serverName = entry.getKey();
                    Map<String, Object> serverInfo = (Map<String, Object>) entry.getValue();
                    int timeout = (int) serverInfo.getOrDefault("timeout", -1);
                    if (timeout != -1) {
                        scheduleServerShutdown(serverName, timeout);
                    }
                }
            }
        }
    }

    private void scheduleServerShutdown(String serverName, int timeout) {
        proxyServer.getScheduler().buildTask(this, () -> {
            if (isServerEmpty(serverName)) {
                powerServer(serverName, "stop");
            }
        }).delay(timeout, TimeUnit.SECONDS).schedule();
    }

    private Map<String, Object> readConfigFile() {
        Path configFile = Path.of("plugins", "VelocityPteroPower", "config.yml");
        try (InputStream inputStream = Files.newInputStream(configFile)) {
            Yaml yaml = new Yaml();
            return yaml.load(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean isServerOnline(String serverId) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pterodactylUrl + "api/client/servers/" + serverId + "/resources"))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {

                String responseBody = response.body();
                return responseBody.contains("\"current_state\": \"running\"");
            } else {
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public void powerServer(String serverId, String signal) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pterodactylUrl + "api/client/servers/" + serverId + "/power"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + pterodactylApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"signal\": \"" + signal + "\"}"))
                    .build();

            client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("Error powering server.", e);
        }
    }

    private boolean isServerEmpty(String serverId) {
        Optional<RegisteredServer> server = proxyServer.getServer(serverId);
        return server.map(value -> value.getPlayersConnected().isEmpty()).orElse(true);
    }

    private void connectPlayer(Player player, String serverName) {
        RegisteredServer server = proxyServer.getServer(serverName).orElseThrow(() -> new RuntimeException("Server not found: " + serverName));

        if (isServerOnline(serverInfoMap.get(serverName).getServerId())) {
            player.createConnectionRequest(server).fireAndForget();
        } else {
            //player.sendMessage(Component.text("Server is currently offline."));
        }
    }

    public Map<String, PteroServerInfo> getServerInfoMap() {
        return serverInfoMap;
    }
}
