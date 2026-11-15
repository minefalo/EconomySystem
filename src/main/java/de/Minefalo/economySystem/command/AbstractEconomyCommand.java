package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.Messages;
import de.Minefalo.economySystem.currency.Currency;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.storage.StorageException;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Gemeinsame Basis für alle Economy-Befehle. Kapselt Fehlerbehandlung
 * und die Betrag-/Spielerauflösung.
 */
public abstract class AbstractEconomyCommand implements CommandExecutor, TabCompleter {

    protected final EconomySystem plugin;
    protected final EconomyService economy;

    protected AbstractEconomyCommand(EconomySystem plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    @Override
    public final boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                   @NotNull String label, @NotNull String[] args) {
        if (!plugin.isEnabled()) {
            return true;
        }
        try {
            return execute(sender, label, args);
        } catch (StorageException exception) {
            plugin.getLogger().severe("Zugriff auf den Economy-Speicher fehlgeschlagen: "
                    + exception.getMessage());
            messages().send(sender, "storage-error");
            return true;
        }
    }

    protected abstract boolean execute(CommandSender sender, String label, String[] args);

    /**
     * Sucht ein Konto anhand des Spielernamens.
     */
    protected Account resolve(CommandSender sender, String playerName) {
        Optional<Account> target = economy.find(playerName);
        if (target.isEmpty()) {
            messages().send(sender, "player-not-found", "player", playerName);
            return null;
        }
        return target.get();
    }

    /**
     * Parst einen Betrag. Gibt {@code null} zurück, wenn die Eingabe ungültig
     * ist (der Spieler wurde dann bereits informiert).
     */
    protected BigDecimal parseAmount(CommandSender sender, String input, boolean allowZero) {
        Optional<BigDecimal> parsed = currency().parseAmount(input);
        if (parsed.isEmpty() || (!allowZero && parsed.get().signum() == 0)) {
            messages().send(sender, "invalid-amount", "input", input);
            return null;
        }

        BigDecimal amount = currency().normalize(parsed.get());
        BigDecimal maxBalance = currency().maxBalance();
        if (maxBalance != null && amount.compareTo(maxBalance) > 0) {
            messages().send(sender, "amount-too-large",
                    "max", currency().format(maxBalance),
                    "player", sender.getName());
            return null;
        }
        return amount;
    }

    protected void noPermission(CommandSender sender) {
        messages().send(sender, "no-permission");
    }

    protected Currency currency() {
        return plugin.settings().currency();
    }

    protected Messages messages() {
        return plugin.messages();
    }

    protected static List<String> filter(List<String> options, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
