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

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /eco credit take <betrag> <tage>} - nimmt einen Kredit auf.
 *
 * <p>Die Prüfreihenfolge (System aktiv → Laufzeit → Mindestbetrag →
 * Kreditplätze → Rahmen → Maximalguthaben) steckt im {@link CreditService};
 * das Modul übersetzt nur noch das Ergebnis in Nachrichten.</p>
 */
public final class CreditTakeModule extends AbstractModule {

    private final CreditService credit;

    public CreditTakeModule(EconomySystem plugin, EconomyService economy, CreditService credit) {
        super(plugin, economy);
        this.credit = credit;
    }

    @Override
    public String id() {
        return "take";
    }

    @Override
    public String path() {
        return "credit.take";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public String usage() {
        return "/eco credit take <betrag> <tage>";
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
        if (args.size() != 2) {
            usageError(sender, String.join(" ", args));
            return CommandResult.handled();
        }

        BigDecimal amount = parseAmount(sender, args.get(0), false);
        if (amount == null) {
            return CommandResult.handled();
        }
        Integer days = parseDays(sender, args.get(1));
        if (days == null) {
            return CommandResult.handled();
        }

        Settings.Credits config = plugin.settings().credits();
        Account account = economy.account(player.getUniqueId(), player.getName());
        CreditService.Result result = credit.takeLoan(account, amount, days);

        return switch (result.status()) {
            case SUCCESS -> {
                Loan loan = result.loan();
                yield CommandResult.ok("credit-created")
                        .with("amount", currency().format(loan.principal()))
                        .with("days", String.valueOf(loan.days()))
                        .with("interest", currency().format(loan.interest()))
                        .with("total", currency().format(loan.total()))
                        .with("due", credit.formatDue(loan));
            }
            case DISABLED -> CommandResult.rejected("credit-disabled");
            case TOO_MANY_LOANS -> CommandResult.rejected("credit-too-many-loans")
                    .with("max", String.valueOf(config.maxLoans()))
                    .with("count", String.valueOf(credit.activeLoanCount(player.getUniqueId())));
            case INVALID_DAYS -> CommandResult.rejected("credit-invalid-days")
                    .with("min", String.valueOf(config.minDays()))
                    .with("max", String.valueOf(config.maxDays()));
            case NO_CREDIT_LINE -> CommandResult.rejected("credit-no-line");
            case LIMIT_EXCEEDED -> CommandResult.rejected("credit-limit-exceeded")
                    .with("max", currency().format(credit.maxLoan(account)))
                    .with("multiplier", config.limitMultiplier().stripTrailingZeros().toPlainString());
            case AMOUNT_TOO_SMALL -> CommandResult.rejected("credit-amount-too-small")
                    .with("min", currency().format(config.minimumAmount()));
            case MAX_BALANCE -> CommandResult.rejected("credit-max-balance")
                    .with("max", currency().format(currency().maxBalance()));
        };
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
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.size() == 1) {
            return filter(plugin.settings().suggestions().creditAmounts(), raw.get(0));
        }
        if (raw.size() == 2) {
            return filter(plugin.settings().suggestions().creditDays(), raw.get(1));
        }
        return List.of();
    }
}
