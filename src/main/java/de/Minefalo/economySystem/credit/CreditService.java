package de.Minefalo.economySystem.credit;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.currency.Currency;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.storage.LoanData;
import de.Minefalo.economySystem.storage.StorageException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verwaltet Kredite: Aufnahme, Rückzahlung, Fälligkeit und die
 * automatische Tilgung aus Einnahmen.
 *
 * <p>Regeln (alle über die settings.yml konfigurierbar):</p>
 * <ul>
 *   <li>Kreditrahmen = Multiplikator × (Guthaben − offene Kredite).</li>
 *   <li>Zinsen: {@code y = x · m + a} mit {@code x} = Tage,
 *       {@code a} = {@code interest.factors.kredit}, {@code m} = {@code credits.slope}.</li>
 *   <li>Höchstens {@code credits.max-loans} gleichzeitige Kredite.</li>
 *   <li>Wird ein Kredit fällig, bucht das System zwangsweise ab – bis zur
 *       Untergrenze {@code currency.min-balance}. Bleibt dann etwas offen,
 *       werden die restlichen Zinsen erlassen und alle Einnahmen tilgen
 *       den Kredit automatisch.</li>
 * </ul>
 */
public final class CreditService {

    private static final DateTimeFormatter DUE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /**
     * Ergebnis einer Kreditaufnahme.
     */
    public enum Status {
        SUCCESS,
        DISABLED,
        TOO_MANY_LOANS,
        INVALID_DAYS,
        NO_CREDIT_LINE,
        LIMIT_EXCEEDED,
        AMOUNT_TOO_SMALL,
        MAX_BALANCE
    }

    public record Result(Status status, Loan loan) {
        static Result of(Status status) {
            return new Result(status, null);
        }
    }

    private final EconomySystem plugin;
    private final EconomyService economy;
    private final Map<UUID, Loan> loans = new ConcurrentHashMap<>();

    private BukkitTask checkTask;

    public CreditService(EconomySystem plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    public void init() {
        for (LoanData data : economy.storage().loadLoans()) {
            Loan loan = Loan.fromData(data);
            loans.put(loan.id(), loan);
        }
        if (!loans.isEmpty()) {
            plugin.getLogger().info(loans.size() + " Kredit(e) geladen.");
        }
        scheduleChecks();
    }

    /**
     * Übernimmt geänderte Einstellungen (z. B. das Prüfintervall).
     */
    public void reload() {
        scheduleChecks();
    }

    public void stop() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
    }

    private void scheduleChecks() {
        stop();
        Settings.Credits config = plugin.settings().credits();
        // Auch bei deaktiviertem Kreditsystem müssen bestehende Schulden
        // weiter bedient werden - nur neue Kredite sind gesperrt.
        if (!config.enabled() && loans.isEmpty()) {
            return;
        }
        long ticks = config.checkIntervalSeconds() * 20L;
        // Etwas Verzögerung, damit überfällige Kredite nach einem Neustart
        // zügig, aber nicht während des Starts geprüft werden.
        checkTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::processDueLoans, 100L, ticks);
    }

    public Settings.Credits config() {
        return plugin.settings().credits();
    }

    /**
     * Alle laufenden Kredite eines Spielers, älteste zuerst.
     */
    public List<Loan> loansOf(UUID owner) {
        List<Loan> result = new ArrayList<>();
        for (Loan loan : loans.values()) {
            if (loan.owner().equals(owner)) {
                result.add(loan);
            }
        }
        result.sort(Comparator.comparingLong(Loan::createdAt));
        return result;
    }

    public int activeLoanCount(UUID owner) {
        return loansOf(owner).size();
    }

    /**
     * Summe aller noch offenen Kreditbeträge (inklusive Zinsen).
     */
    public BigDecimal outstanding(UUID owner) {
        BigDecimal total = BigDecimal.ZERO;
        for (Loan loan : loansOf(owner)) {
            total = total.add(loan.remaining());
        }
        return currency().normalize(total);
    }

    /**
     * Verfügbarer Kreditrahmen: {@code multiplier × (Guthaben − offene Kredite)}.
     * Ist das Eigenkapital negativ, wird kein Kredit vergeben.
     */
    public BigDecimal maxLoan(Account account) {
        BigDecimal equity = account.balance().subtract(outstanding(account.uniqueId()));
        if (equity.signum() <= 0) {
            return currency().normalize(BigDecimal.ZERO);
        }
        return currency().normalize(equity.multiply(config().limitMultiplier()));
    }

    /**
     * Nimmt einen Kredit auf. Der Betrag wird sofort ausgezahlt.
     */
    public Result takeLoan(Account account, BigDecimal amount, int days) {
        Settings.Credits config = config();
        if (!config.enabled()) {
            return Result.of(Status.DISABLED);
        }
        if (activeLoanCount(account.uniqueId()) >= config.maxLoans()) {
            return Result.of(Status.TOO_MANY_LOANS);
        }
        if (days < config.minDays() || days > config.maxDays()) {
            return Result.of(Status.INVALID_DAYS);
        }

        BigDecimal normalized = currency().normalize(amount);
        if (normalized.signum() <= 0 || normalized.compareTo(config.minimumAmount()) < 0) {
            return Result.of(Status.AMOUNT_TOO_SMALL);
        }

        BigDecimal maximum = maxLoan(account);
        if (maximum.signum() <= 0) {
            return Result.of(Status.NO_CREDIT_LINE);
        }
        if (normalized.compareTo(maximum) > 0) {
            return Result.of(Status.LIMIT_EXCEEDED);
        }

        BigDecimal maxBalance = currency().maxBalance();
        if (maxBalance != null && account.balance().add(normalized).compareTo(maxBalance) > 0) {
            return Result.of(Status.MAX_BALANCE);
        }

        BigDecimal interest = currency().normalize(config.interestFor(
                days, plugin.settings().interest().creditFactor()));
        if (interest.signum() < 0) {
            interest = currency().normalize(BigDecimal.ZERO);
        }

        long now = System.currentTimeMillis();
        long dueAt = Instant.ofEpochMilli(now).plus(days, ChronoUnit.DAYS).toEpochMilli();
        Loan loan = new Loan(UUID.randomUUID(), account.uniqueId(), account.name(),
                normalized, interest, days, now, dueAt);

        if (!economy.depositDirect(account, normalized)) {
            return Result.of(Status.MAX_BALANCE);
        }
        loans.put(loan.id(), loan);
        persist(loan);
        return new Result(Status.SUCCESS, loan);
    }

    /**
     * Zahlt einen Kredit manuell zurück (ältester zuerst). Es wird nie mehr
     * abgebucht, als auf dem Konto ist.
     *
     * @param requested gewünschter Betrag oder {@code null} für "so viel wie möglich"
     * @return tatsächlich zurückgezahlter Betrag
     */
    public BigDecimal repay(Account account, BigDecimal requested) {
        BigDecimal budget = account.balance().max(BigDecimal.ZERO);
        if (budget.signum() <= 0) {
            return currency().normalize(BigDecimal.ZERO);
        }
        if (requested != null) {
            budget = budget.min(currency().normalize(requested));
        }

        BigDecimal paid = payLoans(account, budget);
        return paid;
    }

    private BigDecimal payLoans(Account account, BigDecimal budget) {
        BigDecimal left = budget;
        BigDecimal paidTotal = BigDecimal.ZERO;

        for (Loan loan : loansOf(account.uniqueId())) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal capacity = left.min(loan.remaining());
            if (capacity.signum() <= 0) {
                continue;
            }
            if (!economy.withdraw(account, capacity)) {
                break;
            }
            BigDecimal paid = loan.pay(capacity);
            left = left.subtract(paid);
            paidTotal = paidTotal.add(paid);
            finishOrPersist(loan);
        }
        return currency().normalize(paidTotal);
    }

    /**
     * Wird nach jeder Gutschrift aufgerufen. Hat der Spieler einen Kredit in
     * der Tilgungsphase, werden alle Einnahmen sofort abgebucht.
     */
    public void handleIncome(Account account, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }

        List<Loan> collecting = loansOf(account.uniqueId()).stream()
                .filter(Loan::autoCollect)
                .toList();
        if (collecting.isEmpty()) {
            return;
        }

        BigDecimal left = amount;
        BigDecimal seized = BigDecimal.ZERO;
        for (Loan loan : collecting) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal capacity = left.min(loan.remaining());
            if (capacity.signum() <= 0) {
                continue;
            }
            if (!economy.withdraw(account, capacity)) {
                break;
            }
            BigDecimal paid = loan.pay(capacity);
            left = left.subtract(paid);
            seized = seized.add(paid);
            finishOrPersist(loan);
        }

        if (seized.signum() > 0) {
            notifyPlayer(account.uniqueId(), "credit-income-seized",
                    "amount", currency().format(seized),
                    "remaining", currency().format(outstanding(account.uniqueId())));
        }
    }

    /**
     * Prüft alle fälligen Kredite und bucht zwangsweise ab.
     */
    public void processDueLoans() {
        if (!plugin.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Loan loan : new ArrayList<>(loans.values())) {
            if (!loan.isOverdue(now)) {
                continue;
            }
            try {
                Account account = economy.account(loan.owner(), loan.ownerName());
                collect(loan, account);
            } catch (StorageException exception) {
                plugin.getLogger().warning("Fälliger Kredit " + loan.id()
                        + " konnte nicht verarbeitet werden: " + exception.getMessage());
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("Fälliger Kredit " + loan.id()
                        + " konnte nicht verarbeitet werden: " + exception);
            }
        }
    }

    private void collect(Loan loan, Account account) {
        Currency currency = currency();
        BigDecimal floor = currency.minBalance();
        BigDecimal balance = account.balance();
        BigDecimal capacity = balance.subtract(floor);

        BigDecimal paid = BigDecimal.ZERO;
        if (capacity.signum() > 0) {
            BigDecimal taken = capacity.min(loan.remaining());
            economy.setBalance(account, currency.normalize(balance.subtract(taken)));
            paid = loan.pay(taken);
        }

        if (loan.isPaid()) {
            notifyPlayer(loan.owner(), "credit-forced-paid",
                    "amount", currency.format(paid),
                    "credit", currency.format(loan.principal()));
            finishOrPersist(loan);
            return;
        }

        if (account.balance().compareTo(floor) <= 0 && !loan.autoCollect()) {
            BigDecimal waived = loan.waiveInterest();
            persist(loan);
            plugin.getLogger().info("Kredit " + loan.id() + " von " + loan.ownerName()
                    + " ist überfällig: Minimalguthaben erreicht, "
                    + currency.format(waived) + " Zinsen erlassen, offen bleiben "
                    + currency.format(loan.remaining()) + ".");
            notifyPlayer(loan.owner(), "credit-hardship",
                    "paid", currency.format(paid),
                    "waived", currency.format(waived),
                    "remaining", currency.format(loan.remaining()),
                    "floor", currency.format(floor));
            return;
        }

        if (paid.signum() > 0) {
            persist(loan);
            notifyPlayer(loan.owner(), "credit-forced-paid",
                    "amount", currency.format(paid),
                    "credit", currency.format(loan.principal()));
        }
    }

    private void finishOrPersist(Loan loan) {
        if (loan.isPaid()) {
            loans.remove(loan.id());
            economy.deleteLoanAsync(loan.id());
            notifyPlayer(loan.owner(), "credit-completed",
                    "amount", currency().format(loan.principal()));
            return;
        }
        persist(loan);
    }

    private void persist(Loan loan) {
        economy.saveLoanAsync(loan.snapshot());
    }

    private void notifyPlayer(UUID uniqueId, String key, String... placeholders) {
        Player player = Bukkit.getPlayer(uniqueId);
        if (player == null) {
            return;
        }
        plugin.messages().send(player, key, placeholders);
    }

    private Currency currency() {
        return plugin.settings().currency();
    }

    /**
     * Formatiert ein Fälligkeitsdatum für Nachrichten.
     */
    public static String formatDue(Loan loan) {
        return loan.dueDate().format(DUE_FORMAT);
    }
}
