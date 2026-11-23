package de.Minefalo.economySystem.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Der Befehlsbaum unterhalb des Wurzelbefehls: Knoten je Pfadsegment, ein
 * Modul pro Knoten (Gruppenknoten haben keins) und die aus dem Modul
 * abgeleiteten Attribute {@code playerOnly}, {@code permission} und
 * {@code usage}.
 *
 * <p>Unterbefehle sind Knoten und brauchen keinen Eintrag in der
 * {@code plugin.yml} (Regel R4). Der {@link CommandParser} löst
 * {@code /eco <pfad…>} gegen diesen Baum auf.</p>
 */
public final class CommandTree {

    private final Node root = new Node("", "");
    private final List<CommandModule> modules = new ArrayList<>();

    /**
     * Baut den Baum aus den Modulen. Gruppenmodule melden über
     * {@link CommandModule#register(CommandTree)} weitere Module an; diese
     * werden ebenfalls registriert.
     *
     * @throws IllegalStateException wenn ein Pfad doppelt belegt ist
     */
    public CommandTree(Collection<CommandModule> initialModules) {
        if (initialModules != null) {
            initialModules.forEach(this::add);
        }
        // register() kann den Baum erweitern; die Liste wächst mit und jeder
        // neu hinzugekommene Pfad wird ebenfalls befragt.
        for (int index = 0; index < modules.size(); index++) {
            modules.get(index).register(this);
        }
    }

    /**
     * Hängt ein Modul an seinen Pfad.
     *
     * @throws IllegalStateException wenn der Pfad bereits von einem Modul
     *                               belegt ist
     */
    public void add(CommandModule module) {
        if (module == null) {
            throw new IllegalArgumentException("Modul darf nicht null sein.");
        }
        String path = module.path();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Modul '" + module.id() + "' hat keinen Pfad.");
        }

        Node current = root;
        StringBuilder built = new StringBuilder();
        for (String rawSegment : path.split("\\.")) {
            String segment = rawSegment.trim().toLowerCase(Locale.ROOT);
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Modul '" + module.id()
                        + "' hat einen ungültigen Pfad: " + path);
            }
            if (built.length() > 0) {
                built.append('.');
            }
            built.append(segment);
            current = current.childOrCreate(segment, built.toString());
        }

        if (current.module != null) {
            throw new IllegalStateException("Der Pfad '" + path + "' ist doppelt belegt: '"
                    + current.module.id() + "' und '" + module.id() + "'.");
        }
        current.module = module;
        modules.add(module);
    }

    public Node root() {
        return root;
    }

    /**
     * @return alle Segmente im Baum, ohne Duplikate
     */
    public List<String> segments() {
        List<String> segments = new ArrayList<>();
        collectSegments(root, segments);
        return List.copyOf(segments);
    }

    /**
     * Schlägt zu einem unbekannten Segment das nächstliegende bekannte vor
     * (Abschnitt 15.3). Ist keines nah genug, bleibt der Vorschlag leer.
     */
    public String suggest(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String needle = input.trim().toLowerCase(Locale.ROOT);
        String best = "";
        int bestDistance = Integer.MAX_VALUE;
        for (String segment : segments()) {
            if (segment.equals(needle)) {
                return segment;
            }
            int distance = distance(needle, segment);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = segment;
            }
        }
        int tolerance = Math.max(2, needle.length() / 3);
        return bestDistance <= tolerance ? best : "";
    }

    private static void collectSegments(Node node, List<String> into) {
        for (Node child : node.children()) {
            if (!child.segment().isEmpty()) {
                into.add(child.segment());
            }
            collectSegments(child, into);
        }
    }

    private static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) {
            previous[index] = index;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int cost = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(Math.min(current[column - 1] + 1,
                        previous[column] + 1), previous[column - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /**
     * Ein Knoten im Befehlsbaum. Gruppenknoten tragen kein Modul, sind aber
     * als Segment sichtbar, sobald ein Unterpfad ausführbar ist.
     */
    public static final class Node {

        private final String segment;
        private final String path;
        private final Map<String, Node> children = new LinkedHashMap<>();
        private CommandModule module;

        private Node(String segment, String path) {
            this.segment = segment;
            this.path = path;
        }

        public String segment() {
            return segment;
        }

        public String path() {
            return path;
        }

        public CommandModule module() {
            return module;
        }

        public boolean hasModule() {
            return module != null;
        }

        public boolean playerOnly() {
            return module != null && module.playerOnly();
        }

        public String permission() {
            return module == null ? "" : module.permission();
        }

        public String usage() {
            return module == null ? "" : module.usage();
        }

        public List<String> pathSegments() {
            return path.isEmpty() ? List.of() : List.of(path.split("\\."));
        }

        public Collection<Node> children() {
            return Collections.unmodifiableCollection(children.values());
        }

        public Node child(String segment) {
            if (segment == null) {
                return null;
            }
            return children.get(segment.trim().toLowerCase(Locale.ROOT));
        }

        private Node childOrCreate(String segment, String path) {
            Node existing = children.get(segment);
            if (existing != null) {
                return existing;
            }
            Node created = new Node(segment, path);
            children.put(segment, created);
            return created;
        }

        @Override
        public String toString() {
            return path.isEmpty() ? "<root>" : path;
        }
    }
}
