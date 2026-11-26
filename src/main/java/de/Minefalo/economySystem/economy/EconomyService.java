package de.Minefalo.economySystem.economy;

import de.Minefalo.economySystem.api.BuchungsErgebnis;
import de.Minefalo.economySystem.api.BuchungsTyp;
import de.Minefalo.economySystem.api.event.EconomyTransactionEvent;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.currency.Currency;
import de.Minefalo.economySystem.storage.AccountData;
import de.Minefalo.economySystem.storage.AuditEntry;
import de.Minefalo.economySystem.storage.EconomyStorage;
import de.Minefalo.economySystem.storage.LeaseInfo;
import de.Minefalo.economySystem.storage.LoanData;
import de.Minefalo.economySystem.storage.StorageException;
import de.Minefalo.economySystem.storage.TransferData;
import de.Minefalo.economySystem.storage.TransferResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
    private static final DateTimeFormatter CYCLE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    /** Zuletzt bekannter Name je Konto, auch für nicht geladene Offline-Konten. */
    private final Map<UUID, String> knownNames = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    /** Schneller Idempotenz-Cache der zuletzt über die API gebuchten IDs. */
    private final Set<UUID> processedTransactions =
            Collections.synchronizedSet(new TransactionCache());
    private IncomeHandler incomeHandler;

    private BukkitTask autoSaveTask;
    private BukkitTask auditTask;

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
        loadKnownNames();
        scheduleAutoSave();
        scheduleAuditCleanup();
    }

    /**
     * Füllt den Namensindex für die Tab-Vervollständigung mit allen
     * gespeicherten Konten, auch von offline Spielern. Läuft im
     * Storage-Executor, damit der Start nicht blockiert.
     */
    private void loadKnownNames() {
        if (executor.isShutdown()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    for (AccountData data : storage.loadAll()) {
                        rememberName(data.uniqueId(), data.name());
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Spielernamen konnten nicht geladen werden: "
                            + exception.getMessage());
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Spielernamen konnten nicht geladen werden: "
                    + exception.getMessage());
        }
    }

    private void rememberName(UUID uniqueId, String name) {
        if (uniqueId != null && name != null && !name.isBlank()) {
            knownNames.put(uniqueId, name);
        }
    }

    /**
     * Alle bekannten Spielernamen aus Cache und Speicher, sortiert und ohne
     * Duplikate. Für die Tab-Vervollständigung ohne Speicherzugriff nutzbar.
     */
    public List<String> knownPlayerNames() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Account account : accounts.values()) {
            names.add(account.name());
        }
        names.addAll(knownNames.values());
        return List.copyOf(names);
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
            rememberName(uniqueId, data.name());
            return new Account(uniqueId, data.name(), currency().normalize(data.balance()));
        }

        Account account = new Account(uniqueId, name, currency().startingBalance());
        rememberName(uniqueId, name);
        account.markDirty();
        saveAsync(account);
        return account;
    }

    private void updateName(Account account, String name) {
        if (name == null || name.isBlank() || name.equals(account.name())) {
            return;
        }
        account.name(name);
        rememberName(account.uniqueId(), name);
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

    /**
     * Liefert ein vorhandenes Konto, ohne eines anzulegen. Wird für
     * seiteneffektfreie Fremdzugriffe (z. B. Vault) verwendet; unbekannte
     * Konten liefern ein leeres Optional.
     */
    public CompletableFuture<Optional<Account>> existingAccountAsync(UUID uniqueId, String name) {
        Account cached = accounts.get(uniqueId);
        if (cached != null) {
            updateName(cached, name);
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        if (executor.isShutdown()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            return CompletableFuture.supplyAsync(() -> storage.load(uniqueId)
                    .map(data -> {
                        rememberName(data.uniqueId(), data.name());
                        return accounts.computeIfAbsent(data.uniqueId(),
                                id -> new Account(id, data.name(), currency().normalize(data.balance())));
                    }),
                    executor);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /**
     * Löst einen Namen über Cache und Speicher auf ein bekanntes Konto auf,
     * ohne eines anzulegen. Läuft vollständig auf dem Storage-Executor und
     * ruft deshalb keine Bukkit-API aus fremden Threads auf.
     */
    public CompletableFuture<Optional<Account>> existingAccountByNameAsync(String name) {
        if (name == null || name.isBlank() || executor.isShutdown()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                Optional<Account> cached = accountByName(name);
                if (cached.isPresent()) {
                    return cached;
                }
                return storage.loadByName(name).map(data -> {
                    rememberName(data.uniqueId(), data.name());
                    return accounts.computeIfAbsent(data.uniqueId(),
                            id -> new Account(id, data.name(), currency().normalize(data.balance())));
                });
            }, executor);
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
                return storage.loadByName(name).map(data -> {
                    rememberName(data.uniqueId(), data.name());
                    return accounts.computeIfAbsent(data.uniqueId(),
                            id -> new Account(id, data.name(), currency().normalize(data.balance())));
                });
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
                    Optional<AccountData> stored = storage.loadByName(name);
                    stored.ifPresent(data -> rememberName(data.uniqueId(), data.name()));
                    future.complete(stored.map(AccountData::balance));
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
     * Aus dem Speicher kommen nur die besten {@code limit} Einträge
     * ({@link EconomyStorage#loadTop}); der Cache der Online-Spieler wird
     * zusätzlich zusammengeführt, weil deren Guthaben aktueller sein kann als
     * der gespeicherte Stand.
     *
     * @param limit maximale Anzahl der Einträge
     */
    public CompletableFuture<List<AccountData>> topAsync(int limit) {
        return topAsync(limit, false);
    }

    /**
     * Wie {@link #topAsync(int)}; mit {@code includeZeroBalances} werden auch
     * Konten mit Guthaben 0 aufgenommen. Negative Guthaben bleiben außen vor.
     */
    public CompletableFuture<List<AccountData>> topAsync(int limit, boolean includeZeroBalances) {
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
                    // Der Speicher liefert nur die besten Einträge; loadAll()
                    // bleibt dem Zinszyklus vorbehalten (SICHERHEITSAUDIT, ROT-2).
                    for (AccountData data : storage.loadTop(maximum, includeZeroBalances)) {
                        rememberName(data.uniqueId(), data.name());
                        balances.put(data.uniqueId(), data);
                    }
                    for (Account account : accounts.values()) {
                        balances.put(account.uniqueId(), account.snapshot());
                    }

                    List<AccountData> top = balances.values().stream()
                            .filter(data -> includeZeroBalances
                                    ? data.balance().signum() >= 0
                                    : data.balance().signum() > 0)
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
                        rememberName(data.uniqueId(), data.name());
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
        if (readOnly()) {
            return false;
        }
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
        if (readOnly()) {
            return false;
        }
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
        if (readOnly()) {
            return false;
        }
        if (!account.withdraw(currency().normalize(amount))) {
            return false;
        }
        saveAsync(account);
        return true;
    }

    public void setBalance(Account account, BigDecimal amount) {
        if (readOnly()) {
            return;
        }
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
        if (readOnly()) {
            return false;
        }
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
        // (SQLite/MySQL: eine Transaktion, YAML: ein Journal).
        notifyIncome(to, normalized);
        if (!executor.isShutdown()) {
            executor.execute(() -> saveAll(List.of(from.snapshot(), to.snapshot())));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Entwickler-API (Dauerhafte, idempotente Buchungen)
    // ------------------------------------------------------------------

    /**
     * {@code true}, wenn dieser Server im Modus {@code READ_ONLY} läuft.
     *
     * <p>Die Prüfung liegt bewusst in der Kernlogik und nicht nur im
     * Befehls-Router: Auch Vault und die
     * {@link de.Minefalo.economySystem.api.EconomyApi} buchen über diese
     * Methoden und werden so zentral gesperrt.</p>
     */
    public boolean readOnly() {
        return settings.network().readOnly();
    }

    /**
     * Bucht einen Betrag dauerhaft und idempotent vom Konto ab. Das Future
     * schließt erst ab, nachdem der neue Kontostand im Speicher geschrieben
     * wurde.
     *
     * @see de.Minefalo.economySystem.api.EconomyApi#abbuchen
     */
    public CompletableFuture<BuchungsErgebnis> abbuchen(UUID transactionId, UUID spielerId,
                                                        String spielerName, BigDecimal betrag,
                                                        String quelle, String grund) {
        return book(BuchungsTyp.ABBUCHUNG, transactionId, spielerId, spielerName,
                betrag, quelle, grund);
    }

    /**
     * Schreibt einen Betrag dauerhaft und idempotent gut. Das Future schließt
     * erst ab, nachdem der neue Kontostand im Speicher geschrieben wurde.
     *
     * @see de.Minefalo.economySystem.api.EconomyApi#erstatten
     */
    public CompletableFuture<BuchungsErgebnis> erstatten(UUID transactionId, UUID spielerId,
                                                         String spielerName, BigDecimal betrag,
                                                         String quelle, String grund) {
        return book(BuchungsTyp.ERSTATTUNG, transactionId, spielerId, spielerName,
                betrag, quelle, grund);
    }

    private CompletableFuture<BuchungsErgebnis> book(BuchungsTyp typ, UUID transactionId,
                                                     UUID spielerId, String spielerName,
                                                     BigDecimal betrag, String quelle,
                                                     String grund) {
        if (transactionId == null || spielerId == null || betrag == null
                || betrag.signum() <= 0) {
            return CompletableFuture.completedFuture(BuchungsErgebnis.UNGUELTIGER_BETRAG);
        }
        BigDecimal amount = currency().normalize(betrag);
        if (amount.signum() <= 0) {
            return CompletableFuture.completedFuture(BuchungsErgebnis.UNGUELTIGER_BETRAG);
        }
        if (executor.isShutdown()) {
            return CompletableFuture.completedFuture(BuchungsErgebnis.FEHLER);
        }

        String pluginName = normalizeSource(quelle);
        String reason = grund == null || grund.isBlank() ? null : grund.trim();
        String playerName = spielerName == null || spielerName.isBlank()
                ? "unknown" : spielerName.trim();
        CompletableFuture<BuchungsErgebnis> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                BuchungsErgebnis ergebnis;
                try {
                    ergebnis = applyBooking(typ, transactionId, spielerId, playerName,
                            amount, pluginName, reason);
                } catch (Throwable throwable) {
                    plugin.getLogger().severe("Buchung " + transactionId + " (" + typ
                            + ") ist fehlgeschlagen: " + throwable);
                    ergebnis = BuchungsErgebnis.FEHLER;
                }
                fireTransactionEvent(transactionId, spielerId, playerName, amount, typ,
                        pluginName, reason, ergebnis);
                future.complete(ergebnis);
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Buchung " + transactionId
                    + " konnte nicht eingeplant werden: " + exception.getMessage());
            future.complete(BuchungsErgebnis.FEHLER);
        }
        return future;
    }

    private BuchungsErgebnis applyBooking(BuchungsTyp typ, UUID transactionId, UUID spielerId,
                                          String spielerName, BigDecimal amount,
                                          String quelle, String grund) {
        if (readOnly()) {
            return BuchungsErgebnis.NICHT_ERLAUBT;
        }
        if (processedTransactions.contains(transactionId)) {
            return BuchungsErgebnis.DUPLIKAT;
        }
        if (hasStoredTransaction(transactionId)) {
            processedTransactions.add(transactionId);
            return BuchungsErgebnis.DUPLIKAT;
        }

        Account account = account(spielerId, spielerName);
        synchronized (account) {
            AccountData before = account.snapshot();
            if (typ == BuchungsTyp.ABBUCHUNG) {
                BigDecimal remaining = account.balance().subtract(amount);
                if (account.balance().compareTo(amount) < 0
                        || remaining.compareTo(currency().minBalance()) < 0) {
                    return BuchungsErgebnis.KEIN_GELD;
                }
                if (!account.withdraw(amount)) {
                    return BuchungsErgebnis.KEIN_GELD;
                }
            } else if (!account.deposit(amount, currency().maxBalance())) {
                return BuchungsErgebnis.MAXIMUM;
            }

            AccountData after = account.snapshot();
            try {
                if (!storage.saveIdempotent(after, transactionId)) {
                    // Ein anderer Server oder ein früherer Lauf hat die
                    // Transaktion bereits gebucht: Änderung zurücknehmen.
                    account.setBalance(before.balance());
                    processedTransactions.add(transactionId);
                    return BuchungsErgebnis.DUPLIKAT;
                }
            } catch (RuntimeException exception) {
                account.setBalance(before.balance());
                plugin.getLogger().severe("Buchung " + transactionId + " (" + typ
                        + ") konnte nicht gespeichert werden: " + exception.getMessage());
                recordBookingAudit(transactionId, spielerId, spielerName, amount, typ,
                        quelle, grund, before.balance(), BuchungsErgebnis.FEHLER);
                return BuchungsErgebnis.FEHLER;
            }

            account.markSaved(after);
            processedTransactions.add(transactionId);
            recordBookingAudit(transactionId, spielerId, spielerName, amount, typ,
                    quelle, grund, after.balance(), BuchungsErgebnis.OK);
        }

        if (typ == BuchungsTyp.ERSTATTUNG) {
            // Kredit-Tilgung wie bei einer regulären Gutschrift, aber auf dem
            // Server-Thread: Kreditlogik und Spielerbenachrichtigungen sind
            // dort sicher.
            settleIncome(account, amount);
        }
        return BuchungsErgebnis.OK;
    }

    private boolean hasStoredTransaction(UUID transactionId) {
        try {
            return storage.hasTransaction(transactionId);
        } catch (RuntimeException exception) {
            // Die verbindliche Prüfung erfolgt beim Schreiben; ein Fehler der
            // Vorabkontrolle darf die Buchung nicht verhindern.
            plugin.getLogger().warning("Idempotenz-Prüfung für " + transactionId
                    + " fehlgeschlagen: " + exception.getMessage());
            return false;
        }
    }

    private void settleIncome(Account account, BigDecimal amount) {
        if (incomeHandler == null) {
            return;
        }
        try {
            if (Bukkit.isPrimaryThread()) {
                notifyIncome(account, amount);
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> notifyIncome(account, amount));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Kredit-Tilgung für " + account.uniqueId()
                    + " konnte nicht eingeplant werden: " + exception.getMessage());
        }
    }

    private void fireTransactionEvent(UUID transactionId, UUID spielerId, String spielerName,
                                      BigDecimal betrag, BuchungsTyp typ, String quelle,
                                      String grund, BuchungsErgebnis ergebnis) {
        try {
            Bukkit.getPluginManager().callEvent(new EconomyTransactionEvent(
                    transactionId, spielerId, spielerName, betrag, typ, quelle, grund,
                    ergebnis));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("EconomyTransactionEvent für " + transactionId
                    + " konnte nicht ausgelöst werden: " + exception.getMessage());
        }
    }

    /**
     * Protokolliert eine API-Buchung (Abschnitt 11.6). Quelle und Grund
     * landen gemeinsam im Begründungsfeld, z. B. {@code "SimplePlots: Claim 3;7"}.
     */
    private void recordBookingAudit(UUID transactionId, UUID spielerId, String spielerName,
                                    BigDecimal betrag, BuchungsTyp typ, String quelle,
                                    String grund, BigDecimal balanceAfter,
                                    BuchungsErgebnis ergebnis) {
        if (!settings.audit().enabled()) {
            return;
        }
        try {
            String reason = grund == null || grund.isBlank() ? quelle : quelle + ": " + grund;
            int maximum = Math.max(1, settings.reasonMaxLength());
            if (reason.length() > maximum) {
                reason = reason.substring(0, maximum);
            }
            storage.recordAudit(new AuditEntry(transactionId, System.currentTimeMillis(),
                    settings.network().id(), settings.network().scopeKey(),
                    spielerId, spielerName, "API",
                    typ == BuchungsTyp.ABBUCHUNG ? "api.abbuchen" : "api.erstatten",
                    spielerId, spielerName, betrag, balanceAfter, reason,
                    ergebnis == BuchungsErgebnis.OK
                            ? AuditEntry.Result.APPLIED : AuditEntry.Result.REJECTED));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Audit-Eintrag für " + transactionId
                    + " konnte nicht geschrieben werden: " + exception.getMessage());
        }
    }

    private static String normalizeSource(String quelle) {
        if (quelle == null || quelle.isBlank()) {
            return "API";
        }
        return quelle.trim();
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
        return runInterestCycle(savingsFactor, null);
    }

    /**
     * Zinszyklus eines geplanten Laufs: prüft die Lease des Scopes und bucht
     * jeden Zyklusschlüssel genau einmal (Abschnitt 11.4). Ohne diese Prüfung
     * würde jeder Server im Netzwerk denselben Zyklus ausführen.
     */
    public CompletableFuture<InterestResult> runScheduledInterestCycle(BigDecimal savingsFactor,
                                                                       String cycleKey) {
        return runInterestCycle(savingsFactor, cycleKey);
    }

    /**
     * Schlüssel des aktuellen Zyklus: aus {@code interest.start-time} und
     * {@code interest.interval-minutes} berechnet, damit zwei Server zum
     * selben Lauf denselben Schlüssel erzeugen.
     */
    public String cycleKey() {
        Settings.Interest interest = settings.interest();
        LocalTime start = interest.startTime();
        int interval = Math.max(1, interest.intervalMinutes());
        ZonedDateTime now = ZonedDateTime.now(settings.zoneId());
        ZonedDateTime anchor = now.toLocalDate().atTime(start).atZone(settings.zoneId());
        long minutes = Duration.between(anchor, now).toMinutes();
        long slot = minutes <= 0 ? 0 : minutes / interval;
        return anchor.plusMinutes(slot * interval).format(CYCLE_FORMAT);
    }

    /**
     * Bucht eine Überweisung idempotent im Speicher (Abschnitt 11.2). Vorher
     * werden eigene, noch nicht gespeicherte Änderungen geschrieben, damit die
     * Datenbank nicht mit einem älteren Stand überschrieben wird.
     */
    public CompletableFuture<TransferResult> remoteTransferAsync(TransferData data) {
        CompletableFuture<TransferResult> future = new CompletableFuture<>();
        if (executor.isShutdown()) {
            future.complete(TransferResult.FAILED);
            return future;
        }
        try {
            executor.execute(() -> {
                try {
                    List<AccountData> pending = new ArrayList<>();
                    Account from = accounts.get(data.from());
                    if (from != null) {
                        pending.add(from.snapshot());
                    }
                    Account to = accounts.get(data.to());
                    if (to != null) {
                        pending.add(to.snapshot());
                    }
                    if (!pending.isEmpty()) {
                        storage.saveAll(pending);
                    }

                    TransferResult result = storage.transfer(data, currency().maxBalance());
                    recordTransferAudit(data, result);
                    if (result == TransferResult.APPLIED) {
                        // Eigene Schreibwerte nie aus einem fremden Cache lesen (11.3).
                        accounts.remove(data.from());
                        accounts.remove(data.to());
                    }
                    future.complete(result);
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
     * Protokolliert eine Fernbuchung (11.6). Ein Fehler im Audit darf die
     * Buchung nicht kippen: die Buchung ist die Wahrheit, das Audit die Spur.
     */
    private void recordTransferAudit(TransferData data, TransferResult result) {
        if (!settings.audit().enabled()) {
            return;
        }
        try {
            storage.recordAudit(new AuditEntry(data.id(), System.currentTimeMillis(),
                    settings.network().id(), data.scope(),
                    data.from(), data.fromName(), "PLAYER", "pay",
                    data.to(), data.toName(), data.amount(), null, null,
                    result == TransferResult.APPLIED
                            ? AuditEntry.Result.APPLIED : AuditEntry.Result.REJECTED));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Audit-Eintrag für " + data.id()
                    + " konnte nicht geschrieben werden: " + exception.getMessage());
        }
    }

    private CompletableFuture<InterestResult> runInterestCycle(BigDecimal savingsFactor,
                                                              String cycleKey) {
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
                    future.complete(applyInterestCycle(savingsFactor, cycleKey));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    private InterestResult applyInterestCycle(BigDecimal savingsFactor, String cycleKey) {
        Settings.Network network = settings.network();
        String scope = network.scopeKey();
        if (cycleKey == null) {
            return creditInterest(savingsFactor);
        }

        if (!settings.interest().runsOn(network.id())) {
            plugin.getLogger().info("Zinszyklus übersprungen: interest.executor ist '"
                    + settings.interest().executor() + "'.");
            return emptyCycle();
        }
        if (!storage.acquireLease(scope, network.id(),
                TimeUnit.SECONDS.toMillis(settings.network().leaseTtlSeconds()))) {
            String holder = storage.lease(scope).map(LeaseInfo::holder).orElse("?");
            plugin.getLogger().warning("Zinszyklus übersprungen: die Lease für '" + scope
                    + "' hält '" + holder + "'.");
            return emptyCycle();
        }
        try {
            if (!storage.claimInterestRun(scope, cycleKey)) {
                plugin.getLogger().info("Zinszyklus '" + cycleKey + "' wurde im Scope '"
                        + scope + "' bereits gebucht.");
                return emptyCycle();
            }
            return creditInterest(savingsFactor);
        } finally {
            storage.releaseLease(scope, network.id());
        }
    }

    private static InterestResult emptyCycle() {
        return new InterestResult(0, 0, BigDecimal.ZERO, Map.of());
    }

    private InterestResult creditInterest(BigDecimal savingsFactor) {
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
     * Im SQLite-/MySQL-Modus geschieht das in einer Transaktion, im YAML-Modus über
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

    /**
     * Idempotenz-Cache mit fester Obergrenze. Die verbindliche Prüfung liegt
     * im Speicher; der Cache spart nur den Zugriff auf bereits bekannte IDs.
     */
    private static final class TransactionCache extends LinkedHashSet<UUID> {

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

    /**
     * Plant die tägliche Audit-Bereinigung. Der eigentliche Löschvorgang läuft
     * auf dem Storage-Executor, damit der Server-Thread frei bleibt.
     */
    private void scheduleAuditCleanup() {
        if (auditTask != null) {
            auditTask.cancel();
            auditTask = null;
        }
        Settings.Audit audit = settings.audit();
        if (audit.retentionDays() <= 0) {
            return;
        }
        auditTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
                this::pruneAudit, 20L * 60L * 5L, 20L * 60L * 60L * 24L);
    }

    private void pruneAudit() {
        if (executor.isShutdown()) {
            return;
        }
        long retention = settings.audit().retentionDays();
        if (retention <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retention);
        String scope = settings.network().scopeKey();
        try {
            executor.execute(() -> {
                try {
                    int deleted = storage.pruneAudit(scope, cutoff, 1000);
                    if (deleted > 0) {
                        plugin.getLogger().info(deleted + " alte Audit-Einträge im Scope '"
                                + scope + "' entfernt.");
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Audit-Einträge konnten nicht aufgeräumt werden: "
                            + exception.getMessage());
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Audit-Aufräumung konnte nicht eingeplant werden: "
                    + exception.getMessage());
        }
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
        if (auditTask != null) {
            auditTask.cancel();
            auditTask = null;
        }
        scheduleAutoSave();
        scheduleAuditCleanup();
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
        if (auditTask != null) {
            auditTask.cancel();
            auditTask = null;
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

        int timeoutSeconds = settings.shutdownTimeoutSeconds();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            while (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                if (System.nanoTime() - deadline >= 0) {
                    List<Runnable> dropped = executor.shutdownNow();
                    plugin.getLogger().severe("Speicherung beim Stoppen nach " + timeoutSeconds
                            + " Sekunden abgebrochen; " + dropped.size()
                            + " Vorgänge wurden verworfen. Offene Änderungen können verloren gehen.");
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
