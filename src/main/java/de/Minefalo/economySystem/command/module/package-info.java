/**
 * Fachmodule der Befehlsschicht ({@code Commands.md}, Abschnitt 5 und 10).
 *
 * <p>Jeder Pfad aus Abschnitt 5 ist genau ein Modul. Ein Modul deklariert
 * Pfad, Permission, Syntax und Herkunft selbst; der
 * {@link de.Minefalo.economySystem.command.CommandRegistry} prüft die
 * Reihenfolge aus Abschnitt 6.7 an einer Stelle. Gruppenmodule melden ihre
 * Kinder über {@link de.Minefalo.economySystem.command.CommandModule#register}
 * an, damit die Modulliste in {@code EconomySystem} nur die Wurzeln nennt.</p>
 */
package de.Minefalo.economySystem.command.module;
