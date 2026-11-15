package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.InterestType;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.help.HelpBook;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /economysystem-admin - Admin-Befehle für Geldverwaltung und Zinsen.
 */
public final class EconomySystemAdminCommand extends AbstractEconomyCommand {

    private static final String PERMISSION_ADMIN = "economysystem.admin";

    private final HelpBook helpBook;

    public EconomySystemAdminCommand(EconomySystem plugin, EconomyService economy, HelpBook helpBook) {
        super(plugin, economy);
        this.helpBook = helpBook;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            noPermission(sender);
            return true;
        }
        if (args.length == 0) {
            helpBook.show(sender, label, 1);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help", "?" -> showHelp(sender, label, args);
            case "money", "geld" -> money(sender, args);
            case "zinsen", "interest" -> interest(sender, args);
            default -> messages().send(sender, "usage-admin");
        }
        return true;
    }

    private void showHelp(CommandSender sender, String label, String[] args) {
        int page = 1;
        if (args.length > 2) {
            messages().send(sender, "usage-admin");
            return;
        }
        if (args.length == 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                messages().send(sender, "invalid-page", "input", args[1]);
                return;
            }
        }
        helpBook.show(sender, label, page);
    }

    private void money(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages().send(sender, "usage-admin-money");
            return;
        }

        Account target = resolve(sender, args[1]);
        if (target == null) {
            return;
        }

        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "add", "give" -> add(sender, target, args);
            case "remove", "take" -> remove(sender, target, args);
            case "set" -> set(sender, target, args);
            case "reset" -> reset(sender, target);
            default -> messages().send(sender, "usage-admin-money");
        }
    }

    private void add(CommandSender sender, Account target, String[] args) {
        BigDecimal amount = amount(sender, args, false);
        if (amount == null) {
            return;
        }
        if (!economy.deposit(target, amount)) {
            BigDecimal maxBalance = currency().maxBalance();
            messages().send(sender, "amount-too-large",
                    "max", maxBalance == null ? "-" : currency().format(maxBalance),
                    "player", target.name());
            return;
        }
        messages().send(sender, "balance-given",
                "player", target.name(),
                "amount", currency().format(amount),
                "balance", currency().format(target.balance()),
                "currency", currency().name());
    }

    private void remove(CommandSender sender, Account target, String[] args) {
        BigDecimal amount = amount(sender, args, false);
        if (amount == null) {
            return;
        }
        if (!economy.withdraw(target, amount)) {
            messages().send(sender, "insufficient-funds-other",
                    "player", target.name(),
                    "balance", currency().format(target.balance()),
                    "currency", currency().name());
            return;
        }
        messages().send(sender, "balance-taken",
                "player", target.name(),
                "amount", currency().format(amount),
                "balance", currency().format(target.balance()),
                "currency", currency().name());
    }

    private void set(CommandSender sender, Account target, String[] args) {
        BigDecimal amount = amount(sender, args, true);
        if (amount == null) {
            return;
        }
        economy.setBalance(target, amount);
        messages().send(sender, "balance-set",
                "player", target.name(),
                "balance", currency().format(target.balance()),
                "currency", currency().name());
    }

    private void reset(CommandSender sender, Account target) {
        economy.setBalance(target, currency().startingBalance());
        messages().send(sender, "balance-reset",
                "player", target.name(),
                "balance", currency().format(target.balance()));
    }

    private BigDecimal amount(CommandSender sender, String[] args, boolean allowZero) {
        if (args.length < 4) {
            messages().send(sender, "usage-admin-money");
            return null;
        }
        return parseAmount(sender, args[3], allowZero);
    }

    private void interest(CommandSender sender, String[] args) {
        if (args.length != 4 || !args[2].equalsIgnoreCase("set")) {
            messages().send(sender, "usage-admin-zinsen");
            return;
        }

        InterestType type = parseType(args[1]);
        if (type == null) {
            messages().send(sender, "usage-admin-zinsen");
            return;
        }

        BigDecimal factor = parseFactor(sender, args[3]);
        if (factor == null) {
            return;
        }

        try {
            plugin.settings().setInterestFactor(type, factor);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Zinsfaktor konnte nicht gespeichert werden: "
                    + exception.getMessage());
            messages().send(sender, "settings-error");
            return;
        }

        messages().send(sender, "interest-set",
                "type", messages().raw(type.messageKey()),
                "factor", factor.stripTrailingZeros().toPlainString());
        if (type == InterestType.CREDIT) {
            messages().send(sender, "interest-credit-hint");
        }
    }

    private static InterestType parseType(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "spar", "sparen", "savings" -> InterestType.SAVINGS;
            case "kredit", "kredite", "credit" -> InterestType.CREDIT;
            default -> null;
        };
    }

    private BigDecimal parseFactor(CommandSender sender, String input) {
        String cleaned = input.trim().replace(',', '.');
        if (cleaned.isEmpty() || !cleaned.matches("[+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")) {
            messages().send(sender, "interest-factor-invalid");
            return null;
        }
        try {
            BigDecimal factor = new BigDecimal(cleaned);
            if (factor.signum() <= 0) {
                messages().send(sender, "interest-factor-invalid");
                return null;
            }
            return factor;
        } catch (NumberFormatException exception) {
            messages().send(sender, "interest-factor-invalid");
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            return List.of();
        }

        if (args.length == 1) {
            return filter(List.of("help", "money", "zinsen"), args[0]);
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (subcommand) {
                case "money" -> filter(onlinePlayerNames(), args[1]);
                case "zinsen" -> filter(List.of("spar", "kredit"), args[1]);
                case "help" -> filter(pageSuggestions(), args[1]);
                default -> List.of();
            };
        }

        if (args.length == 3) {
            if (subcommand.equals("money")) {
                return filter(List.of("add", "remove", "set", "reset"), args[2]);
            }
            if (subcommand.equals("zinsen")) {
                return filter(List.of("set"), args[2]);
            }
            return List.of();
        }

        if (args.length == 4) {
            if (subcommand.equals("money") && !args[2].equalsIgnoreCase("reset")) {
                return filter(List.of("1", "10", "100", "1000"), args[3]);
            }
            if (subcommand.equals("zinsen")) {
                return filter(List.of("1.3"), args[3]);
            }
        }

        return List.of();
    }

    private static List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
        return names;
    }

    private List<String> pageSuggestions() {
        List<String> pages = new ArrayList<>();
        for (int page = 1; page <= helpBook.pageCount(); page++) {
            pages.add(String.valueOf(page));
        }
        return pages;
    }
}
