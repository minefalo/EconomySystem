package de.Minefalo.economySystem.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Das Ergebnis eines Pfades. Ein Modul sendet keine Nachrichten selbst,
 * sondern liefert Code, Platzhalter und Zeilen; der Router formatiert sie.
 *
 * <p>Ein leeres {@link #code()} bedeutet: das Modul hat bereits selbst
 * geantwortet (Übergangszustand der Brücke in Schritt 1).</p>
 *
 * @param status OK, REJECTED oder DRY_RUN
 * @param code   Message-Key nach Abschnitt 9.1, z. B. {@code "command.pay.sent"}
 * @param values Platzhalter für den Message-Key
 * @param lines  Zeilen für Listen-Ausgaben
 */
public record CommandResult(Status status, String code,
                            Map<String, String> values, List<String> lines) {

    public enum Status {
        OK,
        REJECTED,
        DRY_RUN
    }

    public CommandResult {
        status = status == null ? Status.OK : status;
        code = code == null ? "" : code;
        values = values == null || values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * Das Modul hat den Aufruf bereits beantwortet; der Router schweigt.
     */
    public static CommandResult handled() {
        return new CommandResult(Status.OK, "", Map.of(), List.of());
    }

    public static CommandResult ok(String code) {
        return new CommandResult(Status.OK, code, Map.of(), List.of());
    }

    public static CommandResult ok(String code, List<String> lines) {
        return new CommandResult(Status.OK, code, Map.of(), lines);
    }

    public static CommandResult rejected(String code) {
        return new CommandResult(Status.REJECTED, code, Map.of(), List.of());
    }

    public static CommandResult dryRun(String code) {
        return new CommandResult(Status.DRY_RUN, code, Map.of(), List.of());
    }

    public CommandResult with(String placeholder, String value) {
        Map<String, String> updated = new LinkedHashMap<>(values);
        updated.put(placeholder, value);
        return new CommandResult(status, code, updated, lines);
    }

    /**
     * @return {@code true}, wenn der Router für dieses Ergebnis eine Nachricht
     *         senden muss
     */
    public boolean answers() {
        return !code.isEmpty();
    }

    /**
     * @return Platzhalter als Schlüssel/Wert-Paare für
     *         {@code Messages#send(CommandSender, String, String...)}
     */
    public String[] placeholderPairs() {
        List<String> pairs = new ArrayList<>(values.size() * 2);
        values.forEach((key, value) -> {
            pairs.add(key);
            pairs.add(value);
        });
        return pairs.toArray(String[]::new);
    }
}
