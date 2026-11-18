package de.Minefalo.economySystem;

import de.Minefalo.economySystem.command.CommandRegistry;
import de.Minefalo.economySystem.command.EconomySystemAdminCommand;
import de.Minefalo.economySystem.command.EconomySystemCommand;
import de.Minefalo.economySystem.command.EconomySystemGuiCommand;
import de.Minefalo.economySystem.command.KreditCommand;
import de.Minefalo.economySystem.command.MoneyCommand;
import de.Minefalo.economySystem.command.PayCommand;
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

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(economy), this);
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
        commandRegistry = new CommandRegistry(this, commandsConfig);

        MoneyCommand moneyCommand = new MoneyCommand(this, economy);
        PayCommand payCommand = new PayCommand(this, economy);
        EconomySystemCommand systemCommand = new EconomySystemCommand(this, economy);
        EconomySystemAdminCommand adminCommand = new EconomySystemAdminCommand(this, economy, helpBook);
        KreditCommand kreditCommand = new KreditCommand(this, economy, creditService);
        EconomySystemGuiCommand guiCommand = new EconomySystemGuiCommand(this, economy, guiManager);

        boolean registered = commandRegistry.register("money", moneyCommand, moneyCommand)
                && commandRegistry.register("pay", payCommand, payCommand)
                && commandRegistry.register("kredit", kreditCommand, kreditCommand)
                && commandRegistry.register("economysystem", systemCommand, systemCommand)
                && commandRegistry.register("economysystem-admin", adminCommand, adminCommand)
                && commandRegistry.register("economysystem-gui", guiCommand, guiCommand);
        if (!registered) {
            return false;
        }

        commandRegistry.registerAliases();
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
            commandRegistry.unregisterAliases();
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
     * Verwirft zwischengespeicherte Platzhalterwerte und aktualisiert die
     * Top-Liste, z. B. nach einem Zinszyklus oder Reload.
     */
    public void refreshPlaceholders() {
        if (placeholderHook != null) {
            placeholderHook.reload();
        }
    }
}
