package de.Minefalo.economySystem.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Wandelt Konfigurationstexte im MiniMessage-Markup in Adventure-Komponenten um.
 * Unterstützt Farben ({@code <red>}, {@code <#RRGGBB>}), Formatierungen
 * ({@code <bold>}, {@code <italic>}, ...) sowie {@code <reset>}.
 */
public final class Text {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private Text() {
    }

    public static Component component(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        return MINI_MESSAGE.deserialize(input);
    }
}
