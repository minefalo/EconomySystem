package de.Minefalo.economySystem.economy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Ergebnis eines Zinszyklus.
 *
 * @param accountsChecked  Anzahl aller geprüften Konten
 * @param accountsCredited Anzahl der Konten, denen Zinsen gutgeschrieben wurden
 * @param totalCredited    Summe aller gutgeschriebenen Zinsen
 * @param credits          Gutschrift je Konto (UUID), für Spieler-Feedback
 */
public record InterestResult(int accountsChecked,
                             int accountsCredited,
                             BigDecimal totalCredited,
                             Map<UUID, BigDecimal> credits) {

    public InterestResult {
        credits = Map.copyOf(credits);
    }
}
