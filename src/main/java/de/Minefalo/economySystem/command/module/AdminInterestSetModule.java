package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.config.InterestType;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.command.CommandSender;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * {@code /eco admin interest set <savings|credit> <faktor>} - setzt einen
 * Zinsfaktor und speichert ihn in der {@code settings.yml}.
 */
public final class AdminInterestSetModule extends AbstractModule {

    public AdminInterestSetModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    public String id() {
        return "set";
    }

    @Override
    public String path() {
        return "admin.interest.set";
    }

    @Override
    public String usage() {
        return "/eco admin interest set <savings|credit> <faktor>";
    }

    @Override
    public CommandResult execute(CommandContext context) {
        CommandSender sender = context.sender();
        List<String> args = context.args().positional();
        if (args.size() != 2) {
            usageError(sender, String.join(" ", args));
            return CommandResult.handled();
        }

        InterestType type = parseType(args.get(0));
        if (type == null) {
            usageError(sender, args.get(0));
            return CommandResult.handled();
        }
        return apply(sender, type, args.get(1));
    }

    /**
     * Setzt den Faktor; wird auch von der bisherigen Schreibweise
     * {@code /economysystem-admin zinsen <typ> set <faktor>} genutzt.
     */
    CommandResult apply(CommandSender sender, InterestType type, String rawFactor) {
        BigDecimal factor = parseFactor(sender, rawFactor);
        if (factor == null) {
            return CommandResult.handled();
        }

        try {
            plugin.settings().setInterestFactor(type, factor);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Zinsfaktor konnte nicht gespeichert werden: "
                    + exception.getMessage());
            return CommandResult.rejected("settings-error");
        }

        if (type == InterestType.CREDIT) {
            messages().send(sender, "interest-credit-hint");
        }
        return CommandResult.ok("interest-set")
                .with("type", messages().raw(type.messageKey()))
                .with("factor", factor.stripTrailingZeros().toPlainString());
    }

    static InterestType parseType(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "spar", "sparen", "savings" -> InterestType.SAVINGS;
            case "kredit", "kredite", "credit" -> InterestType.CREDIT;
            default -> null;
        };
    }

    private BigDecimal parseFactor(CommandSender sender, String input) {
        String cleaned = input.trim().replace(',', '.');
        if (cleaned.isEmpty() || !cleaned.matches("[+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")) {
            messages().send(sender, "interest-factor-invalid");
            return null;
        }
        try {
            BigDecimal factor = new BigDecimal(cleaned);
            if (factor.signum() <= 0) {
                messages().send(sender, "interest-factor-invalid");
                return null;
            }
            return factor;
        } catch (NumberFormatException exception) {
            messages().send(sender, "interest-factor-invalid");
            return null;
        }
    }

    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.size() == 1) {
            return filter(List.of("savings", "credit"), raw.get(0));
        }
        if (raw.size() == 2) {
            return filter(List.of("1.3"), raw.get(1));
        }
        return List.of();
    }
}
