package de.tubyoub.velocitypteropower.manager;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.updater.MergeRule;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

public class MessagesManager {
    private final VelocityPteroPower plugin;
    private final Logger logger;
    private YamlDocument messages;

    public MessagesManager(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void loadMessages() {
        try {
            messages = YamlDocument.create(new File(plugin.getDataDirectory().toFile(), "messages.yml"),
                    Objects.requireNonNull(getClass().getResourceAsStream("/messages.yml")),
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

        } catch (IOException e) {
            logger.error("Error creating/loading messages: " + e.getMessage());
        }
    }

    public String getMessage(String key) {
        return messages.getString(key, "Message not found: " + key);
    }
}