package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.credit.CreditService;
import de.Minefalo.economySystem.credit.Loan;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /eco admin credit list <konto>} - Kredite eines Kontos.
 */
public final class AdminCreditListModule extends AbstractModule {

    private final CreditService credit;

    public AdminCreditListModule(EconomySystem plugin, EconomyService economy, CreditService credit) {
        super(plugin, economy);
        this.credit = credit;
    }

    @Override
    public String id() {
        return "list";
    }

    @Override
    public String path() {
        return "admin.credit.list";
    }

    @Override
    public String usage() {
        return "/eco admin credit list <konto>";
    }

    @Override
    public CompletableFuture<?> prepare(CommandContext context) {
        List<String> args = context.args().positional();
        if (!args.isEmpty()) {
            return economy.findAsync(args.get(0));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CommandResult execute(CommandContext context) {
        CommandSender sender = context.sender();
        List<String> args = context.args().positional();
        if (args.size() != 1) {
            usageError(sender, String.join(" ", args));
            return CommandResult.handled();
        }

        Account target = resolve(sender, args.get(0));
        if (target == null) {
            return CommandResult.handled();
        }

        List<Loan> loans = credit.loansOf(target.uniqueId());
        messages().send(sender, "command.admin.credit.list.header", "player", target.name());
        if (loans.isEmpty()) {
            messages().send(sender, "command.admin.credit.list.empty", "player", target.name());
            return CommandResult.handled();
        }

        long now = System.currentTimeMillis();
        for (Loan loan : loans) {
            messages().send(sender, "credit-info-entry",
                    "id", CreditListModule.shortId(loan),
                    "amount", currency().format(loan.principal()),
                    "remaining", currency().format(loan.remaining()),
                    "interest", currency().format(loan.interestRemaining()),
                    "due", credit.formatDue(loan),
                    "status", messages().raw(loan.isOverdue(now) ? "credit-status-overdue"
                            : loan.autoCollect() ? "credit-status-collecting" : "credit-status-running"));
        }
        return CommandResult.handled();
    }

    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.size() != 1) {
            return List.of();
        }
        return filter(onlinePlayerNames(), raw.get(0));
    }
}
