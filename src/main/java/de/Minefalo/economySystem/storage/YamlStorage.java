package de.Minefalo.economySystem.storage;

import de.Minefalo.economySystem.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speichert jedes Konto in einer eigenen YAML-Datei
 * ({@code accounts/<uuid>.yml}). Namens- und Guthabenindex werden im Speicher
 * gehalten, damit {@code /money <Spieler>} ohne Datei-Scan funktioniert und
 * Bestenliste sowie Spielerauswahl nicht bei jedem Aufruf alle Dateien parsen
 * (SICHERHEITSAUDIT, ROT-2). Der Bestand wird beim Start einmal eingelesen und
 * danach über die Schreib- und Einzel-Lesepfade aktuell gehalten; direkt auf
 * dem Datenträger geänderte Dateien werden erst nach einem Neustart sichtbar.
 * Kredite liegen gebündelt in {@code loans.yml}.
 *
 * <p>Konto- und Kreditänderungen, die zusammen gehören (Kreditaufnahme,
 * Rückzahlung, Tilgung), werden zuerst in ein Journal geschrieben. Erst
 * danach werden die eigentlichen Dateien ersetzt. Bricht der Server
 * dazwischen ab, zieht {@link #init()} das Journal beim nächsten Start
 * nach. Die Einträge sind absolut und damit beliebig oft wiederholbar.</p>
 */
public final class YamlStorage implements EconomyStorage {

    private static final String FILE_EXTENSION = ".yml";
    private static final String JOURNAL_FILE = "pending-transaction.yml";

    private final JavaPlugin plugin;
    private final File folder;
    private final File loansFile;
    private final File journalFile;
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final Map<UUID, AccountData> accountIndex = new ConcurrentHashMap<>();
    private final Map<UUID, LoanData> loans = new ConcurrentHashMap<>();

    public YamlStorage(JavaPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), settings.yamlFolder());
        this.loansFile = new File(plugin.getDataFolder(), "loans.yml");
        this.journalFile = new File(plugin.getDataFolder(), JOURNAL_FILE);
    }

    @Override
    public String name() {
        return "YAML";
    }

    @Override
    public void init() {
        if (!folder.exists() && !folder.mkdirs()) {
            throw new StorageException("Konto-Ordner konnte nicht erstellt werden: " + folder.getAbsolutePath());
        }
        // Erst den vollständigen Bestand laden, dann ein eventuell offenes
        // Journal anwenden. Sonst würde loans.yml beim Nachziehen nur die
        // Kredite aus dem Journal enthalten.
        rebuildIndexes();
        loadLoansFile();
        recoverJournal();
    }

    /**
     * Zieht eine beim letzten Absturz offen gebliebene Transaktion nach.
     */
    private void recoverJournal() {
        if (!journalFile.exists()) {
            return;
        }
        try {
            applyJournal(loadJournal());
        } catch (RuntimeException exception) {
            throw new StorageException("Offene Speichertransaktion aus " + JOURNAL_FILE
                    + " konnte nicht nachgezogen werden: " + exception.getMessage(), exception);
        }
        deleteJournal();
        plugin.getLogger().info("Offene Speichertransaktion aus " + JOURNAL_FILE + " wurde nachgezogen.");
    }

    /**
     * Baut Namens- und Guthabenindex in einem Durchlauf über alle
     * Konto-Dateien auf. Eine unlesbare Datei wird mit Warnung übersprungen,
     * damit ein einzelnes beschädigtes Konto nicht den gesamten Bestand
     * unlesbar macht.
     */
    private void rebuildIndexes() {
        nameIndex.clear();
        accountIndex.clear();
        File[] files = folder.listFiles((directory, fileName) -> fileName.endsWith(FILE_EXTENSION));
        if (files == null) {
            return;
        }

        for (File file : files) {
            UUID uniqueId = parseUniqueId(file);
            if (uniqueId == null) {
                plugin.getLogger().warning("Ungültiger Konto-Dateiname: " + file.getName());
                continue;
            }
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                String rawBalance = config.getString("balance");
                if (rawBalance == null) {
                    throw new StorageException("Konto-Datei ohne Guthaben: " + file.getName());
                }
                String name = config.getString("name", "unknown");
                accountIndex.put(uniqueId, new AccountData(uniqueId, name,
                        new BigDecimal(rawBalance.trim())));
                if (name != null && !name.isBlank()) {
                    putInIndex(name, uniqueId);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Konto-Datei konnte nicht gelesen werden und wird "
                        + "übersprungen: " + file.getName() + " (" + exception.getMessage() + ")");
            }
        }
    }

    @Override
    public Optional<AccountData> load(UUID uniqueId) {
        File file = fileFor(uniqueId);
        if (!file.exists()) {
            return Optional.empty();
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String rawBalance = config.getString("balance");
        if (rawBalance == null) {
            throw new StorageException("Konto-Datei ohne Guthaben: " + file.getName());
        }

        BigDecimal balance;
        try {
            balance = new BigDecimal(rawBalance.trim());
        } catch (NumberFormatException exception) {
            throw new StorageException("Ungültiges Guthaben in " + file.getName() + ": " + rawBalance, exception);
        }

        String name = config.getString("name", "unknown");
        AccountData data = new AccountData(uniqueId, name, balance);
        // Jeder Einzel-Lesevorgang hält den Guthabenindex aktuell, damit
        // loadAll()/loadTop() keinen zweiten Datei-Durchlauf brauchen.
        accountIndex.put(uniqueId, data);
        return Optional.of(data);
    }

    @Override
    public Optional<AccountData> loadByName(String name) {
        UUID uniqueId = nameIndex.get(name.toLowerCase(Locale.ROOT));
        if (uniqueId == null) {
            return Optional.empty();
        }

        Optional<AccountData> account = load(uniqueId);
        if (account.isEmpty()) {
            nameIndex.remove(name.toLowerCase(Locale.ROOT));
        }
        return account;
    }

    /**
     * Liefert den beim Start indizierten Bestand. Ein erneuter Durchlauf über
     * alle Dateien entfällt; der Index wird von jedem Schreib- und
     * Einzel-Lesezugriff aktualisiert.
     */
    @Override
    public List<AccountData> loadAll() {
        return List.copyOf(accountIndex.values());
    }

    /**
     * Sortiert den In-Memory-Index, statt für jede Bestenliste alle
     * Konto-Dateien zu parsen (SICHERHEITSAUDIT, ROT-2).
     */
    @Override
    public List<AccountData> loadTop(int limit, boolean includeZeroBalances) {
        int maximum = Math.max(1, limit);
        return accountIndex.values().stream()
                .filter(data -> includeZeroBalances
                        ? data.balance().signum() >= 0
                        : data.balance().signum() > 0)
                .sorted(Comparator.comparing(AccountData::balance, Comparator.reverseOrder())
                        .thenComparing(AccountData::name, String.CASE_INSENSITIVE_ORDER))
                .limit(maximum)
                .toList();
    }

    @Override
    public void save(AccountData account) {
        writeAccountFile(account);
    }

    @Override
    public void saveAll(Collection<AccountData> accounts) {
        // Auch mehrere Konten (Überweisung, Zinszyklus) werden über das
        // Journal gemeinsam abgesichert.
        saveTransaction(accounts, List.of(), List.of());
    }

    @Override
    public void saveTransaction(Collection<AccountData> accounts,
                                Collection<LoanData> loanUpdates,
                                Collection<UUID> deletedLoanIds) {
        if (accounts.isEmpty() && loanUpdates.isEmpty() && deletedLoanIds.isEmpty()) {
            return;
        }
        if (journalFile.exists()) {
            // Eine frühere Transaktion wurde noch nicht vollständig
            // angewendet; erst nachziehen, damit nichts überschrieben wird.
            recoverJournal();
        }

        YamlConfiguration journal = new YamlConfiguration();

        List<Map<String, Object>> accountEntries = new ArrayList<>();
        for (AccountData account : accounts) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("uuid", account.uniqueId().toString());
            entry.put("name", account.name());
            entry.put("balance", account.balance().toPlainString());
            accountEntries.add(entry);
        }
        journal.set("accounts", accountEntries);

        if (!loanUpdates.isEmpty()) {
            List<Map<String, Object>> loanEntries = new ArrayList<>();
            for (LoanData loan : loanUpdates) {
                loanEntries.add(loanEntry(loan));
            }
            journal.set("loans", loanEntries);
        }

        if (!deletedLoanIds.isEmpty()) {
            List<String> deleted = new ArrayList<>();
            for (UUID id : deletedLoanIds) {
                deleted.add(id.toString());
            }
            journal.set("deleted-loans", deleted);
        }

        writeAtomically(journalFile, journal);
        applyJournal(journal);
        deleteJournal();
    }

    private YamlConfiguration loadJournal() {
        return YamlConfiguration.loadConfiguration(journalFile);
    }

    private void applyJournal(YamlConfiguration journal) {
        if (!journal.contains("accounts") && !journal.contains("loans")
                && !journal.contains("deleted-loans")) {
            throw new StorageException("Journal enthält keine Transaktion: " + JOURNAL_FILE);
        }

        for (Map<?, ?> entry : journal.getMapList("accounts")) {
            writeAccountFile(parseAccountEntry(entry));
        }

        for (Map<?, ?> entry : journal.getMapList("loans")) {
            LoanData loan = parseLoan(entry, true);
            loans.put(loan.id(), loan);
        }

        for (String raw : journal.getStringList("deleted-loans")) {
            try {
                loans.remove(UUID.fromString(raw));
            } catch (IllegalArgumentException exception) {
                throw new StorageException("Ungültige Kredit-ID im Journal: " + raw, exception);
            }
        }

        if (journal.contains("loans") || journal.contains("deleted-loans")) {
            writeLoansFile();
        }
    }

    private void deleteJournal() {
        try {
            Files.deleteIfExists(journalFile.toPath());
        } catch (IOException exception) {
            plugin.getLogger().warning(JOURNAL_FILE + " konnte nicht entfernt werden; "
                    + "die Transaktion wird beim nächsten Start erneut angewendet: " + exception.getMessage());
        }
    }

    private AccountData parseAccountEntry(Map<?, ?> entry) {
        try {
            UUID uniqueId = UUID.fromString(String.valueOf(entry.get("uuid")));
            Object rawName = entry.get("name");
            String name = rawName == null ? "unknown" : String.valueOf(rawName);
            return new AccountData(uniqueId, name, decimal(entry.get("balance")));
        } catch (RuntimeException exception) {
            throw new StorageException("Konto-Eintrag im Journal konnte nicht gelesen werden: "
                    + exception.getMessage(), exception);
        }
    }

    private void writeAccountFile(AccountData account) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("name", account.name());
        // Als String speichern, damit die BigDecimal-Präzision exakt erhalten bleibt.
        config.set("balance", account.balance().toPlainString());
        config.set("updated-at", System.currentTimeMillis());

        writeAtomically(fileFor(account.uniqueId()), config);
        putInIndex(account.name(), account.uniqueId());
        accountIndex.put(account.uniqueId(), account);
    }

    /**
     * Schreibt eine YAML-Datei über eine temporäre Datei, erzwingt den
     * Inhalt auf das Datenträger-Medium und ersetzt erst dann das Ziel.
     */
    private void writeAtomically(File target, YamlConfiguration config) {
        File temporary;
        try {
            temporary = File.createTempFile(target.getName() + "-", ".tmp",
                    target.getAbsoluteFile().getParentFile());
        } catch (IOException exception) {
            throw new StorageException(target.getName() + " konnte nicht vorbereitet werden: "
                    + exception.getMessage(), exception);
        }

        try {
            config.save(temporary);
            forceToDisk(temporary);
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new StorageException(target.getName() + " konnte nicht gespeichert werden: "
                    + exception.getMessage(), exception);
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    private static void forceToDisk(File file) {
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (IOException ignored) {
            // Der atomare Austausch schützt weiterhin vor halben Dateien.
        }
    }

    @Override
    public List<LoanData> loadLoans() {
        return List.copyOf(loans.values());
    }

    @Override
    public void saveLoan(LoanData loan) {
        loans.put(loan.id(), loan);
        writeLoansFile();
    }

    @Override
    public void deleteLoan(UUID id) {
        if (loans.remove(id) != null) {
            writeLoansFile();
        }
    }

    private void loadLoansFile() {
        loans.clear();
        if (!loansFile.exists()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(loansFile);
        for (Map<?, ?> entry : config.getMapList("loans")) {
            LoanData loan = parseLoan(entry, false);
            if (loan != null) {
                loans.put(loan.id(), loan);
            }
        }
    }

    private LoanData parseLoan(Map<?, ?> entry, boolean strict) {
        try {
            UUID id = UUID.fromString(String.valueOf(entry.get("id")));
            UUID owner = UUID.fromString(String.valueOf(entry.get("owner")));
            Object rawName = entry.get("owner-name");
            String ownerName = rawName == null ? "unknown" : String.valueOf(rawName);
            return new LoanData(
                    id,
                    owner,
                    ownerName,
                    decimal(entry.get("principal")),
                    decimal(entry.get("interest")),
                    decimal(entry.get("principal-remaining")),
                    decimal(entry.get("interest-remaining")),
                    ((Number) entry.get("days")).intValue(),
                    ((Number) entry.get("created-at")).longValue(),
                    ((Number) entry.get("due-at")).longValue(),
                    Boolean.TRUE.equals(entry.get("auto-collect")));
        } catch (RuntimeException exception) {
            if (strict) {
                throw new StorageException("Kredit-Eintrag im Journal konnte nicht gelesen werden: "
                        + exception.getMessage(), exception);
            }
            plugin.getLogger().warning("Kredit-Eintrag in loans.yml konnte nicht gelesen werden: "
                    + exception.getMessage());
            return null;
        }
    }

    private static BigDecimal decimal(Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Betrag fehlt");
        }
        return new BigDecimal(String.valueOf(raw).trim());
    }

    private void writeLoansFile() {
        YamlConfiguration config = new YamlConfiguration();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (LoanData loan : loans.values()) {
            entries.add(loanEntry(loan));
        }
        config.set("loans", entries);

        writeAtomically(loansFile, config);
    }

    private static Map<String, Object> loanEntry(LoanData loan) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", loan.id().toString());
        entry.put("owner", loan.owner().toString());
        entry.put("owner-name", loan.ownerName());
        entry.put("principal", loan.principal().toPlainString());
        entry.put("interest", loan.interest().toPlainString());
        entry.put("principal-remaining", loan.principalRemaining().toPlainString());
        entry.put("interest-remaining", loan.interestRemaining().toPlainString());
        entry.put("days", loan.days());
        entry.put("created-at", loan.createdAt());
        entry.put("due-at", loan.dueAt());
        entry.put("auto-collect", loan.autoCollect());
        return entry;
    }

    private void putInIndex(String name, UUID uniqueId) {
        String key = name.toLowerCase(Locale.ROOT);
        nameIndex.entrySet().removeIf(entry -> entry.getValue().equals(uniqueId) && !entry.getKey().equals(key));
        nameIndex.put(key, uniqueId);
    }

    private File fileFor(UUID uniqueId) {
        return new File(folder, uniqueId + FILE_EXTENSION);
    }

    private static UUID parseUniqueId(File file) {
        String fileName = file.getName();
        String withoutExtension = fileName.substring(0, fileName.length() - FILE_EXTENSION.length());
        try {
            return UUID.fromString(withoutExtension);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @Override
    public void close() {
        // Keine Ressourcen offen.
    }
}
