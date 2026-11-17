package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.InterestType;
import de.Minefalo.economySystem.config.Messages;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.currency.Currency;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.economy.InterestResult;
import de.Minefalo.economySystem.help.HelpBook;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * /economysystem-admin - Admin-Befehle für Geldverwaltung, Zinsen,
 * Informationen, Reload und Tests.
 */
public final class EconomySystemAdminCommand extends AbstractEconomyCommand {

    private static final String PERMISSION_ADMIN = "economysystem.admin";
    private static final String PERMISSION_RELOAD = "economysystem.reload";
    private static final String PERMISSION_TESTS = "economysystem.tests";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_TEST_CYCLES = 1000;

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
            case "info", "status" -> info(sender);
            case "reload" -> reload(sender);
            case "money", "geld" -> money(sender, args);
            case "zinsen", "interest" -> interest(sender, args);
            case "tests", "test" -> tests(sender, args);
            default -> messages().send(sender, "usage-admin");
        }
        return true;
    }

    private void info(CommandSender sender) {
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
        if (!sender.hasPermission(PERMISSION_RELOAD)) {
            noPermission(sender);
            return;
        }

        plugin.reload();
        messages().send(sender, "reload-success");
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

    /**
     * {@code /economysystem-admin tests zins-zyklus add <Anzahl>} - führt den
     * Zinszyklus sofort {@code <Anzahl>}-mal aus und simuliert damit
     * vergangene Zyklen. Nur aktiv, wenn {@code tests.enabled} in der
     * settings.yml auf {@code true} steht.
     */
    private void tests(CommandSender sender, String[] args) {
        if (!plugin.settings().testsEnabled()) {
            messages().send(sender, "tests-disabled");
            return;
        }
        if (!sender.hasPermission(PERMISSION_TESTS)) {
            noPermission(sender);
            return;
        }
        if (args.length != 4
                || !matchesIgnoreCase(args[1], "zins-zyklus", "zinszyklus", "interest-cycle")
                || !args[2].equalsIgnoreCase("add")) {
            messages().send(sender, "usage-admin-tests");
            return;
        }

        int cycles = parseCycleCount(sender, args[3]);
        if (cycles <= 0) {
            return;
        }

        simulateCycles(sender, cycles);
    }

    private int parseCycleCount(CommandSender sender, String input) {
        try {
            int cycles = Integer.parseInt(input.trim());
            if (cycles >= 1 && cycles <= MAX_TEST_CYCLES) {
                return cycles;
            }
        } catch (NumberFormatException ignored) {
            // Fällt unten auf die Fehlermeldung zurück.
        }
        messages().send(sender, "tests-cycles-invalid", "max", String.valueOf(MAX_TEST_CYCLES));
        return 0;
    }

    private void simulateCycles(CommandSender sender, int cycles) {
        BigDecimal factor = plugin.settings().interest().savingsFactor();
        EconomyService economy = this.economy;

        messages().send(sender, "tests-started",
                "cycles", String.valueOf(cycles),
                "factor", factor.stripTrailingZeros().toPlainString());
        plugin.getLogger().info("Test: " + cycles + " Zinszyklen werden sofort simuliert (Sparzins-Faktor "
                + factor.stripTrailingZeros().toPlainString() + ").");

        BigDecimal[] totalCredited = {BigDecimal.ZERO};
        int[] accountsCredited = {0};
        int[] accountsChecked = {0};

        // Die Zyklen werden nacheinander ausgeführt, damit jeder Lauf auf dem
        // Guthaben des vorherigen aufbaut (wie bei echten Zyklen).
        CompletableFuture<InterestResult> chain = CompletableFuture.completedFuture(null);
        for (int cycle = 0; cycle < cycles; cycle++) {
            chain = chain.thenCompose(previous -> economy.runInterestCycle(factor)
                    .thenApply(result -> {
                        totalCredited[0] = totalCredited[0].add(result.totalCredited());
                        accountsCredited[0] += result.accountsCredited();
                        accountsChecked[0] = result.accountsChecked();
                        return result;
                    }));
        }

        chain.whenComplete((result, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            String errorMessage = error == null ? null : messageOf(error);
            if (errorMessage != null) {
                plugin.getLogger().severe("Test-Zinszyklen fehlgeschlagen: " + errorMessage);
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (errorMessage != null) {
                    messages().send(sender, "tests-failed", "error", errorMessage);
                    return;
                }
                plugin.refreshPlaceholders();
                messages().send(sender, "tests-result",
                        "cycles", String.valueOf(cycles),
                        "credited", String.valueOf(accountsCredited[0]),
                        "checked", String.valueOf(accountsChecked[0]),
                        "total", currency().format(totalCredited[0]));
            });
        });
    }

    private static boolean matchesIgnoreCase(String input, String... options) {
        for (String option : options) {
            if (input.equalsIgnoreCase(option)) {
                return true;
            }
        }
        return false;
    }

    private static String messageOf(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("help", "info", "reload", "money", "zinsen"));
            if (plugin.settings().testsEnabled() && sender.hasPermission(PERMISSION_TESTS)) {
                options.add("tests");
            }
            return filter(options, args[0]);
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (subcommand) {
                case "money" -> filter(onlinePlayerNames(), args[1]);
                case "zinsen" -> filter(List.of("spar", "kredit"), args[1]);
                case "tests", "test" -> filter(List.of("zins-zyklus"), args[1]);
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
            if (subcommand.equals("tests") || subcommand.equals("test")) {
                return filter(List.of("add"), args[2]);
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
            if (subcommand.equals("tests") || subcommand.equals("test")) {
                return filter(List.of("1", "7", "30"), args[3]);
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
