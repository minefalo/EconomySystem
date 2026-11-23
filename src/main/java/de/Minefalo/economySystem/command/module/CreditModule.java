package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.command.CommandTree;
import de.Minefalo.economySystem.credit.CreditService;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /eco credit} - Gruppe der Kreditpfade. Ohne Unterpfad zeigt sie die
 * Übersicht, weil {@code /kredit} ohne Argument schon immer so geantwortet
 * hat (Abschnitt 12.1).
 */
public final class CreditModule extends AbstractModule {

    private final CreditService credit;
    private final CreditInfoModule info;

    public CreditModule(EconomySystem plugin, EconomyService economy, CreditService credit) {
        super(plugin, economy);
        this.credit = credit;
        this.info = new CreditInfoModule(plugin, economy, credit);
    }

    @Override
    public String id() {
        return "credit";
    }

    @Override
    public String path() {
        return "credit";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public String usage() {
        return "/eco credit <take <betrag> <tage>|repay [<betrag>]|info|list>";
    }

    @Override
    public void register(CommandTree tree) {
        tree.add(new CreditTakeModule(plugin, economy, credit));
        tree.add(new CreditRepayModule(plugin, economy, credit));
        tree.add(info);
        tree.add(new CreditListModule(plugin, economy, credit));
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
        if (!context.args().positional().isEmpty()) {
            usageError(context.sender(), String.join(" ", context.args().positional()));
            return CommandResult.handled();
        }
        return info.show(context.sender(), player);
    }

    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.size() != 1) {
            return List.of();
        }
        return filter(List.of("take", "repay", "info", "list"), raw.get(0));
    }
}
