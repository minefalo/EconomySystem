package de.Minefalo.economySystem.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wandelt Konfigurationstexte in Adventure-Komponenten um.
 * Unterstützt die klassischen {@code &}-Farbcodes sowie Hex-Farben
 * im Format {@code &#RRGGBB}.
 */
public final class Text {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private Text() {
    }

    /**
     * Wandelt Hex-Farben in das von Adventure verstandene
     * {@code &x&R&R&G&G&B&B}-Format um.
     */
    public static String colorize(String input) {
        if (input == null) {
            return "";
        }
        return convertHex(input);
    }

    public static Component component(String input) {
        return SERIALIZER.deserialize(colorize(input));
    }

    private static String convertHex(String input) {
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            StringBuilder replacement = new StringBuilder("&x");
            for (char character : matcher.group(1).toCharArray()) {
                replacement.append('&').append(character);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
