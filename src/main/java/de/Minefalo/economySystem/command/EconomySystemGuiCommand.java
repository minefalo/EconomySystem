package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.gui.GuiManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * /economysystem-gui - öffnet das klickbare Wirtschafts-Menü.
 *
 * <p>Die Items und Befehle stehen in der {@code gui.yml}. Ohne Argument
 * öffnet sich das Standardmenü; ein Menüname kann direkt angegeben werden
 * (z. B. {@code /egui admin}).</p>
 */
public final class EconomySystemGuiCommand extends AbstractEconomyCommand {

    private static final String PERMISSION = "economysystem.gui";

    private final GuiManager gui;

    public EconomySystemGuiCommand(EconomySystem plugin, EconomyService economy, GuiManager gui) {
        super(plugin, economy);
        this.gui = gui;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            noPermission(sender);
            return true;
        }
        if (args.length > 1) {
            messages().send(sender, "usage-gui");
            return true;
        }

        String menu = args.length == 0 ? gui.defaultMenu() : args[0].toLowerCase(Locale.ROOT);
        gui.open(player, menu);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1 || !(sender instanceof Player player) || !player.hasPermission(PERMISSION)) {
            return List.of();
        }
        return filter(gui.menuIds(player), args[0]);
    }
}
