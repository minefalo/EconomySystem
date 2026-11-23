package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.credit.CreditService;
import de.Minefalo.economySystem.credit.Loan;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /eco credit | /eco credit info} - Guthaben, Rahmen und laufende
 * Kredite. Dieselbe Ausgabe liefert der Kurzbefehl {@code /kredit} ohne
 * Argument.
 */
public final class CreditInfoModule extends AbstractModule {

    private final CreditService credit;

    public CreditInfoModule(EconomySystem plugin, EconomyService economy, CreditService credit) {
        super(plugin, economy);
        this.credit = credit;
    }

    @Override
    public String id() {
        return "info";
    }

    @Override
    public String path() {
        return "credit.info";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public String usage() {
        return "/eco credit info";
    }

    @Override
    public CompletableFuture<?> prepare(CommandContext context) {
        if (context.sender() instanceof Player player) {
            return economy.accountAsync(player.getUniqueId(), player.getName());
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CommandResult execute(CommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            return CommandResult.rejected("command._default.error.player-only");
        }
        return show(context.sender(), player);
    }

    /**
     * Zeigt die Kreditübersicht eines Spielers.
     */
    CommandResult show(CommandSender sender, Player player) {
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
            return CommandResult.handled();
        }

        int index = 1;
        for (Loan loan : loans) {
            messages().send(sender, "credit-info-entry",
                    "id", String.valueOf(index),
                    "amount", currency().format(loan.principal()),
                    "remaining", currency().format(loan.remaining()),
                    "interest", currency().format(loan.interestRemaining()),
                    "due", credit.formatDue(loan),
                    "status", messages().raw(statusKey(loan, now)));
            index++;
        }
        return CommandResult.handled();
    }

    private static String statusKey(Loan loan, long now) {
        if (loan.isOverdue(now)) {
            return "credit-status-overdue";
        }
        return loan.autoCollect() ? "credit-status-collecting" : "credit-status-running";
    }
}
