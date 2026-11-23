package de.Minefalo.economySystem.storage;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ein Eintrag im Audit-Trail ({@code Commands.md}, Abschnitt 11.6). Die
 * {@code txId} ist eindeutig; ein doppelt zugestellter Eintrag wird verworfen.
 *
 * @param txId         eindeutige Transaktions-ID
 * @param occurredAt   Zeitpunkt in Millisekunden
 * @param serverId     {@code network.id} des Servers
 * @param scope        Scope-Kennung
 * @param actorId      UUID des Aufrufers, {@code null} bei der Konsole
 * @param actorName    Name des Aufrufers
 * @param origin       {@code PLAYER}, {@code CONSOLE}, {@code GUI}, {@code API}, {@code SCHEDULER}
 * @param action       Pfad der Aktion, z. B. {@code pay} oder {@code admin.balance.set}
 * @param targetId     UUID des Zielkontos, falls vorhanden
 * @param targetName   Name des Zielkontos, falls vorhanden
 * @param amount       Betrag, falls vorhanden
 * @param balanceAfter Guthaben nach der Aktion, falls vorhanden
 * @param reason       Begründung ({@code --reason}), falls vorhanden
 * @param result       {@code APPLIED}, {@code REJECTED} oder {@code DRY_RUN}
 */
public record AuditEntry(UUID txId,
                         long occurredAt,
                         String serverId,
                         String scope,
                         UUID actorId,
                         String actorName,
                         String origin,
                         String action,
                         UUID targetId,
                         String targetName,
                         BigDecimal amount,
                         BigDecimal balanceAfter,
                         String reason,
                         Result result) {

    public enum Result {
        APPLIED,
        REJECTED,
        DRY_RUN
    }

    public AuditEntry {
        result = result == null ? Result.APPLIED : result;
        origin = origin == null || origin.isBlank() ? "API" : origin;
        action = action == null ? "" : action;
    }
}
