package de.Minefalo.economySystem.permission;

import de.Minefalo.economySystem.config.RolesConfig;
import org.bukkit.command.CommandSender;

/**
 * Einzige Stelle, an der das Plugin entscheidet, ob ein Aufrufer eine
 * Permission besitzt.
 *
 * <p>Geprüft wird zuerst die normale Bukkit-Permission und danach, ob eine in
 * der {@code roles.yml} definierte Rolle des Aufrufers die Permission
 * mitbringt. So wirken Rollen in Befehlen, GUI, Listenern und Scheduler
 * gleich.</p>
 */
public final class PermissionService {

    private final RolesConfig roles;

    public PermissionService(RolesConfig roles) {
        this.roles = roles;
    }

    /**
     * @return {@code true}, wenn die Permission direkt oder über eine Rolle
     *         vergeben ist; ein leerer Knoten bedeutet „keine Prüfung nötig“
     */
    public boolean has(CommandSender sender, String permission) {
        if (sender == null) {
            return false;
        }
        if (permission == null || permission.isBlank()) {
            return true;
        }
        return sender.hasPermission(permission) || roles.grants(sender, permission);
    }
}
