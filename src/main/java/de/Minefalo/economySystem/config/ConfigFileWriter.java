package de.Minefalo.economySystem.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Schreibt Konfigurationsdateien abgesichert zurück.
 *
 * <p>Der Aufbau orientiert sich an {@code DateiSicherung} aus PunishSystem:
 * unveränderter Inhalt wird nicht geschrieben, vor dem Austausch entsteht
 * eine Sicherung im {@code backup}-Ordner, die neue Datei wird vor dem
 * Austausch auf den Datenträger geschrieben ({@code fsync}) und ersetzt die
 * alte Datei atomar. Scheitert die Sicherung, bleibt die Originaldatei
 * unangetastet.</p>
 */
public final class ConfigFileWriter {

    /** Endung der Sicherung, die vor jedem Schreibvorgang entsteht. */
    static final String BACKUP_SUFFIX = ".vor-config-update.bak";

    private ConfigFileWriter() {
    }

    /**
     * Ersetzt {@code target} durch {@code after}, sofern sich der Inhalt
     * ändert.
     *
     * @param target    Zieldatei
     * @param before    Inhalt, der beim Lesen vorgefunden wurde
     * @param after     neuer Inhalt
     * @param backupDir Ordner für die Sicherung (wird bei Bedarf angelegt)
     * @return {@code true}, wenn geschrieben wurde
     * @throws IOException wenn die Datei zwischenzeitlich geändert wurde oder
     *                     Sicherung bzw. Austausch fehlschlagen
     */
    public static boolean writeIfChanged(Path target, String before, String after,
                                         Path backupDir, Logger logger) throws IOException {
        Objects.requireNonNull(target, "Die Konfigurationsdatei darf nicht null sein.");
        Objects.requireNonNull(before, "Der bisherige Inhalt darf nicht null sein.");
        Objects.requireNonNull(after, "Der neue Inhalt darf nicht null sein.");
        Objects.requireNonNull(backupDir, "Der Sicherungsordner darf nicht null sein.");

        if (before.equals(after)) {
            return false;
        }
        if (Files.exists(target) && !Files.readString(target, StandardCharsets.UTF_8).equals(before)) {
            throw new IOException("Konfiguration wurde während des Abgleichs geändert: "
                    + target.getFileName());
        }

        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) {
            parent = Path.of(".");
        }
        Files.createDirectories(parent);

        Path temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, after, StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }

            backup(target, backupDir);

            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                logger.warning("Atomarer Austausch für " + target.getFileName()
                        + " nicht unterstützt - verwende Dateiersatz.");
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Kopiert den bisherigen Inhalt nach
     * {@code <backupDir>/<datei>.vor-config-update.bak}. Eine ältere Sicherung
     * derselben Datei wird ersetzt.
     */
    private static void backup(Path target, Path backupDir) throws IOException {
        Files.createDirectories(backupDir);
        Path backup = backupDir.resolve(target.getFileName() + BACKUP_SUFFIX);
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
    }
}
