package de.Minefalo.economySystem.config;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Liest die {@code roles.yml}, in der Permission-Knoten als Rollen mit einer
 * Liste weiterer Permissions vordefiniert werden.
 *
 * <p>Eine Rolle ist selbst ein Permission-Knoten: Wer
 * {@code economysystem.role.admin} besitzt, erhält bei den Rechteprüfungen
 * dieses Plugins zusätzlich alle unter {@code permissions} eingetragenen
 * Knoten. Die Rollen ergänzen die normalen Bukkit-Permissions, sie ersetzen
 * sie nicht und werden auch nicht automatisch vergeben.</p>
 */
public final class RolesConfig {

    /** Erlaubte Schreibweise eines Rollen- oder Permission-Knotens. */
    private static final String NODE_PATTERN = "[a-z0-9._-]+";

    /** Maximale Verschachtelungstiefe von Rollen, die auf Rollen verweisen. */
    private static final int MAX_DEPTH = 10;

    private final JavaPlugin plugin;
    private final File file;
    private Map<String, Set<String>> roles = Map.of();

    public RolesConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = ConfigUpdater.file(plugin, "roles.yml");
        reload();
    }

    /**
     * Lädt die Datei neu. Fehlende Schlüssel kommen aus der Standarddatei,
     * vorhandene Rollen bleiben unverändert.
     */
    public void reload() {
        ConfigUpdater.update(plugin, "roles.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = ConfigUpdater.bundled(plugin, "roles.yml");
        if (defaults != null) {
            config.setDefaults(defaults);
        }
        roles = resolve(readRoles(config),
                message -> plugin.getLogger().warning("roles.yml: " + message));
    }

    /**
     * Ob eine der Rollen des Aufrufers die Permission enthält. Die Rollen
     * selbst werden über die normalen Bukkit-Permissions geprüft.
     */
    public boolean grants(CommandSender sender, String permission) {
        String node = normalize(permission);
        if (node.isEmpty() || roles.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Set<String>> role : roles.entrySet()) {
            if (!sender.hasPermission(role.getKey())) {
                continue;
            }
            for (String granted : role.getValue()) {
                if (matches(granted, node)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Rohdaten der Rollen: Rollen-Knoten → Permissions oder Rollen-Verweise.
     * Schlüssel mit Punkten werden über {@link ConfigurationSection#getValues}
     * gelesen, weil die Pfadzugriffe von Bukkit am Punkt trennen würden.
     */
    private Map<String, List<String>> readRoles(YamlConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("roles");
        Map<String, List<String>> raw = new LinkedHashMap<>();
        if (section == null) {
            return raw;
        }
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            String role = normalize(entry.getKey());
            if (!role.matches(NODE_PATTERN)) {
                plugin.getLogger().warning("roles.yml: '" + entry.getKey()
                        + "' ist kein gültiger Rollen-Knoten und wird übersprungen"
                        + " (erlaubt sind a-z, 0-9, . _ und -).");
                continue;
            }
            List<String> permissions = permissionsOf(role, entry.getValue());
            if (permissions == null) {
                continue;
            }
            List<String> cleaned = new ArrayList<>();
            for (String rawPermission : permissions) {
                String node = normalize(rawPermission);
                if (!isValidPermission(node)) {
                    plugin.getLogger().warning("roles.yml: Rolle '" + role
                            + "' enthält den ungültigen Eintrag '" + rawPermission
                            + "' - er wird übersprungen.");
                    continue;
                }
                cleaned.add(node);
            }
            if (cleaned.isEmpty()) {
                plugin.getLogger().warning("roles.yml: Rolle '" + role
                        + "' hat keine Permissions und bleibt wirkungslos.");
                continue;
            }
            if (raw.putIfAbsent(role, cleaned) != null) {
                plugin.getLogger().warning("roles.yml: Rolle '" + role
                        + "' ist doppelt vorhanden; der erste Eintrag gilt.");
            }
        }
        return raw;
    }

    /**
     * Liest die Permissions einer Rolle: als Liste unter {@code permissions}
     * oder als direkt unter der Rolle stehende Liste.
     */
    private List<String> permissionsOf(String role, Object value) {
        List<?> values;
        if (value instanceof ConfigurationSection roleSection) {
            values = roleSection.getList("permissions");
        } else if (value instanceof List<?> list) {
            values = list;
        } else {
            plugin.getLogger().warning("roles.yml: Rolle '" + role
                    + "' braucht eine Liste von Permissions und wird übersprungen.");
            return null;
        }
        if (values == null || values.isEmpty()) {
            plugin.getLogger().warning("roles.yml: Rolle '" + role
                    + "' hat keine Permissions und bleibt wirkungslos.");
            return null;
        }
        List<String> permissions = new ArrayList<>(values.size());
        for (Object entry : values) {
            permissions.add(entry == null ? "" : String.valueOf(entry));
        }
        return permissions;
    }

    /**
     * Löst alle Rollen auf. Verweise auf andere Rollen werden übernommen;
     * Zyklen und zu tiefe Verschachtelungen werden gemeldet und übersprungen.
     */
    static Map<String, Set<String>> resolve(Map<String, List<String>> raw,
                                            Consumer<String> warning) {
        Map<String, Set<String>> resolved = new LinkedHashMap<>();
        for (String role : raw.keySet()) {
            resolved.put(role, expand(role, raw, new LinkedHashSet<>(), warning));
        }
        return Map.copyOf(resolved);
    }

    private static Set<String> expand(String role, Map<String, List<String>> raw,
                                      Set<String> chain, Consumer<String> warning) {
        if (!chain.add(role)) {
            warning.accept("Rolle '" + role + "' ist Teil eines Rollen-Zyklus"
                    + " und wird an dieser Stelle übersprungen.");
            return Set.of();
        }
        if (chain.size() > MAX_DEPTH) {
            warning.accept("Rolle '" + role + "' überschreitet die maximale"
                    + " Verschachtelungstiefe von " + MAX_DEPTH + " Ebenen"
                    + " und wird an dieser Stelle übersprungen.");
            chain.remove(role);
            return Set.of();
        }

        Set<String> permissions = new LinkedHashSet<>();
        for (String node : raw.getOrDefault(role, List.of())) {
            if (!raw.containsKey(node)) {
                permissions.add(node);
            } else if (chain.contains(node)) {
                warning.accept("Rolle '" + role + "' verweist im Kreis auf '"
                        + node + "'; der Verweis wird übersprungen.");
            } else {
                permissions.addAll(expand(node, raw, chain, warning));
            }
        }
        chain.remove(role);
        return permissions;
    }

    /**
     * Ob ein eingetragener Knoten die gesuchte Permission abdeckt. Neben
     * exakten Knoten gelten {@code *} und Endungen auf {@code .*}.
     */
    static boolean matches(String granted, String node) {
        if (granted.equals("*")) {
            return true;
        }
        if (granted.endsWith(".*")) {
            return node.startsWith(granted.substring(0, granted.length() - 1));
        }
        return granted.equals(node);
    }

    private static boolean isValidPermission(String node) {
        if (node.isEmpty()) {
            return false;
        }
        if (node.equals("*")) {
            return true;
        }
        String base = node.endsWith(".*")
                ? node.substring(0, node.length() - 2)
                : node;
        return base.matches(NODE_PATTERN);
    }

    private static String normalize(String node) {
        return node == null ? "" : node.trim().toLowerCase(Locale.ROOT);
    }
}
