package de.Minefalo.economySystem.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alles, was ein Modul über einen Aufruf wissen muss: wer ruft auf, über
 * welchen Pfad, mit welchen Argumenten und in welcher Herkunft.
 *
 * @param sender  Aufrufer (Spieler oder Konsole)
 * @param actorId UUID des Aufrufers, {@code null} bei der Konsole
 * @param label   wie aufgerufen, inklusive der aufgelösten Pfadsegmente
 *                ({@code "money"}, {@code "eco admin help"}); dient dazu,
 *                klickbare Befehle wieder zusammenzusetzen
 * @param path    aufgelöste Pfadsegmente, z. B. {@code ["admin", "balance"]}
 * @param args      Argumente und Flags hinter dem Pfad
 * @param origin    Herkunft des Aufrufs
 * @param confirmed {@code true}, wenn die Bestätigung für diesen Aufruf
 *                  bereits erteilt wurde - nur die GUI setzt das, und zwar
 *                  erst nach einem eigenen Bestätigungsklick. Text aus einer
 *                  Befehlszeile kann es nie setzen.
 * @param state     Zwischenspeicher zwischen {@code prepare()} und
 *                  {@code execute()} eines Aufrufs (z. B. das Ergebnis einer
 *                  asynchronen Speicherbuchung); nie über den Aufruf hinaus
 */
public record CommandContext(
        CommandSender sender,
        UUID actorId,
        String label,
        List<String> path,
        Arguments args,
        Origin origin,
        boolean confirmed,
        Map<String, Object> state) {

    /**
     * Herkunft eines Aufrufs. {@code PLAYER} und {@code CONSOLE} leitet der
     * Router aus dem {@link CommandSender} ab; {@code GUI}, {@code API} und
     * {@code SCHEDULER} setzen spätere Aufrufer selbst.
     */
    public enum Origin {
        PLAYER,
        CONSOLE,
        GUI,
        API,
        SCHEDULER
    }

    public CommandContext {
        Objects.requireNonNull(sender, "sender");
        path = path == null ? List.of() : List.copyOf(path);
        args = args == null ? Arguments.empty() : args;
        origin = origin == null ? Origin.API : origin;
        state = state == null ? new ConcurrentHashMap<>() : state;
    }

    /**
     * Baut einen Kontext aus einem Bukkit-Aufrufer und leitet die Herkunft ab.
     */
    public static CommandContext of(CommandSender sender, String label,
                                    List<String> path, Arguments args) {
        return of(sender, label, path, args, null, false);
    }

    /**
     * Baut einen Kontext mit ausdrücklich gesetzter Herkunft. Ein {@code null}
     * als Herkunft leitet sie wie {@link #of(CommandSender, String, List,
     * Arguments)} aus dem Aufrufer ab.
     */
    public static CommandContext of(CommandSender sender, String label,
                                    List<String> path, Arguments args,
                                    Origin origin, boolean confirmed) {
        UUID actorId = null;
        Origin resolved = origin;
        if (sender instanceof Player player) {
            actorId = player.getUniqueId();
            if (resolved == null) {
                resolved = Origin.PLAYER;
            }
        } else if (resolved == null) {
            resolved = Origin.CONSOLE;
        }
        return new CommandContext(sender, actorId, label == null ? "" : label, path, args, resolved,
                confirmed, new ConcurrentHashMap<>());
    }
}
