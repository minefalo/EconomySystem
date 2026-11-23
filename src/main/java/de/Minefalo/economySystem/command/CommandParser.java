package de.Minefalo.economySystem.command;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser für Pfad, Argumente und Flags nach {@code Commands.md}, Abschnitt 6.
 *
 * <p>Der Parser prüft nur Syntax und Rechte der Flags. Die Prüfreihenfolge
 * (Abschnitt 6.7) steuert der Router: erst Pfad, Herkunft und Permission,
 * dann Syntax und Flags - so erfährt niemand ohne Recht die Syntax.</p>
 */
public final class CommandParser {

    /** Maximale Länge einer Begründung (Abschnitt 6.4). */
    private static final int MAX_REASON_LENGTH = 160;

    private static final String PERMISSION_AUDIT = CommandPermissions.PREFIX + "admin.audit";
    private static final String PERMISSION_NETWORK = CommandPermissions.PREFIX + "admin.network";

    private final CommandTree tree;
    private final Map<String, String> subcommandWords;

    /**
     * @param tree            Befehlsbaum, gegen den Pfade aufgelöst werden
     * @param subcommandWords Synonym → kanonisches Wort aus {@code commands.yml}
     */
    public CommandParser(CommandTree tree, Map<String, String> subcommandWords) {
        this.tree = tree;
        this.subcommandWords = subcommandWords == null ? Map.of() : Map.copyOf(subcommandWords);
    }

    // ------------------------------------------------------------------
    // Stufe 1: Pfad
    // ------------------------------------------------------------------

    /**
     * Löst den Pfad gegen den Baum auf. Die zurückgegebenen Tokens sind die
     * Argumente hinter dem Pfad.
     *
     * <p>Synonyme aus {@code commands.yml → subcommand-words} werden an jeder
     * Pfadposition ersetzt; Argumente bleiben unverändert, weil nur vorhandene
     * Pfadsegmente ersetzt werden.</p>
     */
    public PathResult parsePath(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return PathResult.failure(Failure.usage(""));
        }

        Walk walk = walk(tokens);
        if (walk.module() == null) {
            String input = tokens.get(0);
            return PathResult.failure(Failure.unknownSubcommand(input, tree.suggest(input)));
        }
        return PathResult.of(walk.module(), tokens.subList(walk.moduleDepth(), tokens.size()));
    }

    /**
     * Läuft so weit durch den Baum, wie die Tokens als Pfad (auch als Synonym)
     * erkannt werden. Liefert den tiefsten Knoten und das tiefste Modul; die
     * Tab-Vervollständigung braucht beides.
     */
    public Walk walk(List<String> tokens) {
        CommandTree.Node node = tree.root();
        CommandTree.Node module = null;
        int depth = 0;
        int moduleDepth = 0;

        for (String token : tokens) {
            String canonical = canonicalWord(node, token);
            if (canonical == null) {
                break;
            }
            node = node.child(canonical);
            depth++;
            if (node.hasModule()) {
                module = node;
                moduleDepth = depth;
            }
        }
        return new Walk(node, depth, module, moduleDepth);
    }

    /**
     * Kanonische Schreibweise eines Tokens an dieser Pfadposition; {@code null},
     * wenn das Token weder Segment noch Synonym eines Segments ist.
     */
    private String canonicalWord(CommandTree.Node node, String token) {
        String plain = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        if (node.child(plain) != null) {
            return plain;
        }
        String canonical = subcommandWords.get(plain);
        return canonical != null && node.child(canonical) != null ? canonical : null;
    }

    // ------------------------------------------------------------------
    // Stufe 5: Argumente und Flags
    // ------------------------------------------------------------------

    /**
     * Teilt die Argumente in Stellungsparameter und Flags. Unbekannte Flags,
     * fehlende Flag-Werte und Stellungsparameter nach einem Flag sind ein
     * Syntaxfehler (Abschnitt 6.1/6.4).
     */
    public ArgumentsResult parseArguments(List<String> tokens) {
        List<String> positional = new ArrayList<>();
        Map<String, String> flags = new LinkedHashMap<>();

        int index = 0;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            if (token.startsWith("--")) {
                String name = token.substring(2).trim().toLowerCase(Locale.ROOT);
                Flag flag = FLAGS.get(name);
                if (flag == null) {
                    return ArgumentsResult.failure(Failure.usage(token));
                }
                if (flag.takesValue()) {
                    if (index + 1 >= tokens.size()) {
                        return ArgumentsResult.failure(Failure.usage(token));
                    }
                    flags.put(name, sanitize(flag, tokens.get(index + 1)));
                    index += 2;
                } else {
                    flags.put(name, "");
                    index++;
                }
                // Flags stehen immer am Ende.
                if (index < tokens.size() && !tokens.get(index).startsWith("--")) {
                    return ArgumentsResult.failure(Failure.usage(tokens.get(index)));
                }
                continue;
            }
            positional.add(token);
            index++;
        }

        return ArgumentsResult.of(Arguments.of(tokens, positional, flags));
    }

    /**
     * Prüft die Rechte der verwendeten Flags sowie die Regel, dass Aufrufe
     * aus der GUI {@code --confirm} nicht verwenden dürfen. Die GUI setzt ihre
     * Herkunft über {@code CommandRegistry.dispatchGui}; ein Klick bestätigt
     * damit nie einen Befehl, der seinerseits {@code --confirm} verlangt.
     *
     * @return {@code null}, wenn die Flags zulässig sind
     */
    public Failure validateFlags(CommandSender sender, CommandContext.Origin origin,
                                 CommandTree.Node node, Arguments args) {
        for (String name : args.flags().keySet()) {
            Flag flag = FLAGS.get(name);
            if (flag == null) {
                return Failure.usage("--" + name);
            }
            if (flag.adminOnly() && !isAdminPath(node.path())) {
                return Failure.usage("--" + name);
            }
            if (flag.permission() != null && !grantedFlag(sender, flag)) {
                return Failure.noPermission();
            }
        }
        if (origin == CommandContext.Origin.GUI && args.hasFlag("confirm")) {
            // Ein Klick darf die Bestätigung nicht ersetzen (Abschnitt 6.4).
            return Failure.usage("--confirm");
        }
        return null;
    }

    private static boolean grantedFlag(CommandSender sender, Flag flag) {
        return sender.hasPermission(flag.permission())
                || sender.hasPermission(CommandPermissions.ADMIN)
                || sender.hasPermission(CommandPermissions.LEGACY_ADMIN);
    }

    private static boolean isAdminPath(String path) {
        return path.equals("admin") || path.startsWith("admin.");
    }

    private static String sanitize(Flag flag, String value) {
        if (!flag.name().equals("reason")) {
            return value;
        }
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isISOControl(character)) {
                cleaned.append(character);
            }
        }
        String result = cleaned.toString();
        return result.length() > MAX_REASON_LENGTH ? result.substring(0, MAX_REASON_LENGTH) : result;
    }

    // ------------------------------------------------------------------
    // Flag-Katalog (Abschnitt 6.4)
    // ------------------------------------------------------------------

    /**
     * @param name       Name ohne führende {@code --}
     * @param takesValue {@code true}, wenn ein Wert folgt
     * @param permission Knoten, der das Flag erlaubt; {@code null} = jeder
     * @param adminOnly  {@code true}, wenn das Flag nur auf Admin-Pfaden gilt
     */
    public record Flag(String name, boolean takesValue, String permission, boolean adminOnly) {
    }

    private static final Map<String, Flag> FLAGS = flags();

    private static Map<String, Flag> flags() {
        Map<String, Flag> flags = new LinkedHashMap<>();
        register(flags, new Flag("reason", true, null, false));
        register(flags, new Flag("silent", false, null, true));
        register(flags, new Flag("dry-run", false, null, true));
        register(flags, new Flag("confirm", false, null, false));
        register(flags, new Flag("player", true, PERMISSION_AUDIT, false));
        register(flags, new Flag("action", true, PERMISSION_AUDIT, false));
        register(flags, new Flag("server", true, PERMISSION_NETWORK, false));
        register(flags, new Flag("scope", true, PERMISSION_NETWORK, false));
        return Map.copyOf(flags);
    }

    private static void register(Map<String, Flag> flags, Flag flag) {
        flags.put(flag.name(), flag);
    }

    // ------------------------------------------------------------------
    // Ergebnisse
    // ------------------------------------------------------------------

    /**
     * Ein Fehler mit Code und Platzhaltern. Codes: {@code usage},
     * {@code unknown-subcommand}, {@code no-permission}.
     */
    public record Failure(String code, Map<String, String> values) {

        public Failure {
            values = values == null ? Map.of() : Map.copyOf(values);
        }

        public static Failure usage(String input) {
            return new Failure("usage", Map.of("input", input == null ? "" : input));
        }

        public static Failure unknownSubcommand(String input, String suggestion) {
            return new Failure("unknown-subcommand", Map.of(
                    "input", input == null ? "" : input,
                    "suggestion", suggestion == null ? "" : suggestion));
        }

        public static Failure noPermission() {
            return new Failure("no-permission", Map.of());
        }

        public String value(String name) {
            return values.getOrDefault(name, "");
        }
    }

    /**
     * Ergebnis eines Pfad-Walks: tiefster Knoten, Anzahl erkannter Segmente,
     * tiefstes Modul und dessen Tiefe.
     */
    public record Walk(CommandTree.Node node, int depth,
                       CommandTree.Node module, int moduleDepth) {
    }

    public record PathResult(CommandTree.Node node, List<String> rest, Failure failure) {

        public static PathResult of(CommandTree.Node node, List<String> rest) {
            return new PathResult(node, List.copyOf(rest), null);
        }

        public static PathResult failure(Failure failure) {
            return new PathResult(null, List.of(), failure);
        }

        public boolean failed() {
            return failure != null;
        }
    }

    public record ArgumentsResult(Arguments args, Failure failure) {

        public static ArgumentsResult of(Arguments args) {
            return new ArgumentsResult(args, null);
        }

        public static ArgumentsResult failure(Failure failure) {
            return new ArgumentsResult(Arguments.empty(), failure);
        }

        public boolean failed() {
            return failure != null;
        }
    }
}
