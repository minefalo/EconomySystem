package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.command.module.AdminModule;
import de.Minefalo.economySystem.command.module.BalanceModule;
import de.Minefalo.economySystem.command.module.CreditModule;
import de.Minefalo.economySystem.command.module.GuiModule;
import de.Minefalo.economySystem.command.module.HelpModule;
import de.Minefalo.economySystem.command.module.InfoModule;
import de.Minefalo.economySystem.command.module.PayModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verdrahtung der echten Module aus {@code EconomySystem.registerCommands}:
 * Jeder Pfad darf nur einmal belegt sein, sonst bricht der Server beim
 * Aktivieren ab ({@code CommandTree.add}).
 *
 * <p>Die Module werden ohne Plugin und Dienst gebaut - der Befehlsbaum fragt
 * sie nur nach Pfad, {@code register} und ihren Attributen, nie nach
 * Fachlogik. Genau diese Prüfung fehlte, als {@code admin.test} doppelt
 * angemeldet wurde.</p>
 */
@DisplayName("Modul-Verdrahtung: jeder Pfad genau einmal")
class ModuleTreeTest {

    @Test
    @DisplayName("die Module aus registerCommands bauen einen Baum ohne doppelten Pfad")
    void realModulesBuildASingleTree() {
        CommandTree tree = new CommandTree(List.of(
                new BalanceModule(null, null),
                new PayModule(null, null),
                new CreditModule(null, null, null),
                new GuiModule(null, null, null),
                new InfoModule(null, null),
                new HelpModule(null, null),
                new AdminModule(null, null, null, null)));

        List<String> paths = new ArrayList<>();
        collect(tree.root(), paths);

        assertEquals(List.copyOf(TestCommands.PATHS).stream().sorted().toList(),
                paths.stream().sorted().toList(),
                "der echte Baum muss genau die Pfade aus Abschnitt 5 enthalten");
    }

    @Test
    @DisplayName("ein mehrfach belegter Pfad wird gemeldet")
    void duplicatePathIsRejected() {
        assertThrows(IllegalStateException.class, () ->
                        new CommandTree(List.of(new PayModule(null, null), new PayModule(null, null))),
                "derselbe Pfad zweimal muss auffallen");
    }

    private static void collect(CommandTree.Node node, List<String> into) {
        if (node.hasModule()) {
            into.add(node.path());
        }
        for (CommandTree.Node child : node.children()) {
            collect(child, into);
        }
    }
}
