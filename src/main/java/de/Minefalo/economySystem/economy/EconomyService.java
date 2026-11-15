package de.Minefalo.economySystem.economy;

import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.currency.Currency;
import de.Minefalo.economySystem.storage.AccountData;
import de.Minefalo.economySystem.storage.EconomyStorage;
import de.Minefalo.economySystem.storage.StorageException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Zentrale Economy-Logik. Konten werden im Speicher gehalten und
 * asynchron über die {@link EconomyStorage}-Implementierung gesichert.
 */
public final class EconomyService {

    private final JavaPlugin plugin;
    private final Settings settings;
    private final EconomyStorage storage;
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    private BukkitTask autoSaveTask;

    public EconomyService(JavaPlugin plugin, Settings settings, EconomyStorage storage) {
        this.plugin = plugin;
        this.settings = settings;
        this.storage = storage;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "EconomySystem-Storage");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void init() {
        storage.init();
        scheduleAutoSave();
    }

    public Currency currency() {
        return settings.currency();
    }

    public EconomyStorage storage() {
        return storage;
    }

    public int cachedAccountCount() {
        return accounts.size();
    }

    /**
     * Liefert das Konto und legt es bei Bedarf mit dem Startguthaben an.
     */
    public Account account(UUID uniqueId, String name) {
        Account account = accounts.get(uniqueId);
        if (account == null) {
            account = accounts.computeIfAbsent(uniqueId, id -> loadAccount(id, name));
        }
        updateName(account, name);
        return account;
    }

    private Account loadAccount(UUID uniqueId, String name) {
        Optional<AccountData> stored = storage.load(uniqueId);
        if (stored.isPresent()) {
            AccountData data = stored.get();
            // Gespeicherten Namen übernehmen; ein abweichender aktueller Name
            // wird anschließend von updateName() erkannt und gespeichert.
            return new Account(uniqueId, data.name(), currency().normalize(data.balance()));
        }

        Account account = new Account(uniqueId, name, currency().startingBalance());
        account.markDirty();
        saveAsync(account);
        return account;
    }

    private void updateName(Account account, String name) {
        if (name == null || name.isBlank() || name.equals(account.name())) {
            return;
        }
        account.name(name);
        saveAsync(account);
    }

    /**
     * Sucht ein Konto anhand des Spielernamens (online oder gespeichert).
     */
    public Optional<Account> find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(account(online.getUniqueId(), online.getName()));
        }

        Optional<Account> cached = accounts.values().stream()
                .filter(account -> account.name().equalsIgnoreCase(name))
                .findFirst();
        if (cached.isPresent()) {
            return cached;
        }

        Optional<AccountData> stored = storage.loadByName(name);
        if (stored.isEmpty()) {
            return Optional.empty();
        }

        AccountData data = stored.get();
        Account account = accounts.computeIfAbsent(data.uniqueId(),
                id -> new Account(id, data.name(), currency().normalize(data.balance())));
        return Optional.of(account);
    }

    /**
     * Sucht einen Kontostand asynchron, ohne den Haupt-Thread zu blockieren.
     * Zuerst wird der Cache geprüft, danach der Speicher.
     *
     * @param name Spielername
     * @return Future mit dem Kontostand, falls ein Konto existiert
     */
    public CompletableFuture<Optional<BigDecimal>> balanceAsync(String name) {
        CompletableFuture<Optional<BigDecimal>> future = new CompletableFuture<>();
        if (name == null || name.isBlank() || executor.isShutdown()) {
            future.complete(Optional.empty());
            return future;
        }

        try {
            executor.execute(() -> {
                try {
                    Optional<Account> cached = accountByName(name);
                    if (cached.isPresent()) {
                        future.complete(Optional.of(cached.get().balance()));
                        return;
                    }
                    future.complete(storage.loadByName(name).map(AccountData::balance));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    /**
     * Liefert die Konten mit dem höchsten Guthaben (absteigend) asynchron.
     * Cache und Speicher werden zusammengeführt, damit auch offline
     * gespeicherte Konten berücksichtigt werden.
     *
     * @param limit maximale Anzahl der Einträge
     */
    public CompletableFuture<List<AccountData>> topAsync(int limit) {
        CompletableFuture<List<AccountData>> future = new CompletableFuture<>();
        int maximum = Math.max(1, limit);
        if (executor.isShutdown()) {
            future.complete(List.of());
            return future;
        }

        try {
            executor.execute(() -> {
                try {
                    Map<UUID, AccountData> balances = new LinkedHashMap<>();
                    for (AccountData data : storage.loadAll()) {
                        balances.put(data.uniqueId(), data);
                    }
                    for (Account account : accounts.values()) {
                        balances.put(account.uniqueId(), account.snapshot());
                    }

                    List<AccountData> top = balances.values().stream()
                            .filter(data -> data.balance().signum() > 0)
                            .sorted(Comparator.comparing(AccountData::balance,
                                            Comparator.reverseOrder())
                                    .thenComparing(AccountData::name, String.CASE_INSENSITIVE_ORDER))
                            .limit(maximum)
                            .toList();
                    future.complete(top);
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    private Optional<Account> accountByName(String name) {
        return accounts.values().stream()
                .filter(account -> account.name().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * Kontostand aus dem Cache, ohne Speicher- oder Bukkit-Zugriff.
     */
    public Optional<BigDecimal> cachedBalance(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return accountByName(name).map(Account::balance);
    }

    public BigDecimal balance(UUID uniqueId, String name) {
        return account(uniqueId, name).balance();
    }

    /**
     * @return {@code false}, wenn das maximale Guthaben überschritten würde
     */
    public boolean deposit(Account account, BigDecimal amount) {
        if (!account.deposit(currency().normalize(amount), currency().maxBalance())) {
            return false;
        }
        saveAsync(account);
        return true;
    }

    /**
     * @return {@code false}, wenn das Guthaben nicht ausreicht
     */
    public boolean withdraw(Account account, BigDecimal amount) {
        if (!account.withdraw(currency().normalize(amount))) {
            return false;
        }
        saveAsync(account);
        return true;
    }

    public void setBalance(Account account, BigDecimal amount) {
        account.setBalance(currency().normalize(amount));
        saveAsync(account);
    }

    /**
     * Überweist Geld zwischen zwei Konten. Schlägt die Gutschrift fehl
     * (maximales Guthaben), wird die Abbuchung zurückgenommen.
     *
     * @return {@code false}, wenn das Guthaben nicht reicht oder das
     *         Zielkonto das Maximum überschreiten würde
     */
    public boolean transfer(Account from, Account to, BigDecimal amount) {
        BigDecimal normalized = currency().normalize(amount);
        if (from.uniqueId().equals(to.uniqueId())) {
            return false;
        }
        if (!from.withdraw(normalized)) {
            return false;
        }
        if (!to.deposit(normalized, currency().maxBalance())) {
            from.deposit(normalized, null);
            return false;
        }

        saveAsync(from);
        saveAsync(to);
        return true;
    }

    /**
     * Lädt das Konto beim Beitritt asynchron vor, damit /money sofort
     * antwortet.
     */
    public void preload(Player player) {
        UUID uniqueId = player.getUniqueId();
        String name = player.getName();
        executor.execute(() -> {
            try {
                account(uniqueId, name);
            } catch (StorageException exception) {
                plugin.getLogger().warning("Konto von " + name + " konnte nicht geladen werden: "
                        + exception.getMessage());
            }
        });
    }

    /**
     * Speichert das Konto beim Verlassen des Servers.
     */
    public void handleQuit(Player player) {
        Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            return;
        }
        updateName(account, player.getName());
        saveAsync(account);
    }

    /**
     * Speichert das Konto asynchron. Der Snapshot wird erst im Executor
     * erstellt, damit später eingereihte Speichervorgänge immer den
     * aktuellsten Stand schreiben und keine zwischenzeitlichen Änderungen
     * überschreiben.
     */
    private void saveAsync(Account account) {
        if (executor.isShutdown()) {
            return;
        }
        executor.execute(() -> {
            AccountData data = account.snapshot();
            try {
                storage.save(data);
                account.markSaved(data);
            } catch (StorageException exception) {
                plugin.getLogger().severe("Konto " + data.uniqueId()
                        + " konnte nicht gespeichert werden: " + exception.getMessage());
            }
        });
    }

    /**
     * Speichert alle geänderten Konten asynchron.
     */
    public void flushAsync() {
        if (executor.isShutdown()) {
            return;
        }
        executor.execute(this::saveDirty);
    }

    private void saveDirty() {
        List<AccountData> dirty = dirtySnapshots();
        if (!dirty.isEmpty()) {
            saveAll(dirty);
        }
    }

    private void saveAll(List<AccountData> dirty) {
        try {
            storage.saveAll(dirty);
            for (AccountData data : dirty) {
                Account account = accounts.get(data.uniqueId());
                if (account != null) {
                    account.markSaved(data);
                }
            }
        } catch (StorageException exception) {
            plugin.getLogger().severe("Konten konnten nicht gespeichert werden: " + exception.getMessage());
        }
    }

    private List<AccountData> dirtySnapshots() {
        List<AccountData> dirty = new ArrayList<>();
        for (Account account : accounts.values()) {
            if (account.isDirty()) {
                dirty.add(account.snapshot());
            }
        }
        return dirty;
    }

    /**
     * Führt einen Zinszyklus für alle gespeicherten Konten aus (auch offline).
     * Die Berechnung läuft auf dem Storage-Executor, damit Kontozugriffe und
     * Speichervorgänge serialisiert bleiben.
     *
     * <p>Formel: {@code y = a * sqrt(x)} mit {@code x = Kontostand} und
     * {@code a = savingsFactor}.</p>
     *
     * @param savingsFactor Faktor a des Sparzinses (muss größer als 0 sein)
     * @return Future mit dem Ergebnis des Zyklus
     */
    public CompletableFuture<InterestResult> runInterestCycle(BigDecimal savingsFactor) {
        CompletableFuture<InterestResult> future = new CompletableFuture<>();
        if (savingsFactor == null || savingsFactor.signum() <= 0) {
            future.completeExceptionally(new IllegalArgumentException(
                    "Der Sparzins-Faktor muss größer als 0 sein."));
            return future;
        }
        if (executor.isShutdown()) {
            future.completeExceptionally(new IllegalStateException(
                    "Der Economy-Speicher ist bereits geschlossen."));
            return future;
        }

        try {
            executor.execute(() -> {
                try {
                    future.complete(applyInterestCycle(savingsFactor));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    private InterestResult applyInterestCycle(BigDecimal savingsFactor) {
        // Gespeicherte Konten laden und mit den aktuellen Cache-Werten
        // überschreiben, damit keine Änderungen verloren gehen.
        Map<UUID, AccountData> balances = new LinkedHashMap<>();
        for (AccountData data : storage.loadAll()) {
            balances.put(data.uniqueId(), data);
        }
        for (Account account : accounts.values()) {
            balances.put(account.uniqueId(), account.snapshot());
        }

        BigDecimal maxBalance = currency().maxBalance();
        List<AccountData> changed = new ArrayList<>();
        BigDecimal totalCredited = BigDecimal.ZERO;
        int credited = 0;

        for (AccountData data : balances.values()) {
            Account cached = accounts.get(data.uniqueId());
            if (cached != null) {
                synchronized (cached) {
                    BigDecimal credit = InterestCalculator.interestFor(
                            cached.balance(), savingsFactor, maxBalance, currency());
                    if (credit.signum() <= 0 || !cached.deposit(credit, maxBalance)) {
                        continue;
                    }
                    changed.add(cached.snapshot());
                    totalCredited = totalCredited.add(credit);
                    credited++;
                }
                continue;
            }

            BigDecimal balance = currency().normalize(data.balance());
            BigDecimal credit = InterestCalculator.interestFor(
                    balance, savingsFactor, maxBalance, currency());
            if (credit.signum() <= 0) {
                continue;
            }
            changed.add(new AccountData(data.uniqueId(), data.name(),
                    currency().normalize(balance.add(credit))));
            totalCredited = totalCredited.add(credit);
            credited++;
        }

        if (!changed.isEmpty()) {
            storage.saveAll(changed);
            for (AccountData data : changed) {
                Account cached = accounts.get(data.uniqueId());
                if (cached != null) {
                    cached.markSaved(data);
                }
            }
        }

        return new InterestResult(balances.size(), credited, currency().normalize(totalCredited));
    }

    private void scheduleAutoSave() {
        int seconds = settings.saveIntervalSeconds();
        if (seconds <= 0) {
            return;
        }
        long ticks = seconds * 20L;
        autoSaveTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::flushAsync, ticks, ticks);
    }

    /**
     * Übernimmt geänderte Einstellungen (z. B. Speicherintervall).
     */
    public void reload() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        scheduleAutoSave();
    }

    /**
     * Speichert beim Deaktivieren alle offenen Änderungen und schließt den
     * Speicher.
     */
    public void shutdown() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }

        if (executor.isShutdown()) {
            return;
        }

        executor.execute(() -> {
            try {
                List<AccountData> dirty = dirtySnapshots();
                if (!dirty.isEmpty()) {
                    storage.saveAll(dirty);
                }
            } catch (StorageException exception) {
                plugin.getLogger().severe("Konten konnten beim Herunterfahren nicht gespeichert werden: "
                        + exception.getMessage());
            } finally {
                storage.close();
            }
        });
        executor.shutdown();

        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Speichern beim Herunterfahren dauerte länger als 15 Sekunden.");
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
