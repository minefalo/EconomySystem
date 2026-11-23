package de.Minefalo.economySystem.storage;

/**
 * Ergebnis einer idempotenten Überweisung ({@code Commands.md}, 11.2).
 */
public enum TransferResult {

    /** Gebucht: Absender belastet, Empfänger gutgeschrieben. */
    APPLIED,

    /** Der Idempotenzschlüssel war bereits verarbeitet - es wurde nichts gebucht. */
    DUPLICATE,

    /** Der Absender hat nicht genug Guthaben; es wurde nichts gebucht. */
    INSUFFICIENT_FUNDS,

    /** Der Empfänger würde das maximale Guthaben überschreiten; nichts gebucht. */
    MAX_BALANCE,

    /** Der Speicher kann keine serverübergreifenden Buchungen (z. B. YAML). */
    UNSUPPORTED,

    /** Technischer Fehler; der Aufrufer darf es erneut versuchen. */
    FAILED
}
