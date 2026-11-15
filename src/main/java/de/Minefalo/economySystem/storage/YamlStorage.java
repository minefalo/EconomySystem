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
 */
public final class YamlStorage implements EconomyStorage {

    private static final String FILE_EXTENSION = ".yml";

    private final JavaPlugin plugin;
    private final File folder;
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();

    public YamlStorage(JavaPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), settings.yamlFolder());
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
