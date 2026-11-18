package de.Minefalo.economySystem.economy;

import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.currency.Currency;
import de.Minefalo.economySystem.storage.AccountData;
import de.Minefalo.economySystem.storage.EconomyStorage;
import de.Minefalo.economySystem.storage.LoanData;
import de.Minefalo.economySystem.storage.StorageException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
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
    private IncomeHandler incomeHandler;

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
            if (Bukkit.isPrimaryThread()) {
                throw new StorageException("Konto wird noch geladen. Bitte versuche es erneut.");
            }
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

    /** Konten werden ausschließlich im Storage-Executor geladen. */
    public CompletableFuture<Account> accountAsync(UUID uniqueId, String name) {
        Account cached = accounts.get(uniqueId);
        if (cached != null) {
            updateName(cached, name);
            return CompletableFuture.completedFuture(cached);
        }
        try {
            return CompletableFuture.supplyAsync(() -> account(uniqueId, name), executor);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /** Bereitet eine Offline-Namenssuche vor, ohne den Server-Thread zu blockieren. */
    public CompletableFuture<Optional<Account>> findAsync(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return accountAsync(online.getUniqueId(), online.getName()).thenApply(Optional::of);
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                Optional<Account> cached = accountByName(name);
                if (cached.isPresent()) {
                    return cached;
                }
                return storage.loadByName(name).map(data -> accounts.computeIfAbsent(data.uniqueId(),
                        id -> new Account(id, data.name(), currency().normalize(data.balance()))));
            }, executor);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /** Liefert nur bereits vorbereitete Konten, ohne Speicherzugriff. */
    public Optional<Account> find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online == null ? accountByName(name) : Optional.ofNullable(accounts.get(online.getUniqueId()));
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

    /**
     * Liefert alle bekannten Konten (Cache und Speicher) asynchron,
     * zum Beispiel für die Spielerauswahl im GUI.
     */
    public CompletableFuture<List<AccountData>> accountsAsync() {
        CompletableFuture<List<AccountData>> future = new CompletableFuture<>();
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
                    future.complete(List.copyOf(balances.values()));
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
        BigDecimal normalized = currency().normalize(amount);
        if (!account.deposit(normalized, currency().maxBalance())) {
            return false;
        }
        // Erst die Tilgung anstoßen: Sie speichert Konto und Kredit gemeinsam.
        // Würde das Konto vorher separat gespeichert, könnte ein Absturz
        // zwischen beiden Schreibvorgängen die Tilgung verlieren.
        notifyIncome(account, normalized);
        saveAsync(account);
        return true;
    }

    /**
     * Zahlt einen Betrag gut, ohne die Einnahmen-Aufteilung (z. B. für
     * Kredite) auszulösen.
     *
     * @return {@code false}, wenn das maximale Guthaben überschritten würde
     */
    public boolean depositDirect(Account account, BigDecimal amount) {
        if (!account.deposit(currency().normalize(amount), currency().maxBalance())) {
            return false;
        }
        saveAsync(account);
        return true;
    }

    /**
     * Bucht einen Betrag gut, ohne selbst zu speichern. Der Aufrufer muss
     * die Änderung anschließend gemeinsam mit einem Kredit über
     * {@link #saveLoanTransaction(Account, Collection, Collection)} sichern.
     *
     * @return {@code false}, wenn das maximale Guthaben überschritten würde
     */
    public boolean depositUnsaved(Account account, BigDecimal amount) {
        return account.deposit(currency().normalize(amount), currency().maxBalance());
    }

    /**
     * Bucht einen Betrag ab, ohne selbst zu speichern.
     *
     * @return {@code false}, wenn das Guthaben nicht ausreicht
     */
    public boolean withdrawUnsaved(Account account, BigDecimal amount) {
        return account.withdraw(currency().normalize(amount));
    }

    /**
     * Setzt den Kontostand, ohne selbst zu speichern.
     */
    public void setBalanceUnsaved(Account account, BigDecimal amount) {
        account.setBalance(currency().normalize(amount));
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

        // Erst die Tilgung anstoßen: Sie speichert Konto und Kredit gemeinsam.
        // Danach werden beide Konten noch einmal gemeinsam geschrieben
        // (MySQL: eine Transaktion, YAML: ein Journal).
        notifyIncome(to, normalized);
        if (!executor.isShutdown()) {
            executor.execute(() -> saveAll(List.of(from.snapshot(), to.snapshot())));
        }
        return true;
    }

    /**
     * Lädt das Konto beim Beitritt asynchron vor, damit /money sofort
     * antwortet.
     */
    public void preload(Player player) {
        UUID uniqueId = player.getUniqueId();
        String name = player.getName();
        accountAsync(uniqueId, name).exceptionally(exception -> {
            plugin.getLogger().warning("Konto von " + name + " konnte nicht geladen werden: "
                    + exception.getMessage());
            return null;
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
     * Erst im Executor einen Snapshot erstellen, damit zwischenzeitliche
     * Änderungen (insbesondere ein Zinszyklus) nicht überschrieben werden.
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
        executor.execute(() -> {
            List<AccountData> dirty = dirtySnapshots();
            if (!dirty.isEmpty()) {
                saveAll(dirty);
            }
        });
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
        Map<UUID, BigDecimal> creditsByAccount = new LinkedHashMap<>();
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
                    creditsByAccount.put(cached.uniqueId(), credit);
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
            creditsByAccount.put(data.uniqueId(), credit);
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

        return new InterestResult(balances.size(), credited,
                currency().normalize(totalCredited), creditsByAccount);
    }

    /**
     * Registriert den Empfänger für Gutschriften (z. B. die Kredit-Tilgung).
     */
    public void setIncomeHandler(IncomeHandler handler) {
        this.incomeHandler = handler;
    }

    private void notifyIncome(Account account, BigDecimal amount) {
        IncomeHandler handler = incomeHandler;
        if (handler == null || amount == null || amount.signum() <= 0) {
            return;
        }
        try {
            handler.onIncome(account, amount);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Einnahmen konnten nicht mit Krediten verrechnet werden: "
                    + exception.getMessage());
        }
    }

    /**
     * Speichert eine Kontostandsänderung gemeinsam mit einer Kreditänderung.
     * Im MySQL-Modus geschieht das in einer Transaktion, im YAML-Modus über
     * ein Journal, das bei einem Absturz beim nächsten Start nachgezogen
     * wird. So kann eine Kreditaufnahme oder -tilgung nicht "halb"
     * gespeichert werden.
     */
    public void saveLoanTransaction(Account account, LoanData loan) {
        saveLoanTransaction(account, List.of(loan), List.of());
    }

    /**
     * Speichert eine Kontostandsänderung gemeinsam mit mehreren Kredit-
     * änderungen (Aktualisierungen und Löschungen).
     */
    public void saveLoanTransaction(Account account,
                                    Collection<LoanData> loans,
                                    Collection<UUID> deletedLoanIds) {
        if (executor.isShutdown()) {
            return;
        }
        List<LoanData> loanSnapshots = List.copyOf(loans);
        List<UUID> deleted = List.copyOf(deletedLoanIds);
        try {
            executor.execute(() -> {
                AccountData data = account.snapshot();
                try {
                    storage.saveTransaction(List.of(data), loanSnapshots, deleted);
                    account.markSaved(data);
                } catch (StorageException exception) {
                    plugin.getLogger().severe("Konto " + data.uniqueId()
                            + " und Kreditänderungen konnten nicht gemeinsam gespeichert werden: "
                            + exception.getMessage());
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Kreditänderung konnte nicht gespeichert werden: "
                    + exception.getMessage());
        }
    }

    /**
     * Speichert einen Kredit asynchron.
     */
    public void saveLoanAsync(LoanData loan) {
        if (executor.isShutdown()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    storage.saveLoan(loan);
                } catch (StorageException exception) {
                    plugin.getLogger().severe("Kredit " + loan.id()
                            + " konnte nicht gespeichert werden: " + exception.getMessage());
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Kredit " + loan.id()
                    + " konnte nicht gespeichert werden: " + exception.getMessage());
        }
    }

    /**
     * Entfernt einen vollständig zurückgezahlten Kredit asynchron.
     */
    public void deleteLoanAsync(UUID id) {
        if (executor.isShutdown()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    storage.deleteLoan(id);
                } catch (StorageException exception) {
                    plugin.getLogger().severe("Kredit " + id
                            + " konnte nicht entfernt werden: " + exception.getMessage());
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Kredit " + id
                    + " konnte nicht entfernt werden: " + exception.getMessage());
        }
    }

    /**
     * Empfänger für Gutschriften auf einem Konto.
     */
    public interface IncomeHandler {
        void onIncome(Account account, BigDecimal amount);
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

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        try {
            while (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                if (System.nanoTime() - deadline >= 0) {
                    List<Runnable> dropped = executor.shutdownNow();
                    plugin.getLogger().severe("Speicherung beim Stoppen nach 60 Sekunden abgebrochen; "
                            + dropped.size() + " Vorgänge wurden verworfen. Offene Änderungen können verloren gehen.");
                    break;
                }
                plugin.getLogger().warning("Speichervorgänge laufen noch; der Server wartet auf ihren Abschluss.");
            }
        } catch (InterruptedException exception) {
            plugin.getLogger().severe("Warten auf Speichervorgänge unterbrochen; offene Änderungen könnten verloren gehen.");
            Thread.currentThread().interrupt();
        }
    }
}
