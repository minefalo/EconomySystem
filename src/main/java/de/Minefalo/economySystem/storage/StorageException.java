package de.Minefalo.economySystem.storage;

/**
 * Laufzeitfehler der Speicher-Schicht. Wird vom Service abgefangen und
 * als freundliche Nachricht an den Spieler weitergegeben.
 */
public class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
