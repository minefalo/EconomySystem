package de.Minefalo.economySystem.gui;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.credit.CreditService;
import de.Minefalo.economySystem.currency.Currency;
import de.Minefalo.economySystem.economy.Account;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Öffnet die konfigurierten GUI-Menüs, führt Klicks aus und sammelt Eingaben.
 *
 * <p>Klicks auf Items lösen ausschließlich Befehle aus, die über den Router
 * mit {@code Origin.GUI} laufen ({@code CommandRegistry.dispatchGui}) und
 * daher dieselben Prüfstufen wie ein Chat-Befehl durchlaufen. Ein Klick
 * bestätigt keine bestätigungspflichtige Aktion: Verlangt der Pfad
 * {@code --confirm}, zeigt die GUI zuerst einen eigenen Bestätigungsdialog.
 * Items ohne passende Permission werden nicht angezeigt. Werte werden nie
 * über den Chat eingegeben: Zahlen und Texte über einen Amboss und Spieler
 * über eine durchsuchbare Kopfauswahl.</p>
 */
public final class GuiManager implements Listener {

    private static final int PICKER_SIZE = 54;
    private static final int PICKER_FIRST = 9;
    private static final int PICKER_PAGE_SIZE = 36;

    private static final int ANVIL_RESULT_SLOT = 2;

    /** Höchstlänge einer Antwort; sie wird genau ein Argument der Befehlszeile. */
    private static final int ANSWER_MAX_LENGTH = 64;
    /** Ziffern mit höchstens einem Dezimalpunkt, wie der Amboss sie liefert. */
    private static final Pattern ANSWER_NUMBER = Pattern.compile("\\d{1,19}(?:\\.\\d{1,8})?");
    /** Spielernamen inkl. der Punkt-Präfixe von Bedrock-/Floodgate-Konten. */
    private static final Pattern ANSWER_PLAYER = Pattern.compile("[A-Za-z0-9_.]{1,16}");

    private static final int CONFIRM_SIZE = 27;
    private static final int CONFIRM_ACCEPT = 11;
    private static final int CONFIRM_CANCEL = 15;
    private static final int PICKER_CONFIRM_HEAD = 13;

    private final EconomySystem plugin;
    private final GuiConfig config;
    private final Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();
    private final Map<ClickKey, Long> lastCommandClick = new ConcurrentHashMap<>();
    private final Map<UUID, AnvilTarget> anvils = new ConcurrentHashMap<>();
    /** Befehle, die auf die Bestätigung des Spielers warten. */
    private final Map<UUID, List<String>> confirmations = new ConcurrentHashMap<>();

    /**
     * Wofür ein offener Amboss verwendet wird.
     */
    private enum AnvilMode {
        TEXT,
        NUMBER,
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
        confirmations.clear();
        config.reload();
    }

    public void stop() {
        sessions.clear();
        lastCommandClick.clear();
        closeInventories();
        anvils.clear();
        confirmations.clear();
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

        Map<String, String> placeholders = placeholders(player, account(player));
        ItemStack fill = menu.fill() == null ? null : createFill(menu.fill());
        for (int slot = 0; slot < menu.size(); slot++) {
            GuiConfig.Item item = menu.items().get(slot);
            if (item != null && hasAccess(player, item.permission())) {
                inventory.setItem(slot, createItem(player, item, placeholders));
            } else if (fill != null) {
                inventory.setItem(slot, fill.clone());
            }
        }

        player.openInventory(inventory);
    }

    /**
     * Konto aus dem Cache. Die Module laden es über {@code prepare()} vor; ein
     * fehlendes Konto führt nur zu Platzhaltern, nie zu einem Speicherzugriff
     * auf dem Tick-Thread.
     */
    private Account account(Player player) {
        return plugin.economy() == null ? null
                : plugin.economy().find(player.getName()).orElse(null);
    }

    private ItemStack createItem(Player player, GuiConfig.Item item, Map<String, String> placeholders) {
        ItemStack stack = new ItemStack(item.material(), item.amount());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (item.isPlayerHead() && meta instanceof SkullMeta skull) {
                skull.setOwningPlayer(player);
            }
            meta.displayName(Text.component(resolve(item.name(), placeholders)));
            if (!item.lore().isEmpty()) {
                meta.lore(item.lore().stream()
                        .map(line -> Text.component(resolve(line, placeholders)))
                        .toList());
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * Werte für die Platzhalter in {@code name} und {@code lore}. Kreditwerte
     * kommen live aus dem {@link CreditService}, damit die Anzeige beim
     * nächsten Öffnen des Menüs bereits aktuell ist.
     */
    private Map<String, String> placeholders(Player player, Account account) {
        Currency currency = plugin.settings().currency();
        Settings.Credits creditConfig = plugin.settings().credits();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("%player%", player.getName());
        values.put("%balance%", account == null ? "-" : currency.format(account.balance()));
        values.put("%min_balance%", currency.format(currency.minBalance()));
        BigDecimal maxBalance = currency.maxBalance();
        values.put("%max_balance%", maxBalance == null
                ? plugin.messages().raw("unlimited")
                : currency.format(maxBalance));
        values.put("%credit_status%", plugin.messages().raw(creditConfig.enabled()
                ? "enabled" : "disabled"));
        values.put("%credit_max_loans%", String.valueOf(creditConfig.maxLoans()));

        CreditService credit = plugin.creditService();
        if (credit == null || account == null) {
            values.put("%credit_limit%", "-");
            values.put("%credit_debt%", "-");
            values.put("%credit_loans%", "0");
            return values;
        }
        values.put("%credit_limit%", currency.format(credit.maxLoan(account)));
        values.put("%credit_debt%", currency.format(credit.outstanding(account.uniqueId())));
        values.put("%credit_loans%", String.valueOf(credit.activeLoanCount(account.uniqueId())));
        return values;
    }

    private static String resolve(String text, Map<String, String> placeholders) {
        if (text == null || placeholders.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
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
        if (item.isDisplayOnly() || holder.handled()) {
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
            if (!answersAllowed(session)) {
                plugin.messages().send(player, "gui-input-invalid");
                reopenOrigin(player, session);
                return;
            }
            runCommands(player, substitute(session.commands(), session.answers(), player.getName()));
            return;
        }

        GuiConfig.Input input = session.current();
        switch (input.type()) {
            case NUMBER -> openAnvil(player, session, input);
            case TEXT -> openAnvil(player, session, input);
            case PLAYER -> openPicker(player, session);
        }
    }

    private void cancelSession(Player player, GuiSession session, boolean reopen) {
        sessions.remove(player.getUniqueId());
        plugin.messages().send(player, "gui-input-cancelled");
        if (reopen) {
            reopenOrigin(player, session);
        }
    }

    /** Öffnet das Menü wieder, aus dem die Eingabe gestartet wurde. */
    private void reopenOrigin(Player player, GuiSession session) {
        if (session.originMenu() == null) {
            return;
        }
        nextTick(() -> {
            if (player.isOnline()) {
                open(player, session.originMenu());
            }
        });
    }

    // ------------------------------------------------------------
    // Amboss für Zahlen und Texte
    // ------------------------------------------------------------

    private void openAnvil(Player player, GuiSession session, GuiConfig.Input input) {
        openAnvil(player, session, input, "");
    }

    private void openAnvil(Player player, GuiSession session, GuiConfig.Input input, String preset) {
        AnvilMode mode = input.type() == GuiConfig.Input.Type.NUMBER
                ? AnvilMode.NUMBER
                : AnvilMode.TEXT;
        openAnvil(player, session, mode, input.prompt(), preset);
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
            top.setItem(0, nametag(preset, mode));
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

    private ItemStack nametag(String preset, AnvilMode mode) {
        ItemStack tag = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = tag.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(preset == null || preset.isBlank() ? " " : preset));
            meta.lore(List.of(label(mode == AnvilMode.NUMBER
                    ? "gui-anvil-number-hint"
                    : "gui-anvil-hint")));
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

        String value = text == null ? "" : text.trim();

        if (target.mode() == AnvilMode.SEARCH) {
            // Leer oder nur Leerzeichen löscht den Suchfilter.
            GuiSession.Picker picker = session.picker();
            if (picker != null) {
                picker.search(value);
                picker.page(0);
            }
            nextTick(() -> openPicker(player, session));
            return;
        }

        if (target.mode() == AnvilMode.NUMBER) {
            acceptNumber(player, session, value);
            return;
        }

        if (value.isEmpty()) {
            plugin.messages().send(player, "gui-input-empty");
            // Amboss erneut öffnen, damit die Eingabe nicht verloren geht.
            nextTick(() -> {
                if (player.isOnline()) {
                    openAnvil(player, session, session.current());
                }
            });
            return;
        }

        session.answer(value);
        nextTick(() -> beginInput(player, session));
    }

    /**
     * Übernimmt eine Zahl aus dem Amboss. Alle Zeichen außer Ziffern werden
     * verworfen; der erste Punkt oder das erste Komma wird zum
     * Dezimalpunkt. Aus {@code "4h5 7.4"} wird so {@code 457.4}, aus
     * {@code "1+6/d,o84"} wird {@code 16.84}. Liegt der Wert außerhalb von
     * {@code min}/{@code max}, bleibt die gefilterte Eingabe im Amboss
     * stehen und kann korrigiert werden.
     */
    private void acceptNumber(Player player, GuiSession session, String raw) {
        GuiConfig.Input input = session.current();
        String value = filterNumber(raw);
        if (value.isEmpty()) {
            plugin.messages().send(player, "gui-input-empty");
            reopenAnvil(player, session, "");
            return;
        }

        BigDecimal number;
        try {
            number = new BigDecimal(value);
        } catch (NumberFormatException exception) {
            plugin.messages().send(player, "gui-input-number-invalid", "input", value);
            reopenAnvil(player, session, value);
            return;
        }

        if (!ANSWER_NUMBER.matcher(value).matches()) {
            plugin.messages().send(player, "gui-input-number-invalid", "input", value);
            reopenAnvil(player, session, value);
            return;
        }

        if (number.compareTo(input.min()) < 0 || number.compareTo(input.max()) > 0) {
            plugin.messages().send(player, "gui-input-number-range",
                    "min", input.min().toPlainString(),
                    "max", input.max().toPlainString());
            reopenAnvil(player, session, value);
            return;
        }

        session.answer(number.stripTrailingZeros().toPlainString());
        nextTick(() -> beginInput(player, session));
    }

    private void reopenAnvil(Player player, GuiSession session, String preset) {
        GuiConfig.Input input = session.current();
        nextTick(() -> {
            if (player.isOnline()) {
                openAnvil(player, session, input, preset);
            }
        });
    }

    /**
     * Entfernt alle Zeichen außer Ziffern. Der erste Punkt oder das erste
     * Komma wird zum Dezimalpunkt, weitere Trennzeichen entfallen. Eine
     * führende Trennung wird zu {@code 0.}, eine abschließende entfällt.
     */
    private static String filterNumber(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder number = new StringBuilder(raw.length());
        boolean separator = false;
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character >= '0' && character <= '9') {
                number.append(character);
            } else if ((character == '.' || character == ',') && !separator) {
                number.append('.');
                separator = true;
            }
        }
        if (number.length() == 0) {
            return "";
        }
        if (number.charAt(0) == '.') {
            number.insert(0, '0');
        }
        if (number.charAt(number.length() - 1) == '.') {
            number.deleteCharAt(number.length() - 1);
        }
        return number.toString();
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
        inventory.setItem(46, item(Material.BUCKET,
                label("gui-picker-reset"),
                List.of(label("gui-picker-reset-hint"))));
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
            case 46 -> {
                picker.reset();
                renderPicker(holder.getInventory(), session);
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
        picker.selected(visible.get(index));
        player.closeInventory();
        nextTick(() -> openPlayerConfirm(player, session));
    }

    /**
     * Zeigt die gewählte Spielerauswahl als eigene, abbrechbare Eingabe.
     * Erst der Klick auf "Bestätigen" übernimmt den Namen; Abbrechen führt
     * zur Auswahl zurück.
     */
    private void openPlayerConfirm(Player player, GuiSession session) {
        GuiSession.Picker picker = session.picker();
        if (picker == null || picker.selected() == null) {
            nextTick(() -> openPicker(player, session));
            return;
        }
        AccountData selected = picker.selected();

        GuiHolder holder = GuiHolder.input(GuiHolder.Kind.PICKER_CONFIRM, session);
        Inventory inventory = Bukkit.createInventory(holder, CONFIRM_SIZE,
                Text.component(plugin.messages().raw("gui-picker-confirm-title")));
        holder.inventory(inventory);

        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < CONFIRM_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(PICKER_CONFIRM_HEAD, playerHead(selected, false));
        inventory.setItem(CONFIRM_ACCEPT, item(Material.LIME_CONCRETE,
                label("gui-picker-confirm-accept"),
                List.of(label("gui-picker-confirm-hint", "player", selected.name()))));
        inventory.setItem(CONFIRM_CANCEL, item(Material.RED_CONCRETE,
                label("gui-picker-confirm-cancel"),
                List.of(label("gui-picker-confirm-back-hint"))));
        player.openInventory(inventory);
    }

    private void clickPlayerConfirm(Player player, GuiHolder holder, int slot) {
        if (slot != CONFIRM_ACCEPT && slot != CONFIRM_CANCEL) {
            return;
        }
        GuiSession session = holder.session();
        if (session == null || sessions.get(player.getUniqueId()) != session || session.finished()) {
            return;
        }
        GuiSession.Picker picker = session.picker();
        if (picker == null || picker.selected() == null) {
            return;
        }

        String selected = picker.selected().name();
        picker.selected(null);
        holder.handled(true);
        player.closeInventory();
        if (slot == CONFIRM_ACCEPT) {
            session.answer(selected);
            nextTick(() -> beginInput(player, session));
            return;
        }
        nextTick(() -> openPicker(player, session));
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
        return playerHead(account, true);
    }

    /**
     * Kopf eines Kontos mit allen verfügbaren Angaben im Tooltip: Status,
     * Guthaben, Kreditrahmen, offene Kredite und laufende Kredite.
     */
    private ItemStack playerHead(AccountData account, boolean selectable) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            Currency currency = plugin.settings().currency();
            boolean online = plugin.getServer().getPlayerExact(account.name()) != null;
            List<Component> lore = new ArrayList<>();
            lore.add(label(online
                    ? "gui-picker-head-status-online"
                    : "gui-picker-head-status-offline"));
            lore.add(label("gui-picker-head-balance",
                    "balance", currency.format(account.balance())));

            CreditService credit = plugin.creditService();
            Account target = plugin.economy() == null
                    ? null
                    : plugin.economy().find(account.name()).orElse(null);
            if (credit != null && target != null) {
                lore.add(label("gui-picker-head-limit",
                        "limit", currency.format(credit.maxLoan(target))));
                lore.add(label("gui-picker-head-debt",
                        "debt", currency.format(credit.outstanding(account.uniqueId()))));
                lore.add(label("gui-picker-head-loans",
                        "loans", String.valueOf(credit.activeLoanCount(account.uniqueId())),
                        "max", String.valueOf(plugin.settings().credits().maxLoans())));
            }
            lore.add(label(selectable
                    ? "gui-picker-head-click"
                    : "gui-picker-confirm-selected"));

            skull.setOwningPlayer(Bukkit.getOfflinePlayer(account.uniqueId()));
            skull.displayName(label("gui-picker-head-name", "player", account.name()));
            skull.lore(lore);
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
                case PICKER -> clickPicker(player, holder, event.getSlot());
                case PICKER_CONFIRM -> clickPlayerConfirm(player, holder, event.getSlot());
                case CONFIRM -> clickConfirm(player, holder, event.getSlot());
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
        if (holder.kind() == GuiHolder.Kind.PICKER_CONFIRM) {
            // Schließen ohne Klick führt zurück zur Auswahl.
            if (holder.handled()) {
                return;
            }
            GuiSession session = holder.session();
            if (session == null || sessions.get(player.getUniqueId()) != session) {
                return;
            }
            GuiSession.Picker picker = session.picker();
            if (picker != null) {
                picker.selected(null);
            }
            nextTick(() -> openPicker(player, session));
            return;
        }
        if (holder.kind() == GuiHolder.Kind.CONFIRM) {
            // Schließen ohne Klick ist ein Abbruch.
            if (!holder.handled()) {
                confirmations.remove(player.getUniqueId());
                plugin.messages().send(player, "gui-input-cancelled");
            }
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
        confirmations.remove(event.getPlayer().getUniqueId());
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
            boolean needsConfirmation = commands.stream()
                    .anyMatch(plugin.commandRouter()::requiresConfirmation);
            if (needsConfirmation) {
                openConfirm(player, commands);
                return;
            }
            dispatchCommands(player, commands, false);
        });
    }

    private void dispatchCommands(Player player, List<String> commands, boolean confirmed) {
        for (String command : commands) {
            plugin.commandRouter().dispatchGui(player, command, confirmed);
        }
    }

    /**
     * Fragt vor einer bestätigungspflichtigen Aktion nach. Erst der Klick auf
     * "Bestätigen" setzt {@code confirmed} - der Klick auf das Item im Menü
     * und die Eingaben davor zählen nicht als Bestätigung.
     */
    private void openConfirm(Player player, List<String> commands) {
        confirmations.put(player.getUniqueId(), List.copyOf(commands));
        GuiHolder holder = GuiHolder.confirm();
        Inventory inventory = Bukkit.createInventory(holder, CONFIRM_SIZE,
                Text.component(plugin.messages().raw("gui-confirm-title")));
        holder.inventory(inventory);

        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < CONFIRM_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(CONFIRM_ACCEPT, item(Material.LIME_CONCRETE,
                label("gui-confirm-accept"), List.of(label("gui-confirm-hint"))));
        inventory.setItem(CONFIRM_CANCEL, item(Material.RED_CONCRETE,
                label("gui-confirm-cancel"), List.of()));
        player.openInventory(inventory);
    }

    private void clickConfirm(Player player, GuiHolder holder, int slot) {
        if (slot != CONFIRM_ACCEPT && slot != CONFIRM_CANCEL) {
            return;
        }
        List<String> commands = confirmations.remove(player.getUniqueId());
        if (commands == null) {
            return;
        }
        holder.handled(true);
        player.closeInventory();
        if (slot != CONFIRM_ACCEPT) {
            plugin.messages().send(player, "gui-input-cancelled");
            return;
        }
        dispatchCommands(player, commands, true);
    }

    /**
     * Prüft die Antworten einer beendeten Eingabe, bevor sie in die
     * Befehlszeile wandern: jede Antwort muss ein einzelnes Argument ohne
     * Leerzeichen und ohne führendes {@code --} sein und zum Eingabetyp passen.
     * Damit kann ein Textfeld weder zusätzliche Argumente noch Flags
     * einschleusen (SICHERHEITSAUDIT, ORANGE-1).
     */
    private static boolean answersAllowed(GuiSession session) {
        List<GuiConfig.Input> inputs = session.inputs();
        List<String> answers = session.answers();
        if (inputs.size() != answers.size()) {
            return false;
        }
        for (int index = 0; index < answers.size(); index++) {
            if (!answerAllowed(inputs.get(index), answers.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean answerAllowed(GuiConfig.Input input, String answer) {
        if (answer == null || answer.isEmpty() || answer.length() > ANSWER_MAX_LENGTH) {
            return false;
        }
        for (int index = 0; index < answer.length(); index++) {
            char character = answer.charAt(index);
            if (character < ' ' || character == '\u007f' || Character.isWhitespace(character)) {
                return false;
            }
        }
        if (answer.startsWith("--")) {
            return false;
        }
        return switch (input.type()) {
            case NUMBER -> ANSWER_NUMBER.matcher(answer).matches() && inRange(input, answer);
            case PLAYER -> ANSWER_PLAYER.matcher(answer).matches();
            case TEXT -> input.pattern() == null || input.pattern().matcher(answer).matches();
        };
    }

    private static boolean inRange(GuiConfig.Input input, String answer) {
        try {
            BigDecimal value = new BigDecimal(answer);
            return value.compareTo(input.min()) >= 0 && value.compareTo(input.max()) <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
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

    private boolean hasAccess(Player player, String permission) {
        return permission == null || permission.isBlank()
                || plugin.permissions().has(player, permission);
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
