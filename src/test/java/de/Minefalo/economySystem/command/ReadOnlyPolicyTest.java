package de.Minefalo.economySystem.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static de.Minefalo.economySystem.command.TestCommands.PATHS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Betriebsmodi aus {@code Commands.md}, Abschnitt 11.8: {@code READ_ONLY}
 * zeigt Guthaben an und weist jede schreibende Aktion im Router ab.
 */
@DisplayName("READ_ONLY: lesende Pfade erlaubt, schreibende gesperrt")
class ReadOnlyPolicyTest {

    /** Pfade, die laut 11.8 auch in READ_ONLY erlaubt sind. */
    private static final Set<String> ALLOWED = Set.of(
            "balance", "balance.top",
            "credit.info", "credit.list",
            "gui", "info", "help",
            "admin.reload", "admin.interest.show");

    @Test
    @DisplayName("FULL weist nichts ab")
    void fullAllowsEverything() {
        for (String path : PATHS) {
            assertFalse(ReadOnlyPolicy.blocks(path, false), path + " darf in FULL nicht gesperrt sein");
        }
    }

    @Test
    @DisplayName("READ_ONLY: Positivliste lesender Pfade")
    void readOnlyAllowsOnlyReadPaths() {
        for (String path : PATHS) {
            boolean expected = !ALLOWED.contains(path);
            assertEquals(expected, ReadOnlyPolicy.blocks(path, true),
                    path + " ist in READ_ONLY " + (expected ? "gesperrt" : "erlaubt"));
        }
    }

    @Test
    @DisplayName("READ_ONLY: schreibende Pfade aus 11.8 sind gesperrt")
    void writePathsAreBlocked() {
        for (String path : List.of("pay", "credit.take", "credit.repay",
                "admin.balance.add", "admin.balance.reset",
                "admin.interest.set", "admin.test.interest-cycle")) {
            assertTrue(ReadOnlyPolicy.blocks(path, true), path + " muss in READ_ONLY gesperrt sein");
        }
        assertTrue(ReadOnlyPolicy.blocks(null, true), "unbekannter Pfad gilt als schreibend");
    }

    @Test
    @DisplayName("READ_ONLY trifft die Kurzbefehle über ihren Pfad")
    void shortcutsResolveToTheProtectedPath() {
        // /money bleibt lesend, /kredit add und /pay sind schreibend.
        assertEquals("balance", TestCommands.startPath("money").get(0));
        assertFalse(ReadOnlyPolicy.blocks("balance", true));

        CommandParser.PathResult credit = TestCommands.route(
                TestCommands.tokens("kredit", "add", "50", "30"));
        assertEquals("credit.take", credit.node().path());
        assertTrue(ReadOnlyPolicy.blocks(credit.node().path(), true));

        CommandParser.PathResult pay = TestCommands.route(TestCommands.tokens("pay"));
        assertEquals("pay", pay.node().path());
        assertTrue(ReadOnlyPolicy.blocks(pay.node().path(), true));
    }
}
