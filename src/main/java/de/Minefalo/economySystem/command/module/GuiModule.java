package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.gui.GuiManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /eco gui [<menü>]} - öffnet das klickbare Wirtschafts-Menü. Die
 * Items und Befehle stehen in der {@code gui.yml}.
 */
public final class GuiModule extends AbstractModule {

    private final GuiManager gui;

    public GuiModule(EconomySystem plugin, EconomyService economy, GuiManager gui) {
        super(plugin, economy);
        this.gui = gui;
    }

    @Override
    public String id() {
        return "gui";
    }

    @Override
    public String path() {
        return "gui";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public String usage() {
        return "/eco gui [<menü>]";
    }

    /**
     * Das Menü lädt für die Spielerauswahl den Kontobestand. Zwei Sekunden
     * Mindestabstand verhindern, dass ein Makro diese Last ungedrosselt
     * auslöst (SICHERHEITSAUDIT, ROT-2); {@code economysystem.bypass.cooldown}
     * hebt den Abstand auf.
     */
    @Override
    public long cooldownMillis() {
        return 2_000L;
    }

    /**
     * Lädt das Konto, damit der Spielerkopf im Menü sofort Guthaben und
     * Kreditwerte zeigt.
     */
    @Override
    public CompletableFuture<?> prepare(CommandContext context) {
        if (context.sender() instanceof Player player) {
            return economy.accountAsync(player.getUniqueId(), player.getName());
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CommandResult execute(CommandContext context) {
        CommandSender sender = context.sender();
        if (!(sender instanceof Player player)) {
            return CommandResult.rejected("command._default.error.player-only");
        }

        List<String> args = context.args().positional();
        if (args.size() > 1) {
            usageError(sender, String.join(" ", args));
            return CommandResult.handled();
        }

        String menu = args.isEmpty() ? gui.defaultMenu() : args.get(0).toLowerCase(Locale.ROOT);
        gui.open(player, menu);
        return CommandResult.handled();
    }

    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.size() != 1 || !(context.sender() instanceof Player player)) {
            return List.of();
        }
        return filter(gui.menuIds(player), raw.get(0));
    }
}
