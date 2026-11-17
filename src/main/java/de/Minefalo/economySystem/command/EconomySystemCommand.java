package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * /economysystem - Hauptbefehl für die Hilfe.
 *
 * <p>Die früheren Unterbefehle {@code info} und {@code reload} sind
 * Admin-Befehle und liegen jetzt unter {@code /economysystem-admin}.</p>
 */
public final class EconomySystemCommand extends AbstractEconomyCommand {

    private static final String PERMISSION_ADMIN = "economysystem.admin";

    public EconomySystemCommand(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help", "?" -> showHelp(sender);
            default -> messages().send(sender, "usage-economysystem");
        }
        return true;
    }

    private void showHelp(CommandSender sender) {
        messages().sendList(sender, "help");
        if (sender.hasPermission(PERMISSION_ADMIN)) {
            messages().sendList(sender, "help-admin");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        return filter(List.of("help"), args[0]);
    }
}
