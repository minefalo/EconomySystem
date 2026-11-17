package de.Minefalo.economySystem.storage;

import java.util.Collection;
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

    void save(AccountData account);

    void saveAll(Collection<AccountData> accounts);

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

    /**
     * @return Anzeigename des Speichers (z. B. "YAML" oder "MySQL")
     */
    String name();

    void close();
}
