package de.Minefalo.economySystem.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Die Argumente eines Aufrufs: die unveränderten Tokens, die
 * Stellungsparameter ohne Flags und die Flags.
 *
 * <p>Gefüllt wird die Klasse ausschließlich vom {@link CommandParser}, der
 * Pfad, Argumente und Flags nach {@code Commands.md}, Abschnitt 6, trennt.
 * {@link #asArray()} liefert die Tokens unverändert, damit die bisherigen
 * Befehle ohne Verhaltensänderung weiterlaufen.</p>
 */
public final class Arguments {

    private static final Arguments EMPTY = new Arguments(List.of(), List.of(), Map.of());

    private final List<String> raw;
    private final List<String> positional;
    private final Map<String, String> flags;

    private Arguments(List<String> raw, List<String> positional, Map<String, String> flags) {
        this.raw = raw;
        this.positional = positional;
        this.flags = flags;
    }

    /**
     * Argumente aus geprüften Tokens. Der Parser liefert Stellungsparameter
     * und Flags getrennt; die Tokens bleiben unverändert.
     */
    static Arguments of(List<String> raw, List<String> positional, Map<String, String> flags) {
        return new Arguments(List.copyOf(raw), List.copyOf(positional),
                Collections.unmodifiableMap(new LinkedHashMap<>(flags)));
    }

    public static Arguments empty() {
        return EMPTY;
    }

    /**
     * Alle Tokens in der Reihenfolge, in der sie getippt wurden - Flags
     * eingeschlossen.
     */
    public List<String> raw() {
        return raw;
    }

    /**
     * Die Stellungsparameter ohne Flags.
     */
    public List<String> positional() {
        return positional;
    }

    public String[] asArray() {
        return raw.toArray(String[]::new);
    }

    public boolean hasFlag(String name) {
        return flags.containsKey(normalize(name));
    }

    /**
     * @return Wert des Flags oder {@code null}, wenn es nicht gesetzt ist
     */
    public String flag(String name) {
        return flags.get(normalize(name));
    }

    /**
     * Nimmt Flag-Namen mit und ohne führende {@code --} an.
     */
    private static String normalize(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        while (key.startsWith("-")) {
            key = key.substring(1);
        }
        return key;
    }

    public Map<String, String> flags() {
        return flags;
    }
}
