package de.Minefalo.economySystem.command;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Der Vertrag für genau einen Pfad (optional mit Unterpfaden) aus
 * {@code Commands.md}, Abschnitt 10.
 *
 * <p>Ein Modul deklariert selbst, was es vorab laden muss ({@link #prepare})
 * und welche Vorschläge es kennt ({@link #complete}). Die Basisklasse muss
 * dafür keinen einzigen Befehl kennen; ein neuer Befehl ist ein Modul plus ein
 * Eintrag in der Modulliste der {@link CommandRegistry}.</p>
 */
public interface CommandModule {

    /**
     * Stabile Kennung, identisch mit dem letzten Pfadsegment.
     */
    String id();

    /**
     * Kanonischer Pfad ohne Wurzel, z. B. {@code "admin.balance.add"}.
     */
    String path();

    /**
     * Permission-Knoten des Pfades. Standard ist
     * {@code economysystem.command.<pfad>}. Ein leerer Knoten bedeutet, dass
     * das Modul feinere Prüfungen selbst vornimmt (Übergangszustand der
     * bisherigen Befehle).
     */
    default String permission() {
        return "economysystem.command." + path();
    }

    /**
     * Kurzbefehle und Aliase, die an diesem Pfad hängen.
     */
    default List<String> aliases() {
        return List.of();
    }

    /**
     * {@code true} = die Konsole wird mit {@code player-only} abgewiesen.
     */
    default boolean playerOnly() {
        return false;
    }

    /**
     * Mindestabstand zwischen zwei Aufrufen in Millisekunden; 0 = kein Cooldown.
     */
    default long cooldownMillis() {
        return 0L;
    }

    /**
     * Vollständige kanonische Syntax für die Fehlermeldung {@code usage}.
     */
    default String usage() {
        return "";
    }

    /**
     * {@code true} = der Pfad verlangt {@code --confirm} (Abschnitt 6.4:
     * {@code set}, {@code reset}, {@code forgive}, {@code run},
     * {@code reload network}).
     */
    default boolean requiresConfirmation() {
        return false;
    }

    /**
     * Audit-Aktionen, die dieses Modul schreiben kann.
     */
    default Set<String> auditActions() {
        return Set.of();
    }

    /**
     * Meldet Unterpfade an; Standard: keine. Gruppenmodule nutzen das, um ihre
     * Kinder in den Baum zu hängen.
     */
    default void register(CommandTree tree) {
    }

    /**
     * Lädt vorab, was {@link #execute} braucht (Konten, Kredite, Rangliste).
     * Läuft im Storage-Executor, niemals auf dem Tick-Thread.
     */
    default CompletableFuture<?> prepare(CommandContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Führt aus. Wird erst nach erfolgreichem {@link #prepare} aufgerufen, und
     * zwar genau einmal, auf dem Haupt-Thread.
     */
    CommandResult execute(CommandContext context);

    /**
     * Vorschläge für das aktuelle Argument.
     */
    default List<String> complete(CommandContext context) {
        return List.of();
    }
}
