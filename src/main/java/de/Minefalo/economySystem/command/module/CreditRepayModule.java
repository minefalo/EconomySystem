package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.credit.CreditService;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /eco credit repay [<betrag>]} - zahlt Kredite zurück. Ohne Betrag
 * wird so viel getilgt, wie möglich ist.
 */
public final class CreditRepayModule extends AbstractModule {

    private final CreditService credit;

    public CreditRepayModule(EconomySystem plugin, EconomyService economy, CreditService credit) {
        super(plugin, economy);
        this.credit = credit;
    }

    @Override
    public String id() {
        return "repay";
    }

    @Override
    public String path() {
        return "credit.repay";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public String usage() {
        return "/eco credit repay [<betrag>]";
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
        CommandSender sender = context.sender();
        if (!(sender instanceof Player player)) {
            return CommandResult.rejected("command._default.error.player-only");
        }

        List<String> args = context.args().positional();
        if (args.size() > 1) {
            usageError(sender, String.join(" ", args));
            return CommandResult.handled();
        }

        BigDecimal requested = null;
        if (args.size() == 1) {
            requested = parseAmount(sender, args.get(0), false);
            if (requested == null) {
                return CommandResult.handled();
            }
        }

        Account account = economy.account(player.getUniqueId(), player.getName());
        BigDecimal owed = credit.outstanding(player.getUniqueId());
        if (owed.signum() <= 0) {
            return CommandResult.rejected("credit-nothing-to-pay");
        }

        BigDecimal paid = credit.repay(account, requested);
        if (paid.signum() <= 0) {
            return CommandResult.rejected("credit-no-money");
        }

        return CommandResult.ok("credit-paid")
                .with("amount", currency().format(paid))
                .with("remaining", currency().format(credit.outstanding(player.getUniqueId())));
    }

    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.size() != 1) {
            return List.of();
        }
        return filter(plugin.settings().suggestions().repayAmounts(), raw.get(0));
    }
}
