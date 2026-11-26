package de.Minefalo.economySystem.storage;

import de.Minefalo.economySystem.config.Settings;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Verschiebt die lokalen Daten früherer Versionen aus dem Plugin-Ordner in
 * den gemeinsamen {@code data}-Ordner. Läuft beim Start vor dem Öffnen des
 * Speichers.
 *
 * <p>Vorhandene Zieldateien werden nie überschrieben. Schlägt ein
 * Verschieben fehl, bleibt die Datei am alten Ort und wird über
 * {@link StoragePaths#resolve} weiterhin gefunden.</p>
 */
public final class StorageMigration {

    private static final List<String> YAML_FILES = List.of(
            "loans.yml", "pending-transaction.yml", "api-transactions.yml", "audit.yml");
    private static final List<String> DATABASE_SUFFIXES = List.of("-wal", "-shm");

    private StorageMigration() {
    }

    public static void migrate(JavaPlugin plugin, Settings settings) {
        File data = StoragePaths.dataFolder(plugin);
        List<String> moved = new ArrayList<>();

        move(plugin, settings.yamlFolder(), data, moved);
        // Erst die Journaldateien, dann die Datenbank: Bricht das Verschieben
        // dazwischen ab, bleibt die Datenbank am alten Ort und SQLite findet
        // ihre WAL-Dateien weiterhin dort.
        for (String suffix : DATABASE_SUFFIXES) {
            move(plugin, settings.sqliteFile() + suffix, data, moved);
        }
        move(plugin, settings.sqliteFile(), data, moved);
        for (String name : YAML_FILES) {
            move(plugin, name, data, moved);
        }

        if (!moved.isEmpty()) {
            plugin.getLogger().info("Lokale Daten nach data/ verschoben: "
                    + String.join(", ", moved) + ".");
        }
    }

    private static boolean inside(File root, File candidate) {
        try {
            Path base = root.getCanonicalFile().toPath();
            return candidate.getCanonicalFile().toPath().startsWith(base);
        } catch (IOException exception) {
            return false;
        }
    }

    private static void move(JavaPlugin plugin, String relativePath, File data, List<String> moved) {
        File source = new File(plugin.getDataFolder(), relativePath);
        File target = new File(data, relativePath);
        if (!source.exists() || target.exists() || source.equals(target)) {
            return;
        }
        // Pfade mit ".." nicht verschieben; das bisherige Ziel bleibt über
        // StoragePaths.resolve erreichbar.
        if (!inside(plugin.getDataFolder(), source) || !inside(plugin.getDataFolder(), target)) {
            return;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Datenordner konnte nicht angelegt werden: " + parent);
            return;
        }

        try {
            Files.move(source.toPath(), target.toPath());
            moved.add(relativePath);
        } catch (IOException exception) {
            plugin.getLogger().warning("Lokale Daten konnten nicht nach data/ verschoben werden ("
                    + relativePath + "): " + exception.getMessage()
                    + " - der bisherige Pfad wird weiterverwendet.");
        }
    }
}
