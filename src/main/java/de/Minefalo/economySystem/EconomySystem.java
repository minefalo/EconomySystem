package de.Minefalo.economySystem;

import de.Minefalo.economySystem.command.CommandRegistry;
import de.Minefalo.economySystem.command.module.AdminModule;
import de.Minefalo.economySystem.command.module.BalanceModule;
import de.Minefalo.economySystem.command.module.CreditModule;
import de.Minefalo.economySystem.command.module.GuiModule;
import de.Minefalo.economySystem.command.module.HelpModule;
import de.Minefalo.economySystem.command.module.InfoModule;
import de.Minefalo.economySystem.command.module.PayModule;
import de.Minefalo.economySystem.config.CommandsConfig;
import de.Minefalo.economySystem.config.Messages;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.config.StorageType;
import de.Minefalo.economySystem.credit.CreditService;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.economy.InterestScheduler;
import de.Minefalo.economySystem.gui.GuiManager;
import de.Minefalo.economySystem.help.HelpBook;
import de.Minefalo.economySystem.listener.PlayerConnectionListener;
import de.Minefalo.economySystem.placeholder.PlaceholderHook;
import de.Minefalo.economySystem.storage.EconomyStorage;
import de.Minefalo.economySystem.storage.StorageException;
import de.Minefalo.economySystem.storage.StorageFactory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class EconomySystem extends JavaPlugin {

    private Settings settings;
    private Messages messages;
    private CommandsConfig commandsConfig;
    private HelpBook helpBook;
    private GuiManager guiManager;
    private EconomyStorage storage;
    private EconomyService economy;
    private CreditService creditService;
    private InterestScheduler interestScheduler;
    private PlaceholderHook placeholderHook;
    private CommandRegistry commandRegistry;

    @Override
    public void onEnable() {
        try {
            settings = new Settings(this);
        } catch (RuntimeException exception) {
            getLogger().severe("settings.yml konnte nicht geladen werden: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        messages = new Messages(this);
        commandsConfig = new CommandsConfig(this);
        helpBook = new HelpBook(this);
        guiManager = new GuiManager(this);

        try {
            storage = StorageFactory.create(this, settings);
        } catch (StorageException exception) {
            getLogger().severe("Speicher konnte nicht erstellt werden: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economy = new EconomyService(this, settings, storage);
        try {
            economy.init();
        } catch (StorageException exception) {
            getLogger().severe("Der Speicher (" + settings.storageType() + ") konnte nicht initialisiert werden.");
            getLogger().severe(exception.getMessage());
            economy.shutdown();
            economy = null;
            storage = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        creditService = new CreditService(this, economy);
        try {
            creditService.init();
        } catch (StorageException exception) {
            getLogger().severe("Kredite konnten nicht geladen werden: " + exception.getMessage());
            creditService.stop();
            creditService = null;
            economy.shutdown();
            economy = null;
            storage = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy.setIncomeHandler(creditService::handleIncome);

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this, economy), this);
        getServer().getPluginManager().registerEvents(guiManager, this);

        if (!registerCommands()) {
            getLogger().severe("Die Befehle konnten nicht registriert werden. Prüfe die plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        interestScheduler = new InterestScheduler(this);
        interestScheduler.start();

        setupPlaceholders();

        getLogger().info("EconomySystem aktiviert (Speicher: " + storage.name()
                + ", Währung: " + settings.currency().name() + ").");
    }

    private void setupPlaceholders() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI nicht gefunden - Platzhalter sind deaktiviert.");
            return;
        }

        try {
            placeholderHook = new PlaceholderHook(this);
            placeholderHook.register();
            getLogger().info("PlaceholderAPI-Erweiterungen registriert (money, eco, aktuelle, economysystem).");
        } catch (Throwable throwable) {
            placeholderHook = null;
            getLogger().warning("PlaceholderAPI konnte nicht eingebunden werden: " + throwable);
        }
    }

    private boolean registerCommands() {
        // Einzige Registrierungsstelle der Module (Commands.md, Abschnitt 10.3).
        // Gruppenmodule melden ihre Kinder selbst an (CommandModule#register).
        commandRegistry = new CommandRegistry(this, commandsConfig, List.of(
                new BalanceModule(this, economy),
                new PayModule(this, economy),
                new CreditModule(this, economy, creditService),
                new GuiModule(this, economy, guiManager),
                new InfoModule(this, economy),
                new HelpModule(this, economy),
                new AdminModule(this, economy, creditService, helpBook)));

        // Alle Kurzbefehle laufen über denselben Router; ihr Pfad steht in der
        // commands.yml (Abschnitt 4.2).
        boolean registered = commandRegistry.register("money", commandRegistry, commandRegistry)
                && commandRegistry.register("pay", commandRegistry, commandRegistry)
                && commandRegistry.register("kredit", commandRegistry, commandRegistry)
                && commandRegistry.register("economysystem", commandRegistry, commandRegistry)
                && commandRegistry.register("economysystem-admin", commandRegistry, commandRegistry)
                && commandRegistry.register("economysystem-gui", commandRegistry, commandRegistry);
        if (!registered) {
            return false;
        }

        commandRegistry.registerAliases();
        commandRegistry.registerRoot();
        return true;
    }

    @Override
    public void onDisable() {
        if (placeholderHook != null) {
            placeholderHook.unregister();
            placeholderHook = null;
        }
        if (interestScheduler != null) {
            interestScheduler.stop();
            interestScheduler = null;
        }
        if (creditService != null) {
            creditService.stop();
            creditService = null;
        }
        if (guiManager != null) {
            guiManager.stop();
            guiManager = null;
        }
        if (commandRegistry != null) {
            commandRegistry.unregisterDynamicCommands();
            commandRegistry = null;
        }
        if (economy != null) {
            economy.shutdown();
            economy = null;
        } else if (storage != null) {
            storage.close();
        }
        storage = null;
    }

    /**
     * Lädt settings.yml, messages.yml, help.yml und commands.yml neu. Ein
     * Wechsel der Speicherart benötigt einen Server-Neustart.
     */
    public void reload() {
        StorageType previousType = settings.storageType();
        settings.reload();
        messages.reload();
        helpBook.reload();
        if (guiManager != null) {
            guiManager.reload();
        }
        if (commandRegistry != null) {
            commandRegistry.reload();
        }

        if (settings.storageType() != previousType) {
            getLogger().warning("storage.type wurde von " + previousType + " auf "
                    + settings.storageType() + " geändert. Ein Server-Neustart ist erforderlich.");
        }
        if (economy != null) {
            economy.reload();
        }
        if (placeholderHook != null) {
            placeholderHook.reload();
        }
        if (interestScheduler != null) {
            interestScheduler.reload();
        }
        if (creditService != null) {
            creditService.reload();
        }
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public EconomyService economy() {
        return economy;
    }

    public CreditService creditService() {
        return creditService;
    }

    /**
     * Der Router aller Befehle. Die Kurzbefehle leiten ihre Aufrufe dorthin
     * weiter, damit es nur einen Ausführungspfad gibt.
     */
    public CommandRegistry commandRouter() {
        return commandRegistry;
    }

    /**
     * Verwirft zwischengespeicherte Platzhalterwerte und aktualisiert die
     * Top-Liste, z. B. nach einem Zinszyklus oder Reload.
     */
    public void refreshPlaceholders() {
        if (placeholderHook != null) {
            placeholderHook.reload();
        }
    }
}
