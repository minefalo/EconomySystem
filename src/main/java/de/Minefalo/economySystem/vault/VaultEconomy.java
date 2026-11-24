package de.Minefalo.economySystem.vault;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.currency.Currency;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Vault-Wirtschaftsanbieter von EconomySystem.
 *
 * <p>Intern bleibt {@code BigDecimal} führend; {@code double} existiert nur
 * an der Grenze zur Vault-API. Lesende Zugriffe legen keine Konten an,
 * schreibende Zugriffe laufen auf dem Haupt-Thread, weil sie die
 * Kredit-Tilgung und damit Bukkit-nahe Benachrichtigungen auslösen können.
 * Alle Wartezeiten sind auf {@code vault.timeout-ms} begrenzt.</p>
 */
public final class VaultEconomy implements Economy {

    private static final EconomyResponse.ResponseType SUCCESS = EconomyResponse.ResponseType.SUCCESS;
    private static final EconomyResponse.ResponseType FAILURE = EconomyResponse.ResponseType.FAILURE;
    private static final EconomyResponse.ResponseType NOT_IMPLEMENTED =
            EconomyResponse.ResponseType.NOT_IMPLEMENTED;

    private final EconomySystem plugin;
    private final EconomyService economy;

    public VaultEconomy(EconomySystem plugin) {
        this.plugin = plugin;
        this.economy = plugin.economy();
    }

    // ------------------------------------------------------------------
    // Metadaten
    // ------------------------------------------------------------------

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled() && economy != null;
    }

    @Override
    public String getName() {
        return plugin.getName();
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return currency().decimals();
    }

    @Override
    public String format(double amount) {
        BigDecimal value = Double.isFinite(amount)
                ? BigDecimal.valueOf(amount)
                : BigDecimal.ZERO;
        return currency().format(value);
    }

    @Override
    public String currencyNamePlural() {
        return currency().name();
    }

    @Override
    public String currencyNameSingular() {
        String configured = settings().vault().currencyNameSingular();
        return configured == null || configured.isBlank() ? currency().name() : configured;
    }

    // ------------------------------------------------------------------
    // Konten lesen (ohne Neuanlage)
    // ------------------------------------------------------------------

    @Override
    public boolean hasAccount(String playerName) {
        return readByName(playerName).isPresent();
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return player != null;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        return balanceOf(readByName(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return balanceOf(read(player));
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return hasBalance(getBalance(playerName), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return hasBalance(getBalance(player), amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    // ------------------------------------------------------------------
    // Abbuchen
    // ------------------------------------------------------------------

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        if (settings().network().readOnly()) {
            return readOnlyFailure();
        }
        Optional<BigDecimal> parsed = validAmount(amount);
        if (parsed.isEmpty()) {
            return amountFailure();
        }
        Optional<Account> account = readByName(playerName);
        if (account.isEmpty()) {
            return failure("Für '" + playerName + "' ist kein Konto bekannt.");
        }
        return apply(account.get(), parsed.get(), false);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdraw(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdraw(player, amount);
    }

    // ------------------------------------------------------------------
    // Einzahlen
    // ------------------------------------------------------------------

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        if (settings().network().readOnly()) {
            return readOnlyFailure();
        }
        Optional<BigDecimal> parsed = validAmount(amount);
        if (parsed.isEmpty()) {
            return amountFailure();
        }
        Optional<Account> account = readByName(playerName);
        if (account.isEmpty()) {
            return failure("Für '" + playerName + "' ist kein Konto bekannt.");
        }
        return apply(account.get(), parsed.get(), true);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return deposit(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return deposit(player, amount);
    }

    // ------------------------------------------------------------------
    // Konten anlegen
    // ------------------------------------------------------------------

    @Override
    public boolean createPlayerAccount(String playerName) {
        // Ohne UUID lässt sich kein Konto anlegen; bekanntes Konto bleibt gültig.
        return !settings().network().readOnly() && readByName(playerName).isPresent();
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        if (player == null || settings().network().readOnly()) {
            return false;
        }
        return resolve(player.getUniqueId(), player.getName()) != null;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // ------------------------------------------------------------------
    // Banken: nicht unterstützt
    // ------------------------------------------------------------------

    @Override
    public EconomyResponse createBank(String name, String player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, String player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    // ------------------------------------------------------------------
    // Buchungen
    // ------------------------------------------------------------------

    private EconomyResponse withdraw(OfflinePlayer player, double amount) {
        if (player == null) {
            return failure("Spieler fehlt.");
        }
        if (settings().network().readOnly()) {
            return readOnlyFailure();
        }
        Optional<BigDecimal> parsed = validAmount(amount);
        if (parsed.isEmpty()) {
            return amountFailure();
        }
        Account account = resolve(player.getUniqueId(), player.getName());
        if (account == null) {
            return failure("Konto konnte nicht geladen werden.");
        }
        return apply(account, parsed.get(), false);
    }

    private EconomyResponse deposit(OfflinePlayer player, double amount) {
        if (player == null) {
            return failure("Spieler fehlt.");
        }
        if (settings().network().readOnly()) {
            return readOnlyFailure();
        }
        Optional<BigDecimal> parsed = validAmount(amount);
        if (parsed.isEmpty()) {
            return amountFailure();
        }
        Account account = resolve(player.getUniqueId(), player.getName());
        if (account == null) {
            return failure("Konto konnte nicht geladen werden.");
        }
        return apply(account, parsed.get(), true);
    }

    /**
     * Führt die Buchung auf dem Haupt-Thread aus (Kredit-Tilgung und
     * Benachrichtigungen sind dort sicher) und antwortet mit dem tatsächlich
     * gebuchten Betrag und dem Kontostand danach.
     */
    private EconomyResponse apply(Account account, BigDecimal amount, boolean deposit) {
        return onMainThread(() -> {
            boolean success = deposit
                    ? economy.deposit(account, amount)
                    : economy.withdraw(account, amount);
            double balance = VaultAmounts.toDouble(account.balance());
            if (!success) {
                String reason = deposit
                        ? "Das maximale Guthaben würde überschritten."
                        : "Das Guthaben reicht nicht aus.";
                return new EconomyResponse(0.0, balance, FAILURE, reason);
            }
            return new EconomyResponse(VaultAmounts.toDouble(amount), balance, SUCCESS, null);
        }, () -> new EconomyResponse(0.0, VaultAmounts.toDouble(account.balance()), FAILURE,
                "Zeitüberschreitung beim Ausführen der Buchung."));
    }

    private <T> T onMainThread(Callable<T> action, Supplier<T> fallback) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return action.call();
            } catch (Exception exception) {
                plugin.getLogger().warning("Vault: Buchung konnte nicht ausgeführt werden: " + exception);
                return fallback.get();
            }
        }
        if (!plugin.isEnabled()) {
            return fallback.get();
        }
        Future<T> future = null;
        try {
            future = Bukkit.getScheduler().callSyncMethod(plugin, action);
            return future.get(settings().vault().timeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return fallback.get();
        } catch (ExecutionException exception) {
            plugin.getLogger().warning("Vault: Buchung konnte nicht ausgeführt werden: " + exception);
            return fallback.get();
        } catch (TimeoutException exception) {
            // Noch nicht gestartete Aufgaben verwerfen, damit eine gemeldete
            // Zeitüberschreitung nicht später doch noch bucht.
            if (future != null) {
                future.cancel(false);
            }
            plugin.getLogger().warning("Vault: Buchung nach "
                    + settings().vault().timeoutMillis() + " ms nicht ausgeführt.");
            return fallback.get();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Vault: Buchung konnte nicht eingeplant werden: " + exception);
            return fallback.get();
        }
    }

    // ------------------------------------------------------------------
    // Laden und Umrechnen
    // ------------------------------------------------------------------

    /**
     * Lädt ein Konto bei Bedarf und legt es mit dem Startguthaben an.
     */
    private Account resolve(UUID uniqueId, String name) {
        try {
            return economy.accountAsync(uniqueId, name)
                    .get(settings().vault().timeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            plugin.getLogger().warning("Vault: Konto " + uniqueId
                    + " konnte nicht geladen werden: " + exception);
            return null;
        }
    }

    private Optional<Account> read(OfflinePlayer player) {
        return player == null ? Optional.empty()
                : readById(player.getUniqueId(), player.getName());
    }

    /**
     * Liest ein vorhandenes Konto, ohne eines anzulegen.
     */
    private Optional<Account> readById(UUID uniqueId, String name) {
        if (uniqueId == null) {
            return Optional.empty();
        }
        try {
            return economy.existingAccountAsync(uniqueId, name)
                    .get(settings().vault().timeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            plugin.getLogger().warning("Vault: Konto " + uniqueId
                    + " konnte nicht gelesen werden: " + exception);
            return Optional.empty();
        }
    }

    /**
     * Löst einen Namen über Cache und Speicher auf bekannte Konten auf.
     * Unbekannte Namen werden nicht angelegt.
     */
    private Optional<Account> readByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return economy.existingAccountByNameAsync(name)
                    .get(settings().vault().timeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            plugin.getLogger().warning("Vault: Konto '" + name
                    + "' konnte nicht gelesen werden: " + exception);
            return Optional.empty();
        }
    }

    private Optional<BigDecimal> validAmount(double amount) {
        return VaultAmounts.toBigDecimal(amount, currency());
    }

    private double balanceOf(Optional<Account> account) {
        return account.map(value -> VaultAmounts.toDouble(value.balance())).orElse(0.0);
    }

    private static boolean hasBalance(double balance, double amount) {
        return Double.isFinite(amount) && balance >= amount;
    }

    private static EconomyResponse failure(String message) {
        return new EconomyResponse(0.0, 0.0, FAILURE, message);
    }

    private EconomyResponse amountFailure() {
        return failure("Betrag muss eine positive Zahl mit höchstens "
                + currency().decimals() + " Nachkommastellen sein.");
    }

    private static EconomyResponse readOnlyFailure() {
        return failure("Der Server läuft im READ_ONLY-Modus; Vault-Buchungen sind gesperrt.");
    }

    private static EconomyResponse notImplemented() {
        return new EconomyResponse(0.0, 0.0, NOT_IMPLEMENTED,
                "EconomySystem unterstützt keine Bankkonten.");
    }

    private Settings settings() {
        return plugin.settings();
    }

    private Currency currency() {
        return settings().currency();
    }
}
