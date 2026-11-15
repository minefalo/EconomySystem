package de.Minefalo.economySystem.storage;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Unveränderlicher Snapshot eines Kontos. Wird zwischen Service und
 * Speicher ausgetauscht, damit asynchrone Schreibvorgänge keine
 * veränderlichen Objekte sehen.
 */
public record AccountData(UUID uniqueId, String name, BigDecimal balance) {

    public AccountData {
        Objects.requireNonNull(uniqueId, "uniqueId");
        Objects.requireNonNull(balance, "balance");
        name = name == null || name.isBlank() ? "unknown" : name;
    }
}
