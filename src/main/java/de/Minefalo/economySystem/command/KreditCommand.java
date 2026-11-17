package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.credit.CreditService;
import de.Minefalo.economySystem.credit.Loan;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * /kredit - Kredite aufnehmen, zurückzahlen und einsehen.
 */
public final class KreditCommand extends AbstractEconomyCommand {

    private static final String PERMISSION = "economysystem.kredit";

    private final CreditService credit;

    public KreditCommand(EconomySystem plugin, EconomyService economy, CreditService credit) {
        super(plugin, economy);
        this.credit = credit;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            noPermission(sender);
            return true;
        }

        if (args.length == 0) {
            info(sender, player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add", "aufnehmen", "nehmen" -> add(sender, player, args);
            case "bezahlen", "zahlen", "pay", "repay", "zurueckzahlen" -> pay(sender, player, args);
            case "info", "status" -> info(sender, player);
            default -> messages().send(sender, "usage-kredit");
        }
        return true;
    }

    private void add(CommandSender sender, Player player, String[] args) {
        if (args.length != 3) {
            messages().send(sender, "usage-kredit-add");
            return;
        }

        BigDecimal amount = parseAmount(sender, args[1], false);
        if (amount == null) {
            return;
        }
        Integer days = parseDays(sender, args[2]);
        if (days == null) {
            return;
        }

        Settings.Credits config = plugin.settings().credits();
        Account account = economy.account(player.getUniqueId(), player.getName());
        CreditService.Result result = credit.takeLoan(account, amount, days);

        switch (result.status()) {
            case SUCCESS -> {
                Loan loan = result.loan();
                messages().send(sender, "credit-created",
                        "amount", currency().format(loan.principal()),
                        "days", String.valueOf(loan.days()),
                        "interest", currency().format(loan.interest()),
                        "total", currency().format(loan.total()),
                        "due", CreditService.formatDue(loan));
            }
            case DISABLED -> messages().send(sender, "credit-disabled");
            case TOO_MANY_LOANS -> messages().send(sender, "credit-too-many-loans",
                    "max", String.valueOf(config.maxLoans()),
                    "count", String.valueOf(credit.activeLoanCount(player.getUniqueId())));
            case INVALID_DAYS -> messages().send(sender, "credit-invalid-days",
                    "min", String.valueOf(config.minDays()),
                    "max", String.valueOf(config.maxDays()));
            case NO_CREDIT_LINE -> messages().send(sender, "credit-no-line");
            case LIMIT_EXCEEDED -> messages().send(sender, "credit-limit-exceeded",
                    "max", currency().format(credit.maxLoan(account)),
                    "multiplier", config.limitMultiplier().stripTrailingZeros().toPlainString());
            case AMOUNT_TOO_SMALL -> messages().send(sender, "credit-amount-too-small",
                    "min", currency().format(config.minimumAmount()));
            case MAX_BALANCE -> messages().send(sender, "credit-max-balance",
                    "max", currency().format(currency().maxBalance()));
        }
    }

    private void pay(CommandSender sender, Player player, String[] args) {
        if (args.length > 2) {
            messages().send(sender, "usage-kredit-pay");
            return;
        }

        BigDecimal requested = null;
        if (args.length == 2) {
            requested = parseAmount(sender, args[1], false);
            if (requested == null) {
                return;
            }
        }

        Account account = economy.account(player.getUniqueId(), player.getName());
        BigDecimal owed = credit.outstanding(player.getUniqueId());
        if (owed.signum() <= 0) {
            messages().send(sender, "credit-nothing-to-pay");
            return;
        }

        BigDecimal paid = credit.repay(account, requested);
        if (paid.signum() <= 0) {
            messages().send(sender, "credit-no-money");
            return;
        }

        messages().send(sender, "credit-paid",
                "amount", currency().format(paid),
                "remaining", currency().format(credit.outstanding(player.getUniqueId())));
    }

    private void info(CommandSender sender, Player player) {
        Settings.Credits config = plugin.settings().credits();
        Account account = economy.account(player.getUniqueId(), player.getName());
        List<Loan> loans = credit.loansOf(player.getUniqueId());
        long now = System.currentTimeMillis();

        messages().send(sender, "credit-info-header");
        messages().send(sender, "credit-info-balance",
                "balance", currency().format(account.balance()),
                "min", currency().format(currency().minBalance()));
        messages().send(sender, "credit-info-limit",
                "limit", currency().format(credit.maxLoan(account)),
                "multiplier", config.limitMultiplier().stripTrailingZeros().toPlainString());
        messages().send(sender, "credit-info-loans",
                "count", String.valueOf(loans.size()),
                "max", String.valueOf(config.maxLoans()));

        if (loans.isEmpty()) {
            messages().send(sender, "credit-info-none");
            return;
        }

        int index = 1;
        for (Loan loan : loans) {
            messages().send(sender, "credit-info-entry",
                    "id", String.valueOf(index),
                    "amount", currency().format(loan.principal()),
                    "remaining", currency().format(loan.remaining()),
                    "interest", currency().format(loan.interestRemaining()),
                    "due", CreditService.formatDue(loan),
                    "status", messages().raw(loan.isOverdue(now)
                            ? "credit-status-overdue"
                            : loan.autoCollect() ? "credit-status-collecting" : "credit-status-running"));
            index++;
        }
    }

    private Integer parseDays(CommandSender sender, String input) {
        Settings.Credits config = plugin.settings().credits();
        try {
            int days = Integer.parseInt(input.trim());
            if (days >= config.minDays() && days <= config.maxDays()) {
                return days;
            }
        } catch (NumberFormatException ignored) {
            // Fällt unten auf die Fehlermeldung zurück.
        }
        messages().send(sender, "credit-invalid-days",
                "min", String.valueOf(config.minDays()),
                "max", String.valueOf(config.maxDays()));
        return null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("add", "bezahlen", "info"), args[0]);
        }
        if (args.length == 2) {
            String subcommand = args[0].toLowerCase(Locale.ROOT);
            if (subcommand.equals("add")) {
                return filter(List.of("100", "500", "1000"), args[1]);
            }
            if (subcommand.equals("bezahlen") || subcommand.equals("pay")) {
                return filter(List.of("10", "50", "100"), args[1]);
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            Settings.Credits config = plugin.settings().credits();
            return filter(List.of(String.valueOf(config.minDays()), "7", "30", "90"), args[2]);
        }
        return List.of();
    }
}
