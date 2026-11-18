package de.Minefalo.economySystem.gui;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.storage.AccountData;
import de.Minefalo.economySystem.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Öffnet die konfigurierten GUI-Menüs, führt Klicks aus und sammelt Eingaben.
 *
 * <p>Klicks auf Items lösen ausschließlich Befehle aus, die vom Spieler
 * selbst ausgeführt werden ({@link Player#performCommand(String)}). Items
 * ohne passende Permission werden nicht angezeigt. Werte werden nie über
 * den Chat eingegeben: ganze Zahlen über einen Regler aus Glasscheiben,
 * Texte über einen Amboss und Spieler über eine durchsuchbare Kopfauswahl.</p>
 */
public final class GuiManager implements Listener {

    private static final int SLIDER_SIZE = 36;
    private static final int SLIDER_TRACK_FIRST = 9;
    private static final int SLIDER_TRACK_LAST = 17;
    private static final int SLIDER_STEP_BUTTONS = 10;

    private static final int PICKER_SIZE = 54;
    private static final int PICKER_FIRST = 9;
    private static final int PICKER_PAGE_SIZE = 36;

    private static final int ANVIL_RESULT_SLOT = 2;

    private final EconomySystem plugin;
    private final GuiConfig config;
    private final Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();
    private final Map<ClickKey, Long> lastCommandClick = new ConcurrentHashMap<>();
    private final Map<UUID, AnvilTarget> anvils = new ConcurrentHashMap<>();

    /**
     * Wofür ein offener Amboss verwendet wird.
     */
    private enum AnvilMode {
        TEXT,
        SEARCH
    }

    /**
     * Verknüpft einen offenen Amboss mit der laufenden Eingabe. Der Amboss
     * ist ein Menü des Servers und trägt daher keinen {@link GuiHolder}.
     */
    private record AnvilTarget(GuiSession session, AnvilMode mode) {
    }

    /**
     * Ein Klick wird pro Spieler und Slot gedrosselt. Dadurch blockiert ein
     * Klick auf ein anderes Item nicht mehr den nächsten Klick.
     */
    private record ClickKey(UUID player, int slot) {
    }

    public GuiManager(EconomySystem plugin) {
        this.plugin = plugin;
        this.config = new GuiConfig(plugin);
    }

    public void reload() {
        sessions.clear();
        lastCommandClick.clear();
        closeInventories();
        anvils.clear();
        config.reload();
    }

    public void stop() {
        sessions.clear();
        lastCommandClick.clear();
        closeInventories();
        anvils.clear();
    }

    private void closeInventories() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (anvils.containsKey(player.getUniqueId())
                    || player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder) {
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

        renderMenu(player, menu);
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

    // ------------------------------------------------------------
    // Menüs
    // ------------------------------------------------------------

    private void renderMenu(Player player, GuiConfig.Menu menu) {
        GuiHolder holder = GuiHolder.menu(menu.id());
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
            meta.displayName(name == null || name.isBlank() ? Component.text(" ") : Text.component(name));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void clickMenu(Player player, GuiHolder holder, int slot) {
        GuiConfig.Menu menu = config.menu(holder.menuId()).orElse(null);
        if (menu == null) {
            return;
        }
        GuiConfig.Item item = menu.items().get(slot);
        if (item == null || !hasAccess(player, item.permission())) {
            return;
        }
        if (item.isSubmenu()) {
            // Erst im nächsten Tick wechseln, damit der Klick sauber beendet ist.
            nextTick(() -> open(player, item.open()));
            return;
        }
        if (item.commands().isEmpty() || holder.handled()) {
            return;
        }
        long cooldown = config.clickCooldownMs();
        if (cooldown > 0) {
            long now = System.currentTimeMillis();
            ClickKey key = new ClickKey(player.getUniqueId(), slot);
            Long lastClick = lastCommandClick.get(key);
            if (lastClick != null && now - lastClick < cooldown) {
                return;
            }
            lastCommandClick.put(key, now);
        }
        holder.handled(true);
        if (!item.inputs().isEmpty()) {
            GuiSession session = new GuiSession(item.inputs(), item.commands(), menu.id());
            sessions.put(player.getUniqueId(), session);
            nextTick(() -> beginInput(player, session));
            return;
        }
        if (config.closeOnClick()) {
            player.closeInventory();
        }
        runCommands(player, item.commands());
        if (!config.closeOnClick()) {
            // Doppelklicks im selben Tick ausfiltern, das Menü bleibt bedienbar.
            nextTick(() -> holder.handled(false));
        }
    }

    // ------------------------------------------------------------
    // Eingaben
    // ------------------------------------------------------------

    private void beginInput(Player player, GuiSession session) {
        if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) {
            return;
        }
        if (session.finished()) {
            sessions.remove(player.getUniqueId());
            player.closeInventory();
            runCommands(player, substitute(session.commands(), session.answers(), player.getName()));
            return;
        }

        GuiConfig.Input input = session.current();
        switch (input.type()) {
            case NUMBER -> openSlider(player, session, input);
            case TEXT -> openAnvil(player, session, input);
            case PLAYER -> openPicker(player, session);
        }
    }

    private void cancelSession(Player player, GuiSession session, boolean reopen) {
        sessions.remove(player.getUniqueId());
        plugin.messages().send(player, "gui-input-cancelled");
        if (reopen && session.originMenu() != null) {
            nextTick(() -> {
                if (player.isOnline()) {
                    open(player, session.originMenu());
                }
            });
        }
    }

    // ------------------------------------------------------------
    // Regler für ganze Zahlen
    // ------------------------------------------------------------

    private void openSlider(Player player, GuiSession session, GuiConfig.Input input) {
        GuiHolder holder = GuiHolder.input(GuiHolder.Kind.SLIDER, session);
        Inventory inventory = Bukkit.createInventory(holder, SLIDER_SIZE, Text.component(input.prompt()));
        holder.inventory(inventory);
        renderSlider(inventory, session, input);
        player.openInventory(inventory);
    }

    private void renderSlider(Inventory inventory, GuiSession session, GuiConfig.Input input) {
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < SLIDER_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        int position = trackPosition(session.value(), input);
        for (int index = 0; index <= SLIDER_TRACK_LAST - SLIDER_TRACK_FIRST; index++) {
            Material material = index == position
                    ? Material.YELLOW_STAINED_GLASS_PANE
                    : index < position
                            ? Material.LIME_STAINED_GLASS_PANE
                            : Material.GRAY_STAINED_GLASS_PANE;
            long value = trackValue(index, input);
            inventory.setItem(SLIDER_TRACK_FIRST + index,
                    item(material, label("gui-slider-track", "value", String.valueOf(value)), List.of()));
        }

        long step = input.step();
        inventory.setItem(19, item(Material.RED_STAINED_GLASS_PANE,
                label("gui-slider-decrease", "amount", String.valueOf(step * SLIDER_STEP_BUTTONS)),
                List.of(label("gui-slider-decrease-hint"))));
        inventory.setItem(20, item(Material.RED_STAINED_GLASS_PANE,
                label("gui-slider-decrease", "amount", String.valueOf(step)),
                List.of(label("gui-slider-decrease-hint"))));
        inventory.setItem(22, item(Material.PAPER,
                label("gui-slider-value", "value", String.valueOf(session.value())),
                List.of(label("gui-slider-hint"))));
        inventory.setItem(24, item(Material.LIME_STAINED_GLASS_PANE,
                label("gui-slider-increase", "amount", String.valueOf(step)),
                List.of(label("gui-slider-increase-hint"))));
        inventory.setItem(25, item(Material.LIME_STAINED_GLASS_PANE,
                label("gui-slider-increase", "amount", String.valueOf(step * SLIDER_STEP_BUTTONS)),
                List.of(label("gui-slider-increase-hint"))));

        inventory.setItem(28, item(Material.BARRIER, label("gui-slider-cancel"), List.of()));
        inventory.setItem(31, item(Material.LIME_CONCRETE, label("gui-slider-confirm"), List.of()));
        inventory.setItem(34, item(Material.CLOCK, label("gui-slider-reset"), List.of()));
    }

    private void clickSlider(Player player, GuiHolder holder, int slot) {
        GuiSession session = holder.session();
        if (session == null || sessions.get(player.getUniqueId()) != session || session.finished()) {
            return;
        }
        GuiConfig.Input input = session.current();
        if (input.type() != GuiConfig.Input.Type.NUMBER) {
            return;
        }

        long step = input.step();
        switch (slot) {
            case 19 -> session.value(snap(session.value() - step * SLIDER_STEP_BUTTONS, input));
            case 20 -> session.value(snap(session.value() - step, input));
            case 24 -> session.value(snap(session.value() + step, input));
            case 25 -> session.value(snap(session.value() + step * SLIDER_STEP_BUTTONS, input));
            case 28 -> {
                holder.handled(true);
                player.closeInventory();
                cancelSession(player, session, true);
                return;
            }
            case 31 -> {
                holder.handled(true);
                player.closeInventory();
                session.answer(String.valueOf(session.value()));
                nextTick(() -> beginInput(player, session));
                return;
            }
            case 34 -> session.value(snap(input.initial(), input));
            default -> {
                if (slot < SLIDER_TRACK_FIRST || slot > SLIDER_TRACK_LAST) {
                    return;
                }
                session.value(trackValue(slot - SLIDER_TRACK_FIRST, input));
            }
        }

        renderSlider(holder.getInventory(), session, input);
    }

    private static int trackPosition(long value, GuiConfig.Input input) {
        int steps = SLIDER_TRACK_LAST - SLIDER_TRACK_FIRST;
        double fraction = (double) (value - input.min()) / (double) (input.max() - input.min());
        long index = Math.round(fraction * steps);
        return (int) Math.max(0, Math.min(steps, index));
    }

    private static long trackValue(int index, GuiConfig.Input input) {
        int steps = SLIDER_TRACK_LAST - SLIDER_TRACK_FIRST;
        double fraction = (double) index / steps;
        long raw = input.min() + Math.round((input.max() - input.min()) * fraction);
        return snap(raw, input);
    }

    private static long snap(long value, GuiConfig.Input input) {
        long clamped = Math.max(input.min(), Math.min(input.max(), value));
        long offset = clamped - input.min();
        long snapped = input.min() + Math.round((double) offset / input.step()) * input.step();
        if (snapped > input.max()) {
            snapped = input.min() + ((input.max() - input.min()) / input.step()) * input.step();
        }
        return Math.max(input.min(), Math.min(input.max(), snapped));
    }

    // ------------------------------------------------------------
    // Amboss für Texte
    // ------------------------------------------------------------

    private void openAnvil(Player player, GuiSession session, GuiConfig.Input input) {
        openAnvil(player, session, AnvilMode.TEXT, input.prompt(), "");
    }

    private void openSearch(Player player, GuiSession session) {
        GuiSession.Picker picker = session.picker();
        openAnvil(player, session, AnvilMode.SEARCH,
                plugin.messages().raw("gui-picker-search-title"),
                picker == null ? "" : picker.search());
    }

    /**
     * Öffnet einen echten Amboss über die Menü-API.
     *
     * <p>Ein selbst erzeugtes Inventar vom Typ {@code ANVIL} wird vom Server
     * nur als Container-Kopie geöffnet: Es besitzt kein Feld zum Umbenennen
     * und ist keine {@link AnvilView}, sodass der eingegebene Text nie
     * ankommt. Deshalb wird hier ein echtes Amboss-Menü aufgebaut.</p>
     */
    private void openAnvil(Player player, GuiSession session, AnvilMode mode,
                           String title, String preset) {
        AnvilView view = null;
        try {
            AnvilView created = MenuType.ANVIL.builder()
                    .title(Text.component(title))
                    .checkReachable(false)
                    .build(player);

            Inventory top = created.getTopInventory();
            top.clear();
            top.setItem(0, nametag(preset));
            created.setRepairCost(0);
            created.setRepairItemCountCost(0);
            created.bypassEnchantmentLevelRestriction(true);

            player.openInventory(created);
            if (player.getOpenInventory() instanceof AnvilView opened) {
                view = opened;
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Amboss konnte nicht geöffnet werden: "
                    + exception.getMessage());
        }
        if (view == null) {
            plugin.messages().send(player, "gui-input-unavailable");
            cancelSession(player, session, true);
            return;
        }
        anvils.put(player.getUniqueId(), new AnvilTarget(session, mode));
    }

    private ItemStack nametag(String preset) {
        ItemStack tag = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = tag.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(preset == null || preset.isBlank() ? " " : preset));
            meta.lore(List.of(label("gui-anvil-hint")));
            tag.setItemMeta(meta);
        }
        return tag;
    }

    private void clickAnvil(Player player, AnvilTarget target, AnvilView view, int slot) {
        if (slot != ANVIL_RESULT_SLOT) {
            return;
        }
        GuiSession session = target.session();
        if (sessions.get(player.getUniqueId()) != session || session.finished()) {
            anvils.remove(player.getUniqueId());
            return;
        }

        String text = view.getRenameText();
        if (text == null) {
            // Solange nichts getippt wurde, liefert das Menü keinen Text.
            text = inputName(view);
        }
        anvils.remove(player.getUniqueId());
        clearAnvil(view);
        player.closeInventory();

        if (text == null || text.isBlank()) {
            plugin.messages().send(player, "gui-input-empty");
            // Amboss erneut öffnen, damit die Eingabe nicht verloren geht.
            nextTick(() -> {
                if (!player.isOnline()) {
                    return;
                }
                if (target.mode() == AnvilMode.SEARCH) {
                    openSearch(player, session);
                } else {
                    openAnvil(player, session, session.current());
                }
            });
            return;
        }

        String value = text.trim();
        if (target.mode() == AnvilMode.SEARCH) {
            GuiSession.Picker picker = session.picker();
            if (picker != null) {
                picker.search(value);
                picker.page(0);
            }
            nextTick(() -> openPicker(player, session));
            return;
        }

        session.answer(value);
        nextTick(() -> beginInput(player, session));
    }

    /**
     * Name des Items im Amboss. Er dient als Vorgabe, solange der Spieler
     * noch nichts getippt hat.
     */
    private static String inputName(AnvilView view) {
        ItemStack input = view.getTopInventory().getItem(0);
        if (input == null) {
            return null;
        }
        ItemMeta meta = input.getItemMeta();
        if (meta == null || !meta.hasDisplayName() || meta.displayName() == null) {
            return null;
        }
        return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
    }

    /**
     * Entfernt alle Items aus dem Amboss, bevor er geschlossen wird. Sonst
     * fallen sie beim Schließen vor die Füße des Spielers.
     */
    private static void clearAnvil(AnvilView view) {
        try {
            view.getTopInventory().clear();
        } catch (RuntimeException ignored) {
            // Der Amboss ist bereits geschlossen.
        }
    }

    // ------------------------------------------------------------
    // Spielerauswahl
    // ------------------------------------------------------------

    private void openPicker(Player player, GuiSession session) {
        GuiSession.Picker picker = session.picker();
        if (picker == null) {
            picker = session.startPicker(session.current());
        }

        GuiHolder holder = GuiHolder.input(GuiHolder.Kind.PICKER, session);
        Inventory inventory = Bukkit.createInventory(holder, PICKER_SIZE,
                Text.component(session.current().prompt()));
        holder.inventory(inventory);
        renderPicker(inventory, session);
        player.openInventory(inventory);

        if (picker.loading() && !picker.loadingStarted()) {
            picker.loadingStarted(true);
            loadAccounts(player, holder, picker);
        }
    }

    private void renderPicker(Inventory inventory, GuiSession session) {
        GuiSession.Picker picker = session.picker();
        if (picker == null) {
            return;
        }

        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < PICKER_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        List<AccountData> visible = visibleAccounts(picker);
        int pages = pages(visible.size());
        if (picker.page() >= pages) {
            picker.page(pages - 1);
        }

        inventory.setItem(0, item(Material.HOPPER,
                label("gui-picker-filter", "filter", filterName(picker.filter())),
                List.of(label("gui-picker-filter-hint"))));
        inventory.setItem(4, item(Material.COMPASS,
                label("gui-picker-search"),
                List.of(picker.search().isBlank()
                        ? label("gui-picker-search-hint")
                        : label("gui-picker-search-active", "search", picker.search()))));
        inventory.setItem(8, item(Material.COMPARATOR,
                label("gui-picker-sort", "sort", sortName(picker.sort())),
                List.of(label("gui-picker-sort-hint"))));

        int start = picker.page() * PICKER_PAGE_SIZE;
        for (int offset = 0; offset < PICKER_PAGE_SIZE && start + offset < visible.size(); offset++) {
            inventory.setItem(PICKER_FIRST + offset, playerHead(visible.get(start + offset)));
        }

        if (picker.loading()) {
            inventory.setItem(22, item(Material.CLOCK, label("gui-picker-loading"), List.of()));
        } else if (visible.isEmpty()) {
            inventory.setItem(22, item(Material.PAPER, label("gui-picker-empty"), List.of()));
        }

        inventory.setItem(45, item(Material.BARRIER, label("gui-picker-cancel"), List.of()));
        inventory.setItem(48, item(Material.ARROW, label("gui-picker-previous"), List.of()));
        inventory.setItem(49, item(Material.PAPER,
                label("gui-picker-page",
                        "page", String.valueOf(picker.page() + 1),
                        "pages", String.valueOf(pages)),
                List.of()));
        inventory.setItem(50, item(Material.ARROW, label("gui-picker-next"), List.of()));
    }

    private void loadAccounts(Player player, GuiHolder holder, GuiSession.Picker picker) {
        GuiSession session = holder.session();
        plugin.economy().accountsAsync().whenComplete((accounts, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (session == null || sessions.get(player.getUniqueId()) != session
                            || session.picker() != picker) {
                        return;
                    }
                    picker.loading(false);
                    if (error != null) {
                        plugin.getLogger().warning("Spielerliste konnte nicht geladen werden: "
                                + error.getMessage());
                        picker.accounts(List.of());
                    } else {
                        picker.accounts(accounts);
                    }
                    Inventory inventory = holder.getInventory();
                    if (inventory != null) {
                        renderPicker(inventory, session);
                    }
                }));
    }

    private void clickPicker(Player player, GuiHolder holder, int slot) {
        GuiSession session = holder.session();
        if (session == null || sessions.get(player.getUniqueId()) != session || session.finished()) {
            return;
        }
        GuiSession.Picker picker = session.picker();
        if (picker == null) {
            return;
        }

        List<AccountData> visible = visibleAccounts(picker);
        switch (slot) {
            case 0 -> {
                picker.filter(picker.filter() == GuiConfig.Input.Filter.ALL
                        ? GuiConfig.Input.Filter.ONLINE
                        : GuiConfig.Input.Filter.ALL);
                picker.page(0);
                renderPicker(holder.getInventory(), session);
                return;
            }
            case 4 -> {
                // Beim Wechsel in den Amboss wird dieses Inventar geschlossen:
                // ohne "handled" würde die Eingabe dadurch abgebrochen.
                holder.handled(true);
                nextTick(() -> openSearch(player, session));
                return;
            }
            case 8 -> {
                picker.sort(nextSort(picker.sort()));
                picker.page(0);
                renderPicker(holder.getInventory(), session);
                return;
            }
            case 45 -> {
                holder.handled(true);
                player.closeInventory();
                cancelSession(player, session, true);
                return;
            }
            case 48 -> {
                if (picker.page() > 0) {
                    picker.page(picker.page() - 1);
                    renderPicker(holder.getInventory(), session);
                }
                return;
            }
            case 50 -> {
                if (picker.page() + 1 < pages(visible.size())) {
                    picker.page(picker.page() + 1);
                    renderPicker(holder.getInventory(), session);
                }
                return;
            }
            default -> {
                // Spielerköpfe
            }
        }

        if (slot < PICKER_FIRST || slot >= PICKER_FIRST + PICKER_PAGE_SIZE) {
            return;
        }
        int index = picker.page() * PICKER_PAGE_SIZE + (slot - PICKER_FIRST);
        if (index < 0 || index >= visible.size()) {
            return;
        }

        holder.handled(true);
        player.closeInventory();
        session.answer(visible.get(index).name());
        nextTick(() -> beginInput(player, session));
    }

    private List<AccountData> visibleAccounts(GuiSession.Picker picker) {
        String query = picker.search().toLowerCase(Locale.ROOT);
        return picker.accounts().stream()
                .filter(account -> query.isEmpty()
                        || account.name().toLowerCase(Locale.ROOT).contains(query))
                .filter(account -> picker.filter() != GuiConfig.Input.Filter.ONLINE
                        || plugin.getServer().getPlayerExact(account.name()) != null)
                .sorted(comparator(picker.sort()))
                .toList();
    }

    private static Comparator<AccountData> comparator(GuiConfig.Input.Sort sort) {
        Comparator<AccountData> byName = Comparator.comparing(AccountData::name,
                String.CASE_INSENSITIVE_ORDER);
        Comparator<AccountData> byBalance = Comparator.comparing(AccountData::balance);
        return switch (sort) {
            case NAME_DESC -> byName.reversed();
            case BALANCE_DESC -> byBalance.reversed().thenComparing(byName);
            case BALANCE_ASC -> byBalance.thenComparing(byName);
            default -> byName;
        };
    }

    private static int pages(int entries) {
        return Math.max(1, (entries + PICKER_PAGE_SIZE - 1) / PICKER_PAGE_SIZE);
    }

    private static GuiConfig.Input.Sort nextSort(GuiConfig.Input.Sort sort) {
        return switch (sort) {
            case NAME_ASC -> GuiConfig.Input.Sort.NAME_DESC;
            case NAME_DESC -> GuiConfig.Input.Sort.BALANCE_DESC;
            case BALANCE_DESC -> GuiConfig.Input.Sort.BALANCE_ASC;
            case BALANCE_ASC -> GuiConfig.Input.Sort.NAME_ASC;
        };
    }

    private String filterName(GuiConfig.Input.Filter filter) {
        return plugin.messages().raw(filter == GuiConfig.Input.Filter.ALL
                ? "gui-picker-filter-all"
                : "gui-picker-filter-online");
    }

    private String sortName(GuiConfig.Input.Sort sort) {
        String key = switch (sort) {
            case NAME_DESC -> "gui-picker-sort-name-desc";
            case BALANCE_DESC -> "gui-picker-sort-balance-desc";
            case BALANCE_ASC -> "gui-picker-sort-balance-asc";
            default -> "gui-picker-sort-name-asc";
        };
        return plugin.messages().raw(key);
    }

    private ItemStack playerHead(AccountData account) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(account.uniqueId()));
            skull.displayName(label("gui-picker-head-name", "player", account.name()));
            skull.lore(List.of(
                    label("gui-picker-head-balance",
                            "balance", plugin.settings().currency().format(account.balance())),
                    label("gui-picker-head-click")));
            head.setItemMeta(skull);
        }
        return head;
    }

    // ------------------------------------------------------------
    // Ereignisse
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder holder) {
            if (!isTopInventoryClick(event)) {
                // Shift-Klicks und Doppelklicks können Gegenstände ins Menü bewegen.
                if (event.isShiftClick() || event.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) {
                    event.setCancelled(true);
                }
                return;
            }
            event.setCancelled(true);
            switch (holder.kind()) {
                case MENU -> clickMenu(player, holder, event.getSlot());
                case SLIDER -> clickSlider(player, holder, event.getSlot());
                case PICKER -> clickPicker(player, holder, event.getSlot());
            }
            return;
        }

        AnvilTarget target = anvils.get(player.getUniqueId());
        if (target == null || !(event.getView() instanceof AnvilView view)) {
            return;
        }
        if (!isTopInventoryClick(event)) {
            if (event.isShiftClick() || event.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) {
                event.setCancelled(true);
            }
            return;
        }
        // Im Amboss darf nichts entnommen oder abgelegt werden.
        event.setCancelled(true);
        clickAnvil(player, target, view, event.getSlot());
    }

    private static boolean isTopInventoryClick(InventoryClickEvent event) {
        Inventory clicked = event.getClickedInventory();
        return clicked != null && clicked.equals(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        boolean ours = event.getView().getTopInventory().getHolder() instanceof GuiHolder
                || event.getWhoClicked() instanceof Player player && anvils.containsKey(player.getUniqueId());
        if (ours && event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (event.getView() instanceof AnvilView view) {
            AnvilTarget target = anvils.remove(player.getUniqueId());
            if (target != null) {
                clearAnvil(view);
                GuiSession session = target.session();
                if (sessions.get(player.getUniqueId()) != session || session.finished()) {
                    return;
                }
                if (target.mode() == AnvilMode.SEARCH) {
                    // Abbrechen der Suche führt zurück zur Spielerauswahl.
                    nextTick(() -> openPicker(player, session));
                    return;
                }
                cancelSession(player, session, false);
                return;
            }
        }

        if (!(event.getInventory().getHolder() instanceof GuiHolder holder)) {
            return;
        }
        if (holder.kind() == GuiHolder.Kind.MENU || holder.handled()) {
            return;
        }

        GuiSession session = holder.session();
        if (session == null || sessions.get(player.getUniqueId()) != session) {
            return;
        }
        cancelSession(player, session, false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        anvils.remove(event.getPlayer().getUniqueId());
        lastCommandClick.keySet().removeIf(key -> key.player().equals(event.getPlayer().getUniqueId()));
    }

    // ------------------------------------------------------------
    // Hilfsmethoden
    // ------------------------------------------------------------

    private void runCommands(Player player, List<String> commands) {
        nextTick(() -> {
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

    private void nextTick(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    private Component label(String key, String... placeholders) {
        String text = plugin.messages().raw(key);
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            text = text.replace("%" + placeholders[index] + "%", placeholders[index + 1]);
        }
        return Text.component(text);
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
