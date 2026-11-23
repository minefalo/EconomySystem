package de.Minefalo.economySystem.storage;

/**
 * Lease eines Scopes ({@code Commands.md}, 11.4). Genau ein Server hält sie und
 * darf den Zinszyklus ausführen.
 *
 * @param scope       Scope-Kennung
 * @param holder      {@code network.id} des haltenden Servers
 * @param leasedUntil Ablaufzeitpunkt in Millisekunden
 */
public record LeaseInfo(String scope, String holder, long leasedUntil) {

    public boolean expired(long now) {
        return leasedUntil <= now;
    }
}
