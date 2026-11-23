package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandPermissions;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.command.CommandTree;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /eco balance [<konto>]} - eigenes oder fremdes Guthaben.
 *
 * <p>Das Modul prüft selbst, weil zwei verschiedene Rechte gelten: eigenes
 * Guthaben und fremdes Guthaben ({@code Commands.md}, 5.2). Ohne Argument
 * zeigt es das eigene Konto, die Konsole erhält die Hilfe.</p>
 */
public final class BalanceModule extends AbstractModule {

    private static final String PERMISSION_BALANCE = "economysystem.balance";
    private static final String PERMISSION_OTHERS = "economysystem.balance.others";

    private final BalanceTopModule top = new BalanceTopModule(plugin, economy);

    public BalanceModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    public String id() {
        return "balance";
    }

    @Override
    public String path() {
        return "balance";
    }

    @Override
    public String permission() {
        // Eigenes und fremdes Guthaben sind unterschiedlich berechtigt.
        return "";
    }

    @Override
    public String usage() {
        return "/eco balance [<konto>]";
    }

    @Override
    public void register(CommandTree tree) {
        tree.add(top);
    }

    @Override
    public CompletableFuture<?> prepare(CommandContext context) {
        CommandSender sender = context.sender();
        List<String> args = context.args().positional();
        CompletableFuture<?> preparation = CompletableFuture.completedFuture(null);
        if (sender instanceof Player player && args.isEmpty()
                && CommandPermissions.granted(sender, PERMISSION_BALANCE)) {
            preparation = economy.accountAsync(player.getUniqueId(), player.getName());
        }
        if (CommandPermissions.granted(sender, PERMISSION_OTHERS) && args.size() == 1
                && !args.get(0).equalsIgnoreCase("help")) {
            preparation = CompletableFuture.allOf(preparation, economy.findAsync(args.get(0)));
        }
        return preparation;
    }

    @Override
    public CommandResult execute(CommandContext context) {
        CommandSender sender = context.sender();
        List<String> args = context.args().positional();

        if (args.isEmpty()) {
            return showSelf(sender);
        }
        if (args.size() > 1) {
            usageError(sender, String.join(" ", args));
            return CommandResult.handled();
        }
        if (args.get(0).equalsIgnoreCase("help")) {
            if (!CommandPermissions.granted(sender, PERMISSION_BALANCE)) {
                return CommandResult.rejected("command._default.error.no-permission");
            }
            messages().sendList(sender, "help");
            return CommandResult.handled();
        }
        return showOther(sender, args.get(0));
    }

    private CommandResult showSelf(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            // Die Konsole hat kein eigenes Guthaben; sie bekommt die Hilfe.
            messages().sendList(sender, "help");
            return CommandResult.handled();
        }
        if (!CommandPermissions.granted(sender, PERMISSION_BALANCE)) {
            return CommandResult.rejected("command._default.error.no-permission");
        }

        Account account = economy.account(player.getUniqueId(), player.getName());
        return CommandResult.ok("balance-self")
                .with("player", player.getName())
                .with("balance", currency().format(account.balance()))
                .with("currency", currency().name());
    }

    private CommandResult showOther(CommandSender sender, String playerName) {
        if (!CommandPermissions.granted(sender, PERMISSION_OTHERS)) {
            return CommandResult.rejected("command._default.error.no-permission");
        }

        Account account = resolve(sender, playerName);
        if (account == null) {
            return CommandResult.handled();
        }
        return CommandResult.ok("balance-other")
                .with("player", account.name())
                .with("balance", currency().format(account.balance()))
                .with("currency", currency().name());
    }

    @Override
    public List<String> complete(CommandContext context) {
        CommandSender sender = context.sender();
        List<String> raw = context.args().raw();
        if (raw.isEmpty()) {
            return List.of();
        }
        String prefix = raw.get(raw.size() - 1);

        // "top" ist ein eigener Knoten und wird vom Router vorgeschlagen.
        List<String> options = new ArrayList<>();
        if (CommandPermissions.granted(sender, PERMISSION_BALANCE)) {
            options.add("help");
        }
        if (CommandPermissions.granted(sender, PERMISSION_OTHERS)) {
            options.addAll(onlinePlayerNames());
        }
        return filter(options, prefix);
    }
}
