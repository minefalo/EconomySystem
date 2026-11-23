package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.command.CommandSender;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /eco admin balance add <konto> <betrag> [--reason <text>]} - bucht
 * Guthaben auf ein Konto.
 */
public final class AdminBalanceAddModule extends AbstractModule {

    public AdminBalanceAddModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    public String id() {
        return "add";
    }

    @Override
    public String path() {
        return "admin.balance.add";
    }

    @Override
    public String usage() {
        return "/eco admin balance add <konto> <betrag> [--reason <text>]";
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
        if (args.size() != 2) {
            usageError(sender, String.join(" ", args));
            return CommandResult.handled();
        }

        Account target = resolve(sender, args.get(0));
        if (target == null) {
            return CommandResult.handled();
        }
        BigDecimal amount = parseAmount(sender, args.get(1), false);
        if (amount == null) {
            return CommandResult.handled();
        }
        return apply(sender, target, amount);
    }

    /**
     * Bucht den Betrag; wird auch von der bisherigen Schreibweise
     * {@code /economysystem-admin money <konto> add <betrag>} genutzt.
     */
    CommandResult apply(CommandSender sender, Account target, BigDecimal amount) {
        if (!economy.deposit(target, amount)) {
            BigDecimal maxBalance = currency().maxBalance();
            return CommandResult.rejected("amount-too-large")
                    .with("max", maxBalance == null ? "-" : currency().format(maxBalance))
                    .with("player", target.name());
        }
        return CommandResult.ok("balance-given")
                .with("player", target.name())
                .with("amount", currency().format(amount))
                .with("balance", currency().format(target.balance()))
                .with("currency", currency().name());
    }

    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.size() == 1) {
            return filter(onlinePlayerNames(), raw.get(0));
        }
        if (raw.size() == 2) {
            return filter(plugin.settings().suggestions().amounts(), raw.get(1));
        }
        return List.of();
    }
}
