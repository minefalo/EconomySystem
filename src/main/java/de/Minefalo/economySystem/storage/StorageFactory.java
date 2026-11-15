package de.Minefalo.economySystem.storage;

import de.Minefalo.economySystem.config.Settings;
import org.bukkit.plugin.java.JavaPlugin;

public final class StorageFactory {

    private StorageFactory() {
    }

    public static EconomyStorage create(JavaPlugin plugin, Settings settings) {
        return switch (settings.storageType()) {
            case YAML -> new YamlStorage(plugin, settings);
            case MYSQL -> new MySqlStorage(plugin, settings);
        };
    }
}
