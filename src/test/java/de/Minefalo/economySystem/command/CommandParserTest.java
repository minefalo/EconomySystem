package de.Minefalo.economySystem.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Prüft die konfigurierbare Länge von {@code --reason}
 * ({@code commands.reason-max-length}, Abschnitt 6.4).
 */
class CommandParserTest {

    @Test
    void reasonWirdAufKonfigurierteLaengeGekuerzt() {
        CommandParser parser = new CommandParser(TestCommands.TREE, Map.of(), 10);

        CommandParser.ArgumentsResult result = parser.parseArguments(
                List.of("--reason", "abcdefghijklmnop"));

        assertFalse(result.failed());
        assertEquals("abcdefghij", result.args().flags().get("reason"));
    }

    @Test
    void standardLaengeIst160() {
        CommandParser parser = new CommandParser(TestCommands.TREE, Map.of());

        CommandParser.ArgumentsResult result = parser.parseArguments(
                List.of("--reason", "x".repeat(200)));

        assertFalse(result.failed());
        assertEquals(160, result.args().flags().get("reason").length());
    }
}
