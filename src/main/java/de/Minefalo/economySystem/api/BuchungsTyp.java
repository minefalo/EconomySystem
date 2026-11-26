package de.Minefalo.economySystem.api;

/**
 * Art einer Buchung über die {@link EconomyApi}.
 */
public enum BuchungsTyp {

    /** Belastung eines Kontos ({@link EconomyApi#abbuchen}). */
    ABBUCHUNG,

    /** Gutschrift eines Kontos ({@link EconomyApi#erstatten}). */
    ERSTATTUNG
}
