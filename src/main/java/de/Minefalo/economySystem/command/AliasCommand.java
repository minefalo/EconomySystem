package de.Minefalo.economySystem.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Predicate;

/**
 * Ein dynamisch registrierter Befehl, der an einen Executor weiterleitet:
 * die Aliase aus der commands.yml und der Wurzelbefehl {@code /eco}. So
 * brauchen weder Aliase noch Unterpfade einen Eintrag in der plugin.yml.
 */
final class AliasCommand extends Command {

    private final CommandExecutor executor;
    private final TabCompleter tabCompleter;
    private final Predicate<CommandSender> visible;

    AliasCommand(String name, String description, String usage,
                 CommandExecutor executor, TabCompleter tabCompleter,
                 Predicate<CommandSender> visible) {
        super(name,
                description == null ? "" : description,
                usage == null ? "/" + name : usage,
                List.of());
        this.executor = executor;
        this.tabCompleter = tabCompleter;
        this.visible = visible;
    }

    /**
     * Blendet den Alias für Aufrufer ohne Recht aus: Bukkit fragt diese Methode
     * vor der Vorschlagsliste und beim Ausführen eines Befehls. So erscheint
     * z. B. {@code /ecoadmin} nur noch bei vorhandener Admin-Permission.
     */
    @Override
    public boolean testPermissionSilent(@NotNull CommandSender target) {
        return visible == null || visible.test(target);
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel,
                           @NotNull String[] args) {
        return executor.onCommand(sender, this, commandLabel, args);
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias,
                                    @NotNull String[] args) throws IllegalArgumentException {
        if (tabCompleter == null) {
            return List.of();
        }
        List<String> completions = tabCompleter.onTabComplete(sender, this, alias, args);
        return completions == null ? List.of() : completions;
    }
}
