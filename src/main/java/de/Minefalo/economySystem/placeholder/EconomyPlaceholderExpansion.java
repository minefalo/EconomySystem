package de.Minefalo.economySystem.placeholder;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.storage.AccountData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * PlaceholderAPI-Erweiterung für Guthaben, Top-Liste und Sparzins.
 *
 * <p>Es werden mehrere Präfixe registriert, damit sowohl die kurzen
 * Schreibweisen als auch ein eindeutig benannter Präfix funktionieren:</p>
 *
 * <ul>
 *   <li>{@code %money_%} (eigenes Guthaben), {@code %money_<player>%}</li>
 *   <li>{@code %eco_top_<rank>%} (optional {@code _name}, {@code _raw})</li>
 *   <li>{@code %aktuelle_sparzinsen%} (optional {@code _<player>})</li>
 *   <li>{@code %economysystem_...%} als vollständige Variante</li>
 * </ul>
 */
public final class EconomyPlaceholderExpansion extends PlaceholderExpansion {

    private final EconomySystem plugin;
    private final PlaceholderData data;
    private final String identifier;

    public EconomyPlaceholderExpansion(EconomySystem plugin, PlaceholderData data, String identifier) {
        this.plugin = plugin;
        this.data = data;
        this.identifier = identifier;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getAuthor() {
        return "Minefalo";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String request = params == null ? "" : params.trim();
        return switch (identifier) {
            case "money" -> money(player, request);
            case "eco" -> top(request);
            case "aktuelle" -> savings(player, request);
            default -> economySystem(player, request);
        };
    }

    private String money(OfflinePlayer player, String request) {
        String name = request.isBlank() ? playerName(player) : request;
        return format(data.balance(name));
    }

    private String savings(OfflinePlayer player, String request) {
        String lower = request.toLowerCase(Locale.ROOT);
        if (lower.isBlank() || lower.equals("sparzinsen") || lower.equals("interest")) {
            return interest(playerName(player));
        }
        if (lower.startsWith("sparzinsen_")) {
            return interest(request.substring("sparzinsen_".length()));
        }
        return interest(request);
    }

    private String economySystem(OfflinePlayer player, String request) {
        String lower = request.toLowerCase(Locale.ROOT);
        if (lower.isBlank() || lower.equals("money") || lower.equals("balance")) {
            return money(player, "");
        }
        if (lower.startsWith("money_")) {
            return money(player, request.substring("money_".length()));
        }
        if (lower.startsWith("balance_")) {
            return money(player, request.substring("balance_".length()));
        }
        if (lower.equals("sparzinsen") || lower.equals("interest")
                || lower.equals("aktuelle_sparzinsen")) {
            return savings(player, "");
        }
        if (lower.startsWith("sparzinsen_")) {
            return savings(player, request);
        }
        if (lower.startsWith("top_")) {
            return top(request);
        }
        return "";
    }

    private String interest(String name) {
        return format(data.savingsInterest(data.balance(name)));
    }

    private String top(String request) {
        if (!request.regionMatches(true, 0, "top_", 0, 4)) {
            return "";
        }

        String[] parts = request.substring(4).split("_", 2);
        int rank;
        try {
            rank = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException exception) {
            return "";
        }

        AccountData entry = data.top(rank);
        if (entry == null) {
            return "";
        }

        String mode = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "balance";
        return switch (mode) {
            case "name", "player" -> entry.name();
            case "raw", "plain" -> entry.balance().toPlainString();
            case "balance", "value", "amount", "" -> format(entry.balance());
            default -> "";
        };
    }

    private String format(BigDecimal amount) {
        return amount == null ? "" : plugin.settings().currency().format(amount);
    }

    private static String playerName(OfflinePlayer player) {
        return player == null ? null : player.getName();
    }
}
