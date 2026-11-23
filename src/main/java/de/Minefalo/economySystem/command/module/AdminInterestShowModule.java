package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.economy.EconomyService;

/**
 * {@code /eco admin interest show} - zeigt beide Zinsfaktoren.
 */
public final class AdminInterestShowModule extends AbstractModule {

    public AdminInterestShowModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    public String id() {
        return "show";
    }

    @Override
    public String path() {
        return "admin.interest.show";
    }

    @Override
    public String usage() {
        return "/eco admin interest show";
    }

    @Override
    public CommandResult execute(CommandContext context) {
        Settings.Interest interest = plugin.settings().interest();
        return CommandResult.ok("command.admin.interest.show")
                .with("savings", interest.savingsFactor().stripTrailingZeros().toPlainString())
                .with("credit", interest.creditFactor().stripTrailingZeros().toPlainString());
    }
}
