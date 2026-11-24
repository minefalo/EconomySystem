package de.Minefalo.economySystem.placeholder;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.currency.Currency;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.economy.InterestCalculator;
import de.Minefalo.economySystem.storage.AccountData;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Liefert die Daten für die PlaceholderAPI-Erweiterung. Kontostände von
 * Online-Spielern kommen direkt aus dem Cache; offline Konten werden
 * asynchron geladen und kurz zwischengespeichert. Die Top-Liste wird
 * regelmäßig im Hintergrund neu berechnet.
 */
public final class PlaceholderData {

    private final EconomySystem plugin;
    private final EconomyService economy;
    private long cacheTtlMillis;
    private long negativeTtlMillis;
    private long cacheRetentionMillis;
    private int topSize;
    private long topRefreshTicks;
    private final Map<String, CachedBalance> balances = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    private volatile List<AccountData> top = List.of();
    private BukkitTask topTask;

    public PlaceholderData(EconomySystem plugin) {
        this.plugin = plugin;
        this.economy = plugin.economy();
        configure();
    }

    /**
     * Übernimmt die aktuellen Werte aus {@code settings.placeholder}. Wird im
     * Konstruktor und bei jedem Reload aufgerufen.
     */
    private void configure() {
        Settings.Placeholders config = plugin.settings().placeholders();
        this.cacheTtlMillis = config.cacheTtlSeconds() * 1000L;
        this.negativeTtlMillis = config.negativeCacheTtlSeconds() * 1000L;
        this.cacheRetentionMillis = config.cacheRetentionSeconds() * 1000L;
        this.topSize = config.topSize();
        this.topRefreshTicks = config.topRefreshSeconds() * 20L;
    }

    /**
     * Startet die regelmäßige Aktualisierung der Top-Liste.
     */
    public void start() {
        stop();
        refreshTop();
        topTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::refreshTop, topRefreshTicks, topRefreshTicks);
    }

    public void stop() {
        if (topTask != null) {
            topTask.cancel();
            topTask = null;
        }
    }

    /**
     * Verwirft alle zwischengespeicherten Werte und startet die Top-Liste mit
     * den aktuellen Einstellungen neu (z. B. nach einem Reload).
     */
    public void reload() {
        configure();
        balances.clear();
        start();
    }

    /**
     * Berechnet die Top-Liste neu. Der Speicherzugriff läuft asynchron.
     */
    public void refreshTop() {
        pruneCache();
        economy.topAsync(topSize).thenAccept(result -> top = List.copyOf(result))
                .exceptionally(error -> {
                    plugin.getLogger().warning("Top-Guthaben konnte nicht berechnet werden: "
                            + messageOf(error));
                    return null;
                });
    }

    /**
     * Entfernt alte Offline-Werte, damit der Cache nicht unbegrenzt wächst.
     */
    private void pruneCache() {
        long cutoff = System.currentTimeMillis() - cacheRetentionMillis;
        balances.entrySet().removeIf(entry -> entry.getValue().fetchedAt() < cutoff);
    }

    /**
     * Kontostand eines Spielers. Online-Spieler werden direkt gelesen,
     * offline Konten aus dem Kurzzeit-Cache (und bei Bedarf asynchron
     * aktualisiert).
     *
     * @return Kontostand oder {@code null}, wenn kein Konto existiert
     */
    public BigDecimal balance(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        Optional<BigDecimal> cachedAccount = economy.cachedBalance(name);
        if (cachedAccount.isPresent()) {
            return cachedAccount.get();
        }

        String key = name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        CachedBalance cached = balances.get(key);
        if (cached != null) {
            long ttl = cached.balance() == null ? negativeTtlMillis : cacheTtlMillis;
            if (now - cached.fetchedAt() > ttl) {
                refreshAsync(name, key);
            }
            return cached.balance();
        }

        // Auch auf dem Haupt-Thread niemals synchron auf den Speicher warten.
        refreshAsync(name, key);
        return null;
    }

    private void refreshAsync(String name, String key) {
        if (!pending.add(key)) {
            return;
        }
        economy.balanceAsync(name).whenComplete((result, error) -> {
            pending.remove(key);
            if (error != null || result == null) {
                return;
            }
            balances.put(key, new CachedBalance(result.orElse(null), System.currentTimeMillis()));
        });
    }

    /**
     * Absoluter Sparzins, der bei diesem Kontostand und dem aktuell
     * konfigurierten Faktor gutgeschrieben würde.
     */
    public BigDecimal savingsInterest(BigDecimal balance) {
        if (balance == null) {
            return null;
        }
        Currency currency = plugin.settings().currency();
        Settings.Interest interest = plugin.settings().interest();
        return InterestCalculator.interestFor(balance, interest.savingsFactor(),
                currency.maxBalance(), currency);
    }

    /**
     * Eintrag der Top-Liste (1-basiert) oder {@code null}.
     */
    public AccountData top(int rank) {
        List<AccountData> snapshot = top;
        if (rank < 1 || rank > snapshot.size()) {
            return null;
        }
        return snapshot.get(rank - 1);
    }

    private static String messageOf(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private record CachedBalance(BigDecimal balance, long fetchedAt) {
    }
}
