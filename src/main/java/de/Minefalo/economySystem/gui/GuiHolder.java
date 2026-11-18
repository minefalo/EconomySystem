package de.Minefalo.economySystem.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Kennzeichnet Inventare, die dieses Plugin geöffnet hat, und ordnet sie
 * ihrer Funktion (Menü, Regler oder Spielerauswahl) zu. Der Amboss wird
 * nicht hier geführt: Er ist ein Menü des Servers und wird über die
 * laufende Eingabe zugeordnet.
 */
final class GuiHolder implements InventoryHolder {

    enum Kind {
        MENU,
        SLIDER,
        PICKER
    }

    private final Kind kind;
    private final String menuId;
    private final GuiSession session;
    private Inventory inventory;
    private boolean handled;

    private GuiHolder(Kind kind, String menuId, GuiSession session) {
        this.kind = kind;
        this.menuId = menuId;
        this.session = session;
    }

    static GuiHolder menu(String menuId) {
        return new GuiHolder(Kind.MENU, menuId, null);
    }

    static GuiHolder input(Kind kind, GuiSession session) {
        return new GuiHolder(kind, null, session);
    }

    Kind kind() {
        return kind;
    }

    String menuId() {
        return menuId;
    }

    GuiSession session() {
        return session;
    }

    /**
     * Ob das Inventar absichtlich geschlossen wurde (Bestätigen/Abbruch).
     * Ein normales Schließen durch den Spieler bleibt {@code false}.
     */
    boolean handled() {
        return handled;
    }

    void handled(boolean handled) {
        this.handled = handled;
    }

    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
