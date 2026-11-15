package de.Minefalo.economySystem.economy;

import de.Minefalo.economySystem.storage.AccountData;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Laufzeit-Konto eines Spielers. Alle geldrelevanten Operationen sind
 * synchronisiert, damit parallele Zugriffe (z. B. aus asynchronen
 * Speichervorgängen) keine Race Conditions erzeugen.
 */
public final class Account {

    private final UUID uniqueId;
    private String name;
    private BigDecimal balance;
    private boolean dirty;

    public Account(UUID uniqueId, String name, BigDecimal balance) {
        this.uniqueId = Objects.requireNonNull(uniqueId, "uniqueId");
        this.name = name == null || name.isBlank() ? "unknown" : name;
        this.balance = Objects.requireNonNull(balance, "balance");
    }

    public UUID uniqueId() {
        return uniqueId;
    }

    public synchronized String name() {
        return name;
    }

    public synchronized void name(String name) {
        if (name != null && !name.isBlank() && !name.equals(this.name)) {
            this.name = name;
            this.dirty = true;
        }
    }

    public synchronized BigDecimal balance() {
        return balance;
    }

    /**
     * Bucht einen Betrag auf. {@code maxBalance} darf {@code null} sein
     * (unbegrenzt).
     *
     * @return {@code false}, wenn das maximale Guthaben überschritten würde
     */
    public synchronized boolean deposit(BigDecimal amount, BigDecimal maxBalance) {
        BigDecimal result = balance.add(amount);
        if (maxBalance != null && result.compareTo(maxBalance) > 0) {
            return false;
        }
        this.balance = result;
        this.dirty = true;
        return true;
    }

    /**
     * Bucht einen Betrag ab.
     *
     * @return {@code false}, wenn das Guthaben nicht ausreicht
     */
    public synchronized boolean withdraw(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            return false;
        }
        this.balance = balance.subtract(amount);
        this.dirty = true;
        return true;
    }

    public synchronized void setBalance(BigDecimal amount) {
        this.balance = amount;
        this.dirty = true;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized void markDirty() {
        this.dirty = true;
    }

    public synchronized AccountData snapshot() {
        return new AccountData(uniqueId, name, balance);
    }

    /**
     * Entfernt das Dirty-Flag nur, wenn seit dem Snapshot keine Änderung
     * mehr stattgefunden hat.
     */
    public synchronized void markSaved(AccountData saved) {
        if (saved.name().equals(name) && saved.balance().compareTo(balance) == 0) {
            this.dirty = false;
        }
    }
}
