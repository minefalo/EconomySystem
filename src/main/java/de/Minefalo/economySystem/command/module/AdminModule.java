package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandModule;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.command.CommandTree;
import de.Minefalo.economySystem.config.InterestType;
import de.Minefalo.economySystem.credit.CreditService;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.help.HelpBook;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /eco admin} - Gruppe aller Adminpfade.
 *
 * <p>Die Fachlogik steckt in den Untermodulen. Was hier bleibt, ist die
 * Übersetzung der bisherigen Schreibweisen von
 * {@code /economysystem-admin} ({@code money}, {@code zinsen},
 * {@code info}, {@code help}), die eine andere Argumentreihenfolge haben und
 * sich deshalb nicht über {@code subcommand-words} abbilden lassen
 * (Abschnitt 12.1).</p>
 */
public final class AdminModule extends AbstractModule {

    private final HelpBook helpBook;
    private final InfoModule info;
    private final AdminBalanceAddModule add;
    private final AdminBalanceRemoveModule remove;
    private final AdminBalanceSetModule set;
    private final AdminBalanceResetModule reset;
    private final AdminInterestSetModule interestSet;
    private final List<CommandModule> children;

    public AdminModule(EconomySystem plugin, EconomyService economy,
                       CreditService credit, HelpBook helpBook) {
        super(plugin, economy);
        this.helpBook = helpBook;
        this.info = new InfoModule(plugin, economy);
        this.add = new AdminBalanceAddModule(plugin, economy);
        this.remove = new AdminBalanceRemoveModule(plugin, economy);
        this.set = new AdminBalanceSetModule(plugin, economy);
        this.reset = new AdminBalanceResetModule(plugin, economy);
        this.interestSet = new AdminInterestSetModule(plugin, economy);
        this.children = List.of(
                new GroupModule(plugin, economy, "admin.balance",
                        "/eco admin balance <add|remove|set|reset>",
                        List.of(add, remove, set, reset)),
                new GroupModule(plugin, economy, "admin.credit",
                        "/eco admin credit <list>",
                        List.of(new AdminCreditListModule(plugin, economy, credit))),
                new GroupModule(plugin, economy, "admin.interest",
                        "/eco admin interest <show|set <savings|credit> <faktor>>",
                        List.of(new AdminInterestShowModule(plugin, economy), interestSet)),
                // AdminTestModule ist der Gruppenknoten admin.test selbst und
                // meldet interest-cycle in register() an - keine GroupModule-Hülle,
                // sonst ist der Pfad doppelt belegt.
                new AdminTestModule(plugin, economy),
                new AdminReloadModule(plugin, economy));
    }

    @Override
    public String id() {
        return "admin";
    }

    @Override
    public String path() {
        return "admin";
    }

    @Override
    public String usage() {
        return "/eco admin <help|info|reload|balance|credit|interest|test>";
    }

    @Override
    public void register(CommandTree tree) {
        children.forEach(tree::add);
    }

    @Override
    public CommandResult execute(CommandContext context) {
        CommandSender sender = context.sender();
        List<String> args = context.args().positional();

        if (args.isEmpty()) {
            helpBook.show(sender, commandBase(context), 1);
            return CommandResult.handled();
        }

        String word = args.get(0).toLowerCase(Locale.ROOT);
        return switch (word) {
            case "help", "?" -> showHelp(sender, context, args);
            case "info", "status" -> info.execute(context);
            case "money", "geld" -> legacyMoney(context);
            case "zinsen", "interest" -> legacyInterest(sender, args);
            default -> {
                usageError(sender, args.get(0));
                yield CommandResult.handled();
            }
        };
    }

    private CommandResult showHelp(CommandSender sender, CommandContext context, List<String> args) {
        if (args.size() > 2) {
            usageError(sender, String.join(" ", args));
            return CommandResult.handled();
        }
        int page = 1;
        if (args.size() == 2) {
            try {
                page = Integer.parseInt(args.get(1));
            } catch (NumberFormatException exception) {
                messages().send(sender, "invalid-page", "input", args.get(1));
                return CommandResult.handled();
            }
        }
        helpBook.show(sender, commandBase(context), page);
        return CommandResult.handled();
    }

    /**
     * {@code /economysystem-admin money <konto> <aktion> [<betrag>]} mit der
     * bisherigen Reihenfolge (Konto vor Aktion).
     *
     * <p>Die Altform übersetzt nur in den kanonischen Pfad
     * ({@code /eco admin balance <aktion> <konto> [<betrag>]}) und führt ihn
     * über den Router aus; sie ruft {@code apply()} nicht mehr selbst auf.
     * Damit gelten Bestätigungspflicht, Prüfreihenfolge und Flags der Altform
     * genau wie für den kanonischen Pfad - {@code --confirm} bleibt auch hier
     * Pflicht bei {@code set} und {@code reset}.</p>
     */
    private CommandResult legacyMoney(CommandContext context) {
        CommandSender sender = context.sender();
        List<String> args = context.args().positional();
        if (args.size() < 3) {
            return adminMoneyUsage(sender);
        }

        String action = legacyMoneyAction(args.get(2));
        if (action.isEmpty()) {
            return adminMoneyUsage(sender);
        }
        if (!action.equals("reset") && args.size() < 4) {
            return adminMoneyUsage(sender);
        }

        List<String> tokens = new ArrayList<>(List.of("admin", "balance", action, args.get(1)));
        if (!action.equals("reset")) {
            tokens.add(args.get(3));
        }
        tokens.addAll(flagTokens(context.args().raw()));
        plugin.commandRouter().dispatch(sender, "eco", tokens, true);
        return CommandResult.handled();
    }

    /**
     * Die Flags eines Aufrufs in ihrer ursprünglichen Schreibweise, damit die
     * Weiterleitung an den kanonischen Pfad {@code --confirm} und
     * {@code --reason} nicht verliert. Der Parser lässt Flags nur am Ende zu.
     */
    private static List<String> flagTokens(List<String> raw) {
        List<String> flags = new ArrayList<>();
        for (int index = 0; index < raw.size(); index++) {
            String token = raw.get(index);
            if (!token.startsWith("--")) {
                continue;
            }
            flags.add(token);
            if (index + 1 < raw.size() && !raw.get(index + 1).startsWith("--")) {
                flags.add(raw.get(++index));
            }
        }
        return flags;
    }

    /**
     * Kanonische Aktion einer bisherigen Schreibweise von
     * {@code /economysystem-admin money <konto> <aktion>}; leer, wenn das Wort
     * unbekannt ist. Die Rückgabe ist das Pfadsegment unter
     * {@code admin.balance} - so bleibt die Übersetzung prüfbar (Schritt 4).
     */
    public static String legacyMoneyAction(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "add", "give" -> "add";
            case "remove", "take" -> "remove";
            case "set" -> "set";
            case "reset" -> "reset";
            default -> "";
        };
    }

    private CommandResult adminMoneyUsage(CommandSender sender) {
        messages().send(sender, "usage-admin-money");
        return CommandResult.handled();
    }

    /**
     * {@code /economysystem-admin zinsen <typ> set <faktor>} mit der bisherigen
     * Reihenfolge (Typ vor Aktion).
     */
    private CommandResult legacyInterest(CommandSender sender, List<String> args) {
        if (args.size() != 4 || !args.get(2).equalsIgnoreCase("set")) {
            messages().send(sender, "usage-admin-zinsen");
            return CommandResult.handled();
        }

        InterestType type = AdminInterestSetModule.parseType(args.get(1));
        if (type == null) {
            messages().send(sender, "usage-admin-zinsen");
            return CommandResult.handled();
        }
        return interestSet.apply(sender, type, args.get(3));
    }


    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.isEmpty()) {
            return List.of();
        }

        if (raw.size() == 1) {
            List<String> options = new ArrayList<>(
                    List.of("help", "info", "reload", "balance", "credit", "interest"));
            if (plugin.settings().testsEnabled()) {
                options.add("test");
            }
            return filter(options, raw.get(0));
        }
        String word = raw.get(0).toLowerCase(Locale.ROOT);
        if (raw.size() == 2) {
            return switch (word) {
                case "money", "geld" -> filter(onlinePlayerNames(), raw.get(1));
                case "zinsen", "interest" -> filter(List.of("spar", "kredit"), raw.get(1));
                case "help" -> filter(pageSuggestions(Math.max(1, helpBook.pageCount())), raw.get(1));
                default -> List.of();
            };
        }
        if (raw.size() == 3) {
            return switch (word) {
                case "money", "geld" -> filter(List.of("add", "remove", "set", "reset"), raw.get(2));
                case "zinsen", "interest" -> filter(List.of("set"), raw.get(2));
                default -> List.of();
            };
        }
        if (raw.size() == 4) {
            return switch (word) {
                case "money", "geld" -> filter(List.of("1", "10", "100", "1000"), raw.get(3));
                case "zinsen", "interest" -> filter(List.of("1.3"), raw.get(3));
                default -> List.of();
            };
        }
        return List.of();
    }
}
