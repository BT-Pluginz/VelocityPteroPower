/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 *
 *  Copyright (c) TubYoub <github@tubyoub.de>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package de.tubyoub.velocitypteropower.manager;

import de.tubyoub.velocitypteropower.PteroServerInfo;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.api.PanelType;
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
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * This class manages the configuration for the VelocityPteroPower plugin.
 * It loads the configuration from a YAML file and provides methods to access the configuration values.
 */

public class ConfigurationManager {
    private Path dataDirectory;
    private YamlDocument config;
    private String panelUrl;
    private String apiKey;
    private PanelType panel;
    private boolean checkUpdate;
    private boolean printRateLimit;
    private int startupJoinDelay;
    private int apiThreads;
    private int pingTimeout;
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
        this.logger = plugin.getLogger();
        this.dataDirectory = plugin.getDataDirectory();
    }

    /**
     * This method loads the configuration from a YAML file.
     * It reads the configuration values and stores them in instance variables.
     */
    public void loadConfig(){
        try {
            config = YamlDocument.create(new File(this.dataDirectory.toFile(), "config.yml"),
                    Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.DEFAULT,
                    UpdaterSettings.builder().setVersioning(new BasicVersioning("fileversion"))
                            .setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS)
                            .setMergeRule(MergeRule.MAPPINGS, true)
                            .setMergeRule(MergeRule.MAPPING_AT_SECTION, true)
                            .setMergeRule(MergeRule.SECTION_AT_MAPPING, true)
                            .setKeepAll(true)
                            .build());


            checkUpdate = (boolean) config.get("checkUpdate");
            printRateLimit = (boolean) config.get("printRateLimit");
            pingTimeout = (int) config.get("pingTimeout");
            apiThreads = (int) config.get("apiThreads", 10);
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

            startupJoinDelay = (int) startupJoin.get("joinDelay");

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
            panelUrl = (String) pterodactyl.get("url");
            if (!panelUrl.endsWith("/")) {
                panelUrl += "/";
            }
            apiKey = (String) pterodactyl.get("apiKey");
            panel = detectPanelType(apiKey);


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

    /**
     * This method processes the server section of the configuration.
     * It creates a map of server names to PteroServerInfo objects.
     *
     * @param serversSection the server section of the configuration
     * @return a map of server names to PteroServerInfo objects
     */
    public Map<String, PteroServerInfo> processServerSection(Section serversSection) {
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
                        if (!Objects.equals(id, "1234abcd")){
                            int timeout = (int) serverInfoData.getOrDefault("timeout", -1);
                            serverInfoMap.put(key, new PteroServerInfo(id, timeout, getStartupJoinDelay()));
                            logger.info("Registered Server: " + id + " successfully");
                        }
                    } catch (Exception e) {
                        logger.warn("Error processing server '" + key + "': " + e.getMessage());
                    }
                }
            }
            return serverInfoMap;
        }

    private PanelType detectPanelType(String apiKey) {
        if (apiKey.startsWith("ptlc_")) {
            return PanelType.pterodactyl;
        } else if (apiKey.startsWith("peli_")) {
            return PanelType.pelican;
        } else {
            // Default to Pterodactyl if the prefix is not recognized
            return PanelType.pterodactyl;
        }
    }


    /**
     * This method returns the map of server names to PteroServerInfo objects.
     *
     * @return the map of server names to PteroServerInfo objects
     */
    public Map<String, PteroServerInfo> getServerInfoMap() {
        return serverInfoMap;
    }

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

    /**
     * This method returns the startup join delay.
     *
     * @return the startup join delay
     */
    public int getStartupJoinDelay() {
        return startupJoinDelay;
    }

    public PanelType getPanelType(){
        return panel;
    }

    public int getApiThreads() {
        return apiThreads;
    }

    public boolean isPrintRateLimit() {
        return printRateLimit;
    }

    public int getPingTimeout() {
        return pingTimeout;
    }
}