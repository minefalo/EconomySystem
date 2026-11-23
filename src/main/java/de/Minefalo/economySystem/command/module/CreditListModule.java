package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.credit.CreditService;
import de.Minefalo.economySystem.credit.Loan;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /eco credit list} - laufende Kredite mit kurzer Kredit-ID, damit
 * {@code admin credit forgive} später daran anschließen kann.
 */
public final class CreditListModule extends AbstractModule {

    private final CreditService credit;

    public CreditListModule(EconomySystem plugin, EconomyService economy, CreditService credit) {
        super(plugin, economy);
        this.credit = credit;
    }

    @Override
    public String id() {
        return "list";
    }

    @Override
    public String path() {
        return "credit.list";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public String usage() {
        return "/eco credit list";
    }

    @Override
    public CommandResult execute(CommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            return CommandResult.rejected("command._default.error.player-only");
        }

        List<Loan> loans = credit.loansOf(player.getUniqueId());
        messages().send(context.sender(), "credit-info-header");
        if (loans.isEmpty()) {
            messages().send(context.sender(), "credit-info-none");
            return CommandResult.handled();
        }

        long now = System.currentTimeMillis();
        for (Loan loan : loans) {
            messages().send(context.sender(), "credit-info-entry",
                    "id", shortId(loan),
                    "amount", currency().format(loan.principal()),
                    "remaining", currency().format(loan.remaining()),
                    "interest", currency().format(loan.interestRemaining()),
                    "due", credit.formatDue(loan),
                    "status", messages().raw(loan.isOverdue(now) ? "credit-status-overdue"
                            : loan.autoCollect() ? "credit-status-collecting" : "credit-status-running"));
        }
        return CommandResult.handled();
    }

    static String shortId(Loan loan) {
        return loan.id().toString().substring(0, 8);
    }
}
