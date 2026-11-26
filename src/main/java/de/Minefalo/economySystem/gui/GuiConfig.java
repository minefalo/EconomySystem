package de.Minefalo.economySystem.gui;

import de.Minefalo.economySystem.config.ConfigFileWriter;
import de.Minefalo.economySystem.config.ConfigUpdater;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Liest die gui.yml und stellt die konfigurierten Menüs bereit.
 *
 * <p>Jedes Item hat einen Slot, ein Material, eine Permission und eine Liste
 * von Befehlen. Die Befehle werden beim Klick als Spieler ausgeführt.
 * Optional öffnet ein Item ein Untermenü ({@code open}) oder sammelt vorher
 * Eingaben über {@code inputs}: Zahlen und Texte über einen Amboss und
 * Spieler über eine Kopfauswahl.</p>
 */
public final class GuiConfig {

    private static final int MAX_ROWS = 6;
    private static final long MAX_NUMBER = 1_000_000_000_000L;

    /**
     * Ab dieser Version zeigt das Hauptmenü den Spielerkopf mit Guthaben-Infos
     * und einen eigenen Kredit-Button. Die abgelösten Einzel-Items werden
     * beim Update entfernt, damit die neuen Einträge greifen.
     */
    private static final int LAYOUT_VERSION = 4;
    private static final List<String> LEGACY_MAIN_ITEMS = List.of(
            "balance", "kredit-add", "kredit-info", "kredit-pay", "kredit-pay-part");

    private final JavaPlugin plugin;
    private final File file;

    private YamlConfiguration config;
    private boolean enabled;
    private boolean closeOnClick;
    private long clickCooldownMs;
    private String defaultMenu;
    private Map<String, Menu> menus = Map.of();

    public GuiConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = ConfigUpdater.file(plugin, "gui.yml");
        reload();
    }

    public void reload() {
        migrateLayout();
        ConfigUpdater.update(plugin, "gui.yml");
        config = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = ConfigUpdater.bundled(plugin, "gui.yml");
        if (defaults != null) {
            // Fehlende Schlüssel fallen auch dann auf die Standardwerte
            // zurück, wenn die Datei manuell bearbeitet wurde.
            config.setDefaults(defaults);
        }

        enabled = config.getBoolean("settings.enabled", true);
        closeOnClick = config.getBoolean("settings.close-on-click", true);
        clickCooldownMs = clamp(config.getLong("settings.click-cooldown-ms", 200L), 0L, 5000L);
        defaultMenu = config.getString("settings.default-menu", "main");
        menus = parseMenus();
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean closeOnClick() {
        return closeOnClick;
    }

    /**
     * Mindestabstand zwischen zwei Klicks auf dasselbe Item in
     * Millisekunden. {@code 0} deaktiviert den Filter.
     */
    public long clickCooldownMs() {
        return clickCooldownMs;
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
        String title = section.getString("title", "<dark_gray><bold>" + id);
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
                    itemSection.getString("name", "<white>" + key),
                    lines(itemSection, "lore"),
                    itemSection.getString("permission"),
                    commands(itemSection),
                    inputs(itemSection),
                    openTarget(itemSection),
                    headTarget(itemSection)));
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

    /**
     * {@code head: player} zeigt den Kopf des Spielers, der das Menü öffnet.
     */
    private static String headTarget(ConfigurationSection section) {
        String head = section.getString("head");
        if (head == null || head.isBlank()) {
            return null;
        }
        return head.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Entfernt beim Wechsel auf {@link #LAYOUT_VERSION} die abgelösten Items
     * des Hauptmenüs. Nur diese Schlüssel werden gelöscht; alle anderen Menüs
     * und Items bleiben unverändert. Anschließend ergänzt der
     * {@link ConfigUpdater} die neuen Standardeinträge.
     */
    private void migrateLayout() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration existing = YamlConfiguration.loadConfiguration(file);
        if (ConfigUpdater.version(plugin, "gui.yml") >= LAYOUT_VERSION) {
            return;
        }
        ConfigurationSection items = existing.getConfigurationSection("menus.main.items");
        if (items == null) {
            return;
        }

        boolean changed = false;
        for (String key : LEGACY_MAIN_ITEMS) {
            if (items.contains(key)) {
                items.set(key, null);
                changed = true;
            }
        }
        if (!changed) {
            return;
        }

        try {
            String before = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            ConfigFileWriter.writeIfChanged(file.toPath(), before, existing.saveToString(),
                    ConfigUpdater.backupFolder(plugin).toPath(), plugin.getLogger());
        } catch (IOException exception) {
            plugin.getLogger().warning("gui.yml konnte nicht auf das neue Layout umgestellt werden: "
                    + exception.getMessage());
        }
    }

    /**
     * Liest die typisierten Eingaben eines Items. Ältere Dateien mit
     * {@code prompts} werden als Text-Eingaben (Amboss) unterstützt.
     */
    private List<Input> inputs(ConfigurationSection item) {
        List<Input> result = new ArrayList<>();
        for (Map<?, ?> map : item.getMapList("inputs")) {
            result.add(parseInput(map));
        }
        if (result.isEmpty()) {
            for (String prompt : item.getStringList("prompts")) {
                if (prompt != null && !prompt.isBlank()) {
                    result.add(Input.text(prompt));
                }
            }
        }
        return List.copyOf(result);
    }

    private Input parseInput(Map<?, ?> map) {
        String type = text(map.get("type"), "text").trim().toLowerCase(Locale.ROOT);
        String prompt = text(map.get("prompt"), "<gray>Eingabe");

        return switch (type) {
            case "number", "zahl" -> numberInput(map, prompt);
            case "player", "spieler" -> new Input(Input.Type.PLAYER, prompt,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    filter(text(map.get("filter"), "all")),
                    sort(text(map.get("sort"), "name-asc")),
                    null);
            default -> Input.text(prompt, pattern(map.get("pattern")));
        };
    }

    private Input numberInput(Map<?, ?> map, String prompt) {
        BigDecimal limit = BigDecimal.valueOf(MAX_NUMBER);
        BigDecimal min = decimal(map.get("min"), BigDecimal.ZERO)
                .max(limit.negate())
                .min(limit);
        BigDecimal max = decimal(map.get("max"), min.add(BigDecimal.ONE).max(BigDecimal.valueOf(1000)))
                .min(limit);
        if (max.compareTo(min) <= 0) {
            max = min.add(BigDecimal.ONE).min(limit);
        }
        return new Input(Input.Type.NUMBER, prompt, min, max,
                Input.Filter.ALL, Input.Sort.NAME_ASC, null);
    }

    /**
     * Antwortmuster einer Text-Eingabe ({@code pattern}); {@code null}, wenn
     * keines konfiguriert ist. Ein ungültiger Ausdruck wird gemeldet und
     * ignoriert, statt den Start zu verhindern.
     */
    private Pattern pattern(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException exception) {
            plugin.getLogger().warning("gui.yml: Ungültiges pattern '" + value + "': "
                    + exception.getDescription());
            return null;
        }
    }

    private static Input.Filter filter(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.equals("online") || value.equals("nur-online")
                ? Input.Filter.ONLINE
                : Input.Filter.ALL;
    }

    private static Input.Sort sort(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "name-desc", "name-z-a" -> Input.Sort.NAME_DESC;
            case "balance", "balance-desc", "guthaben-desc" -> Input.Sort.BALANCE_DESC;
            case "balance-asc", "guthaben-asc" -> Input.Sort.BALANCE_ASC;
            default -> Input.Sort.NAME_ASC;
        };
    }

    private static String text(Object raw, String fallback) {
        return raw == null ? fallback : String.valueOf(raw);
    }

    /**
     * Liest einen Dezimalwert aus der Konfiguration. Damit dürfen
     * {@code min} und {@code max} einer Zahleneingabe auch Cent-Beträge wie
     * {@code 0.01} sein; ein Komma wird wie ein Punkt behandelt.
     */
    private static BigDecimal decimal(Object raw, BigDecimal fallback) {
        if (raw instanceof BigDecimal value) {
            return value;
        }
        if (raw instanceof Number value) {
            try {
                return new BigDecimal(value.toString());
            } catch (NumberFormatException ignored) {
                // Fällt unten auf den Standardwert zurück.
            }
        }
        if (raw instanceof String value) {
            try {
                return new BigDecimal(value.trim().replace(',', '.'));
            } catch (NumberFormatException ignored) {
                // Fällt unten auf den Standardwert zurück.
            }
        }
        return fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
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
     * @param inputs     Eingaben vor dem Ausführen ({@code {input1}}, ...)
     * @param open       Zielmenü oder {@code null}
     * @param head       {@code player} für den Kopf des Betrachters, sonst {@code null}
     */
    public record Item(String key,
                       int slot,
                       Material material,
                       int amount,
                       String name,
                       List<String> lore,
                       String permission,
                       List<String> commands,
                       List<Input> inputs,
                       String open,
                       String head) {

        public boolean isSubmenu() {
            return open != null && !open.isBlank();
        }

        /**
         * Ob das Item den Kopf des Spielers zeigt, der das Menü geöffnet hat.
         */
        public boolean isPlayerHead() {
            return "player".equals(head) || "self".equals(head);
        }

        /**
         * Ob das Item nur etwas anzeigt und auf Klicks nicht reagiert.
         */
        public boolean isDisplayOnly() {
            return !isSubmenu() && commands.isEmpty();
        }
    }

    /**
     * Eine Eingabe vor dem Ausführen der Befehle.
     *
     * @param type    Art der Eingabe (Zahl, Text oder Spieler)
     * @param prompt  Titel bzw. Frage der Eingabe
     * @param min     kleinster Wert (nur {@link Type#NUMBER})
     * @param max     größter Wert (nur {@link Type#NUMBER})
     * @param filter  Startfilter der Spielerauswahl
     * @param sort    Startsortierung der Spielerauswahl
     * @param pattern Antwortmuster einer Text-Eingabe oder {@code null}
     */
    public record Input(Type type,
                        String prompt,
                        BigDecimal min,
                        BigDecimal max,
                        Filter filter,
                        Sort sort,
                        Pattern pattern) {

        public enum Type {
            NUMBER,
            TEXT,
            PLAYER
        }

        public enum Filter {
            ALL,
            ONLINE
        }

        public enum Sort {
            NAME_ASC,
            NAME_DESC,
            BALANCE_DESC,
            BALANCE_ASC
        }

        public static Input text(String prompt) {
            return text(prompt, null);
        }

        public static Input text(String prompt, Pattern pattern) {
            return new Input(Type.TEXT, prompt, BigDecimal.ZERO, BigDecimal.ZERO,
                    Filter.ALL, Sort.NAME_ASC, pattern);
        }
    }

    /**
     * Füllmaterial für leere Slots.
     */
    public record Fill(Material material, String name) {
    }
}
