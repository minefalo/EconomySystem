package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.storage.AccountData;
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
    private static final String PERMISSION_TOP = "economysystem.balance.top";

    public MoneyCommand(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            showSelf(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("top")) {
            showTop(sender, args);
            return true;
        }
        if (args.length > 1) {
            messages().send(sender, "usage-money");
            return true;
        }
        if (args[0].equalsIgnoreCase("help")) {
            if (!sender.hasPermission(PERMISSION_BALANCE)) {
                noPermission(sender);
                return true;
            }
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

    private void showTop(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION_TOP)) {
            noPermission(sender);
            return;
        }
        if (args.length > 2) {
            messages().send(sender, "usage-money");
            return;
        }

        int requestedPage = 1;
        if (args.length == 2) {
            try {
                requestedPage = Integer.parseInt(args[1].trim());
            } catch (NumberFormatException exception) {
                messages().send(sender, "top-invalid-page", "input", args[1]);
                return;
            }
            if (requestedPage < 1) {
                messages().send(sender, "top-invalid-page", "input", args[1]);
                return;
            }
        }

        int pageSize = plugin.settings().leaderboard().pageSize();
        int maxEntries = plugin.settings().leaderboard().maxEntries();
        int page = requestedPage;

        economy.topAsync(maxEntries).whenComplete((entries, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (sender instanceof Player player && !player.isOnline()) {
                    return;
                }
                if (error != null) {
                    String reason = error.getMessage() == null
                            ? error.getClass().getSimpleName()
                            : error.getMessage();
                    plugin.getLogger().warning("Bestenliste konnte nicht geladen werden: " + reason);
                    messages().send(sender, "storage-error");
                    return;
                }

                List<AccountData> top = entries == null ? List.of() : entries;
                if (top.isEmpty()) {
                    messages().send(sender, "top-empty");
                    return;
                }

                int pages = Math.max(1, (top.size() + pageSize - 1) / pageSize);
                int current = Math.min(page, pages);
                messages().send(sender, "top-header",
                        "page", String.valueOf(current),
                        "pages", String.valueOf(pages));

                int start = (current - 1) * pageSize;
                int end = Math.min(start + pageSize, top.size());
                for (int index = start; index < end; index++) {
                    AccountData data = top.get(index);
                    messages().send(sender, "top-entry",
                            "rank", String.valueOf(index + 1),
                            "player", data.name(),
                            "balance", currency().format(data.balance()));
                }
            });
        });
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("top")
                && sender.hasPermission(PERMISSION_TOP)) {
            return filter(List.of("1", "2", "3"), args[1]);
        }
        if (args.length != 1) {
            return List.of();
        }

        List<String> options = new ArrayList<>();
        if (sender.hasPermission(PERMISSION_BALANCE)) {
            options.add("help");
        }
        if (sender.hasPermission(PERMISSION_TOP)) {
            options.add("top");
        }
        if (sender.hasPermission(PERMISSION_OTHERS)) {
            Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
        }
        return filter(options, args[0]);
    }
}
