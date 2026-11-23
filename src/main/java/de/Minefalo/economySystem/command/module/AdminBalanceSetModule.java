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
 * {@code /eco admin balance set <konto> <betrag> --confirm} - setzt ein
 * Guthaben. Der Pfad ist bestätigungspflichtig (Abschnitt 6.4), weil er den
 * bisherigen Wert überschreibt.
 */
public final class AdminBalanceSetModule extends AbstractModule {

    public AdminBalanceSetModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    public String id() {
        return "set";
    }

    @Override
    public String path() {
        return "admin.balance.set";
    }

    @Override
    public String usage() {
        return "/eco admin balance set <konto> <betrag>";
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
        if (args.size() != 2) {
            usageError(sender, String.join(" ", args));
            return CommandResult.handled();
        }

        Account target = resolve(sender, args.get(0));
        if (target == null) {
            return CommandResult.handled();
        }
        BigDecimal amount = parseAmount(sender, args.get(1), true);
        if (amount == null) {
            return CommandResult.handled();
        }
        return apply(sender, target, amount, context.args().hasFlag("confirm"));
    }

    /**
     * Setzt den Betrag. Die Bestätigung ist an die Wirkung gebunden: ohne
     * {@code confirmed} wird nichts verändert, unabhängig davon, über welchen
     * Pfad der Aufruf hereinkommt (kanonisch, Altform oder GUI).
     */
    CommandResult apply(CommandSender sender, Account target, BigDecimal amount, boolean confirmed) {
        if (!confirmed) {
            messages().send(sender, "command._default.error.confirm-required",
                    "syntax", usage() + " --confirm");
            return CommandResult.handled();
        }
        economy.setBalance(target, amount);
        return CommandResult.ok("balance-set")
                .with("player", target.name())
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
