package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandPermissions;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * {@code /eco help [<seite>]} - immer erlaubt (5.2). Seite 1 zeigt die
 * Spielerbefehle, Seite 2 die Adminbefehle; mehr Seiten kennt die
 * {@code help.yml} über {@code /eco admin help}.
 */
public final class HelpModule extends AbstractModule {

    private static final String PERMISSION_ADMIN = "economysystem.admin";

    public HelpModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    public String id() {
        return "help";
    }

    @Override
    public String path() {
        return "help";
    }

    @Override
    public String permission() {
        // Die Hilfe ist immer erlaubt.
        return "";
    }

    @Override
    public String usage() {
        return "/eco help [<seite>]";
    }

    @Override
    public CommandResult execute(CommandContext context) {
        CommandSender sender = context.sender();
        List<String> args = context.args().positional();
        if (!args.isEmpty() && matchesIgnoreCase(args.get(0), "help", "?")) {
            // /economysystem help tippt das Wort zusätzlich, weil der
            // Kurzbefehl selbst auf "help" zeigt.
            args = args.subList(1, args.size());
        }
        if (args.size() > 1) {
            usageError(sender, String.join(" ", args));
            return CommandResult.handled();
        }

        int page = 1;
        if (args.size() == 1) {
            try {
                page = Integer.parseInt(args.get(0).trim());
            } catch (NumberFormatException exception) {
                return CommandResult.rejected("invalid-page").with("input", args.get(0));
            }
        }
        if (page < 1) {
            return CommandResult.rejected("invalid-page").with("input", String.valueOf(page));
        }

        if (page > 1) {
            if (!CommandPermissions.granted(sender, PERMISSION_ADMIN)) {
                return CommandResult.rejected("command._default.error.no-permission");
            }
            messages().sendList(sender, "help-admin");
            return CommandResult.handled();
        }

        messages().sendList(sender, "help");
        if (CommandPermissions.granted(sender, PERMISSION_ADMIN)) {
            messages().sendList(sender, "help-admin");
        }
        return CommandResult.handled();
    }

    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.size() != 1) {
            return List.of();
        }
        return filter(List.of("1", "2"), raw.get(0));
    }
}
