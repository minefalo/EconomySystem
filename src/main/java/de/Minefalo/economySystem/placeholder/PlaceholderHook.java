package de.Minefalo.economySystem.placeholder;

import de.Minefalo.economySystem.EconomySystem;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import java.util.ArrayList;
import java.util.List;

/**
 * Registriert die PlaceholderAPI-Erweiterungen. Diese Klasse wird nur
 * geladen, wenn PlaceholderAPI auf dem Server installiert ist.
 */
public final class PlaceholderHook {

    private final EconomySystem plugin;
    private final PlaceholderData data;
    private final List<PlaceholderExpansion> expansions = new ArrayList<>();

    public PlaceholderHook(EconomySystem plugin) {
        this.plugin = plugin;
        this.data = new PlaceholderData(plugin);
    }

    /**
     * Registriert alle Präfixe und startet die Hintergrund-Aktualisierung.
     */
    public void register() {
        data.start();
        register("economysystem");
        register("money");
        register("eco");
        register("aktuelle");
    }

    private void register(String identifier) {
        EconomyPlaceholderExpansion expansion =
                new EconomyPlaceholderExpansion(plugin, data, identifier);
        if (expansion.register()) {
            expansions.add(expansion);
        } else {
            plugin.getLogger().warning("PlaceholderAPI: Das Präfix '" + identifier
                    + "' ist bereits belegt und wird übersprungen.");
        }
    }

    /**
     * Meldet alle Erweiterungen ab, z. B. beim Deaktivieren des Plugins.
     */
    public void unregister() {
        data.stop();
        for (PlaceholderExpansion expansion : expansions) {
            expansion.unregister();
        }
        expansions.clear();
    }

    /**
     * Verwirft zwischengespeicherte Werte nach einem Konfigurations-Reload.
     */
    public void reload() {
        data.clear();
    }
}
