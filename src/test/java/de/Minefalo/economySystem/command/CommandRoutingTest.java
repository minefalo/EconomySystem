package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.command.module.AdminModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pfad- und Synonym-Matrix aus Schritt 4: jede Schreibweise aus
 * {@code Commands.md}, Abschnitt 8, muss auf genau den erwarteten Pfad
 * führen, und die ausgelieferte {@code commands.yml} muss beim Laden
 * warnungsfrei durchgehen.
 *
 * <p>Die Tests arbeiten auf denselben Rohdaten wie der Router
 * ({@code shortcuts}, {@code commands.*.aliases}, {@code subcommand-words})
 * und nutzen seine Validatoren - ein Tippfehler in der Datei oder ein Synonym,
 * das einen Altaufruf auf den falschen Pfad zwingt, fällt damit sofort auf.</p>
 */
@DisplayName("Pfad- und Synonym-Matrix: Kurzbefehle, Aliase, Altformen")
class CommandRoutingTest {

    @Test
    @DisplayName("ausgelieferte commands.yml lädt warnungsfrei und zeigt nur auf Module")
    void shippedCommandsConfigLoadsWithoutWarnings() {
        assertEquals(2, TestCommands.configVersion("commands.yml"),
                "commands.yml muss die aktuelle Dateiversion tragen");

        // Das Laden selbst ist der Test: TestCommands prüft die Warnungen von
        // resolveShortcuts und resolveSubcommandWords (leere Liste).
        assertEquals(Set.of("money", "pay", "kredit", "economysystem",
                        "economysystem-gui", "economysystem-admin"),
                TestCommands.SHORTCUTS.keySet(),
                "jeder Kurzbefehl der plugin.yml braucht einen Pfad");

        assertFalse(TestCommands.SHORTCUTS.isEmpty());
        assertTrue(TestCommands.ALIAS_PATHS.containsKey("ecoadmin"),
                "Aliase müssen über resolveAliases einen Pfad bekommen");
    }

    @Test
    @DisplayName("Kurzbefehle aus commands.yml landen auf ihren Modulen")
    void shortcutsMapToExpectedModules() {
        Map<String, String> expected = Map.of(
                "money", "balance",
                "pay", "pay",
                "kredit", "credit",
                "economysystem", "help",
                "economysystem-gui", "gui",
                "economysystem-admin", "admin");

        expected.forEach((command, path) -> assertRoutes(command, path));
    }

    @Test
    @DisplayName("Aliase zeigen auf denselben Pfad wie ihr Befehl")
    void aliasesKeepThePathOfTheirCommand() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("es", "help"),
                Map.entry("geld", "balance"),
                Map.entry("balance", "balance"),
                Map.entry("coins", "balance"),
                Map.entry("zahlen", "pay"),
                Map.entry("ueberweisen", "pay"),
                Map.entry("credit", "credit"),
                Map.entry("darlehen", "credit"),
                Map.entry("eco-gui", "gui"),
                Map.entry("egui", "gui"),
                Map.entry("esa", "admin"),
                Map.entry("ecoadm", "admin"),
                Map.entry("ecoadmin", "admin"));

        expected.forEach((alias, path) -> assertRoutes(alias, path));

        // /geld top muss die Bestenliste sein, nicht ein Argument von balance.
        assertRoutes("geld", "balance.top", "top");
    }

    @Test
    @DisplayName("bisherige Kredit-Schreibweisen: /kredit add, bezahlen, status …")
    void legacyCreditSpellingsReachTheirModules() {
        assertRoutes("kredit", "credit.take", "add", "50", "30");
        assertRoutes("kredit", "credit.take", "aufnehmen", "50", "30");
        assertRoutes("kredit", "credit.take", "nehmen", "50", "30");
        assertRoutes("kredit", "credit.repay", "bezahlen", "100");
        assertRoutes("kredit", "credit.repay", "zahlen", "100");
        assertRoutes("kredit", "credit.repay", "tilgen", "100");
        assertRoutes("kredit", "credit.repay", "pay", "100");
        assertRoutes("kredit", "credit.info", "status");
        assertRoutes("kredit", "credit.info", "info");
        assertRoutes("kredit", "credit.list", "liste");
        assertRoutes("kredit", "credit.list", "list");

        // ohne Argument bleibt die Gruppe selbst zuständig (Hilfe)
        assertRoutes("kredit", "credit");
        // der Alias /credit kennt die Synonyme genauso
        assertRoutes("darlehen", "credit.take", "aufnehmen", "50", "7");
        // die Argumente hinter dem Pfad bleiben unverändert
        CommandParser.PathResult result = TestCommands.route(
                TestCommands.tokens("kredit", "bezahlen", "100"));
        assertEquals(List.of("100"), result.rest());
        assertEquals("credit.repay", result.node().path());
    }

    @Test
    @DisplayName("bisherige Admin-Schreibweisen: /ecoadmin money …, zinsen …")
    void legacyAdminSpellingsReachTheirModules() {
        // money <konto> <aktion>: 'money' bleibt Argument, die Aktion wird im
        // AdminModule übersetzt (andere Reihenfolge als admin.balance add).
        CommandParser.PathResult money = TestCommands.route(
                TestCommands.tokens("ecoadmin", "money", "Spender", "add", "100"));
        assertFalse(money.failed());
        assertEquals("admin", money.node().path());
        assertEquals(List.of("money", "Spender", "add", "100"), money.rest());

        assertEquals("add", AdminModule.legacyMoneyAction("add"));
        assertEquals("add", AdminModule.legacyMoneyAction("give"));
        assertEquals("remove", AdminModule.legacyMoneyAction("remove"));
        assertEquals("remove", AdminModule.legacyMoneyAction("take"));
        assertEquals("set", AdminModule.legacyMoneyAction("set"));
        assertEquals("reset", AdminModule.legacyMoneyAction("reset"));
        assertEquals("", AdminModule.legacyMoneyAction("unfug"));

        // zinsen <typ> set <faktor>: 'zinsen' darf kein Pfadwort sein, sonst
        // kippt die bisherige Reihenfolge (commands.yml, Kommentar zu interest).
        CommandParser.PathResult interest = TestCommands.route(
                TestCommands.tokens("ecoadmin", "zinsen", "spar", "set", "1.3"));
        assertFalse(interest.failed());
        assertEquals("admin", interest.node().path());
        assertEquals(List.of("zinsen", "spar", "set", "1.3"), interest.rest());

        assertRoutes("ecoadmin", "admin.reload", "reload");
        assertRoutes("ecoadmin", "admin.balance.add", "balance", "give", "Spender", "100");
        assertRoutes("ecoadmin", "admin.test.interest-cycle", "test", "interest-cycle", "add", "5");
        assertRoutes("ecoadmin", "admin.interest.show", "interest", "show");
    }

    @Test
    @DisplayName("Synonyme gelten je Pfadposition, das kanonische Wort gewinnt")
    void synonymsResolveInTheirOwnContext() {
        // 'take' ist unter credit das kanonische Wort ...
        assertRoutes("kredit", "credit.take", "take", "50", "30");
        // ... und unter admin.balance das Synonym für remove.
        assertRoutes("ecoadmin", "admin.balance.remove", "balance", "take", "Spender", "100");
        // 'add' ist unter credit Synonym für take, unter admin.balance kanonisch.
        assertRoutes("kredit", "credit.take", "add", "50", "30");
        assertRoutes("ecoadmin", "admin.balance.add", "balance", "add", "Spender", "100");
        // 'pay' ist unter credit Synonym für repay, als Wurzelwort bleibt es pay.
        assertRoutes("kredit", "credit.repay", "pay", "10");
        assertRoutes("pay", "pay");
        // 'status' ist unter credit Synonym für info ...
        assertRoutes("kredit", "credit.info", "status");
        // ... und bleibt unter admin ein Argument der bisherigen Form.
        CommandParser.PathResult adminInfo = TestCommands.route(
                TestCommands.tokens("ecoadmin", "status"));
        assertEquals("admin", adminInfo.node().path());
        assertEquals(List.of("status"), adminInfo.rest());

        // weitere Synonyme aus commands.yml
        assertRoutes("money", "balance.top", "rangliste");
        assertRoutes("money", "balance.top", "bestenliste");
        assertRoutes("ecoadmin", "admin.test.interest-cycle", "test", "zinszyklus", "add", "5");
        assertRoutes("ecoadmin", "admin.reload", "neu-laden");
        assertRoutes("ecoadmin", "admin.balance.reset", "balance", "zuruecksetzen", "Spender");
    }

    @Test
    @DisplayName("unbekannte Wörter bleiben Argumente, statt den Pfad zu verbreitern")
    void unknownWordsStayArguments() {
        CommandParser.PathResult credit = TestCommands.route(
                TestCommands.tokens("kredit", "unfug", "1"));
        assertFalse(credit.failed());
        assertEquals("credit", credit.node().path());
        assertEquals(List.of("unfug", "1"), credit.rest());

        CommandParser.PathResult balance = TestCommands.route(
                TestCommands.tokens("geld", "unfug"));
        assertEquals("balance", balance.node().path());
        assertEquals(List.of("unfug"), balance.rest());
    }

    // ------------------------------------------------------------------

    /** Prüft, dass ein Kurzbefehl bzw. Alias {@code path} mit {@code args} erreicht. */
    private static void assertRoutes(String command, String path, String... args) {
        CommandParser.PathResult result = TestCommands.route(TestCommands.tokens(command, args));
        assertFalse(result.failed(), "/" + command + " muss ein bekannter Pfad sein");
        assertEquals(path, result.node().path(),
                "/" + command + (args.length == 0 ? "" : " " + String.join(" ", args))
                        + " muss auf " + path + " führen");
    }
}
