package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.economy.EconomyService;

import java.util.List;

/**
 * {@code /eco admin reload [local]} - lädt alle Konfigurationen neu.
 *
 * <p>{@code network} gehört zum Netzwerkbetrieb (Abschnitt 11, Stufe ⏳) und
 * wird deshalb noch abgewiesen.</p>
 */
public final class AdminReloadModule extends AbstractModule {

    public AdminReloadModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    public String id() {
        return "reload";
    }

    @Override
    public String path() {
        return "admin.reload";
    }

    @Override
    public String usage() {
        return "/eco admin reload [local]";
    }

    @Override
    public CommandResult execute(CommandContext context) {
        List<String> args = context.args().positional();
        if (!args.isEmpty()
                && !(args.size() == 1 && args.get(0).equalsIgnoreCase("local"))) {
            usageError(context.sender(), args.get(0));
            return CommandResult.handled();
        }

        try {
            plugin.reload();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Konfiguration konnte nicht vollständig neu geladen werden: "
                    + exception);
            return CommandResult.rejected("settings-error");
        }
        return CommandResult.ok("reload-success");
    }

    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.size() != 1) {
            return List.of();
        }
        return filter(List.of("local"), raw.get(0));
    }
}
