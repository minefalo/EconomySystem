package de.Minefalo.economySystem.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lädt die commands.yml, in der Aliase für alle Befehle registriert werden.
 */
public final class CommandsConfig {

    private static final String ALIAS_PATTERN = "[a-z0-9_-]{1,32}";

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration config;

    public CommandsConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "commands.yml");
        reload();
    }

    public void reload() {
        ConfigUpdater.update(plugin, "commands.yml");
        config = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = loadDefaults();
        if (defaults != null) {
            // Fehlende Schlüssel fallen auf die Standardwerte zurück, ohne die
            // Datei (und damit ihre Kommentare) neu zu schreiben.
            config.setDefaults(defaults);
        }
    }

    /**
     * Liefert die konfigurierten Aliase eines Befehls, normalisiert und
     * ohne Duplikate.
     */
    public List<String> aliases(String commandName) {
        ConfigurationSection commandSection = findCommandSection(commandName);
        List<String> configured;
        if (commandSection != null) {
            configured = commandSection.getStringList("aliases");
        } else {
            // Fallback auf die Standardwerte, damit neu hinzugefügte Befehle
            // auch bei einer älteren commands.yml Aliase erhalten.
            configured = config.getStringList("commands."
                    + commandName.toLowerCase(Locale.ROOT) + ".aliases");
        }
        Set<String> aliases = new LinkedHashSet<>();

        for (String raw : configured) {
            if (raw == null) {
                continue;
            }
            String alias = raw.trim().toLowerCase(Locale.ROOT);
            if (alias.isEmpty()) {
                continue;
            }
            if (!alias.matches(ALIAS_PATTERN)) {
                plugin.getLogger().warning("Ungültiger Alias '" + raw + "' für /" + commandName
                        + "' in der commands.yml - erlaubt sind a-z, 0-9, _ und - (maximal 32 Zeichen).");
                continue;
            }
            if (alias.equals(commandName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            aliases.add(alias);
        }

        return new ArrayList<>(aliases);
    }

    private ConfigurationSection findCommandSection(String commandName) {
        ConfigurationSection commands = config.getConfigurationSection("commands");
        if (commands == null) {
            return null;
        }
        for (String key : commands.getKeys(false)) {
            if (key.equalsIgnoreCase(commandName)) {
                return commands.getConfigurationSection(key);
            }
        }
        return null;
    }

    private YamlConfiguration loadDefaults() {
        try (InputStream input = plugin.getResource("commands.yml")) {
            if (input == null) {
                return null;
            }
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return defaults;
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("Standard-commands.yml konnte nicht geladen werden: "
                    + exception.getMessage());
            return null;
        }
    }
}
