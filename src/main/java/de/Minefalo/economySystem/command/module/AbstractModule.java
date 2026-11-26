package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandModule;
import de.Minefalo.economySystem.config.Messages;
import de.Minefalo.economySystem.currency.Currency;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Gemeinsame Basis aller Fachmodule: Zugriff auf Dienst, Währung und
 * Nachrichten sowie die Argument-Helfer, die vorher in der Befehlsbasis
 * steckten.
 *
 * <p>Ein Modul sendet nur noch mehrzeilige Ausgaben (Hilfe, Info, Listen)
 * selbst und meldet dafür {@link de.Minefalo.economySystem.command.CommandResult#handled()};
 * einzeilige Antworten gibt es als {@code CommandResult} mit Message-Key
 * zurück, damit der Router sie formatiert.</p>
 */
public abstract class AbstractModule implements CommandModule {

    protected final EconomySystem plugin;
    protected final EconomyService economy;

    protected AbstractModule(EconomySystem plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    protected Messages messages() {
        return plugin.messages();
    }

    protected Currency currency() {
        return plugin.settings().currency();
    }

    /**
     * Meldet eine falsche Syntax mit der vollständigen kanonischen Syntax
     * dieses Pfades (Abschnitt 9.2).
     */
    protected void usageError(CommandSender sender, String input) {
        String syntax = usage().isBlank() ? "/eco " + path() : usage();
        messages().send(sender, "command._default.usage",
                "syntax", syntax,
                "input", input == null ? "" : input);
    }

    /**
     * Befehlsname für klickbare Hilfe-Navigation. Bei {@code /eco admin help}
     * ist das {@code eco}, bei einem Kurzbefehl der Kurzbefehlsname.
     */
    protected static String commandBase(CommandContext context) {
        String label = context.label();
        List<String> path = context.path();
        String suffix = path.isEmpty() ? "" : " " + String.join(" ", path);
        if (!suffix.isEmpty() && label.endsWith(suffix)) {
            return label.substring(0, label.length() - suffix.length());
        }
        return label;
    }

    /**
     * Sucht ein Konto anhand des Kontonamens. Gibt {@code null} zurück, wenn
     * es keins gibt (der Spieler wurde dann bereits informiert).
     */
    protected Account resolve(CommandSender sender, String playerName) {
        Optional<Account> target = economy.find(playerName);
        if (target.isEmpty()) {
            messages().send(sender, "player-not-found", "player", playerName);
            return null;
        }
        return target.get();
    }

    /**
     * Parst einen Betrag. Gibt {@code null} zurück, wenn die Eingabe ungültig
     * ist (der Spieler wurde dann bereits informiert).
     */
    protected BigDecimal parseAmount(CommandSender sender, String input, boolean allowZero) {
        Optional<BigDecimal> parsed = currency().parseAmount(input);
        if (parsed.isEmpty() || (!allowZero && parsed.get().signum() == 0)) {
            messages().send(sender, "invalid-amount", "input", input);
            return null;
        }

        BigDecimal amount = currency().normalize(parsed.get());
        BigDecimal maxBalance = currency().maxBalance();
        if (maxBalance != null && amount.compareTo(maxBalance) > 0) {
            messages().send(sender, "amount-too-large",
                    "max", currency().format(maxBalance),
                    "player", sender.getName());
            return null;
        }
        return amount;
    }

    /**
     * Vorschläge für einen Spielernamen: bei leerer oder einbuchstabiger
     * Eingabe sofort alle Online-Spieler, ab zwei Zeichen alle bekannten
     * Konten - auch von offline Spielern.
     */
    protected List<String> playerNameSuggestions(String prefix) {
        String value = prefix == null ? "" : prefix.trim();
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(onlinePlayerNames());
        if (value.length() >= 2) {
            names.addAll(economy.knownPlayerNames());
        }
        return filter(List.copyOf(names), value);
    }

    /**
     * Namen aller Spieler, die gerade auf diesem Server online sind.
     */
    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    protected static List<String> pageSuggestions(int pages) {
        List<String> suggestions = new ArrayList<>();
        for (int page = 1; page <= pages; page++) {
            suggestions.add(String.valueOf(page));
        }
        return suggestions;
    }

    protected static List<String> filter(List<String> options, String prefix) {
        String lowerPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option != null && !option.isBlank())
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    protected static String messageOf(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    protected static boolean matchesIgnoreCase(String input, String... options) {
        for (String option : options) {
            if (input.equalsIgnoreCase(option)) {
                return true;
            }
        }
        return false;
    }
}
