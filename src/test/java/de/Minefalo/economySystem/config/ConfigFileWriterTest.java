package de.Minefalo.economySystem.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft das abgesicherte Schreiben: Sicherung im backup-Ordner, kein
 * Schreibvorgang ohne Änderung, keine Temp-Reste und die Erkennung einer
 * zwischenzeitlichen Änderung.
 */
class ConfigFileWriterTest {

    private static final Logger LOG = Logger.getLogger(ConfigFileWriterTest.class.getName());

    @TempDir
    Path temp;

    @Test
    void schreibtUndSichert() throws IOException {
        Path config = Files.createDirectories(temp.resolve("config"));
        Path target = config.resolve("settings.yml");
        Path backupDir = config.resolve("backup");
        Files.writeString(target, "alt\n", StandardCharsets.UTF_8);

        assertTrue(ConfigFileWriter.writeIfChanged(target, "alt\n", "neu\n", backupDir, LOG));

        assertEquals("neu\n", Files.readString(target, StandardCharsets.UTF_8));
        Path backup = backupDir.resolve("settings.yml" + ConfigFileWriter.BACKUP_SUFFIX);
        assertTrue(Files.exists(backup), "Sicherung muss im backup-Ordner liegen");
        assertEquals("alt\n", Files.readString(backup, StandardCharsets.UTF_8));
        assertNoTemporaryFiles(config);
    }

    @Test
    void legtBackupOrdnerAn() throws IOException {
        Path config = Files.createDirectories(temp.resolve("config"));
        Path target = config.resolve("gui.yml");
        Path backupDir = config.resolve("backup");
        Files.writeString(target, "alt\n", StandardCharsets.UTF_8);

        assertFalse(Files.exists(backupDir));
        assertTrue(ConfigFileWriter.writeIfChanged(target, "alt\n", "neu\n", backupDir, LOG));
        assertTrue(Files.isDirectory(backupDir));
    }

    @Test
    void unveraendertTutNichts() throws IOException {
        Path config = Files.createDirectories(temp.resolve("config"));
        Path target = config.resolve("settings.yml");
        Path backupDir = config.resolve("backup");
        Files.writeString(target, "gleich\n", StandardCharsets.UTF_8);

        assertFalse(ConfigFileWriter.writeIfChanged(target, "gleich\n", "gleich\n", backupDir, LOG));

        assertEquals("gleich\n", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(backupDir), "ohne Änderung darf keine Sicherung entstehen");
    }

    @Test
    void erkenntNebenlaeufigeAenderung() throws IOException {
        Path config = Files.createDirectories(temp.resolve("config"));
        Path target = config.resolve("settings.yml");
        Files.writeString(target, "echt\n", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> ConfigFileWriter.writeIfChanged(
                target, "erwartet\n", "neu\n", config.resolve("backup"), LOG));

        assertEquals("echt\n", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(config.resolve("backup")));
    }

    private static void assertNoTemporaryFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "Temp-Dateien müssen aufgeräumt sein");
        }
    }
}
