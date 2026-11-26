package de.Minefalo.economySystem.api;

import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Implementierung der {@link EconomyApi}. Sie delegiert alle Buchungen an den
 * {@link EconomyService}, damit Prüfungen, Idempotenz, Audit-Log und Event
 * genau einmal in der Kernlogik liegen.
 */
public final class EconomyApiImpl implements EconomyApi {

    private final EconomyService economy;

    public EconomyApiImpl(EconomyService economy) {
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    @Override
    public CompletableFuture<BigDecimal> guthaben(UUID spielerId) {
        if (spielerId == null) {
            return CompletableFuture.completedFuture(zero());
        }
        return economy.existingAccountAsync(spielerId, null)
                .thenApply(account -> account
                        .map(Account::balance)
                        .orElseGet(this::zero));
    }

    @Override
    public CompletableFuture<BuchungsErgebnis> abbuchen(UUID transactionId,
                                                       UUID spielerId,
                                                       String spielerName,
                                                       BigDecimal betrag,
                                                       String quelle,
                                                       String grund) {
        return economy.abbuchen(transactionId, spielerId, spielerName, betrag, quelle, grund);
    }

    @Override
    public CompletableFuture<BuchungsErgebnis> erstatten(UUID transactionId,
                                                        UUID spielerId,
                                                        String spielerName,
                                                        BigDecimal betrag,
                                                        String quelle,
                                                        String grund) {
        return economy.erstatten(transactionId, spielerId, spielerName, betrag, quelle, grund);
    }

    @Override
    public String formatiere(BigDecimal betrag) {
        return economy.currency().format(betrag == null ? zero() : betrag);
    }

    private BigDecimal zero() {
        return economy.currency().normalize(BigDecimal.ZERO);
    }
}
