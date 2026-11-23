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
 * {@code /eco admin balance remove <konto> <betrag> [--reason <text>]} -
 * zieht Guthaben von einem Konto ab.
 */
public final class AdminBalanceRemoveModule extends AbstractModule {

    public AdminBalanceRemoveModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    public String id() {
        return "remove";
    }

    @Override
    public String path() {
        return "admin.balance.remove";
    }

    @Override
    public String usage() {
        return "/eco admin balance remove <konto> <betrag> [--reason <text>]";
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
     * Zieht den Betrag ab; wird auch von der bisherigen Schreibweise
     * {@code /economysystem-admin money <konto> remove <betrag>} genutzt.
     */
    CommandResult apply(CommandSender sender, Account target, BigDecimal amount) {
        if (!economy.withdraw(target, amount)) {
            return CommandResult.rejected("insufficient-funds-other")
                    .with("player", target.name())
                    .with("balance", currency().format(target.balance()))
                    .with("currency", currency().name());
        }
        return CommandResult.ok("balance-taken")
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
