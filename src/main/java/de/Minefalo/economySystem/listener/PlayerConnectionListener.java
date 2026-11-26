package de.Minefalo.economySystem.listener;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandPermissions;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.economy.EconomyService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerConnectionListener implements Listener {

    private final EconomySystem plugin;
    private final EconomyService economy;

    public PlayerConnectionListener(EconomySystem plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        economy.preload(player);

        Settings.Notifications notifications = plugin.settings().notifications();
        if (!notifications.balanceOnJoin()
                || !CommandPermissions.granted(plugin.permissions(), player,
                        "economysystem.balance")) {
            return;
        }

        // Kurz warten, damit Join-Nachrichten anderer Plugins nicht direkt
        // von der Guthabenzeile verdrängt werden.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            economy.accountAsync(player.getUniqueId(), player.getName())
                    .whenComplete((account, error) -> {
                        if (error != null || account == null || !player.isOnline()) {
                            return;
                        }
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            plugin.messages().send(player, "balance-on-join",
                                    "player", player.getName(),
                                    "balance", plugin.settings().currency().format(account.balance()),
                                    "currency", plugin.settings().currency().name());
                        });
                    });
        }, Math.max(0, notifications.balanceOnJoinDelayTicks()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        economy.handleQuit(event.getPlayer());
    }
}
