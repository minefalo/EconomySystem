package de.Minefalo.economySystem.gui;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Öffnet die konfigurierten GUI-Menüs und führt Klicks aus.
 *
 * <p>Klicks auf Items lösen ausschließlich Befehle aus, die vom Spieler
 * selbst ausgeführt werden ({@link Player#performCommand(String)}). Items
 * ohne passende Permission werden nicht angezeigt. Items mit
 * {@code prompts} fragen vor dem Ausführen Werte im Chat ab.</p>
 */
public final class GuiManager implements Listener {

    private final EconomySystem plugin;
    private final GuiConfig config;
    private final Map<UUID, PendingInput> inputs = new ConcurrentHashMap<>();

    public GuiManager(EconomySystem plugin) {
        this.plugin = plugin;
        this.config = new GuiConfig(plugin);
    }

    public void reload() {
        config.reload();
        inputs.clear();
    }

    public void stop() {
        inputs.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder) {
                player.closeInventory();
            }
        }
    }

    /**
     * Öffnet ein Menü für den Spieler.
     *
     * @return {@code false}, wenn das Menü fehlt, deaktiviert ist oder die
     *         Permission fehlt
     */
    public boolean open(Player player, String menuId) {
        if (!config.enabled()) {
            plugin.messages().send(player, "gui-disabled");
            return false;
        }

        GuiConfig.Menu menu = config.menu(menuId).orElse(null);
        if (menu == null) {
            plugin.messages().send(player, "gui-not-found", "menu", menuId);
            return false;
        }
        if (!hasAccess(player, menu.permission())) {
            plugin.messages().send(player, "no-permission");
            return false;
        }

        render(player, menu);
        return true;
    }

    /**
     * Menü, das ohne Argument geöffnet wird.
     */
    public String defaultMenu() {
        return config.defaultMenu();
    }

    /**
     * Menüs, die der Spieler öffnen darf (z. B. für Tab-Vervollständigung).
     */
    public List<String> menuIds(Player player) {
        List<String> result = new ArrayList<>();
        for (GuiConfig.Menu menu : config.menus().values()) {
            if (hasAccess(player, menu.permission())) {
                result.add(menu.id());
            }
        }
        return result;
    }

    private void render(Player player, GuiConfig.Menu menu) {
        GuiHolder holder = new GuiHolder(menu.id());
        Inventory inventory = Bukkit.createInventory(holder, menu.size(), Text.component(menu.title()));
        holder.inventory(inventory);

        ItemStack fill = menu.fill() == null ? null : createFill(menu.fill());
        for (int slot = 0; slot < menu.size(); slot++) {
            GuiConfig.Item item = menu.items().get(slot);
            if (item != null && hasAccess(player, item.permission())) {
                inventory.setItem(slot, createItem(item));
            } else if (fill != null) {
                inventory.setItem(slot, fill.clone());
            }
        }

        player.openInventory(inventory);
    }

    private ItemStack createItem(GuiConfig.Item item) {
        ItemStack stack = new ItemStack(item.material(), item.amount());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.component(item.name()));
            if (!item.lore().isEmpty()) {
                meta.lore(item.lore().stream().map(Text::component).toList());
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack createFill(GuiConfig.Fill fill) {
        ItemStack stack = new ItemStack(fill.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String name = fill.name();
            meta.displayName(name == null || name.isBlank() ? Component.empty() : Text.component(name));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GuiHolder holder)) {
            return;
        }
        // Klicks in unseren Menüs gehören dem Plugin, nicht dem Spieler.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        GuiConfig.Menu menu = config.menu(holder.menuId()).orElse(null);
        if (menu == null) {
            return;
        }
        GuiConfig.Item item = menu.items().get(event.getSlot());
        if (item == null || !hasAccess(player, item.permission())) {
            return;
        }
        handleClick(player, item);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inputs.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingInput pending = inputs.get(player.getUniqueId());
        if (pending == null) {
            return;
        }
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> handleInput(player, pending, input));
    }

    private void handleClick(Player player, GuiConfig.Item item) {
        if (item.isSubmenu()) {
            // Erst im nächsten Tick wechseln, damit der Klick sauber beendet ist.
            plugin.getServer().getScheduler().runTask(plugin, () -> open(player, item.open()));
            return;
        }
        if (item.commands().isEmpty()) {
            return;
        }
        if (!item.prompts().isEmpty()) {
            startPrompt(player, item);
            return;
        }
        if (config.closeOnClick()) {
            player.closeInventory();
        }
        runCommands(player, item.commands());
    }

    private void startPrompt(Player player, GuiConfig.Item item) {
        player.closeInventory();
        PendingInput pending = new PendingInput(item.prompts(), item.commands());
        inputs.put(player.getUniqueId(), pending);
        plugin.getServer().getScheduler().runTask(plugin, () -> prompt(player, pending));
    }

    private void prompt(Player player, PendingInput pending) {
        if (!player.isOnline() || inputs.get(player.getUniqueId()) != pending) {
            return;
        }
        if (pending.answers.isEmpty() && !config.inputHint().isBlank()) {
            player.sendMessage(Text.component(config.inputHint()));
        }
        player.sendMessage(Text.component(pending.prompts.get(pending.answers.size())));
    }

    private void handleInput(Player player, PendingInput pending, String input) {
        if (!player.isOnline() || inputs.get(player.getUniqueId()) != pending) {
            return;
        }
        if (input.isBlank() || input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("abbrechen")) {
            inputs.remove(player.getUniqueId());
            plugin.messages().send(player, "gui-input-cancelled");
            return;
        }

        pending.answers.add(input);
        if (pending.answers.size() < pending.prompts.size()) {
            prompt(player, pending);
            return;
        }

        inputs.remove(player.getUniqueId());
        runCommands(player, substitute(pending.commands, pending.answers, player.getName()));
    }

    private void runCommands(Player player, List<String> commands) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            for (String command : commands) {
                player.performCommand(command);
            }
        });
    }

    /**
     * Ersetzt {@code {player}}, {@code {input}} (alle Antworten) sowie
     * {@code {input1}}, {@code {input2}} usw. (einzelne Antworten).
     */
    private static List<String> substitute(List<String> templates, List<String> answers, String playerName) {
        String joined = String.join(" ", answers);
        List<String> result = new ArrayList<>(templates.size());
        for (String template : templates) {
            String command = template.replace("{player}", playerName).replace("{input}", joined);
            for (int index = 0; index < answers.size(); index++) {
                command = command.replace("{input" + (index + 1) + "}", answers.get(index));
            }
            result.add(command);
        }
        return result;
    }

    private static boolean hasAccess(Player player, String permission) {
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    /**
     * Kennzeichnet Inventare, die von diesem Plugin geöffnet wurden.
     */
    private static final class GuiHolder implements InventoryHolder {

        private final String menuId;
        private Inventory inventory;

        private GuiHolder(String menuId) {
            this.menuId = menuId;
        }

        private String menuId() {
            return menuId;
        }

        private void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /**
     * Laufende Chat-Abfrage eines Items.
     */
    private static final class PendingInput {

        private final List<String> prompts;
        private final List<String> commands;
        private final List<String> answers = new ArrayList<>();

        private PendingInput(List<String> prompts, List<String> commands) {
            this.prompts = prompts;
            this.commands = commands;
        }
    }
}
