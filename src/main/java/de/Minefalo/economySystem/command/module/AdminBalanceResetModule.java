package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /eco admin balance reset <konto> --confirm} - setzt ein Konto auf das
 * Startguthaben zurück.
 */
public final class AdminBalanceResetModule extends AbstractModule {

    public AdminBalanceResetModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    public String id() {
        return "reset";
    }

    @Override
    public String path() {
        return "admin.balance.reset";
    }

    @Override
    public String usage() {
        return "/eco admin balance reset <konto>";
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
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
        return apply(sender, target, context.args().hasFlag("confirm"));
    }

    /**
     * Setzt das Konto zurück. Die Bestätigung ist an die Wirkung gebunden:
     * ohne {@code confirmed} wird nichts verändert, unabhängig davon, über
     * welchen Pfad der Aufruf hereinkommt.
     */
    CommandResult apply(CommandSender sender, Account target, boolean confirmed) {
        if (!confirmed) {
            messages().send(sender, "command._default.error.confirm-required",
                    "syntax", usage() + " --confirm");
            return CommandResult.handled();
        }
        economy.setBalance(target, currency().startingBalance());
        return CommandResult.ok("balance-reset")
                .with("player", target.name())
                .with("balance", currency().format(target.balance()));
    }

    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.size() == 1) {
            return filter(onlinePlayerNames(), raw.get(0));
        }
        return List.of();
    }
}
