package de.Minefalo.economySystem.storage;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Persistenter Snapshot eines Kredits. Geldbeträge werden als
 * {@link BigDecimal}-Strings gespeichert, damit keine Rundungsfehler
 * entstehen.
 *
 * @param id                  eindeutige Kredit-ID
 * @param owner               UUID des Schuldners
 * @param ownerName           Name des Schuldners (für Anzeige und Offline-Zugriff)
 * @param principal           ursprünglich ausgezahlter Betrag
 * @param interest            ursprünglich berechnete Zinsen ({@code y = x*m + a})
 * @param principalRemaining  noch offener Kreditbetrag
 * @param interestRemaining   noch offene Zinsen
 * @param days                vereinbarte Laufzeit in Tagen
 * @param createdAt           Aufnahmezeitpunkt (Epoch-Millis)
 * @param dueAt               Fälligkeit (Epoch-Millis)
 * @param autoCollect         ob alle Einnahmen automatisch getilgt werden
 */
public record LoanData(UUID id,
                       UUID owner,
                       String ownerName,
                       BigDecimal principal,
                       BigDecimal interest,
                       BigDecimal principalRemaining,
                       BigDecimal interestRemaining,
                       int days,
                       long createdAt,
                       long dueAt,
                       boolean autoCollect) {
}
