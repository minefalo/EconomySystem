package de.Minefalo.economySystem.command;

import de.Minefalo.economySystem.config.CommandsConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registriert die Hauptbefehle und deren Aliase aus der commands.yml.
 */
public final class CommandRegistry {

    private final JavaPlugin plugin;
    private final CommandsConfig commandsConfig;
    private final Map<String, Registration> registrations = new LinkedHashMap<>();
    private final List<Command> registeredAliases = new ArrayList<>();

    public CommandRegistry(JavaPlugin plugin, CommandsConfig commandsConfig) {
        this.plugin = plugin;
        this.commandsConfig = commandsConfig;
    }

    /**
     * Setzt Executor und Tab-Completer des in der plugin.yml definierten
     * Befehls.
     *
     * @return {@code false}, wenn der Befehl nicht in der plugin.yml steht
     */
    public boolean register(String name, CommandExecutor executor, TabCompleter tabCompleter) {
        PluginCommand command = plugin.getCommand(name);
        if (command == null) {
            plugin.getLogger().severe("Befehl '" + name + "' ist nicht in der plugin.yml registriert.");
            return false;
        }

        command.setExecutor(executor);
        command.setTabCompleter(tabCompleter);
        registrations.put(name.toLowerCase(Locale.ROOT), new Registration(command, executor, tabCompleter));
        return true;
    }

    /**
     * Registriert alle in der commands.yml konfigurierten Aliase.
     */
    public void registerAliases() {
        for (Registration registration : registrations.values()) {
            String commandName = registration.command().getName();
            for (String alias : commandsConfig.aliases(commandName)) {
                registerAlias(registration, alias);
            }
        }
    }

    private void registerAlias(Registration registration, String alias) {
        CommandMap commandMap = plugin.getServer().getCommandMap();
        if (commandMap.getCommand(alias) != null) {
            plugin.getLogger().warning("Alias '/" + alias + "' für /" + registration.command().getName()
                    + " ist bereits belegt und wird übersprungen.");
            return;
        }

        AliasCommand aliasCommand = new AliasCommand(
                alias,
                registration.command().getDescription(),
                registration.command().getUsage(),
                registration.executor(),
                registration.tabCompleter());

        boolean registered = commandMap.register(plugin.getName().toLowerCase(Locale.ROOT), aliasCommand);
        if (!registered) {
            plugin.getLogger().warning("Alias '/" + alias + "' für /" + registration.command().getName()
                    + " konnte nicht registriert werden.");
            removeCommand(aliasCommand);
            return;
        }
        registeredAliases.add(aliasCommand);
    }

    /**
     * Lädt die commands.yml neu und ersetzt die dynamisch registrierten Aliase.
     */
    public void reload() {
        unregisterAliases();
        commandsConfig.reload();
        registerAliases();
    }

    /**
     * Entfernt alle dynamisch registrierten Aliase (z. B. beim Deaktivieren).
     */
    public void unregisterAliases() {
        for (Command command : registeredAliases) {
            removeCommand(command);
        }
        registeredAliases.clear();
    }

    private void removeCommand(Command command) {
        CommandMap commandMap = plugin.getServer().getCommandMap();
        command.unregister(commandMap);
        try {
            commandMap.getKnownCommands().entrySet().removeIf(entry -> entry.getValue() == command);
        } catch (UnsupportedOperationException exception) {
            plugin.getLogger().warning("Alias '/" + command.getName()
                    + "' konnte nicht aus der Command-Map entfernt werden.");
        }
    }

    private record Registration(PluginCommand command,
                                CommandExecutor executor,
                                TabCompleter tabCompleter) {
    }
}
