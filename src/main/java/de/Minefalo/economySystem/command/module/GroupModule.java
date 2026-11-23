package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandModule;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.command.CommandTree;
import de.Minefalo.economySystem.economy.EconomyService;

import java.util.List;

/**
 * Ein reiner Gruppenknoten wie {@code admin.balance}: Pfad und Syntax, aber
 * keine Fachlogik. Die Permission ist die aus dem Pfad abgeleitete
 * ({@code economysystem.command.<pfad>}). Die Kinder melden sich hier an
 * (Abschnitt 10.3), damit weder {@code plugin.yml} noch die Befehlsliste an
 * einer zweiten Stelle gepflegt werden müssen.
 */
final class GroupModule extends AbstractModule {

    private final String path;
    private final String usage;
    private final List<CommandModule> children;

    GroupModule(EconomySystem plugin, EconomyService economy, String path,
                String usage, List<CommandModule> children) {
        super(plugin, economy);
        this.path = path;
        this.usage = usage;
        this.children = List.copyOf(children);
    }

    @Override
    public String id() {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String usage() {
        return usage;
    }

    @Override
    public void register(CommandTree tree) {
        children.forEach(tree::add);
    }

    @Override
    public CommandResult execute(CommandContext context) {
        usageError(context.sender(), "");
        return CommandResult.handled();
    }
}
