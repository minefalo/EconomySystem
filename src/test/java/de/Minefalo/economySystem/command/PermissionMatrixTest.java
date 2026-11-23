package de.Minefalo.economySystem.command;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static de.Minefalo.economySystem.command.TestCommands.node;
import static de.Minefalo.economySystem.command.TestCommands.player;
import static de.Minefalo.economySystem.command.TestCommands.sender;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rechte-Matrix der Befehlszweige aus {@code Commands.md}, Abschnitt 5.2–5.4
 * und 7.2. Der Test prüft die Stufe 4 der Prüfreihenfolge (6.7) direkt über
 * {@link CommandPermissions}, also genau die Stelle, an der eine Verbreiterung
 * sonst unbemerkt bliebe.
 *
 * <p>Wichtigster Fall: {@code balance.top} ist ein eigenes Recht und wird vom
 * Sammelknoten {@code economysystem.command.balance} <b>nicht</b> mitgetragen -
 * anders als die Gruppenknoten unter {@code admin} und {@code credit}.</p>
 */
@DisplayName("Rechte-Matrix: balance.top, credit.*, admin.*, /money top")
class PermissionMatrixTest {

    @Test
    @DisplayName("balance.top: nur eigener oder bisheriger Knoten")
    void balanceTopNeedsItsOwnPermission() {
        assertTrue(granted("balance.top", "economysystem.command.balance.top"),
                "neuer Knoten muss genügen");
        assertTrue(granted("balance.top", "economysystem.balance.top"),
                "bisheriger Knoten muss weiter genügen (12.1)");

        assertFalse(granted("balance.top", "economysystem.command.balance"),
                "der Guthaben-Knoten darf die Bestenliste nicht mitfreischalten");
        assertFalse(granted("balance.top", "economysystem.balance"),
                "auch der bisherige Guthaben-Knoten nicht");
        assertFalse(granted("balance.top", "economysystem.command.admin"),
                "admin ist kein Ersatz für balance.top");
        assertFalse(granted("balance.top"),
                "ohne Recht ist die Bestenliste gesperrt");
    }

    @Test
    @DisplayName("/money top: Kurzbefehl landet auf balance.top und wird ohne Recht abgewiesen")
    void moneyTopRouteIsGuardedByBalanceTop() {
        assertEquals(List.of("balance"), TestCommands.SHORTCUTS.get("money"),
                "commands.yml muss /money auf den Pfad balance zeigen");

        List<String> tokens = new ArrayList<>(TestCommands.SHORTCUTS.get("money"));
        tokens.add("top");
        CommandParser.PathResult result = TestCommands.route(tokens);

        assertFalse(result.failed(), "/money top muss ein bekannter Pfad sein");
        assertEquals("balance.top", result.node().path());

        assertFalse(CommandPermissions.granted(sender("economysystem.command.balance"), result.node()),
                "/money top ohne balance.top muss abgewiesen werden");
        assertTrue(CommandPermissions.granted(sender("economysystem.command.balance.top"), result.node()));
        assertTrue(CommandPermissions.granted(sender("economysystem.balance.top"), result.node()));
    }

    @Test
    @DisplayName("Tab: /money schlägt top nur mit balance.top vor")
    void tabSuggestionsUseTheSamePermissionCheck() {
        assertFalse(visible("balance", "economysystem.command.balance").contains("top"),
                "ohne balance.top darf top nicht vorgeschlagen werden");
        assertFalse(visible("balance").contains("top"),
                "ohne Recht darf top nicht vorgeschlagen werden");
        assertTrue(visible("balance", "economysystem.command.balance.top").contains("top"),
                "mit dem eigenen Knoten muss top vorgeschlagen werden");
        assertTrue(visible("balance", "economysystem.balance.top").contains("top"),
                "mit dem bisherigen Knoten muss top vorgeschlagen werden");

        // /money top <TAB>: ohne Recht liefert der Router gar keine Vorschläge,
        // weil der Pfad selbst unsichtbar ist (gleiche Prüfung wie bei der
        // Ausführung).
        assertFalse(CommandPermissions.visible(node("balance.top"),
                        sender("economysystem.command.balance")),
                "der Pfad balance.top muss ohne Recht unsichtbar sein");
        assertTrue(CommandPermissions.visible(node("balance.top"),
                sender("economysystem.command.balance.top")));

        assertTrue(CommandPermissions.visible(node("balance"),
                        sender("economysystem.command.balance")),
                "balance selbst bleibt sichtbar");
    }

    @Test
    @DisplayName("Tab: Konsole sieht keine Spielerpfade")
    void consoleSeesNoPlayerOnlyPaths() {
        CommandSender console = sender("economysystem.command.pay", "economysystem.command.credit");
        assertFalse(CommandPermissions.visible(node("pay"), console),
                "pay ist playerOnly und darf der Konsole nicht vorgeschlagen werden");
        assertFalse(CommandPermissions.visible(node("credit"), console),
                "credit ist playerOnly und darf der Konsole nicht vorgeschlagen werden");
        assertTrue(CommandPermissions.visible(node("pay"), player("economysystem.command.pay")),
                "Spieler mit Recht sehen pay");
        assertFalse(CommandPermissions.visible(node("pay"), player()),
                "Spieler ohne Recht sehen pay nicht");
    }

    @Test
    @DisplayName("credit.*: Sammelknoten und bisheriges Kreditrecht schalten alle Unterpfade frei")
    void creditPathsInheritGroupPermission() {
        for (String path : List.of("credit.take", "credit.repay", "credit.info", "credit.list")) {
            assertTrue(granted(path, "economysystem.command.credit"),
                    path + " muss über economysystem.command.credit erlaubt sein (5.2)");
            assertTrue(granted(path, "economysystem.kredit"),
                    path + " muss über den bisherigen Knoten erlaubt sein (7.4)");
            assertFalse(granted(path),
                    path + " darf ohne Recht nicht erlaubt sein");
        }
    }

    @Test
    @DisplayName("admin.*: Sammelrecht, Gruppennknoten und Altknoten")
    void adminPathsInheritAdminPermissions() {
        for (String path : List.of("admin.balance.add", "admin.balance.set",
                "admin.credit.list", "admin.interest.show", "admin.interest.set")) {
            assertTrue(granted(path, "economysystem.command.admin"),
                    path + " muss über economysystem.command.admin erlaubt sein (5.4)");
            assertTrue(granted(path, "economysystem.admin"),
                    path + " muss über den bisherigen Admin-Knoten erlaubt sein (7.2)");
            assertFalse(granted(path),
                    path + " darf ohne Recht nicht erlaubt sein");
        }

        assertTrue(granted("admin.balance.add", "economysystem.command.admin.balance"),
                "Gruppenknoten admin.balance schaltet seine Aktionen frei");
        assertTrue(granted("admin.interest.set", "economysystem.command.admin.interest"),
                "Gruppenknoten admin.interest schaltet show und set frei");
        assertTrue(granted("admin.reload", "economysystem.reload"),
                "admin reload bleibt über economysystem.reload erreichbar (12.1)");
        assertTrue(granted("admin.test.interest-cycle", "economysystem.tests"),
                "Testpfad bleibt über economysystem.tests erreichbar");
    }

    @Test
    @DisplayName("pay: neuer und bisheriger Knoten")
    void payAcceptsBothPermissionNodes() {
        assertTrue(granted("pay", "economysystem.command.pay"));
        assertTrue(granted("pay", "economysystem.pay"));
        assertFalse(granted("pay"));
    }

    // ------------------------------------------------------------------

    private static boolean granted(String path, String... permissions) {
        return CommandPermissions.granted(sender(permissions), node(path));
    }

    /**
     * Die Segmentliste, die der Router im Tab-Pfad verwendet
     * ({@link CommandPermissions#visibleSegments}); {@code visible(node, …)}
     * prüft den Pfad selbst.
     */
    private static List<String> visible(String path, String... permissions) {
        return CommandPermissions.visibleSegments(node(path), sender(permissions));
    }
}
