package de.Minefalo.economySystem.vault;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.Settings;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.ServicePriority;

/**
 * Registriert EconomySystem als Vault-Wirtschaftsanbieter. Diese Klasse wird
 * nur geladen, wenn Vault installiert und die Anbindung aktiviert ist.
 */
public final class VaultHook {

    private final EconomySystem plugin;
    private VaultEconomy provider;

    public VaultHook(EconomySystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Meldet den Anbieter mit der konfigurierten Priorität an.
     */
    public void register() {
        unregister();
        provider = new VaultEconomy(plugin);
        plugin.getServer().getServicesManager().register(
                Economy.class, provider, plugin, priority(plugin.settings().vault()));
    }

    /**
     * Entfernt die Registrierung, z. B. beim Deaktivieren oder Reload.
     */
    public void unregister() {
        if (provider == null) {
            return;
        }
        plugin.getServer().getServicesManager().unregister(Economy.class, provider);
        provider = null;
    }

    /**
     * Bildet die Konfigurationswerte auf die Bukkit-Service-Prioritäten ab.
     * Paper benennt die Konstanten seit 26.3 in gemischter Schreibweise
     * ({@code High} statt {@code HIGH}).
     */
    private static ServicePriority priority(Settings.Vault vault) {
        return switch (vault.priority()) {
            case LOWEST -> ServicePriority.Lowest;
            case LOW -> ServicePriority.Low;
            case NORMAL -> ServicePriority.Normal;
            case HIGH -> ServicePriority.High;
            case HIGHEST -> ServicePriority.Highest;
        };
    }
}
