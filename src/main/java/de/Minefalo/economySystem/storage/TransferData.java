package de.Minefalo.economySystem.storage;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Eine Überweisung, die auch ein anderer Server zustellen kann
 * ({@code Commands.md}, Abschnitt 11.2). Der {@code idempotencyKey} macht die
 * Buchung wiederholbar: derselbe Schlüssel wird nur einmal gebucht, egal über
 * welchen Server der Aufruf kommt.
 *
 * @param id             technische ID der Zeile (auch die angezeigte tx-id)
 * @param idempotencyKey Schlüssel, der die Buchung genau einmal zulässt
 * @param scope          Scope-Kennung ({@code global}, {@code group:…}, {@code server:…})
 * @param from           UUID des Absenders
 * @param fromName       Anzeigename des Absenders (nur für neue Zeilen)
 * @param to             UUID des Empfängers
 * @param toName         Anzeigename des Empfängers (nur für neue Zeilen)
 * @param amount         Betrag
 * @param sourceServer   {@code network.id} des auslösenden Servers
 * @param targetServer   {@code network.id} des Zielservers, {@code null} = offen
 */
public record TransferData(UUID id,
                           String idempotencyKey,
                           String scope,
                           UUID from,
                           String fromName,
                           UUID to,
                           String toName,
                           BigDecimal amount,
                           String sourceServer,
                           String targetServer) {

    /** Zustand einer Zeile in {@code economy_transfers}. */
    public enum State {
        PENDING,
        APPLIED,
        FAILED,
        EXPIRED
    }

    public TransferData {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(amount, "amount");
        scope = scope == null || scope.isBlank() ? "global" : scope;
        fromName = fromName == null || fromName.isBlank() ? "unknown" : fromName;
        toName = toName == null || toName.isBlank() ? "unknown" : toName;
        sourceServer = sourceServer == null || sourceServer.isBlank() ? "default" : sourceServer;
    }

    /**
     * Erzeugt eine neue Überweisung mit frischer tx-id; der Idempotenzschlüssel
     * ist standardmäßig dieselbe ID.
     */
    public static TransferData create(String scope, UUID from, String fromName,
                                      UUID to, String toName, BigDecimal amount,
                                      String sourceServer, String targetServer) {
        UUID id = UUID.randomUUID();
        return new TransferData(id, id.toString(), scope, from, fromName, to, toName,
                amount, sourceServer, targetServer);
    }
}
