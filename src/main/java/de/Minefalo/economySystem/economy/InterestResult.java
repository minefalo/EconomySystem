package de.Minefalo.economySystem.economy;

import java.math.BigDecimal;

/**
 * Ergebnis eines Zinszyklus.
 *
 * @param accountsChecked  Anzahl aller geprüften Konten
 * @param accountsCredited Anzahl der Konten, denen Zinsen gutgeschrieben wurden
 * @param totalCredited    Summe aller gutgeschriebenen Zinsen
 */
public record InterestResult(int accountsChecked,
                             int accountsCredited,
                             BigDecimal totalCredited) {
}
