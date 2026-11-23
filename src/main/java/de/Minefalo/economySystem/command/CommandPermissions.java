package de.Minefalo.economySystem.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Permission-Ableitung nach {@code Commands.md}, Abschnitt 7.
 *
 * <p>Der kanonische Knoten wird aus dem Pfad abgeleitet
 * ({@code economysystem.command.<pfad>}). Die bisherigen Knoten werden
 * weiterhin akzeptiert; beide Formen gelten als ODER (Abschnitt 7.4). Der
 * Admin-Knoten {@code economysystem.command.admin} schaltet alle
 * {@code admin.*}-Pfade frei, der bisherige Sammelknoten
 * {@code economysystem.admin} ebenso (Abschnitt 7.2).</p>
 */
public final class CommandPermissions {

    public static final String PREFIX = "economysystem.command.";
    public static final String ADMIN = PREFIX + "admin";
    public static final String LEGACY_ADMIN = "economysystem.admin";

    /** Alt → Neu; Grundlage für die Übergangszeit (Abschnitt 7.4). */
    private static final Map<String, String> LEGACY_TO_MODERN = Map.of(
            "economysystem.balance", PREFIX + "balance",
            "economysystem.balance.others", PREFIX + "balance.other",
            "economysystem.balance.top", PREFIX + "balance.top",
            "economysystem.pay", PREFIX + "pay",
            "economysystem.kredit", PREFIX + "credit",
            "economysystem.gui", PREFIX + "gui",
            LEGACY_ADMIN, ADMIN,
            "economysystem.reload", PREFIX + "admin.reload",
            "economysystem.tests", PREFIX + "admin.test");

    private CommandPermissions() {
    }

    /**
     * Prüft einen bisherigen Knoten und den zugehörigen neuen Knoten.
     */
    public static boolean granted(CommandSender sender, String legacyNode) {
        if (legacyNode == null || legacyNode.isEmpty()) {
            return true;
        }
        if (sender.hasPermission(legacyNode)) {
            return true;
        }
        String modern = LEGACY_TO_MODERN.get(legacyNode);
        return modern != null && sender.hasPermission(modern);
    }

    /**
     * Prüft den Knoten eines Pfades: deklarierter Knoten, abgeleiteter Knoten,
     * bisheriger Knoten und - unterhalb von {@code admin} - die Sammelknoten.
     *
     * <p>Ein leerer deklarierter Knoten bedeutet, dass das Modul feinere
     * Prüfungen selbst vornimmt; dann lässt der Router durch.</p>
     */
    public static boolean granted(CommandSender sender, CommandTree.Node node) {
        if (node.permission().isEmpty()) {
            return true;
        }
        for (String candidate : candidates(node)) {
            if (sender.hasPermission(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sichtbarkeit im Tab-Vorschlag (Abschnitt 9.4): genau die Bedingung, die
     * der Router auch für die Ausführung prüft - Herkunft ({@code playerOnly})
     * und Permission. Ein Gruppenknoten ohne Modul ist sichtbar, sobald
     * mindestens ein Unterpfad sichtbar ist.
     *
     * <p>Der Router nutzt ausschließlich diese Methode; Ausführung und
     * Vervollständigung können damit nicht auseinanderlaufen.</p>
     */
    public static boolean visible(CommandTree.Node node, CommandSender sender) {
        if (!node.hasModule()) {
            for (CommandTree.Node child : node.children()) {
                if (visible(child, sender)) {
                    return true;
                }
            }
            return false;
        }
        if (node.playerOnly() && !(sender instanceof Player)) {
            return false;
        }
        return granted(sender, node);
    }

    /**
     * @return die Segmente der Kinder, die der Aufrufer sehen und ausführen darf
     */
    public static List<String> visibleSegments(CommandTree.Node node, CommandSender sender) {
        List<String> segments = new ArrayList<>();
        for (CommandTree.Node child : node.children()) {
            if (visible(child, sender)) {
                segments.add(child.segment());
            }
        }
        return segments;
    }

    /**
     * @return alle Knoten, von denen einer den Pfad freischaltet
     */
    private static Set<String> candidates(CommandTree.Node node) {
        Set<String> candidates = new LinkedHashSet<>();
        if (!node.permission().isEmpty()) {
            candidates.add(node.permission());
        }
        String path = node.path();
        candidates.add(PREFIX + path);
        // Gruppenknoten schalten ihre Unterpfade frei (Abschnitt 5.4/7.2):
        // wer economysystem.command.admin.interest hat, darf auch
        // admin.interest.show und admin.interest.set.
        // Ausnahme ist balance.top: laut 5.2 ein eigenes Recht, das der
        // Guthaben-Knoten economysystem.command.balance nicht mitbringt.
        if (!path.equals("balance.top")) {
            int separator = path.lastIndexOf('.');
            while (separator > 0) {
                candidates.add(PREFIX + path.substring(0, separator));
                separator = path.lastIndexOf('.', separator - 1);
            }
        }

        String legacy = legacyFor(path);
        if (legacy != null) {
            candidates.add(legacy);
        }
        if (isAdminPath(path)) {
            candidates.add(ADMIN);
            candidates.add(LEGACY_ADMIN);
        }
        if (path.equals("admin.reload")) {
            candidates.add("economysystem.reload");
        }
        if (path.startsWith("admin.test")) {
            candidates.add("economysystem.tests");
        }
        return Collections.unmodifiableSet(candidates);
    }

    private static boolean isAdminPath(String path) {
        return path.equals("admin") || path.startsWith("admin.");
    }

    private static String legacyFor(String path) {
        if (path.equals("balance")) {
            return "economysystem.balance";
        }
        if (path.equals("balance.top")) {
            return "economysystem.balance.top";
        }
        if (path.equals("pay")) {
            return "economysystem.pay";
        }
        if (path.equals("credit") || path.startsWith("credit.")) {
            return "economysystem.kredit";
        }
        if (path.equals("gui")) {
            return "economysystem.gui";
        }
        if (isAdminPath(path)) {
            return LEGACY_ADMIN;
        }
        return null;
    }
}
