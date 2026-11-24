package de.Minefalo.economySystem.vault;

import de.Minefalo.economySystem.currency.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die Umrechnung an der Vault-Grenze: Rundung auf die konfigurierten
 * Nachkommastellen und Ablehnung unbrauchbarer {@code double}-Werte.
 */
class VaultAmountsTest {

    private final Currency currency = new Currency(
            "Coins", "$", "%symbol%%amount%", "#,##0.00", Locale.GERMANY, 2,
            RoundingMode.HALF_UP, new BigDecimal("100.00"),
            new BigDecimal("1000000000.00"), new BigDecimal("0"));

    @Test
    void positiveBetraegeWerdenAufNachkommastellenGerundet() {
        assertEquals(new BigDecimal("0.10"),
                VaultAmounts.toBigDecimal(0.1, currency).orElseThrow());
        assertEquals(new BigDecimal("1.01"),
                VaultAmounts.toBigDecimal(1.005, currency).orElseThrow());
        assertEquals(new BigDecimal("2.35"),
                VaultAmounts.toBigDecimal(2.345, currency).orElseThrow());
        assertEquals(new BigDecimal("100.00"),
                VaultAmounts.toBigDecimal(100.0, currency).orElseThrow());
    }

    @Test
    void betraegeUnterDerKleinstenEinheitWerdenAbgelehnt() {
        assertTrue(VaultAmounts.toBigDecimal(0.004, currency).isEmpty());
        assertTrue(VaultAmounts.toBigDecimal(0.001, currency).isEmpty());
    }

    @Test
    void nullNegativeUndNichtEndlicheBetraegeWerdenAbgelehnt() {
        assertTrue(VaultAmounts.toBigDecimal(0.0, currency).isEmpty());
        assertTrue(VaultAmounts.toBigDecimal(-0.0, currency).isEmpty());
        assertTrue(VaultAmounts.toBigDecimal(-1.0, currency).isEmpty());
        assertTrue(VaultAmounts.toBigDecimal(Double.NaN, currency).isEmpty());
        assertTrue(VaultAmounts.toBigDecimal(Double.POSITIVE_INFINITY, currency).isEmpty());
        assertTrue(VaultAmounts.toBigDecimal(Double.NEGATIVE_INFINITY, currency).isEmpty());
    }

    @Test
    void interneBetraegeWerdenNachDoubleUmgewandelt() {
        assertEquals(12.34, VaultAmounts.toDouble(new BigDecimal("12.34")), 0.0);
        assertEquals(0.0, VaultAmounts.toDouble(null), 0.0);
    }

    @Test
    void sehrGrosseBetraegeBleibenErhalten() {
        Optional<BigDecimal> result = VaultAmounts.toBigDecimal(Double.MAX_VALUE, currency);
        assertTrue(result.isPresent());
        assertTrue(result.get().signum() > 0);
    }
}
