package de.Minefalo.economySystem.storage;

import de.Minefalo.economySystem.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speichert jedes Konto in einer eigenen YAML-Datei
 * ({@code accounts/<uuid>.yml}). Der Namensindex wird im Speicher
 * gehalten, damit {@code /money <Spieler>} ohne Datei-Scan funktioniert.
 * Kredite liegen gebündelt in {@code loans.yml}.
 */
public final class YamlStorage implements EconomyStorage {

    private static final String FILE_EXTENSION = ".yml";

    private final JavaPlugin plugin;
    private final File folder;
    private final File loansFile;
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final Map<UUID, LoanData> loans = new ConcurrentHashMap<>();

    public YamlStorage(JavaPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), settings.yamlFolder());
        this.loansFile = new File(plugin.getDataFolder(), "loans.yml");
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
        rebuildNameIndex();
        loadLoansFile();
    }

    private void rebuildNameIndex() {
        nameIndex.clear();
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
                String name = config.getString("name");
                if (name != null && !name.isBlank()) {
                    putInIndex(name, uniqueId);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Konto-Datei konnte nicht gelesen werden: "
                        + file.getName() + " (" + exception.getMessage() + ")");
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
        return Optional.of(new AccountData(uniqueId, name, balance));
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

    @Override
    public List<AccountData> loadAll() {
        List<AccountData> accounts = new ArrayList<>();
        File[] files = folder.listFiles((directory, fileName) -> fileName.endsWith(FILE_EXTENSION));
        if (files == null) {
            return accounts;
        }

        for (File file : files) {
            UUID uniqueId = parseUniqueId(file);
            if (uniqueId == null) {
                continue;
            }
            load(uniqueId).ifPresent(accounts::add);
        }
        return accounts;
    }

    @Override
    public void save(AccountData account) {
        File file = fileFor(account.uniqueId());
        YamlConfiguration config = new YamlConfiguration();
        config.set("name", account.name());
        // Als String speichern, damit die BigDecimal-Präzision exakt erhalten bleibt.
        config.set("balance", account.balance().toPlainString());
        config.set("updated-at", System.currentTimeMillis());

        File temporary = new File(folder, account.uniqueId() + ".tmp");
        try {
            config.save(temporary);
            try {
                Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new StorageException("Konto " + account.uniqueId() + " konnte nicht gespeichert werden.", exception);
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }

        putInIndex(account.name(), account.uniqueId());
    }

    @Override
    public void saveAll(Collection<AccountData> accounts) {
        for (AccountData account : accounts) {
            save(account);
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
            LoanData loan = parseLoan(entry);
            if (loan != null) {
                loans.put(loan.id(), loan);
            }
        }
    }

    private LoanData parseLoan(Map<?, ?> entry) {
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
            entries.add(entry);
        }
        config.set("loans", entries);

        File temporary = new File(loansFile.getParentFile(), "loans.yml.tmp");
        try {
            config.save(temporary);
            try {
                Files.move(temporary.toPath(), loansFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), loansFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new StorageException("loans.yml konnte nicht gespeichert werden.", exception);
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
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
