package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.config.Messages;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.currency.Currency;
import de.Minefalo.economySystem.economy.EconomyService;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * {@code /eco info} - Zustand des Systems: Speicher, Währung, Grenzen,
 * Zinsen. Dieselbe Ausgabe liefert die bisherige Admin-Schreibweise
 * {@code /economysystem-admin info}.
 */
public final class InfoModule extends AbstractModule {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public InfoModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    public String id() {
        return "info";
    }

    @Override
    public String path() {
        return "info";
    }

    @Override
    public String usage() {
        return "/eco info";
    }

    @Override
    public CommandResult execute(CommandContext context) {
        Currency currency = currency();
        Messages messages = messages();
        Settings.Interest interest = plugin.settings().interest();
        BigDecimal maxBalance = currency.maxBalance();
        int saveInterval = plugin.settings().saveIntervalSeconds();

        String cycle = interest.enabled()
                ? "alle " + interest.intervalMinutes() + " Min. ab "
                        + interest.startTime().format(TIME_FORMAT)
                : messages.raw("disabled");
        String vault = switch (plugin.vaultState()) {
            case MISSING -> messages.raw("vault-status-missing");
            case DISABLED -> messages.raw("vault-status-disabled");
            case FAILED -> messages.raw("vault-status-failed");
            case ACTIVE -> messages.raw("vault-status-active")
                    .replace("%priority%", String.valueOf(plugin.vaultPriority()));
        };

        messages.sendList(context.sender(), "info",
                "version", plugin.getPluginMeta().getVersion(),
                "storage", economy.storage().name(),
                "currency", currency.name(),
                "symbol", currency.symbol(),
                "decimals", String.valueOf(currency.decimals()),
                "starting-balance", currency.format(currency.startingBalance()),
                "max-balance", maxBalance == null ? messages.raw("unlimited") : currency.format(maxBalance),
                "min-balance", currency.format(currency.minBalance()),
                "save-interval", saveInterval <= 0 ? messages.raw("disabled") : saveInterval + "s",
                "accounts", String.valueOf(economy.cachedAccountCount()),
                "credits", plugin.settings().credits().enabled() ? messages.raw("enabled") : messages.raw("disabled"),
                "interest-savings", interest.savingsFactor().stripTrailingZeros().toPlainString(),
                "interest-credit", interest.creditFactor().stripTrailingZeros().toPlainString(),
                "interest-cycle", cycle,
                "vault", vault);
        return CommandResult.handled();
    }
}
