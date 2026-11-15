package de.Minefalo.economySystem.economy;

import de.Minefalo.economySystem.currency.Currency;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Berechnet den Zinsbetrag nach der Blockcraft-Wirtschaftsformel 1:
 * {@code y = a * sqrt(x)}.
 */
public final class InterestCalculator {

    private static final MathContext CONTEXT = new MathContext(34, RoundingMode.HALF_UP);

    private InterestCalculator() {
    }

    /**
     * Berechnet y und begrenzt es auf das maximale Guthaben.
     *
     * @param balance    x - aktueller Kontostand (darf nicht negativ sein)
     * @param factor     a - Zinsfaktor (muss größer als 0 sein)
     * @param maxBalance maximales Guthaben oder {@code null} für unbegrenzt
     * @param currency   Währung für Rundung und Skalierung
     * @return gutzuschreibender Betrag; {@link BigDecimal#ZERO}, wenn kein
     *         Zins anfällt
     */
    public static BigDecimal interestFor(BigDecimal balance,
                                         BigDecimal factor,
                                         BigDecimal maxBalance,
                                         Currency currency) {
        if (balance == null || factor == null || currency == null) {
            return BigDecimal.ZERO;
        }
        if (balance.signum() <= 0 || factor.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal interest = currency.normalize(
                balance.sqrt(CONTEXT).multiply(factor, CONTEXT));
        if (interest.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        if (maxBalance != null) {
            if (balance.compareTo(maxBalance) >= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal target = balance.add(interest);
            if (target.compareTo(maxBalance) > 0) {
                return currency.normalize(maxBalance.subtract(balance));
            }
        }
        return interest;
    }
}
