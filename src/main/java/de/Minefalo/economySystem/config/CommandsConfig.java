package de.Minefalo.economySystem.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Liest die {@code commands.yml}, in der Aliase, Synonyme, Kurzbefehle und
 * Vorschläge stehen ({@code Commands.md}, Abschnitt 8).
 *
 * <p>Die Datei ändert nur Schreibweisen, niemals die Struktur. Geprüft werden
 * die Einträge vom {@link de.Minefalo.economySystem.command.CommandRegistry},
 * weil nur er den Befehlsbaum kennt; diese Klasse liefert die Rohdaten.</p>
 */
public final class CommandsConfig {

    private static final String ALIAS_PATTERN = "[a-z0-9_-]{1,32}";

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration config;

    public CommandsConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = ConfigUpdater.file(plugin, "commands.yml");
        reload();
    }

    /**
     * Lädt die Datei neu. Vorhandene Aliase bleiben unverändert; fehlende
     * Abschnitte kommen aus der Standarddatei hinzu (Abschnitt 8.3).
     */
    public void reload() {
        ConfigUpdater.update(plugin, "commands.yml");
        config = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = ConfigUpdater.bundled(plugin, "commands.yml");
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

    /**
     * Rohdaten des Abschnitts {@code shortcuts}: Befehlsname → Pfad.
     */
    public Map<String, String> shortcuts() {
        ConfigurationSection section = config.getConfigurationSection("shortcuts");
        Map<String, String> shortcuts = new LinkedHashMap<>();
        if (section == null) {
            return shortcuts;
        }
        for (String key : section.getKeys(false)) {
            String path = section.getString(key + ".path");
            if (path == null || path.isBlank()) {
                plugin.getLogger().warning("commands.yml: shortcuts." + key
                        + " hat keinen Pfad und wird übersprungen.");
                continue;
            }
            shortcuts.put(key.toLowerCase(Locale.ROOT), path.trim());
        }
        return shortcuts;
    }

    /**
     * Rohdaten des Abschnitts {@code subcommand-words}: kanonisches Wort →
     * Schreibweisen.
     */
    public Map<String, List<String>> subcommandWords() {
        ConfigurationSection section = config.getConfigurationSection("subcommand-words");
        Map<String, List<String>> words = new LinkedHashMap<>();
        if (section == null) {
            return words;
        }
        for (String key : section.getKeys(false)) {
            words.put(key, section.getStringList(key));
        }
        return words;
    }

    /**
     * Rohdaten des Abschnitts {@code suggestions}: {@code <pfad>.<argument>} →
     * Vorschläge.
     */
    public Map<String, List<String>> suggestions() {
        ConfigurationSection section = config.getConfigurationSection("suggestions");
        Map<String, List<String>> suggestions = new LinkedHashMap<>();
        if (section == null) {
            return suggestions;
        }
        for (String key : section.getKeys(false)) {
            suggestions.put(key, section.getStringList(key));
        }
        return suggestions;
    }

    /**
     * Vorschläge für einen Pfad. Der Schlüssel nennt zusätzlich das Argument
     * ({@code pay.amount}); übernommen werden nur Einträge, deren Pfadteil
     * exakt passt.
     */
    public List<String> suggestionsFor(String path) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : suggestions().entrySet()) {
            int separator = entry.getKey().lastIndexOf('.');
            String owner = separator < 0 ? "" : entry.getKey().substring(0, separator);
            if (owner.equalsIgnoreCase(path)) {
                values.addAll(entry.getValue());
            }
        }
        return values;
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
}
