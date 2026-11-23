package de.Minefalo.economySystem.config;

import de.Minefalo.economySystem.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Lädt die messages.yml und ersetzt Platzhalter.
 */
public final class Messages {

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration config;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = ConfigUpdater.file(plugin, "messages.yml");
        reload();
    }

    public void reload() {
        ConfigUpdater.update(plugin, "messages.yml");
        config = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = ConfigUpdater.bundled(plugin, "messages.yml");
        if (defaults != null) {
            // Fehlende Schlüssel fallen auf die Standardwerte zurück, ohne die
            // Datei (und damit ihre Kommentare) neu zu schreiben.
            config.setDefaults(defaults);
        }
    }

    public void send(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(component(key, placeholders));
    }

    public void sendList(CommandSender sender, String key, String... placeholders) {
        for (String line : config.getStringList(key)) {
            sender.sendMessage(lineComponent(line, placeholders));
        }
    }

    /**
     * @return {@code true}, wenn der Schlüssel vorhanden ist - auch über die
     *         mitgelieferten Standardwerte. Damit können neue Schlüssel
     *         verwendet und alte als Fallback gelesen werden (Abschnitt 9.1).
     */
    public boolean has(String key) {
        return config.getString(key) != null;
    }

    /**
     * Liefert einen Rohwert ohne Markup/Prefix (z. B. "unbegrenzt").
     */
    public String raw(String key) {
        String value = config.getString(key);
        if (value == null) {
            plugin.getLogger().warning("Fehlender Nachrichten-Schlüssel: " + key);
            return key;
        }
        return value;
    }

    public Component component(String key, String... placeholders) {
        String raw = config.getString(key);
        if (raw == null) {
            plugin.getLogger().warning("Fehlender Nachrichten-Schlüssel: " + key);
            raw = key;
        }
        return lineComponent(raw, placeholders);
    }

    public String prefix() {
        return config.getString("prefix", "");
    }

    private Component lineComponent(String raw, String... placeholders) {
        String text = applyPlaceholders(raw, placeholders);
        boolean hasPrefixPlaceholder = text.contains("%prefix%");
        String prefix = prefix();
        text = text.replace("%prefix%", prefix);

        Component component = Text.component(text);
        if (!hasPrefixPlaceholder && !prefix.isEmpty()) {
            component = Text.component(prefix).append(component);
        }
        return component;
    }

    private static String applyPlaceholders(String text, String... placeholders) {
        if (placeholders.length == 0) {
            return text;
        }
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("Platzhalter müssen als Schlüssel/Wert-Paare übergeben werden.");
        }
        String result = text;
        for (int i = 0; i < placeholders.length; i += 2) {
            result = result.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
        }
        return result;
    }
}
