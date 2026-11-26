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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final String TRANSACTION_FILE = "api-transactions.yml";
    private static final String AUDIT_FILE = "audit.yml";
    private static final int AUDIT_LIMIT = 1000;

    private final JavaPlugin plugin;
    private final File folder;
    private final File loansFile;
    private final File journalFile;
    private final File transactionFile;
    private final File auditFile;
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final Map<UUID, AccountData> accountIndex = new ConcurrentHashMap<>();
    private final Map<UUID, LoanData> loans = new ConcurrentHashMap<>();
    /**
     * Dauerhaft gebuchte Transaktions-IDs der Entwickler-API. Das Register
     * wird zusammen mit dem Kontostand im Journal geschrieben, damit eine
     * Wiederholung nach einem Timeout nichts doppelt bucht.
     */
    private final Set<UUID> processedTransactions =
            Collections.synchronizedSet(new TransactionLedger());
    private final List<AuditEntry> auditEntries = new ArrayList<>();
    private final Set<UUID> auditIds = new HashSet<>();

    public YamlStorage(JavaPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.folder = StoragePaths.resolve(plugin, settings.yamlFolder());
        this.loansFile = StoragePaths.resolve(plugin, "loans.yml");
        this.journalFile = StoragePaths.resolve(plugin, JOURNAL_FILE);
        this.transactionFile = StoragePaths.resolve(plugin, TRANSACTION_FILE);
        this.auditFile = StoragePaths.resolve(plugin, AUDIT_FILE);
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
        loadTransactionLedger();
        loadAuditFile();
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
            accountEntries.add(accountEntry(account));
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

    /**
     * Idempotente Einzelbuchung der Entwickler-API: Kontostand und
     * Transaktions-ID landen gemeinsam im Journal. Erst danach wird die
     * Konto-Datei ersetzt und das Register fortgeschrieben.
     */
    @Override
    public boolean saveIdempotent(AccountData account, UUID transactionId) {
        if (processedTransactions.contains(transactionId)) {
            return false;
        }
        if (journalFile.exists()) {
            // Eine frühere Transaktion wurde noch nicht vollständig
            // angewendet; erst nachziehen, damit nichts überschrieben wird.
            recoverJournal();
        }

        YamlConfiguration journal = new YamlConfiguration();
        journal.set("accounts", List.of(accountEntry(account)));
        journal.set("transactions", List.of(transactionId.toString()));

        writeAtomically(journalFile, journal);
        applyJournal(journal);
        deleteJournal();
        return true;
    }

    @Override
    public boolean hasTransaction(UUID transactionId) {
        return processedTransactions.contains(transactionId);
    }

    private static Map<String, Object> accountEntry(AccountData account) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("uuid", account.uniqueId().toString());
        entry.put("name", account.name());
        entry.put("balance", account.balance().toPlainString());
        return entry;
    }

    private YamlConfiguration loadJournal() {
        return YamlConfiguration.loadConfiguration(journalFile);
    }

    private void applyJournal(YamlConfiguration journal) {
        if (!journal.contains("accounts") && !journal.contains("loans")
                && !journal.contains("deleted-loans") && !journal.contains("transactions")) {
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

        List<String> transactions = journal.getStringList("transactions");
        for (String raw : transactions) {
            try {
                processedTransactions.add(UUID.fromString(raw));
            } catch (IllegalArgumentException exception) {
                throw new StorageException("Ungültige Transaktions-ID im Journal: " + raw, exception);
            }
        }
        if (!transactions.isEmpty()) {
            writeTransactionLedger();
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

    private void loadTransactionLedger() {
        processedTransactions.clear();
        if (!transactionFile.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(transactionFile);
        for (String raw : config.getStringList("transactions")) {
            try {
                processedTransactions.add(UUID.fromString(raw));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ungültige Transaktions-ID in " + TRANSACTION_FILE
                        + ": " + raw);
            }
        }
    }

    private void writeTransactionLedger() {
        YamlConfiguration config = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        synchronized (processedTransactions) {
            for (UUID id : processedTransactions) {
                ids.add(id.toString());
            }
        }
        config.set("transactions", ids);
        writeAtomically(transactionFile, config);
    }

    // ------------------------------------------------------------------
    // Audit-Trail
    // ------------------------------------------------------------------

    @Override
    public void recordAudit(AuditEntry entry) {
        synchronized (auditEntries) {
            if (!auditIds.add(entry.txId())) {
                return;
            }
            auditEntries.add(entry);
            auditEntries.sort(Comparator.comparingLong(AuditEntry::occurredAt));
            while (auditEntries.size() > AUDIT_LIMIT) {
                AuditEntry removed = auditEntries.remove(0);
                auditIds.remove(removed.txId());
            }
            writeAuditFile();
        }
    }

    @Override
    public List<AuditEntry> audit(String scope, int limit) {
        int maximum = Math.max(1, Math.min(limit, 100));
        List<AuditEntry> result = new ArrayList<>();
        synchronized (auditEntries) {
            for (int index = auditEntries.size() - 1;
                 index >= 0 && result.size() < maximum; index--) {
                AuditEntry entry = auditEntries.get(index);
                if (entry.scope() != null && entry.scope().equals(scope)) {
                    result.add(entry);
                }
            }
        }
        return result;
    }

    @Override
    public int pruneAudit(String scope, long cutoffMillis, int batchSize) {
        int limit = Math.max(1, Math.min(batchSize, 1000));
        int deleted = 0;
        synchronized (auditEntries) {
            Iterator<AuditEntry> iterator = auditEntries.iterator();
            while (iterator.hasNext() && deleted < limit) {
                AuditEntry entry = iterator.next();
                if (entry.occurredAt() < cutoffMillis
                        && entry.scope() != null && entry.scope().equals(scope)) {
                    iterator.remove();
                    auditIds.remove(entry.txId());
                    deleted++;
                }
            }
            if (deleted > 0) {
                writeAuditFile();
            }
        }
        return deleted;
    }

    private void loadAuditFile() {
        synchronized (auditEntries) {
            auditEntries.clear();
            auditIds.clear();
            if (!auditFile.exists()) {
                return;
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(auditFile);
            for (Map<?, ?> entry : config.getMapList("entries")) {
                try {
                    AuditEntry audit = parseAudit(entry);
                    auditEntries.add(audit);
                    auditIds.add(audit.txId());
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Audit-Eintrag in " + AUDIT_FILE
                            + " konnte nicht gelesen werden: " + exception.getMessage());
                }
            }
            auditEntries.sort(Comparator.comparingLong(AuditEntry::occurredAt));
            while (auditEntries.size() > AUDIT_LIMIT) {
                AuditEntry removed = auditEntries.remove(0);
                auditIds.remove(removed.txId());
            }
        }
    }

    private void writeAuditFile() {
        YamlConfiguration config = new YamlConfiguration();
        List<Map<String, Object>> entries = new ArrayList<>();
        synchronized (auditEntries) {
            for (AuditEntry entry : auditEntries) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tx-id", entry.txId().toString());
                item.put("occurred-at", entry.occurredAt());
                item.put("server-id", entry.serverId());
                item.put("scope", entry.scope());
                item.put("actor-uuid", entry.actorId() == null ? null : entry.actorId().toString());
                item.put("actor-name", entry.actorName());
                item.put("origin", entry.origin());
                item.put("action", entry.action());
                item.put("target-uuid", entry.targetId() == null ? null : entry.targetId().toString());
                item.put("target-name", entry.targetName());
                item.put("amount", entry.amount() == null ? null : entry.amount().toPlainString());
                item.put("balance-after", entry.balanceAfter() == null
                        ? null : entry.balanceAfter().toPlainString());
                item.put("reason", entry.reason());
                item.put("result", entry.result().name());
                entries.add(item);
            }
        }
        config.set("entries", entries);
        writeAtomically(auditFile, config);
    }

    private static AuditEntry parseAudit(Map<?, ?> entry) {
        return new AuditEntry(
                UUID.fromString(String.valueOf(entry.get("tx-id"))),
                ((Number) entry.get("occurred-at")).longValue(),
                textOrNull(entry.get("server-id")),
                textOrNull(entry.get("scope")),
                uuidOrNull(entry.get("actor-uuid")),
                textOrNull(entry.get("actor-name")),
                textOrNull(entry.get("origin")),
                textOrNull(entry.get("action")),
                uuidOrNull(entry.get("target-uuid")),
                textOrNull(entry.get("target-name")),
                decimalOrNull(entry.get("amount")),
                decimalOrNull(entry.get("balance-after")),
                textOrNull(entry.get("reason")),
                AuditEntry.Result.valueOf(String.valueOf(entry.get("result"))));
    }

    private static String textOrNull(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static UUID uuidOrNull(Object raw) {
        String text = textOrNull(raw);
        return text == null || text.isBlank() ? null : UUID.fromString(text.trim());
    }

    private static BigDecimal decimalOrNull(Object raw) {
        String text = textOrNull(raw);
        return text == null || text.isBlank() ? null : new BigDecimal(text.trim());
    }

    /**
     * Transaktionsregister mit fester Obergrenze. Die ältesten Einträge
     * fallen zuerst heraus; für Wiederholungen nach Timeouts genügt ein
     * begrenzter Zeitraum.
     */
    private static final class TransactionLedger extends LinkedHashSet<UUID> {

        private static final long serialVersionUID = 1L;
        private static final int LIMIT = 10_000;

        @Override
        public boolean add(UUID transactionId) {
            boolean added = super.add(transactionId);
            if (size() > LIMIT) {
                Iterator<UUID> iterator = iterator();
                while (size() > LIMIT && iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            return added;
        }
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
