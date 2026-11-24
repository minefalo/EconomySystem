package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.CommandsConfig;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.storage.StorageException;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Registriert die Kurzbefehle aus der {@code plugin.yml} samt ihrer Aliase und
 * ist gleichzeitig der Router für den Wurzelbefehl {@code /eco}.
 *
 * <p>Der Router löst {@code /eco <pfad…>} gegen den {@link CommandTree} auf und
 * prüft die Reihenfolge aus {@code Commands.md}, Abschnitt 6.7, an genau einer
 * Stelle: Pfad, Herkunft, Permission, Syntax/Flags, Bestätigung, Rate-Limit,
 * Ausführung. Die Module selbst kennen keine Prüfreihenfolge und senden keine
 * Nachrichten über den Router hinaus.</p>
 */
public final class CommandRegistry implements CommandExecutor, TabCompleter {

    /** Wurzelbefehl; bewusst nicht in der {@code plugin.yml}, siehe Regel R4. */
    private static final String ROOT_COMMAND = "eco";

    /** "/eco" ohne Pfad zeigt dieselbe Hilfe wie "/economysystem". */
    private static final String HELP_PATH = "help";

    /** Capability-Knoten: ignoriert Cooldown und Mindestabstand (Abschnitt 7.3). */
    private static final String BYPASS_COOLDOWN = "economysystem.bypass.cooldown";

    /** Capability-Knoten: ignoriert die Mengenbegrenzung (Abschnitt 7.3). */
    private static final String BYPASS_RATE_LIMIT = "economysystem.bypass.rate-limit";

    /** Wortmuster für Aliase und Synonyme (Abschnitt 8.2). */
    private static final String WORD_PATTERN = "[a-z0-9_-]{1,32}";

    /**
     * Kanonische Wörter aus {@code Commands.md}: Aktionen (6.2), Gruppen und
     * Unterpfade (5) sowie reservierte Pfade (13). Wörter außerhalb dieser
     * Liste sind ein Fehler in der {@code commands.yml}.
     */
    private static final Set<String> KNOWN_WORDS = Set.of(
            "add", "remove", "set", "reset", "list", "info", "show", "top",
            "run", "reload", "balance", "pay", "credit", "gui", "help", "admin",
            "take", "repay", "status", "savings", "interest", "network", "scopes",
            "audit", "test", "forgive", "bank", "shop", "tax", "auction", "market",
            "stats", "link");

    private final EconomySystem plugin;
    private final CommandsConfig commandsConfig;
    private final CommandTree tree;
    private final Map<String, Registration> registrations = new LinkedHashMap<>();
    private final List<Command> dynamicCommands = new ArrayList<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    /**
     * Zeitstempel der angenommenen Aufrufe je Spieler und Pfadgruppe; Grundlage
     * der Mengenbegrenzung aus {@code network.limits} (Abschnitt 11.7).
     */
    private final Map<String, Deque<Long>> rateWindows = new HashMap<>();
    private CommandParser parser;
    private Map<String, List<String>> shortcutPaths = Map.of();
    /** Alias → Pfad des Befehls, für den er registriert wurde. */
    private final Map<String, List<String>> aliasPaths = new HashMap<>();

    public CommandRegistry(EconomySystem plugin, CommandsConfig commandsConfig,
                           Collection<CommandModule> modules) {
        this.plugin = plugin;
        this.commandsConfig = commandsConfig;
        this.tree = new CommandTree(modules);
        loadCommandConfig();
    }

    /**
     * Liest und prüft {@code commands.yml}: Synonyme, Kurzbefehl-Pfade und
     * Vorschläge (Abschnitt 8). Nur der Router liest diese Datei.
     */
    private void loadCommandConfig() {
        Consumer<String> warning = message -> plugin.getLogger().warning("commands.yml: " + message);

        Map<String, List<String>> shortcuts = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry
                : resolveShortcuts(commandsConfig.shortcuts(), tree, warning).entrySet()) {
            String name = entry.getKey();
            if (!name.equals(ROOT_COMMAND) && plugin.getCommand(name) == null) {
                warning.accept("shortcuts." + name
                        + " gehört zu keinem Befehl aus der plugin.yml und bleibt wirkungslos.");
                continue;
            }
            shortcuts.put(name, entry.getValue());
        }
        this.shortcutPaths = shortcuts;
        this.parser = new CommandParser(tree,
                resolveSubcommandWords(commandsConfig.subcommandWords(), tree, warning),
                plugin.settings().reasonMaxLength());
    }

    /**
     * Prüft die Pfade der Kurzbefehle. Ein unbekannter Pfad wird gemeldet und
     * bleibt wirkungslos (Abschnitt 15.4).
     */
    static Map<String, List<String>> resolveShortcuts(Map<String, String> configured,
                                                      CommandTree tree,
                                                      Consumer<String> warning) {
        Map<String, List<String>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            List<String> segments = segments(entry.getValue());
            if (segments.isEmpty() || node(tree, segments) == null) {
                warning.accept("shortcuts." + entry.getKey() + " zeigt auf den unbekannten Pfad '"
                        + entry.getValue() + "' und bleibt wirkungslos.");
                continue;
            }
            resolved.put(normalizeWord(entry.getKey()), segments);
        }
        return resolved;
    }

    /**
     * Prüft die Synonyme aus {@code subcommand-words}: unbekannte kanonische
     * Wörter, ungültige Schreibweisen und Doppeldeutigkeiten werden gemeldet.
     * Bei Doppeldeutigkeit gewinnt der kanonische Name (Abschnitt 8.2).
     */
    static Map<String, String> resolveSubcommandWords(Map<String, List<String>> configured,
                                                      CommandTree tree,
                                                      Consumer<String> warning) {
        // Ein Synonym, das anderswo ein kanonisches Wort ist, ist kein
        // Konflikt: der Parser prüft an jeder Pfadposition zuerst den echten
        // Kindknoten, das kanonische Wort gewinnt also immer (Abschnitt 8.2).
        Map<String, String> synonyms = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : configured.entrySet()) {
            String canonical = normalizeWord(entry.getKey());
            if (!isKnownWord(canonical, tree)) {
                warning.accept("subcommand-words." + entry.getKey()
                        + " ist kein kanonisches Wort aus Commands.md und bleibt wirkungslos.");
                continue;
            }
            for (String raw : entry.getValue()) {
                String synonym = normalizeWord(raw);
                if (!synonym.matches(WORD_PATTERN)) {
                    warning.accept("subcommand-words." + entry.getKey() + ": '" + raw
                            + "' ist kein gültiges Wort und wird übersprungen.");
                    continue;
                }
                if (synonym.equals(canonical)) {
                    continue;
                }
                String existing = synonyms.putIfAbsent(synonym, canonical);
                if (existing != null) {
                    warning.accept("subcommand-words: '" + synonym + "' bedeutet bereits '" + existing
                            + "'; die Zuordnung zu '" + canonical + "' wird übersprungen.");
                }
            }
        }
        return synonyms;
    }

    private static boolean isKnownWord(String word, CommandTree tree) {
        return KNOWN_WORDS.contains(word) || tree.segments().contains(word);
    }

    private static List<String> segments(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("\\.")) {
            String trimmed = normalizeWord(segment);
            if (!trimmed.isEmpty()) {
                segments.add(trimmed);
            }
        }
        return segments;
    }

    private static CommandTree.Node node(CommandTree tree, List<String> segments) {
        CommandTree.Node node = tree.root();
        for (String segment : segments) {
            node = node.child(segment);
            if (node == null) {
                return null;
            }
        }
        return node != null && node.hasModule() ? node : null;
    }

    private static String normalizeWord(String word) {
        return word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
    }

    public CommandTree tree() {
        return tree;
    }

    /**
     * Setzt Executor und Tab-Completer des in der plugin.yml definierten
     * Befehls.
     *
     * @return {@code false}, wenn der Befehl nicht in der plugin.yml steht
     */
    public boolean register(String name, CommandExecutor executor, TabCompleter tabCompleter) {
        PluginCommand command = plugin.getCommand(name);
        if (command == null) {
            plugin.getLogger().severe("Befehl '" + name + "' ist nicht in der plugin.yml registriert.");
            return false;
        }

        command.setExecutor(executor);
        command.setTabCompleter(tabCompleter);
        registrations.put(name.toLowerCase(Locale.ROOT), new Registration(command, executor, tabCompleter));
        return true;
    }

    /**
     * Registriert alle in der commands.yml konfigurierten Aliase.
     */
    public void registerAliases() {
        aliasPaths.clear();

        Map<String, List<String>> configured = new LinkedHashMap<>();
        for (Registration registration : registrations.values()) {
            String commandName = registration.command().getName();
            configured.put(commandName, commandsConfig.aliases(commandName));
        }
        // Der Wurzelbefehl steht nicht in der plugin.yml; seine Aliase kommen
        // ebenfalls aus der commands.yml.
        configured.put(ROOT_COMMAND, commandsConfig.aliases(ROOT_COMMAND));
        aliasPaths.putAll(resolveAliases(configured, shortcutPaths));

        for (Registration registration : registrations.values()) {
            String commandName = registration.command().getName();
            Predicate<CommandSender> visible = visibleAt(tree,
                    shortcutPaths.getOrDefault(normalizeWord(commandName), List.of()));
            for (String alias : configured.get(commandName)) {
                registerDynamic(alias, registration.command().getDescription(),
                        registration.command().getUsage(),
                        registration.executor(), registration.tabCompleter(), visible);
            }
        }
        for (String alias : configured.get(ROOT_COMMAND)) {
            registerDynamic(alias, "Alias für /" + ROOT_COMMAND,
                    "/" + ROOT_COMMAND + " <pfad…>", this, this, sender -> true);
        }
    }

    /**
     * Sichtbarkeit eines dynamisch registrierten Alias: Er erscheint nur, wenn
     * der Aufrufer die Permission seines Pfades hat. Bukkit nutzt das für die
     * Vorschlagsliste der Befehle und vor dem Ausführen; {@code /ecoadmin}
     * taucht damit ohne Adminrecht gar nicht erst auf.
     */
    static Predicate<CommandSender> visibleAt(CommandTree tree, List<String> segments) {
        CommandTree.Node node = node(tree, segments);
        if (node == null) {
            return sender -> true;
        }
        return sender -> CommandPermissions.granted(sender, node);
    }

    /**
     * Ordnet die Aliase ihrem Pfad zu: ein Alias zeigt auf denselben Pfad wie
     * sein Befehl; sonst würde "/geld" als Wurzelaufruf enden (Abschnitt 4.2).
     * Befehle ohne Kurzbefehl-Pfad bekommen keinen Eintrag.
     */
    static Map<String, List<String>> resolveAliases(Map<String, List<String>> aliasesByCommand,
                                                    Map<String, List<String>> shortcutPaths) {
        Map<String, List<String>> paths = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : aliasesByCommand.entrySet()) {
            List<String> path = shortcutPaths.get(normalizeWord(entry.getKey()));
            if (path == null) {
                continue;
            }
            for (String alias : entry.getValue()) {
                paths.put(normalizeWord(alias), path);
            }
        }
        return paths;
    }

    /**
     * Registriert den Wurzelbefehl {@code /eco}. Er steht nicht in der
     * {@code plugin.yml}; damit bleibt für jeden Unterpfad ein Eintrag dort
     * überflüssig (Regel R4).
     *
     * @return {@code false}, wenn der Name bereits belegt ist
     */
    public boolean registerRoot() {
        if (plugin.getServer().getCommandMap().getCommand(ROOT_COMMAND) != null) {
            plugin.getLogger().warning("Wurzelbefehl '/" + ROOT_COMMAND
                    + "' ist bereits belegt; /" + ROOT_COMMAND + " steht nicht zur Verfügung.");
            return false;
        }
        return registerDynamic(ROOT_COMMAND,
                "Kanonischer Wurzelbefehl aller Economy-Befehle.",
                "/" + ROOT_COMMAND + " <pfad…>", this, this, sender -> true);
    }

    private boolean registerDynamic(String name, String description, String usage,
                                    CommandExecutor executor, TabCompleter tabCompleter,
                                    Predicate<CommandSender> visible) {
        CommandMap commandMap = plugin.getServer().getCommandMap();
        if (commandMap.getCommand(name) != null) {
            plugin.getLogger().warning("Befehl '/" + name
                    + "' ist bereits belegt und wird übersprungen.");
            return false;
        }

        AliasCommand dynamic = new AliasCommand(name, description, usage, executor, tabCompleter,
                visible);
        boolean registered = commandMap.register(plugin.getName().toLowerCase(Locale.ROOT), dynamic);
        if (!registered) {
            plugin.getLogger().warning("Befehl '/" + name + "' konnte nicht registriert werden.");
            removeCommand(dynamic);
            return false;
        }
        dynamicCommands.add(dynamic);
        return true;
    }

    /**
     * Lädt die commands.yml neu und ersetzt die dynamisch registrierten
     * Aliase. Der Befehlsbaum selbst ändert sich dadurch nicht.
     */
    public void reload() {
        unregisterDynamicCommands();
        commandsConfig.reload();
        // Abschnitt 8.2: reload lädt die Datei neu und baut den Router neu auf.
        loadCommandConfig();
        registerAliases();
        registerRoot();
        cooldowns.clear();
    }

    /**
     * Entfernt alle dynamisch registrierten Befehle (Aliase und Wurzelbefehl),
     * z. B. beim Deaktivieren des Plugins.
     */
    public void unregisterDynamicCommands() {
        for (Command command : dynamicCommands) {
            removeCommand(command);
        }
        dynamicCommands.clear();
    }

    private void removeCommand(Command command) {
        CommandMap commandMap = plugin.getServer().getCommandMap();
        command.unregister(commandMap);
        try {
            commandMap.getKnownCommands().entrySet().removeIf(entry -> entry.getValue() == command);
        } catch (UnsupportedOperationException exception) {
            plugin.getLogger().warning("Befehl '/" + command.getName()
                    + "' konnte nicht aus der Command-Map entfernt werden.");
        }
    }

    // ------------------------------------------------------------------
    // Router: Prüfreihenfolge aus Abschnitt 6.7, genau einmal hier
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        // Ein Kurzbefehl ist nur eine andere Schreibweise seines Pfades
        // (Abschnitt 4.2); der Pfad steht in commands.yml → shortcuts.
        return dispatch(sender, label, tokens(command.getName(), args), false);
    }

    /**
     * Pfadtokens eines Kurzbefehls: konfigurierter Pfad, gefolgt von den
     * getippten Argumenten. Ohne Eintrag wird der Aufruf wie der Wurzelbefehl
     * behandelt (z. B. {@code /economysystem help}).
     */
    private List<String> tokens(String commandName, String[] args) {
        List<String> tokens = new ArrayList<>(pathFor(normalizeWord(commandName)));
        if (args != null) {
            tokens.addAll(List.of(args));
        }
        return tokens;
    }

    /**
     * Pfad eines Kurzbefehls oder Alias; leer, wenn der Name unbekannt ist.
     */
    private List<String> pathFor(String commandName) {
        List<String> path = shortcutPaths.get(commandName);
        if (path == null) {
            path = aliasPaths.get(commandName);
        }
        return path == null ? List.of() : path;
    }

    /**
     * Zerlegt eine in der {@code gui.yml} hinterlegte Befehlszeile in Tokens.
     */
    static List<String> lineTokens(String commandLine) {
        String line = commandLine == null ? "" : commandLine.trim();
        while (line.startsWith("/")) {
            line = line.substring(1).trim();
        }
        return line.isEmpty() ? List.of() : List.of(line.split("\\s+"));
    }

    /**
     * Pfadtokens einer Befehlszeile aus der {@code gui.yml}: Kurzbefehl oder
     * Alias wird wie bei einem Chat-Aufruf durch seinen Pfad ersetzt.
     */
    private List<String> linePathTokens(List<String> line) {
        List<String> tokens = new ArrayList<>(pathFor(normalizeWord(line.get(0))));
        tokens.addAll(line.subList(1, line.size()));
        return tokens;
    }

    /**
     * Ob eine Befehlszeile aus der {@code gui.yml} einen
     * bestätigungspflichtigen Pfad auslöst. Die GUI fragt daraufhin vor dem
     * Ausführen nach einer Bestätigung, statt den Klick als Bestätigung zu
     * werten.
     */
    public boolean requiresConfirmation(String commandLine) {
        List<String> line = lineTokens(commandLine);
        if (line.isEmpty()) {
            return false;
        }
        CommandParser.PathResult path = parser.parsePath(linePathTokens(line));
        return !path.failed() && path.node().module().requiresConfirmation();
    }

    /**
     * Führt eine Befehlszeile aus der {@code gui.yml} über den Router aus, mit
     * {@code Origin.GUI}. Der Aufruf durchläuft damit dieselben Prüfstufen wie
     * ein Chat-Befehl; {@code confirmed} meldet ausschließlich eine Bestätigung,
     * die der Spieler zuvor in einem eigenen GUI-Dialog gegeben hat.
     */
    public boolean dispatchGui(CommandSender sender, String commandLine, boolean confirmed) {
        List<String> line = lineTokens(commandLine);
        if (line.isEmpty()) {
            return false;
        }
        return dispatch(sender, line.get(0), linePathTokens(line), false,
                CommandContext.Origin.GUI, confirmed);
    }

    /**
     * Führt einen Aufruf aus; {@code tokens} beginnen mit dem Pfad.
     *
     * <p>Kurzbefehle stellen ihre Pfadsegmente selbst voran und melden mit
     * {@code labelIncludesPath = false}, dass der Pfad nicht getippt wurde -
     * so bleiben klickbare Befehle (Hilfe-Navigation) korrekt.</p>
     */
    public boolean dispatch(CommandSender sender, String label, List<String> tokens,
                            boolean labelIncludesPath) {
        return dispatch(sender, label, tokens, labelIncludesPath, null, false);
    }

    /**
     * Wie {@link #dispatch(CommandSender, String, List, boolean)}, aber mit
     * ausdrücklicher Herkunft und Bestätigung. Nur die GUI nutzt das; ein
     * Aufruf aus dem Chat oder von der Konsole hat immer {@code confirmed = false}
     * und muss {@code --confirm} selbst mitgeben.
     */
    public boolean dispatch(CommandSender sender, String label, List<String> tokens,
                            boolean labelIncludesPath, CommandContext.Origin origin,
                            boolean confirmed) {
        if (!plugin.isEnabled()) {
            return true;
        }

        // Stufe 1 - Registrierung und Pfad.
        List<String> resolved = tokens == null || tokens.isEmpty() ? List.of(HELP_PATH) : tokens;
        CommandParser.PathResult path = parser.parsePath(resolved);
        if (path.failed()) {
            sendFailure(sender, path.failure(), null);
            return true;
        }
        CommandTree.Node node = path.node();
        CommandModule module = node.module();

        // Stufe 2 - Herkunft: nur Spielerpfade weisen die Konsole ab.
        if (node.playerOnly() && !(sender instanceof Player)) {
            sendError(sender, "player-only", "player-only");
            return true;
        }

        // Stufe 3 - Systemzustand: READ_ONLY zeigt nur, schreibt nie (11.8).
        // Die Prüfung sitzt hier und nicht in den Modulen, damit kein Pfad sie
        // vergessen kann.
        if (ReadOnlyPolicy.blocks(node.path(), plugin.settings().network().readOnly())) {
            sendKey(sender, "command._default.error.read-only", "error.read-only");
            return true;
        }

        // Stufe 4 - Permission: alter und neuer Knoten als ODER (Abschnitt 7.4).
        if (!CommandPermissions.granted(sender, node)) {
            sendError(sender, "no-permission", "no-permission");
            return true;
        }

        // Stufe 5 - Syntax: Argumente, Flags und Flag-Rechte. Erst nach der
        // Permission, damit die Syntax nicht an Unberechtigte gelangt.
        CommandParser.ArgumentsResult parsed = parser.parseArguments(path.rest());
        if (parsed.failed()) {
            sendFailure(sender, parsed.failure(), node);
            return true;
        }
        Arguments arguments = parsed.args();
        List<String> pathSegments = node.pathSegments();
        String invokedLabel = labelIncludesPath ? invoked(label, pathSegments) : label;
        CommandContext context = CommandContext.of(sender, invokedLabel, pathSegments, arguments,
                origin, confirmed);
        CommandParser.Failure flagFailure = parser.validateFlags(sender, context.origin(), node, arguments);
        if (flagFailure != null) {
            sendFailure(sender, flagFailure, node);
            return true;
        }

        // Stufe 6 - Bestätigung: Flag aus der Befehlszeile oder eine echte
        // Bestätigung der GUI. Die Herkunft GUI allein genügt nie, und deren
        // Bestätigung kommt aus einem eigenen Dialog, nicht aus dem Text der
        // gui.yml (siehe CommandParser.validateFlags).
        if (module.requiresConfirmation() && !arguments.hasFlag("confirm")
                && !context.confirmed()) {
            sendKey(sender, "command._default.error.confirm-required", null,
                    "syntax", usage(node) + " --confirm");
            return true;
        }

        // Stufe 7 - Rate-Limit und Cooldown vor der Fachlogik (Abschnitt 11.7).
        if (!throttleAllowed(sender, node)) {
            return true;
        }

        // Stufe 8 - Ausführung über den Dienst des Moduls.
        runModule(plugin, module, context);
        return true;
    }

    private void sendFailure(CommandSender sender, CommandParser.Failure failure,
                             CommandTree.Node node) {
        switch (failure.code()) {
            case "no-permission" -> sendError(sender, "no-permission", "no-permission");
            case "unknown-subcommand" -> {
                String input = failure.value("input");
                String suggestion = failure.value("suggestion");
                if (suggestion.isEmpty()) {
                    sendKey(sender, "command._default.error.unknown-subcommand-plain",
                            "usage-economysystem", "input", input);
                } else {
                    sendKey(sender, "command._default.error.unknown-subcommand",
                            "usage-economysystem", "input", input, "suggestion", suggestion);
                }
            }
            default -> sendKey(sender, "command._default.usage", null,
                    "syntax", node == null ? "/" + ROOT_COMMAND + " <pfad…>" : usage(node),
                    "input", failure.value("input"));
        }
    }

    private void sendError(CommandSender sender, String code, String fallbackKey,
                           String... placeholders) {
        sendKey(sender, "command._default.error." + code, fallbackKey, placeholders);
    }

    /**
     * Sendet den neuen Schlüssel, wenn er vorhanden ist, sonst den bisherigen
     * (Abschnitt 9.1: neue Schlüssel haben Vorrang, alte bleiben Fallback).
     */
    private void sendKey(CommandSender sender, String key, String fallbackKey,
                         String... placeholders) {
        if (plugin.messages().has(key)) {
            plugin.messages().send(sender, key, placeholders);
            return;
        }
        plugin.messages().send(sender, fallbackKey == null ? key : fallbackKey, placeholders);
    }

    private static String usage(CommandTree.Node node) {
        String usage = node.usage();
        return usage.isBlank() ? "/" + ROOT_COMMAND + " " + node.path() : usage;
    }

    private static String invoked(String label, List<String> path) {
        return path.isEmpty() ? label : label + " " + String.join(" ", path);
    }

    /**
     * Stufe 7 - Drosselung vor der Fachlogik: Mindestabstand des Moduls
     * ({@code cooldownMillis()}) und Mengenbegrenzung aus {@code network.limits}
     * (Abschnitt 11.7). Beide werden geprüft, <em>bevor</em> einer von beiden
     * gezählt wird - eine Ablehnung verbraucht kein Kontingent.
     *
     * <p>Der Schlüssel ist (Spieler, Pfad) bzw. (Spieler, konfigurierte
     * Pfadgruppe): {@code admin.balance.add|remove|set|reset} teilen sich damit
     * ein Fenster. {@code count: 0} hebt die Mengenbegrenzung auf,
     * {@code cooldown-seconds: 0} den Mindestabstand.</p>
     */
    private boolean throttleAllowed(CommandSender sender, CommandTree.Node node) {
        long now = System.currentTimeMillis();

        // Mindestabstand des Moduls.
        long cooldown = node.module().cooldownMillis();
        boolean cooldownActive = cooldown > 0 && !sender.hasPermission(BYPASS_COOLDOWN);
        Long cooldownUntil = cooldownActive
                ? cooldowns.get(cooldownKey(sender, node.path()))
                : null;
        long cooldownWait = cooldownUntil == null ? 0L : Math.max(0L, cooldownUntil - now);

        // Rate-Limit je Spieler und Pfadgruppe (gleitendes Fenster).
        Limit limit = limitFor(node.path());
        Deque<Long> window = limit == null ? null : rateWindow(sender, limit.name());
        long limitWait = 0L;
        if (limit != null) {
            long windowMillis = Math.max(1L, limit.rate().seconds()) * 1000L;
            while (!window.isEmpty() && window.peekFirst() <= now - windowMillis) {
                window.pollFirst();
            }
            if (limit.rate().count() > 0 && !sender.hasPermission(BYPASS_RATE_LIMIT)
                    && window.size() >= limit.rate().count()) {
                limitWait = window.peekFirst() + windowMillis - now;
            }
            long minGap = Math.max(0L, limit.rate().cooldownSeconds()) * 1000L;
            if (minGap > 0L && !sender.hasPermission(BYPASS_COOLDOWN) && !window.isEmpty()) {
                limitWait = Math.max(limitWait, window.peekLast() + minGap - now);
            }
        }

        long wait = Math.max(cooldownWait, limitWait);
        if (wait > 0L) {
            long seconds = Math.max(1L, (wait + 999L) / 1000L);
            sendKey(sender, "command._default.error.rate-limited", null,
                    "seconds", String.valueOf(seconds));
            return false;
        }

        if (cooldownActive) {
            cooldowns.put(cooldownKey(sender, node.path()), now + cooldown);
        }
        if (window != null) {
            window.addLast(now);
        }
        return true;
    }

    /**
     * Konfigurierte Pfadgruppe eines Pfades aus {@code network.limits};
     * {@code null}, wenn der Pfad nicht begrenzt wird (Abschnitt 11.7).
     */
    private Limit limitFor(String path) {
        Settings.Limits limits = plugin.settings().network().limits();
        if (limits == null) {
            return null;
        }
        if ("pay".equals(path)) {
            return new Limit("pay", limits.pay());
        }
        if ("credit.take".equals(path)) {
            return new Limit("credit.take", limits.creditTake());
        }
        if ("admin.balance".equals(path) || path.startsWith("admin.balance.")) {
            return new Limit("admin.balance", limits.adminBalance());
        }
        if ("gui".equals(path)) {
            return new Limit("gui", limits.gui());
        }
        if ("balance.top".equals(path)) {
            return new Limit("balance.top", limits.balanceTop());
        }
        return null;
    }

    private Deque<Long> rateWindow(CommandSender sender, String group) {
        return rateWindows.computeIfAbsent(cooldownKey(sender, group), key -> new ArrayDeque<>());
    }

    /** Pfadgruppe und ihr konfiguriertes Limit. */
    private record Limit(String name, Settings.RateLimit rate) {
    }

    private static String cooldownKey(CommandSender sender, String path) {
        if (sender instanceof Player player) {
            return player.getUniqueId() + "|" + path;
        }
        return sender.getName() + "|" + path;
    }

    // ------------------------------------------------------------------
    // Tab-Vervollständigung: nur was erlaubt ist (Abschnitt 9.4)
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        return completeTab(sender, alias, tokens(command.getName(), args), false);
    }

    /**
     * Vervollständigt einen Aufruf; {@code tokens} beginnen mit dem Pfad.
     */
    public List<String> completeTab(CommandSender sender, String alias, List<String> tokens,
                                    boolean labelIncludesPath) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }

        String prefix = tokens.get(tokens.size() - 1);
        List<String> head = tokens.subList(0, tokens.size() - 1);

        // Keine Vorschläge für Flags und für Werte eines Flags: "--reason" ist
        // Freitext, "1" nach "--confirm" ergibt keinen Sinn.
        if (prefix.startsWith("--")) {
            return List.of();
        }
        if (!head.isEmpty() && head.get(head.size() - 1).startsWith("--")) {
            return List.of();
        }

        // Der Walk löst auch Synonyme auf; sonst gäbe es für "/eco kredit …"
        // keine Vorschläge, obwohl der Aufruf gültig ist.
        CommandParser.Walk walk = parser.walk(head);
        CommandTree.Node node = walk.node();
        CommandTree.Node matched = walk.module();
        int matchedDepth = walk.moduleDepth();

        if (matched == null) {
            // Noch kein Modul erreicht: nur Unterpfade vorschlagen, und nur
            // wenn der Pfad bis hierher vollständig aufgelöst ist.
            return walk.depth() == head.size()
                    ? filter(CommandPermissions.visibleSegments(node, sender), prefix)
                    : List.of();
        }
        if (!CommandPermissions.visible(matched, sender)) {
            return List.of();
        }

        List<String> options = new ArrayList<>();
        if (matchedDepth == head.size()) {
            options.addAll(CommandPermissions.visibleSegments(matched, sender));
        }

        List<String> moduleArgs = new ArrayList<>(head.subList(matchedDepth, head.size()));
        moduleArgs.add(prefix);
        CommandParser.ArgumentsResult argumentResult = parser.parseArguments(moduleArgs);
        Arguments arguments = argumentResult.failed() ? Arguments.empty() : argumentResult.args();
        List<String> path = matched.pathSegments();
        String invokedLabel = labelIncludesPath ? invoked(alias, path) : alias;
        List<String> completions = matched.module().complete(
                CommandContext.of(sender, invokedLabel, path, arguments));
        if (completions != null) {
            options.addAll(completions);
        }
        if (completions == null || completions.isEmpty()) {
            // Vorschläge aus commands.yml greifen nur, wenn das Modul selbst
            // keine kennt (Abschnitt 9.4).
            options.addAll(commandsConfig.suggestionsFor(matched.path()));
        }
        return filter(options, prefix);
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option != null && !option.isBlank())
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    // ------------------------------------------------------------------
    // Ausführungspipeline: vorbereiten, auf den Haupt-Thread wechseln,
    // einmal ausführen, Ergebnis formatieren.
    // ------------------------------------------------------------------

    private static void runModule(EconomySystem plugin, CommandModule module, CommandContext context) {
        CompletableFuture<?> preparation;
        try {
            preparation = module.prepare(context);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Vorbereitung für '" + module.path() + "' fehlgeschlagen: "
                    + exception.getMessage());
            plugin.messages().send(context.sender(), "storage-error");
            return;
        }
        if (preparation == null) {
            preparation = CompletableFuture.completedFuture(null);
        }

        preparation.whenComplete((ignored, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                CommandSender sender = context.sender();
                if (sender instanceof Player player && !player.isOnline()) {
                    return;
                }
                if (error != null) {
                    plugin.getLogger().warning("Konto konnte nicht geladen werden: " + error.getMessage());
                    plugin.messages().send(sender, "storage-error");
                    return;
                }

                CommandResult result;
                try {
                    result = module.execute(context);
                } catch (StorageException exception) {
                    plugin.getLogger().severe("Zugriff auf den Economy-Speicher fehlgeschlagen: "
                            + exception.getMessage());
                    plugin.messages().send(sender, "storage-error");
                    return;
                }
                sendResult(plugin, sender, result);
            });
        });
    }

    private static void sendResult(EconomySystem plugin, CommandSender sender, CommandResult result) {
        if (result == null || !result.answers()) {
            return;
        }
        plugin.messages().send(sender, result.code(), result.placeholderPairs());
    }

    private record Registration(PluginCommand command,
                                CommandExecutor executor,
                                TabCompleter tabCompleter) {
    }
}
