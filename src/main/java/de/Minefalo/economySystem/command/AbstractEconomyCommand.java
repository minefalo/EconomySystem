package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.Messages;
import de.Minefalo.economySystem.currency.Currency;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.storage.StorageException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
        CompletableFuture<?> preparation = CompletableFuture.completedFuture(null);
        if (sender instanceof Player player
                && ((this instanceof MoneyCommand && args.length == 0 && sender.hasPermission("economysystem.balance"))
                    || (this instanceof PayCommand && sender.hasPermission("economysystem.pay"))
                    || (this instanceof KreditCommand && sender.hasPermission("economysystem.kredit")))) {
            preparation = economy.accountAsync(player.getUniqueId(), player.getName());
        }

        String target = null;
        if (this instanceof PayCommand && sender instanceof Player && sender.hasPermission("economysystem.pay")
                && args.length == 2 && Bukkit.getPlayerExact(args[0]) != null) {
            target = args[0];
        } else if (this instanceof MoneyCommand && sender.hasPermission("economysystem.balance.others")
                && args.length == 1 && !args[0].equalsIgnoreCase("help")) {
            target = args[0];
        } else if (this instanceof EconomySystemAdminCommand && sender.hasPermission("economysystem.admin")
                && args.length >= 3 && (args[0].equalsIgnoreCase("money") || args[0].equalsIgnoreCase("geld"))) {
            target = args[1];
        }
        if (target != null) {
            CompletableFuture<?> lookup = economy.findAsync(target);
            preparation = CompletableFuture.allOf(preparation, lookup);
        }
        preparation.whenComplete((ignored, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (sender instanceof Player player && !player.isOnline()) {
                    return;
                }
                if (error != null) {
                    plugin.getLogger().warning("Konto konnte nicht geladen werden: " + error.getMessage());
                    messages().send(sender, "storage-error");
                    return;
                }
                try {
                    execute(sender, label, args);
                } catch (StorageException exception) {
                    plugin.getLogger().severe("Zugriff auf den Economy-Speicher fehlgeschlagen: "
                            + exception.getMessage());
                    messages().send(sender, "storage-error");
                }
            });
        });
        return true;
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
