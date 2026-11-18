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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Aktualisiert die Konfigurationsdateien anhand der mitgelieferten
 * Standarddateien.
 *
 * <p>Jede Datei trägt eine {@code config-version}. Ist die vorhandene Datei
 * älter als die mitgelieferte, werden alle fehlenden Schlüssel samt
 * Kommentaren ergänzt. Vorhandene Werte und Kommentare bleiben unverändert,
 * sodass eigene Anpassungen erhalten bleiben.</p>
 */
public final class ConfigUpdater {

    private static final String VERSION_KEY = "config-version";

    private ConfigUpdater() {
    }

    /**
     * Legt die Datei bei Bedarf an und aktualisiert sie auf die aktuelle
     * {@code config-version}.
     *
     * @return {@code true}, wenn die Datei angelegt oder aktualisiert wurde
     */
    public static boolean update(JavaPlugin plugin, String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
            return true;
        }

        YamlConfiguration bundled = loadBundled(plugin, fileName);
        if (bundled == null) {
            return false;
        }
        return update(file, bundled, plugin.getLogger());
    }

    /**
     * Aktualisiert eine bereits vorhandene Datei. Package-private, damit die
     * Logik ohne laufenden Server geprüft werden kann.
     */
    static boolean update(File file, YamlConfiguration bundled, Logger logger) {
        int targetVersion = bundled.getInt(VERSION_KEY, 0);
        if (targetVersion <= 0) {
            return false;
        }

        YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
        if (current.getInt(VERSION_KEY, 0) >= targetVersion) {
            return false;
        }

        apply(bundled, current);
        current.set(VERSION_KEY, targetVersion);

        Path temporary = null;
        try {
            temporary = Files.createTempFile(file.toPath().toAbsolutePath().getParent(), file.getName(), ".tmp");
            current.save(temporary.toFile());
            tidy(temporary.toFile(), targetVersion);
            replaceFile(temporary, file.toPath());
        } catch (IOException exception) {
            logger.warning(file.getName() + " konnte nicht aktualisiert werden: "
                    + exception.getMessage());
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    logger.warning("Temporäre Konfiguration konnte nicht entfernt werden: " + exception.getMessage());
                }
            }
        }

        logger.info(file.getName() + " wurde auf config-version " + targetVersion
                + " aktualisiert.");
        return true;
    }

    /**
     * Ergänzt alle Schlüssel aus {@code defaults}, die in {@code target}
     * fehlen. Vorhandene Werte werden niemals überschrieben.
     */
    public static void apply(YamlConfiguration defaults, YamlConfiguration target) {
        merge(defaults, target, "");
    }

    private static void merge(ConfigurationSection defaults, ConfigurationSection target, String prefix) {
        for (String key : defaults.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = defaults.get(key);

            if (value instanceof ConfigurationSection section) {
                if (target.isConfigurationSection(key)) {
                    merge(section, target.getConfigurationSection(key), path);
                } else if (!target.contains(key)) {
                    merge(section, target.createSection(key), path);
                    copyComments(defaults, target, key, path);
                }
                continue;
            }

            if (!target.contains(key)) {
                target.set(key, value);
                copyComments(defaults, target, key, path);
            }
        }
    }

    private static void copyComments(ConfigurationSection source, ConfigurationSection target,
                                     String key, String path) {
        ConfigurationSection sourceRoot = source.getRoot();
        ConfigurationSection targetRoot = target.getRoot();

        List<String> comments = sourceRoot.getComments(path);
        if (!comments.isEmpty()) {
            targetRoot.setComments(path, comments);
        }
        List<String> inline = sourceRoot.getInlineComments(path);
        if (!inline.isEmpty()) {
            targetRoot.setInlineComments(path, inline);
        }
    }

    /**
     * Entfernt doppelte {@code config-version}-Zeilen, setzt die Version
     * unter den Kopfkommentar und entfernt Leerzeichen am Zeilenende.
     */
    private static void tidy(File file, int version) throws IOException {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);

        List<String> cleaned = new ArrayList<>(lines.size() + 3);
        for (String line : lines) {
            if (line.stripLeading().startsWith(VERSION_KEY + ":")) {
                continue;
            }
            cleaned.add(stripTrailing(line));
        }

        int insertAt = cleaned.size();
        for (int index = 0; index < cleaned.size(); index++) {
            String trimmed = cleaned.get(index).strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                insertAt = index;
                break;
            }
        }

        boolean blankBefore = insertAt > 0 && cleaned.get(insertAt - 1).isBlank();
        List<String> block = new ArrayList<>(3);
        if (!blankBefore && insertAt > 0) {
            block.add("");
        }
        block.add(VERSION_KEY + ": " + version);
        block.add("");
        cleaned.addAll(insertAt, block);

        Files.write(file.toPath(), cleaned, StandardCharsets.UTF_8);
    }

    static void replaceFile(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    private static YamlConfiguration loadBundled(JavaPlugin plugin, String fileName) {
        try (InputStream input = plugin.getResource(fileName)) {
            if (input == null) {
                return null;
            }
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return defaults;
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("Standard-" + fileName + " konnte nicht geladen werden: "
                    + exception.getMessage());
            return null;
        }
    }
}
