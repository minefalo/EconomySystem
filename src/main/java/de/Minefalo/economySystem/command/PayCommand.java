package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * /pay <Spieler> <Betrag> - überweist Geld an einen anderen Spieler.
 */
public final class PayCommand extends AbstractEconomyCommand {

    private static final String PERMISSION_PAY = "economysystem.pay";

    public PayCommand(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "player-only");
            return true;
        }
        if (!sender.hasPermission(PERMISSION_PAY)) {
            noPermission(sender);
            return true;
        }
        if (args.length != 2) {
            messages().send(sender, "usage-pay");
            return true;
        }

        Account source = economy.account(player.getUniqueId(), player.getName());
        Account target = resolve(sender, args[0]);
        if (target == null) {
            return true;
        }
        if (target.uniqueId().equals(source.uniqueId())) {
            messages().send(sender, "pay-self");
            return true;
        }

        BigDecimal amount = parseAmount(sender, args[1], false);
        if (amount == null) {
            return true;
        }

        if (source.balance().compareTo(amount) < 0) {
            messages().send(sender, "insufficient-funds",
                    "balance", currency().format(source.balance()),
                    "currency", currency().name());
            return true;
        }

        BigDecimal maxBalance = currency().maxBalance();
        if (maxBalance != null && target.balance().add(amount).compareTo(maxBalance) > 0) {
            messages().send(sender, "amount-too-large",
                    "max", currency().format(maxBalance),
                    "player", target.name());
            return true;
        }

        if (!economy.transfer(source, target, amount)) {
            messages().send(sender, "insufficient-funds",
                    "balance", currency().format(source.balance()),
                    "currency", currency().name());
            return true;
        }

        messages().send(sender, "pay-sent",
                "player", target.name(),
                "amount", currency().format(amount),
                "balance", currency().format(source.balance()),
                "currency", currency().name());

        Player onlineTarget = Bukkit.getPlayer(target.uniqueId());
        if (onlineTarget != null) {
            messages().send(onlineTarget, "pay-received",
                    "player", player.getName(),
                    "amount", currency().format(amount),
                    "balance", currency().format(target.balance()),
                    "currency", currency().name());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION_PAY)) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
            return filter(names, args[0]);
        }
        if (args.length == 2) {
            return filter(List.of("1", "10", "100", "1000"), args[1]);
        }
        return List.of();
    }
}
