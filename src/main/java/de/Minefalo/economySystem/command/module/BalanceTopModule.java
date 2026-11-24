package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.storage.AccountData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /eco balance top [<seite>]} - Rangliste des Guthabens.
 */
public final class BalanceTopModule extends AbstractModule {

    public BalanceTopModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    public String id() {
        return "top";
    }

    @Override
    public String path() {
        return "balance.top";
    }

    @Override
    public String usage() {
        return "/eco balance top [<seite>]";
    }

    @Override
    public CommandResult execute(CommandContext context) {
        CommandSender sender = context.sender();
        // Die Permission prüft allein der Router (Abschnitt 6.7, Stufe 4):
        // economysystem.command.balance.top oder der bisherige Knoten
        // economysystem.balance.top. Der Sammelknoten
        // economysystem.command.balance trägt die Bestenliste nicht mit (5.2).
        List<String> args = context.args().positional();
        if (args.size() > 1) {
            usageError(sender, String.join(" ", args));
            return CommandResult.handled();
        }

        int requestedPage = 1;
        if (args.size() == 1) {
            try {
                requestedPage = Integer.parseInt(args.get(0).trim());
            } catch (NumberFormatException exception) {
                return CommandResult.rejected("top-invalid-page").with("input", args.get(0));
            }
            if (requestedPage < 1) {
                return CommandResult.rejected("top-invalid-page").with("input", args.get(0));
            }
        }

        int pageSize = plugin.settings().leaderboard().pageSize();
        int maxEntries = plugin.settings().leaderboard().maxEntries();
        int page = requestedPage;

        economy.topAsync(maxEntries, plugin.settings().leaderboard().includeZeroBalances())
                .whenComplete((entries, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (sender instanceof Player player && !player.isOnline()) {
                    return;
                }
                if (error != null) {
                    plugin.getLogger().warning("Bestenliste konnte nicht geladen werden: " + messageOf(error));
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
        return CommandResult.handled();
    }

    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.size() != 1) {
            return List.of();
        }
        // Ohne Recht ruft der Router complete() gar nicht erst auf.
        return filter(List.of("1", "2", "3"), raw.get(0));
    }
}
