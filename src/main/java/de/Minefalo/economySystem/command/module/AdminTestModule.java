package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.command.CommandTree;
import de.Minefalo.economySystem.economy.EconomyService;

import java.util.List;

/**
 * {@code /eco admin test} - Gruppe der Testpfade. Die Kurzform
 * {@code /eco admin test add <anzahl>} bleibt als Übersetzung auf
 * {@code interest-cycle} erhalten.
 */
public final class AdminTestModule extends AbstractModule {

    private final AdminTestInterestCycleModule cycle;

    public AdminTestModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
        this.cycle = new AdminTestInterestCycleModule(plugin, economy);
    }

    @Override
    public String id() {
        return "test";
    }

    @Override
    public String path() {
        return "admin.test";
    }

    @Override
    public String usage() {
        return "/eco admin test interest-cycle add <anzahl>";
    }

    @Override
    public void register(CommandTree tree) {
        tree.add(cycle);
    }

    @Override
    public CommandResult execute(CommandContext context) {
        List<String> args = context.args().positional();
        if (args.size() == 2 && args.get(0).equalsIgnoreCase("add")) {
            return cycle.run(context.sender(), args.get(1));
        }
        usageError(context.sender(), String.join(" ", args));
        return CommandResult.handled();
    }

    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.size() == 1) {
            return filter(List.of("interest-cycle", "add"), raw.get(0));
        }
        if (raw.size() == 2) {
            return filter(List.of("1", "7", "30"), raw.get(1));
        }
        return List.of();
    }
}
