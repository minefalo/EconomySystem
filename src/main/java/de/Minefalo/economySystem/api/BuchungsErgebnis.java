package de.Minefalo.economySystem.api;

/**
 * Ergebnis einer Buchung über die {@link EconomyApi}.
 *
 * <p>Die Werte sind absichtlich deutsch benannt, weil sie Teil der
 * öffentlichen Schnittstelle für andere Plugins sind.</p>
 */
public enum BuchungsErgebnis {

    /** Die Buchung wurde ausgeführt und dauerhaft gespeichert. */
    OK,

    /** Das Guthaben reicht für die Abbuchung nicht aus. */
    KEIN_GELD,

    /** Die Erstattung würde das maximale Guthaben überschreiten. */
    MAXIMUM,

    /** Der Server läuft im Modus {@code READ_ONLY}; es wurde nichts gebucht. */
    NICHT_ERLAUBT,

    /** Betrag, Spieler-ID oder Transaktions-ID sind unbrauchbar. */
    UNGUELTIGER_BETRAG,

    /** Die Transaktions-ID wurde bereits gebucht; es wurde nichts geändert. */
    DUPLIKAT,

    /** Die Buchung konnte nicht gespeichert werden; der Kontostand blieb unverändert. */
    FEHLER
}
