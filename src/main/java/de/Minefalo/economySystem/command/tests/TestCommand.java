package de.Minefalo.economySystem.command.tests;

import org.bukkit.command.CommandSender;

/**
 * Optionaler Test-Befehl. Die Implementierung wird nur in Entwicklungs-
 * Builds kompiliert; im Produktionsprofil (<code>mvn -Pproduction</code>)
 * fehlt sie, und {@code /economysystem-admin tests} antwortet mit dem
 * Hinweis, dass die Testbefehle deaktiviert sind.
 */
public interface TestCommand {

    void execute(CommandSender sender, String[] args);
}
