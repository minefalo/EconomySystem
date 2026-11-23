package de.Minefalo.economySystem.command;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static de.Minefalo.economySystem.command.TestCommands.sender;
import static de.Minefalo.economySystem.command.TestCommands.startPath;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sichtbarkeit der dynamisch registrierten Aliase: Ein Alias wie
 * {@code /ecoadmin} darf ohne die Permission seines Pfades weder in der
 * Vorschlagsliste noch als ausführbarer Befehl auftauchen.
 *
 * <p>Bukkit fragt dafür {@link org.bukkit.command.Command#testPermissionSilent}
 * ab; {@link AliasCommand} beantwortet das mit derselben Rechteprüfung wie der
 * Router ({@link CommandPermissions#granted}).</p>
 */
@DisplayName("Aliase: ohne Permission nicht sichtbar")
class AliasVisibilityTest {

    @Test
    @DisplayName("/ecoadmin erscheint nur mit Adminrecht")
    void adminAliasNeedsAdminPermission() {
        Predicate<CommandSender> visible =
                CommandRegistry.visibleAt(TestCommands.TREE, startPath("ecoadmin"));

        assertFalse(visible.test(sender()), "ohne Recht darf /ecoadmin nicht erscheinen");
        assertFalse(visible.test(sender("economysystem.command.balance", "economysystem.pay")),
                "Spielerrechte dürfen den Admin-Alias nicht sichtbar machen");
        assertTrue(visible.test(sender("economysystem.command.admin")),
                "neuer Admin-Knoten schaltet den Alias frei");
        assertTrue(visible.test(sender("economysystem.admin")),
                "bisheriger Admin-Knoten bleibt gültig (12.1)");
    }

    @Test
    @DisplayName("Spieler-Aliase bleiben für ihre Berechtigten sichtbar")
    void playerAliasesStayVisibleForTheirPermission() {
        Predicate<CommandSender> money =
                CommandRegistry.visibleAt(TestCommands.TREE, startPath("geld"));
        assertTrue(money.test(sender("economysystem.balance")));
        assertTrue(money.test(sender("economysystem.command.balance")));
        assertFalse(money.test(sender()));

        Predicate<CommandSender> kredit =
                CommandRegistry.visibleAt(TestCommands.TREE, startPath("darlehen"));
        assertTrue(kredit.test(sender("economysystem.kredit")));
        assertFalse(kredit.test(sender()));
    }

    @Test
    @DisplayName("der Wurzelbefehl /eco bleibt immer sichtbar")
    void rootAliasIsAlwaysVisible() {
        assertTrue(CommandRegistry.visibleAt(TestCommands.TREE, List.of()).test(sender()),
                "ein Alias ohne Pfad (Wurzelbefehl) ist für alle sichtbar");
    }

    @Test
    @DisplayName("AliasCommand meldet die Sichtbarkeit an Bukkit")
    void aliasCommandReportsVisibility() {
        Predicate<CommandSender> visible =
                CommandRegistry.visibleAt(TestCommands.TREE, startPath("esa"));
        AliasCommand command = new AliasCommand("esa", "", "/eco admin",
                (s, c, l, args) -> true, (s, c, alias, args) -> List.of(), visible);

        assertFalse(command.testPermissionSilent(sender()));
        assertTrue(command.testPermissionSilent(sender("economysystem.command.admin")));

        AliasCommand root = new AliasCommand("eco", "", "/eco",
                (s, c, l, args) -> true, (s, c, alias, args) -> List.of(), s -> true);
        assertTrue(root.testPermissionSilent(sender()));
    }
}
