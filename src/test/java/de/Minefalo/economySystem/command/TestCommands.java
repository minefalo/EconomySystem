package de.Minefalo.economySystem.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Gemeinsamer Unterbau der Befehlstests: der Befehlsbaum aus Abschnitt 5,
 * gemockte Aufrufer und die ausgelieferte {@code commands.yml} samt der
 * Prüfungen, die der Router beim Laden ausführt.
 *
 * <p>Die Module sind Platzhalter, weil die Rechte- und Pfadmatrix nur Pfade,
 * {@code playerOnly} und Synonyme betrifft - keine Fachlogik. Die
 * Pfadliste entspricht Abschnitt 5 der {@code Commands.md}; sie wächst mit,
 * sobald dort ein Pfad dazukommt.</p>
 */
final class TestCommands {

    /** Pfade aus Abschnitt 5. */
    static final List<String> PATHS = List.of(
            "balance", "balance.top",
            "pay",
            "credit", "credit.take", "credit.repay", "credit.info", "credit.list",
            "gui", "info", "help",
            "admin", "admin.reload",
            "admin.balance", "admin.balance.add", "admin.balance.remove",
            "admin.balance.set", "admin.balance.reset",
            "admin.credit", "admin.credit.list",
            "admin.interest", "admin.interest.show", "admin.interest.set",
            "admin.test", "admin.test.interest-cycle");

    /** Spielerpfade, wie sie die Module deklarieren (Abschnitt 5.2). */
    private static final Set<String> PLAYER_ONLY = Set.of(
            "pay", "credit", "credit.take", "credit.repay", "credit.info", "credit.list", "gui");

    static final CommandTree TREE = new CommandTree(PATHS.stream().map(TestCommands::module).toList());

    /** Kurzbefehle aus der ausgelieferten {@code commands.yml}, geprüft. */
    static final Map<String, List<String>> SHORTCUTS = shortcutsChecked();

    /** Aliase je Befehl aus der {@code commands.yml}. */
    static final Map<String, List<String>> ALIASES = aliasesFromYaml();

    /** Alias → Pfad seines Befehls, wie {@code registerAliases} es aufbaut. */
    static final Map<String, List<String>> ALIAS_PATHS =
            CommandRegistry.resolveAliases(ALIASES, SHORTCUTS);

    /** Parser mit den Synonymen der ausgelieferten {@code commands.yml}. */
    static final CommandParser PARSER = parserChecked();

    private TestCommands() {
    }

    // ------------------------------------------------------------------
    // Befehlsbaum
    // ------------------------------------------------------------------

    static CommandTree.Node node(String path) {
        CommandTree.Node node = TREE.root();
        for (String segment : path.split("\\.")) {
            node = node.child(segment);
            assertNotNull(node, "Pfad fehlt im Testbaum: " + path);
        }
        return node;
    }

    private static CommandModule module(String path) {
        return new CommandModule() {
            @Override
            public String id() {
                return path.substring(path.lastIndexOf('.') + 1);
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public boolean playerOnly() {
                return PLAYER_ONLY.contains(path);
            }

            @Override
            public CommandResult execute(CommandContext context) {
                return CommandResult.handled();
            }
        };
    }

    // ------------------------------------------------------------------
    // Aufrufer
    // ------------------------------------------------------------------

    static CommandSender sender(String... permissions) {
        return (CommandSender) Proxy.newProxyInstance(
                TestCommands.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                handler(permissions));
    }

    static Player player(String... permissions) {
        return (Player) Proxy.newProxyInstance(
                TestCommands.class.getClassLoader(),
                new Class<?>[]{Player.class},
                handler(permissions));
    }

    private static InvocationHandler handler(String... permissions) {
        Set<String> granted = Set.of(permissions);
        return (proxy, method, arguments) -> switch (method.getName()) {
            case "hasPermission", "isPermissionSet" -> granted.contains((String) arguments[0]);
            case "getName" -> "Pruefer";
            case "hashCode" -> 1;
            case "equals" -> proxy == arguments[0];
            case "toString" -> "Pruefer";
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // Routing: Kurzbefehl/Alias/Pfad → Modul
    // ------------------------------------------------------------------

    /** Löst Tokens gegen den Baum auf; Tokens beginnen mit dem Pfad. */
    static CommandParser.PathResult route(String... tokens) {
        return PARSER.parsePath(List.of(tokens));
    }

    static CommandParser.PathResult route(List<String> tokens) {
        return PARSER.parsePath(tokens);
    }

    /**
     * Pfadtokens eines Kurzbefehls oder Alias; genau das stellt der Router
     * einem Aufruf voran ({@code tokens} in {@link CommandRegistry}).
     */
    static List<String> startPath(String command) {
        String name = command.toLowerCase(java.util.Locale.ROOT);
        List<String> path = SHORTCUTS.get(name);
        if (path == null) {
            path = ALIAS_PATHS.get(name);
        }
        assertNotNull(path, "commands.yml kennt keinen Befehl und keinen Alias '" + command + "'");
        return path;
    }

    /** Pfadtokens, gefolgt von den getippten Argumenten. */
    static List<String> tokens(String command, String... args) {
        List<String> tokens = new ArrayList<>(startPath(command));
        tokens.addAll(List.of(args));
        return tokens;
    }

    // ------------------------------------------------------------------
    // commands.yml
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> yaml() {
        try (InputStream input = TestCommands.class.getClassLoader()
                .getResourceAsStream("config/commands.yml")) {
            assertNotNull(input, "config/commands.yml fehlt im Klassenpfad");
            return new Yaml().load(input);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Prüft die Kurzbefehle mit dem echten Validator des Routers - so fällt
     * auch ein kaputter Pfad oder eine Warnung beim Laden auf.
     */
    private static Map<String, List<String>> shortcutsChecked() {
        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) yaml().get("shortcuts");
        assertNotNull(section, "commands.yml hat keinen Abschnitt shortcuts");

        Map<String, String> raw = new LinkedHashMap<>();
        section.forEach((key, value) -> {
            if (value instanceof Map<?, ?> entry) {
                raw.put(key, String.valueOf(entry.get("path")));
            }
        });

        List<String> warnings = new ArrayList<>();
        Map<String, List<String>> resolved =
                CommandRegistry.resolveShortcuts(raw, TREE, warnings::add);
        assertEquals(List.of(), warnings,
                "commands.yml darf beim Laden keine Warnung erzeugen");
        return resolved;
    }

    private static Map<String, List<String>> aliasesFromYaml() {
        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) yaml().get("commands");
        assertNotNull(section, "commands.yml hat keinen Abschnitt commands");

        Map<String, List<String>> aliases = new LinkedHashMap<>();
        section.forEach((command, value) -> {
            if (value instanceof Map<?, ?> entry && entry.get("aliases") instanceof List<?> list) {
                aliases.put(command, list.stream().map(String::valueOf).toList());
            }
        });
        return aliases;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> subcommandWordsFromYaml() {
        Map<String, Object> section = (Map<String, Object>) yaml().get("subcommand-words");
        assertNotNull(section, "commands.yml hat keinen Abschnitt subcommand-words");

        Map<String, List<String>> words = new LinkedHashMap<>();
        section.forEach((word, value) -> {
            List<String> synonyms = value instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            words.put(word, synonyms);
        });
        return words;
    }

    /** Synonyme mit dem echten Validator des Routers; Warnungen sind ein Fehler. */
    private static CommandParser parserChecked() {
        List<String> warnings = new ArrayList<>();
        Map<String, String> synonyms = CommandRegistry.resolveSubcommandWords(
                subcommandWordsFromYaml(), TREE, warnings::add);
        assertEquals(List.of(), warnings,
                "subcommand-words dürfen beim Laden keine Warnung erzeugen");
        return new CommandParser(TREE, synonyms);
    }
}
