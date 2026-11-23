package de.Minefalo.economySystem.command;

import java.util.Set;

/**
 * Betriebsmodi aus {@code Commands.md}, Abschnitt 11.8: welcher Pfad im Modus
 * {@code READ_ONLY} abgewiesen wird.
 *
 * <p>Die Prüfung sitzt im Router (Stufe 3 der Prüfreihenfolge) und damit
 * <b>vor</b> der Fachlogik - kein Modul kann sie vergessen. Die Tabelle ist
 * bewusst eine Positivliste der lesenden Pfade: ein neuer Pfad ist zunächst
 * schreibend und damit in {@code READ_ONLY} gesperrt.</p>
 */
public final class ReadOnlyPolicy {

    /** Pfade, die auch im Modus {@code READ_ONLY} erlaubt sind (11.8). */
    private static final Set<String> READ_ONLY_PATHS = Set.of(
            "balance", "balance.top",
            "credit.info", "credit.list",
            "gui", "info", "help",
            "admin.reload",
            "admin.interest.show",
            "admin.network", "admin.network.scopes",
            "admin.audit");

    private ReadOnlyPolicy() {
    }

    /**
     * @return {@code true}, wenn der Pfad in {@code READ_ONLY} abgewiesen wird
     */
    public static boolean blocks(String path, boolean readOnly) {
        return readOnly && (path == null || !READ_ONLY_PATHS.contains(path));
    }
}
