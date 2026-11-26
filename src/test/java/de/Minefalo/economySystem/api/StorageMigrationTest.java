package de.Minefalo.economySystem.api;

import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.storage.StorageMigration;
import de.Minefalo.economySystem.storage.StoragePaths;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft, dass Daten früherer Versionen beim Start nach {@code data/}
 * verschoben werden und der alte Pfad bis dahin als Rückfall dient.
 */
final class StorageMigrationTest {

    @TempDir
    File folder;

    @Test
    void verschiebtAlteDatenNachData() throws IOException {
        File configFolder = new File(folder, "config");
        assertTrue(configFolder.mkdirs() || configFolder.isDirectory());
        Files.writeString(new File(configFolder, "settings.yml").toPath(), """
                storage:
                  type: SQLITE
                  sqlite:
                    file: "economy.db"
                """, StandardCharsets.UTF_8);

        JavaPlugin plugin = ApiTestSupport.plugin(folder);
        Settings settings = new Settings(plugin);

        File accounts = new File(folder, "accounts");
        assertTrue(accounts.mkdirs());
        Files.writeString(new File(accounts, "abc.yml").toPath(), "balance: 10.00\n");
        Files.writeString(new File(folder, "economy.db").toPath(), "db");
        Files.writeString(new File(folder, "economy.db-wal").toPath(), "wal");
        Files.writeString(new File(folder, "loans.yml").toPath(), "loans: {}\n");
        Files.writeString(new File(folder, "pending-transaction.yml").toPath(), "{}\n");

        // Vor der Migration zeigt der Pfad noch auf die alte Datei.
        assertEquals(new File(folder, "economy.db"),
                StoragePaths.resolve(plugin, "economy.db"));

        StorageMigration.migrate(plugin, settings);

        assertTrue(new File(folder, "data/economy.db").isFile());
        assertTrue(new File(folder, "data/economy.db-wal").isFile());
        assertTrue(new File(folder, "data/accounts/abc.yml").isFile());
        assertTrue(new File(folder, "data/loans.yml").isFile());
        assertTrue(new File(folder, "data/pending-transaction.yml").isFile());
        assertFalse(new File(folder, "economy.db").exists());
        assertFalse(accounts.exists());

        assertEquals(new File(folder, "data/economy.db"),
                StoragePaths.resolve(plugin, "economy.db"));
        assertEquals(new File(folder, "data/accounts"),
                StoragePaths.resolve(plugin, "accounts"));
    }
}
