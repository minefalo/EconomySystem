package de.Minefalo.economySystem.storage;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Abstraktion über die persistente Speicherung von Konten.
 * Die Implementierungen (YAML, MySQL) arbeiten ausschließlich mit
 * {@link AccountData}-Snapshots, sodass ein Wechsel der Speicherart
 * ohne Änderungen an der Economy-Logik möglich ist.
 */
public interface EconomyStorage {

    /**
     * Öffnet die Verbindung bzw. bereitet das Verzeichnis vor.
     *
     * @throws StorageException wenn der Speicher nicht verfügbar ist
     */
    void init();

    Optional<AccountData> load(UUID uniqueId);

    Optional<AccountData> loadByName(String name);

    default List<AccountData> loadAll() {
        return List.of();
    }

    /**
     * Liefert die Konten mit dem höchsten Guthaben, absteigend sortiert und
     * auf {@code limit} Einträge begrenzt (nur Konten mit positivem Guthaben).
     *
     * <p>Diese Abfrage darf <b>nicht</b> den vollständigen Bestand laden:
     * Sie ist ohne Recht für jeden Spieler auslösbar (Bestenliste, Placeholder)
     * und läuft auf dem einzigen Storage-Thread. Deshalb überschreiben die
     * Implementierungen sie mit einer begrenzten Abfrage (MySQL:
     * {@code ORDER BY balance DESC LIMIT}, YAML: In-Memory-Index). Die
     * Standardimplementierung sortiert nur den bereits geladenen Bestand und
     * ist ausschließlich als Rückfall für Speicher ohne eigene Abfrage gedacht.</p>
     *
     * @param limit maximale Anzahl der Einträge, mindestens 1
     */
    default List<AccountData> loadTop(int limit) {
        int maximum = Math.max(1, limit);
        return loadAll().stream()
                .filter(data -> data.balance().signum() > 0)
                .sorted(Comparator.comparing(AccountData::balance, Comparator.reverseOrder())
                        .thenComparing(AccountData::name, String.CASE_INSENSITIVE_ORDER))
                .limit(maximum)
                .toList();
    }

    void save(AccountData account);

    void saveAll(Collection<AccountData> accounts);

    /**
     * Speichert Konto- und Kreditänderungen in einem Schritt. Die
     * Implementierungen müssen dafür sorgen, dass ein Absturz nicht zu
     * einem halb gespeicherten Zustand führt (MySQL: eine Transaktion,
     * YAML: Journal, das beim Start nachgezogen wird).
     *
     * @param accounts        zu speichernde Kontostände
     * @param loans           anzulegende oder zu aktualisierende Kredite
     * @param deletedLoanIds  zu löschende Kredite
     */
    void saveTransaction(Collection<AccountData> accounts,
                         Collection<LoanData> loans,
                         Collection<UUID> deletedLoanIds);

    /**
     * Lädt alle gespeicherten Kredite.
     */
    default List<LoanData> loadLoans() {
        return List.of();
    }

    /**
     * Speichert einen Kredit (neu oder aktualisiert).
     */
    default void saveLoan(LoanData loan) {
        throw new UnsupportedOperationException("Kredite werden von diesem Speicher nicht unterstützt.");
    }

    /**
     * Entfernt einen vollständig zurückgezahlten Kredit.
     */
    default void deleteLoan(UUID id) {
        throw new UnsupportedOperationException("Kredite werden von diesem Speicher nicht unterstützt.");
    }

    // ------------------------------------------------------------------
    // Netzwerkbetrieb (Commands.md, Abschnitt 11)
    //
    // Die Standardwerte beschreiben den Einzelserver: keine serverübergreifende
    // Buchung, Lease immer frei, kein Audit, jeder Lauf zählt als neu. Damit
    // bleibt das bisherige Verhalten für Speicher ohne diese Fähigkeiten
    // unverändert.
    // ------------------------------------------------------------------

    /**
     * Bucht eine Überweisung genau einmal: Absender belasten, Empfänger
     * gutschreiben, Status auf {@code APPLIED}. Beides in einer Transaktion.
     *
     * @param transfer   Überweisung samt Idempotenzschlüssel
     * @param maxBalance Obergrenze des Zielkontos, {@code null} = unbegrenzt
     */
    default TransferResult transfer(TransferData transfer, BigDecimal maxBalance) {
        return TransferResult.UNSUPPORTED;
    }

    /**
     * Schreibt einen Audit-Eintrag; eine bereits bekannte {@code txId} wird
     * verworfen.
     */
    default void recordAudit(AuditEntry entry) {
    }

    /**
     * @return die neuesten Audit-Einträge eines Scopes, absteigend nach Zeit
     */
    default List<AuditEntry> audit(String scope, int limit) {
        return List.of();
    }

    /**
     * Holt die Lease für einen Scope oder verlängert sie, wenn sie frei oder
     * abgelaufen ist.
     *
     * @return {@code true}, wenn der Aufrufer die Lease danach hält
     */
    default boolean acquireLease(String scope, String holder, long ttlMillis) {
        return true;
    }

    /**
     * Verlängert eine gehaltene Lease.
     *
     * @return {@code false}, wenn ein anderer Server sie inzwischen hält
     */
    default boolean renewLease(String scope, String holder, long ttlMillis) {
        return true;
    }

    /**
     * Gibt die Lease frei, wenn der Aufrufer sie hält.
     */
    default void releaseLease(String scope, String holder) {
    }

    /**
     * @return die aktuelle Lease des Scopes, falls vorhanden
     */
    default Optional<LeaseInfo> lease(String scope) {
        return Optional.empty();
    }

    /**
     * Beansprucht einen Zinszyklus. Der Schlüssel {@code (scope, cycleKey)} ist
     * eindeutig: ein zweiter Server schreibt nichts.
     *
     * @return {@code true}, wenn dieser Aufruf den Zyklus ausführen darf
     */
    default boolean claimInterestRun(String scope, String cycleKey) {
        return true;
    }

    /**
     * @return Anzeigename des Speichers (z. B. "YAML" oder "MySQL")
     */
    String name();

    void close();
}
