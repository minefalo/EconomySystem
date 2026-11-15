package de.Minefalo.economySystem.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Ein dynamisch registrierter Alias, der an den eigentlichen Befehl
 * weiterleitet. So können Aliase zur Laufzeit aus der commands.yml
 * geladen werden, ohne sie in der plugin.yml hinterlegen zu müssen.
 */
final class AliasCommand extends Command {

    private final CommandExecutor executor;
    private final TabCompleter tabCompleter;

    AliasCommand(String name, String description, String usage,
                 CommandExecutor executor, TabCompleter tabCompleter) {
        super(name,
                description == null ? "" : description,
                usage == null ? "/" + name : usage,
                List.of());
        this.executor = executor;
        this.tabCompleter = tabCompleter;
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
