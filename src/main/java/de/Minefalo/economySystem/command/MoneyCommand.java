package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * /money - zeigt das eigene oder ein fremdes Guthaben an.
 */
public final class MoneyCommand extends AbstractEconomyCommand {

    private static final String PERMISSION_BALANCE = "economysystem.balance";
    private static final String PERMISSION_OTHERS = "economysystem.balance.others";

    public MoneyCommand(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            showSelf(sender);
            return true;
        }
        if (args.length > 1) {
            messages().send(sender, "usage-money");
            return true;
        }
        if (args[0].equalsIgnoreCase("help")) {
            messages().sendList(sender, "help");
            return true;
        }

        showOther(sender, args[0]);
        return true;
    }

    private void showSelf(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            // Die Konsole hat kein eigenes Guthaben; sie bekommt die Hilfe.
            messages().sendList(sender, "help");
            return;
        }
        if (!sender.hasPermission(PERMISSION_BALANCE)) {
            noPermission(sender);
            return;
        }

        Account account = economy.account(player.getUniqueId(), player.getName());
        messages().send(sender, "balance-self",
                "player", player.getName(),
                "balance", currency().format(account.balance()),
                "currency", currency().name());
    }

    private void showOther(CommandSender sender, String playerName) {
        if (!sender.hasPermission(PERMISSION_OTHERS)) {
            noPermission(sender);
            return;
        }

        Optional<Account> target = economy.find(playerName);
        if (target.isEmpty()) {
            messages().send(sender, "player-not-found", "player", playerName);
            return;
        }

        Account account = target.get();
        messages().send(sender, "balance-other",
                "player", account.name(),
                "balance", currency().format(account.balance()),
                "currency", currency().name());
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        List<String> options = new ArrayList<>();
        options.add("help");
        if (sender.hasPermission(PERMISSION_OTHERS)) {
            Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
        }
        return filter(options, args[0]);
    }
}
