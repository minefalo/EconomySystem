package de.Minefalo.economySystem.vault;

import de.Minefalo.economySystem.currency.Currency;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Umrechnung zwischen den {@code double}-Beträgen der Vault-API und den
 * internen {@link BigDecimal}-Beträgen. Die Klasse enthält bewusst keine
 * Vault-Imports und ist damit ohne Vault testbar.
 */
public final class VaultAmounts {

    private VaultAmounts() {
    }

    /**
     * Prüft einen Vault-Betrag und rundet ihn auf die konfigurierten
     * Nachkommastellen. Abgelehnt werden {@code NaN}, unendliche Werte,
     * negative Beträge und Beträge, die auf 0 gerundet würden.
     *
     * @param amount   Betrag aus der Vault-API
     * @param currency Währung mit Rundungsmodus und Nachkommastellen
     * @return der normalisierte Betrag oder ein leeres Optional
     */
    public static Optional<BigDecimal> toBigDecimal(double amount, Currency currency) {
        if (!Double.isFinite(amount) || amount <= 0) {
            return Optional.empty();
        }
        BigDecimal normalized = currency.normalize(BigDecimal.valueOf(amount));
        return normalized.signum() > 0 ? Optional.of(normalized) : Optional.empty();
    }

    /**
     * Wandelt einen internen Betrag für die Vault-API um.
     */
    public static double toDouble(BigDecimal amount) {
        return amount == null ? 0.0 : amount.doubleValue();
    }
}
