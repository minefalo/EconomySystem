package de.Minefalo.economySystem.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Gemeinsamer Ablageort aller lokalen Speicherdaten
 * ({@code plugins/EconomySystem/data}).
 *
 * <p>Konfigurationsdateien bleiben unter {@code config/}; alles, was das
 * Plugin zur Laufzeit an Konten, Krediten und Buchungen ablegt, liegt unter
 * {@code data/}.</p>
 */
public final class StoragePaths {

    /** Name des Datenordners im Plugin-Verzeichnis. */
    public static final String DATA_FOLDER = "data";

    private StoragePaths() {
    }

    /**
     * @return der {@code data}-Ordner; wird nicht angelegt
     */
    public static File dataFolder(JavaPlugin plugin) {
        return new File(plugin.getDataFolder(), DATA_FOLDER);
    }

    /**
     * Löst einen relativen Speicherpfad gegen den {@code data}-Ordner auf.
     *
     * <p>Liegt die Datei nur noch unter dem bisherigen Pfad im Plugin-Ordner
     * (weil das Verschieben beim Start nicht möglich war), wird dieser
     * verwendet, damit keine Daten verloren gehen.</p>
     */
    public static File resolve(JavaPlugin plugin, String relativePath) {
        File target = new File(dataFolder(plugin), relativePath);
        File legacy = new File(plugin.getDataFolder(), relativePath);
        return !target.exists() && legacy.exists() ? legacy : target;
    }
}
