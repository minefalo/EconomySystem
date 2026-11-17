package de.Minefalo.economySystem.credit;

import de.Minefalo.economySystem.storage.LoanData;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * Laufzeit-Kredit eines Spielers. Alle Zustandsänderungen sind
 * synchronisiert, damit Zahlungen aus Befehlen und Einnahmen-Aufteilung
 * nicht kollidieren.
 */
public final class Loan {

    private final UUID id;
    private final UUID owner;
    private final String ownerName;
    private final BigDecimal principal;
    private final BigDecimal interest;
    private final int days;
    private final long createdAt;
    private final long dueAt;

    private BigDecimal principalRemaining;
    private BigDecimal interestRemaining;
    private boolean autoCollect;

    public Loan(UUID id,
                UUID owner,
                String ownerName,
                BigDecimal principal,
                BigDecimal interest,
                int days,
                long createdAt,
                long dueAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ownerName = ownerName == null || ownerName.isBlank() ? "unknown" : ownerName;
        this.principal = Objects.requireNonNull(principal, "principal");
        this.interest = Objects.requireNonNull(interest, "interest");
        this.days = days;
        this.createdAt = createdAt;
        this.dueAt = dueAt;
        this.principalRemaining = principal;
        this.interestRemaining = interest;
    }

    public static Loan fromData(LoanData data) {
        Loan loan = new Loan(data.id(), data.owner(), data.ownerName(), data.principal(),
                data.interest(), data.days(), data.createdAt(), data.dueAt());
        loan.principalRemaining = data.principalRemaining();
        loan.interestRemaining = data.interestRemaining();
        loan.autoCollect = data.autoCollect();
        return loan;
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public synchronized String ownerName() {
        return ownerName;
    }

    public BigDecimal principal() {
        return principal;
    }

    public BigDecimal interest() {
        return interest;
    }

    public synchronized BigDecimal principalRemaining() {
        return principalRemaining;
    }

    public synchronized BigDecimal interestRemaining() {
        return interestRemaining;
    }

    public int days() {
        return days;
    }

    public long createdAt() {
        return createdAt;
    }

    public long dueAt() {
        return dueAt;
    }

    public synchronized LocalDateTime dueDate() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(dueAt), ZoneId.systemDefault());
    }

    public synchronized boolean autoCollect() {
        return autoCollect;
    }

    public synchronized void autoCollect(boolean autoCollect) {
        this.autoCollect = autoCollect;
    }

    public synchronized BigDecimal total() {
        return principal.add(interest);
    }

    public synchronized BigDecimal remaining() {
        return principalRemaining.add(interestRemaining);
    }

    public synchronized boolean isPaid() {
        return remaining().signum() <= 0;
    }

    public synchronized boolean isOverdue(long now) {
        return now >= dueAt && !isPaid();
    }

    /**
     * Verbucht eine Zahlung: zuerst Zinsen, dann Kreditbetrag.
     *
     * @param amount maximal verfügbarer Betrag
     * @return tatsächlich verbuchter Betrag
     */
    public synchronized BigDecimal pay(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal open = remaining();
        BigDecimal used = amount.min(open);
        if (used.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal fromInterest = used.min(interestRemaining);
        interestRemaining = interestRemaining.subtract(fromInterest);
        BigDecimal rest = used.subtract(fromInterest);
        if (rest.signum() > 0) {
            principalRemaining = principalRemaining.subtract(rest);
        }
        return used;
    }

    /**
     * Erlässt die noch offenen Zinsen (z. B. wenn das Minimalguthaben
     * erreicht ist) und aktiviert die automatische Tilgung aus Einnahmen.
     *
     * @return erlassener Zinsbetrag
     */
    public synchronized BigDecimal waiveInterest() {
        BigDecimal waived = interestRemaining;
        interestRemaining = BigDecimal.ZERO;
        autoCollect = true;
        return waived;
    }

    public synchronized LoanData snapshot() {
        return new LoanData(id, owner, ownerName, principal, interest,
                principalRemaining, interestRemaining, days, createdAt, dueAt, autoCollect);
    }
}
