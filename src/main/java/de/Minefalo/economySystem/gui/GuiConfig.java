package de.Minefalo.economySystem.gui;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Liest die gui.yml und stellt die konfigurierten Menüs bereit.
 *
 * <p>Jedes Item hat einen Slot, ein Material, eine Permission und eine Liste
 * von Befehlen. Die Befehle werden beim Klick als Spieler ausgeführt.
 * Optional öffnet ein Item ein Untermenü ({@code open}) oder fragt vorher
 * Werte im Chat ab ({@code prompts}).</p>
 */
public final class GuiConfig {

    private static final int MAX_ROWS = 6;

    private final JavaPlugin plugin;
    private final File file;

    private YamlConfiguration config;
    private boolean enabled;
    private boolean closeOnClick;
    private String defaultMenu;
    private String inputHint;
    private Map<String, Menu> menus = Map.of();

    public GuiConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "gui.yml");
        reload();
    }

    public void reload() {
        if (!file.exists()) {
            plugin.saveResource("gui.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = loadDefaults();
        if (defaults != null) {
            // Fehlende Schlüssel fallen auf die Standardwerte zurück, ohne die
            // Datei (und damit ihre Kommentare) neu zu schreiben.
            config.setDefaults(defaults);
        }

        enabled = config.getBoolean("settings.enabled", true);
        closeOnClick = config.getBoolean("settings.close-on-click", true);
        defaultMenu = config.getString("settings.default-menu", "main");
        inputHint = config.getString("settings.input-hint", "");
        menus = parseMenus();
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean closeOnClick() {
        return closeOnClick;
    }

    public String inputHint() {
        return inputHint;
    }

    /**
     * Menü, das ohne Argument geöffnet wird. Existiert es nicht, wird das
     * erste konfigurierte Menü verwendet.
     */
    public String defaultMenu() {
        if (menus.containsKey(defaultMenu)) {
            return defaultMenu;
        }
        return menus.keySet().stream().findFirst().orElse(defaultMenu);
    }

    public Map<String, Menu> menus() {
        return menus;
    }

    public Optional<Menu> menu(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(menus.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    private Map<String, Menu> parseMenus() {
        ConfigurationSection root = config.getConfigurationSection("menus");
        if (root == null) {
            plugin.getLogger().warning("gui.yml enthält keine Menüs (menus).");
            return Map.of();
        }

        Map<String, Menu> parsed = new LinkedHashMap<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            Menu menu = parseMenu(key.toLowerCase(Locale.ROOT), section);
            if (menu != null) {
                parsed.put(menu.id(), menu);
            }
        }

        for (Menu menu : parsed.values()) {
            for (Item item : menu.items().values()) {
                if (item.isSubmenu() && !parsed.containsKey(item.open())) {
                    plugin.getLogger().warning("gui.yml: Item '" + item.key() + "' in Menü '" + menu.id()
                            + "' verweist auf unbekanntes Menü '" + item.open() + "'.");
                }
            }
        }

        if (parsed.isEmpty()) {
            plugin.getLogger().warning("gui.yml enthält keine gültigen Menüs.");
        }
        // Reihenfolge beibehalten: bestimmt das Standardmenü und die Tab-Liste.
        return Collections.unmodifiableMap(parsed);
    }

    private Menu parseMenu(String id, ConfigurationSection section) {
        int rows = clamp(section.getInt("rows", MAX_ROWS), 1, MAX_ROWS);
        String title = section.getString("title", "&8&l" + id);
        String permission = section.getString("permission");
        Fill fill = parseFill(section.getConfigurationSection("fill"));
        Map<Integer, Item> items = parseItems(id, section.getConfigurationSection("items"), rows * 9);
        return new Menu(id, title, rows, permission, fill, items);
    }

    private Map<Integer, Item> parseItems(String menuId, ConfigurationSection section, int size) {
        if (section == null) {
            return Map.of();
        }

        Map<Integer, Item> items = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection itemSection = section.getConfigurationSection(key);
            if (itemSection == null) {
                continue;
            }

            int slot = itemSection.getInt("slot", -1);
            if (slot < 0 || slot >= size) {
                plugin.getLogger().warning("gui.yml: Item '" + key + "' in Menü '" + menuId
                        + "' hat einen ungültigen Slot (" + slot + ").");
                continue;
            }
            if (items.containsKey(slot)) {
                plugin.getLogger().warning("gui.yml: Slot " + slot + " in Menü '" + menuId
                        + "' ist doppelt belegt - Item '" + key + "' wird ignoriert.");
                continue;
            }

            Material material = matchMaterial(itemSection.getString("material"));
            if (material == null) {
                plugin.getLogger().warning("gui.yml: Item '" + key + "' in Menü '" + menuId
                        + "' hat kein gültiges Material.");
                continue;
            }

            items.put(slot, new Item(
                    key,
                    slot,
                    material,
                    clamp(itemSection.getInt("amount", 1), 1, 64),
                    itemSection.getString("name", "&f" + key),
                    lines(itemSection, "lore"),
                    itemSection.getString("permission"),
                    commands(itemSection),
                    lines(itemSection, "prompts"),
                    openTarget(itemSection)));
        }
        return Collections.unmodifiableMap(items);
    }

    private Fill parseFill(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        Material material = matchMaterial(section.getString("material"));
        if (material == null) {
            return null;
        }
        return new Fill(material, section.getString("name", " "));
    }

    private Material matchMaterial(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(raw.trim());
        if (material == null || !material.isItem()) {
            plugin.getLogger().warning("gui.yml: Unbekanntes Material '" + raw + "'.");
            return null;
        }
        return material;
    }

    private static List<String> lines(ConfigurationSection section, String path) {
        List<String> result = new ArrayList<>();
        for (String raw : section.getStringList(path)) {
            if (raw != null && !raw.isBlank()) {
                result.add(raw);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> commands(ConfigurationSection section) {
        List<String> result = new ArrayList<>();
        for (String raw : section.getStringList("commands")) {
            if (raw == null) {
                continue;
            }
            String command = raw.trim();
            while (command.startsWith("/")) {
                command = command.substring(1).trim();
            }
            if (!command.isEmpty()) {
                result.add(command);
            }
        }
        return List.copyOf(result);
    }

    private static String openTarget(ConfigurationSection section) {
        String open = section.getString("open");
        if (open == null || open.isBlank()) {
            return null;
        }
        return open.trim().toLowerCase(Locale.ROOT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private YamlConfiguration loadDefaults() {
        try (InputStream input = plugin.getResource("gui.yml")) {
            if (input == null) {
                return null;
            }
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return defaults;
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("Standard-gui.yml konnte nicht geladen werden: "
                    + exception.getMessage());
            return null;
        }
    }

    /**
     * Ein Menü mit Titel, Größe und Items.
     */
    public record Menu(String id,
                       String title,
                       int rows,
                       String permission,
                       Fill fill,
                       Map<Integer, Item> items) {

        public int size() {
            return rows * 9;
        }
    }

    /**
     * Ein klickbares Item.
     *
     * @param key        Konfigurationsschlüssel (nur für Meldungen)
     * @param slot       Zielslot im Inventar
     * @param material   Anzeige-Material
     * @param amount     Stack-Größe
     * @param name       Anzeigename (Farbcodes erlaubt)
     * @param lore       Beschreibung
     * @param permission benötigte Permission oder {@code null}
     * @param commands   Befehle, die als Spieler ausgeführt werden
     * @param prompts    Chat-Abfragen; {@code {input1}}, {@code {input2}} usw.
     * @param open       Zielmenü oder {@code null}
     */
    public record Item(String key,
                       int slot,
                       Material material,
                       int amount,
                       String name,
                       List<String> lore,
                       String permission,
                       List<String> commands,
                       List<String> prompts,
                       String open) {

        public boolean isSubmenu() {
            return open != null && !open.isBlank();
        }
    }

    /**
     * Füllmaterial für leere Slots.
     */
    public record Fill(Material material, String name) {
    }
}
