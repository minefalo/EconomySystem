package de.Minefalo.economySystem.help;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.ConfigUpdater;
import de.Minefalo.economySystem.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Liest die help.yml und zeigt die Hilfe seitenweise mit klickbarer
 * Navigation im Chat an.
 */
public final class HelpBook {

    private final EconomySystem plugin;
    private final File file;
    private YamlConfiguration config;

    public HelpBook(EconomySystem plugin) {
        this.plugin = plugin;
        this.file = ConfigUpdater.file(plugin, "help.yml");
        reload();
    }

    public void reload() {
        ConfigUpdater.update(plugin, "help.yml");
        config = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = ConfigUpdater.bundled(plugin, "help.yml");
        if (defaults != null) {
            config.setDefaults(defaults);
        }
    }

    /**
     * @return Anzahl der konfigurierten Hilfe-Seiten
     */
    public int pageCount() {
        return config.getMapList("pages").size();
    }

    /**
     * Zeigt die angeforderte Seite. Werte außerhalb des gültigen Bereichs
     * werden auf die erste bzw. letzte Seite begrenzt.
     *
     * @param label Befehlsname, der für die klickbare Navigation verwendet wird
     */
    public void show(CommandSender sender, String label, int requestedPage) {
        List<Map<?, ?>> pages = config.getMapList("pages");
        if (pages.isEmpty()) {
            plugin.messages().send(sender, "help-empty");
            return;
        }

        int page = Math.max(1, Math.min(requestedPage, pages.size()));
        sendLine(sender, config.getString("title"));

        Map<?, ?> data = pages.get(page - 1);
        String title = data.get("title") == null ? "" : String.valueOf(data.get("title"));
        sendLine(sender, applyVariables(
                config.getString("page-title", "<yellow><bold>%title% <dark_gray>(<gray>Seite %page%/%pages%<dark_gray>)"),
                title, page, pages.size()));

        Object lines = data.get("lines");
        if (lines instanceof List<?> pageLines) {
            for (Object line : pageLines) {
                sender.sendMessage(Text.component(String.valueOf(line)));
            }
        }

        if (pages.size() > 1) {
            sender.sendMessage(navigation(label, page, pages.size()));
        }
    }

    private Component navigation(String label, int page, int pages) {
        Component navigation = Component.empty();
        boolean hasPrevious = page > 1;

        if (hasPrevious) {
            navigation = navigation.append(button(
                    config.getString("navigation.previous", "<green>« Zurück"),
                    config.getString("navigation.previous-hover"),
                    page - 1, label));
            navigation = navigation.append(Text.component("  "));
        }

        navigation = navigation.append(Text.component(applyVariables(
                config.getString("page-indicator", "<gray>Seite <yellow>%page% <gray>von <yellow>%pages%"),
                "", page, pages)));

        if (page < pages) {
            navigation = navigation.append(Text.component("  ")).append(button(
                    config.getString("navigation.next", "<green>Weiter »"),
                    config.getString("navigation.next-hover"),
                    page + 1, label));
        }

        return navigation;
    }

    private Component button(String text, String hover, int targetPage, String label) {
        Component component = Text.component(text);
        if (hover != null && !hover.isBlank()) {
            component = component.hoverEvent(HoverEvent.showText(
                    Text.component(hover.replace("%page%", String.valueOf(targetPage)))));
        }
        return component.clickEvent(ClickEvent.runCommand("/" + label + " help " + targetPage));
    }

    private void sendLine(CommandSender sender, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        sender.sendMessage(Text.component(line));
    }

    private static String applyVariables(String template, String title, int page, int pages) {
        return template
                .replace("%title%", title)
                .replace("%page%", String.valueOf(page))
                .replace("%pages%", String.valueOf(pages));
    }
}
