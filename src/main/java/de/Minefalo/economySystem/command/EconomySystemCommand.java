package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.Messages;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.currency.Currency;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /economysystem - Hauptbefehl für Hilfe, Informationen und Reload.
 */
public final class EconomySystemCommand extends AbstractEconomyCommand {

    private static final String PERMISSION_ADMIN = "economysystem.admin";
    private static final String PERMISSION_RELOAD = "economysystem.reload";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

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
            case "info", "status" -> info(sender);
            case "reload" -> reload(sender);
            default -> messages().send(sender, "usage-economysystem");
        }
        return true;
    }

    private void showHelp(CommandSender sender) {
        messages().sendList(sender, "help");
        if (hasAdminAccess(sender)) {
            messages().sendList(sender, "help-admin");
        }
    }

    private void info(CommandSender sender) {
        if (!hasAdminAccess(sender)) {
            noPermission(sender);
            return;
        }

        Currency currency = currency();
        Messages messages = messages();
        Settings.Interest interest = plugin.settings().interest();
        BigDecimal maxBalance = currency.maxBalance();
        int saveInterval = plugin.settings().saveIntervalSeconds();

        String cycle = interest.enabled()
                ? "alle " + interest.intervalMinutes() + " Min. ab "
                        + interest.startTime().format(TIME_FORMAT)
                : messages.raw("disabled");

        messages.sendList(sender, "info",
                "version", plugin.getPluginMeta().getVersion(),
                "storage", economy.storage().name(),
                "currency", currency.name(),
                "symbol", currency.symbol(),
                "decimals", String.valueOf(currency.decimals()),
                "starting-balance", currency.format(currency.startingBalance()),
                "max-balance", maxBalance == null ? messages.raw("unlimited") : currency.format(maxBalance),
                "save-interval", saveInterval <= 0 ? messages.raw("disabled") : saveInterval + "s",
                "accounts", String.valueOf(economy.cachedAccountCount()),
                "interest-savings", interest.savingsFactor().stripTrailingZeros().toPlainString(),
                "interest-credit", interest.creditFactor().stripTrailingZeros().toPlainString(),
                "interest-cycle", cycle);
    }

    private void reload(CommandSender sender) {
        if (!hasAdminAccess(sender)) {
            noPermission(sender);
            return;
        }

        plugin.reload();
        messages().send(sender, "reload-success");
    }

    private boolean hasAdminAccess(CommandSender sender) {
        return sender.hasPermission(PERMISSION_ADMIN) || sender.hasPermission(PERMISSION_RELOAD);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        List<String> options = new ArrayList<>();
        options.add("help");
        if (hasAdminAccess(sender)) {
            options.add("info");
            options.add("reload");
        }
        return filter(options, args[0]);
    }
}
