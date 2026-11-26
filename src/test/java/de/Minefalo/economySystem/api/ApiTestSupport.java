package de.Minefalo.economySystem.api;

import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.storage.EconomyStorage;
import de.Minefalo.economySystem.storage.StorageException;
import de.Minefalo.economySystem.storage.YamlStorage;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import sun.misc.Unsafe;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Unterbau der API-Tests ohne laufenden Paper-Server.
 *
 * <p>Der {@link JavaPlugin} kann im Unit-Test nicht regulär erzeugt werden
 * (Paper verlangt einen Plugin-ClassLoader). Für die Tests wird deshalb eine
 * Instanz ohne Konstruktor erzeugt und mit Datenordner, Server und Logger
 * versehen. Der {@link Server} ist ein dynamischer Proxy: Scheduler-Aufrufe
 * verpuffen, Events werden mitgeschrieben und {@code isPrimaryThread()} ist
 * {@code false}, damit Konten wie im echten Storage-Executor geladen
 * werden.</p>
 */
final class ApiTestSupport {

    private static final List<Event> EVENTS = Collections.synchronizedList(new ArrayList<>());
    private static final Unsafe UNSAFE = unsafe();
    private static boolean serverInstalled;

    private ApiTestSupport() {
    }

    static void clearEvents() {
        EVENTS.clear();
    }

    static List<Event> events() {
        synchronized (EVENTS) {
            return List.copyOf(EVENTS);
        }
    }

    private static synchronized void installServer() {
        if (serverInstalled) {
            return;
        }

        BukkitScheduler scheduler = proxy(BukkitScheduler.class,
                (instance, method, arguments) -> defaultValue(method.getReturnType()));
        PluginManager pluginManager = proxy(PluginManager.class,
                (instance, method, arguments) -> {
                    if ("callEvent".equals(method.getName())) {
                        EVENTS.add((Event) arguments[0]);
                    }
                    return defaultValue(method.getReturnType());
                });
        Server server = proxy(Server.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getScheduler" -> scheduler;
            case "getPluginManager" -> pluginManager;
            case "getName" -> "TestServer";
            case "getLogger" -> Logger.getLogger("TestServer");
            case "isPrimaryThread" -> false;
            default -> defaultValue(method.getReturnType());
        });

        try {
            Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Bukkit-Testserver konnte nicht installiert werden", exception);
        }
        serverInstalled = true;
    }

    static JavaPlugin plugin(File dataFolder) {
        installServer();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("Testordner konnte nicht erstellt werden: " + dataFolder);
        }
        try {
            JavaPlugin plugin = (JavaPlugin) UNSAFE.allocateInstance(TestPlugin.class);
            setField(plugin, "dataFolder", dataFolder);
            setField(plugin, "server", Bukkit.getServer());
            setField(plugin, "logger", Logger.getLogger("EconomySystemTest"));
            setField(plugin, "isEnabled", true);
            setField(plugin, "classLoader", ApiTestSupport.class.getClassLoader());
            return plugin;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Test-Plugin konnte nicht erzeugt werden", exception);
        }
    }

    private static void writeSettings(File dataFolder, String mode, String maxBalance)
            throws IOException {
        File configFolder = new File(dataFolder, "config");
        if (!configFolder.exists() && !configFolder.mkdirs()) {
            throw new IOException("Konfigurationsordner konnte nicht erstellt werden: " + configFolder);
        }
        String yaml = """
                currency:
                  symbol: "€"
                  format: "%amount% %symbol%"
                  number-format: "#,##0.00"
                  locale: de-DE
                  decimals: 2
                  rounding: HALF_UP
                  starting-balance: "100.00"
                  max-balance: "__MAX_BALANCE__"
                  min-balance: "0"
                network:
                  id: test
                  mode: __MODE__
                audit:
                  enabled: true
                  retention-days: 0
                storage:
                  type: YAML
                  save-interval-seconds: 0
                """
                .replace("__MAX_BALANCE__", maxBalance)
                .replace("__MODE__", mode);
        Files.writeString(new File(configFolder, "settings.yml").toPath(), yaml,
                StandardCharsets.UTF_8);
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException exception) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(ApiTestSupport.class.getClassLoader(),
                new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsafe ist nicht verfügbar", exception);
        }
    }

    private static final class TestPlugin extends JavaPlugin {
    }

    /** Speicher, der jede idempotente Buchung mit einem Fehler abbricht. */
    private static EconomyStorage failingStorage(EconomyStorage delegate) {
        return (EconomyStorage) Proxy.newProxyInstance(
                ApiTestSupport.class.getClassLoader(),
                new Class<?>[]{EconomyStorage.class},
                (proxy, method, arguments) -> {
                    if ("saveIdempotent".equals(method.getName())) {
                        throw new StorageException("Testfehler beim Schreiben");
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    /**
     * Ein vollständiger Satz aus Plugin, Einstellungen, YAML-Speicher und
     * Economy-Service auf einem temporären Ordner.
     */
    static final class Fixture implements AutoCloseable {

        final File folder;
        final JavaPlugin plugin;
        final Settings settings;
        final YamlStorage storage;
        final EconomyService economy;
        final EconomyApi api;

        Fixture(File folder, boolean readOnly, String maxBalance) throws IOException {
            this(folder, readOnly, maxBalance, false);
        }

        /**
         * @param failIdempotentWrites lässt jede idempotente Buchung mit einem
         *                             Speicherfehler scheitern (für den
         *                             Rückroll-Test)
         */
        Fixture(File folder, boolean readOnly, String maxBalance,
                boolean failIdempotentWrites) throws IOException {
            this.folder = folder;
            writeSettings(folder, readOnly ? "READ_ONLY" : "FULL", maxBalance);
            this.plugin = plugin(folder);
            this.settings = new Settings(plugin);
            this.storage = new YamlStorage(plugin, settings);
            EconomyStorage effective = failIdempotentWrites
                    ? failingStorage(storage) : storage;
            this.economy = new EconomyService(plugin, settings, effective);
            this.economy.init();
            this.api = new EconomyApiImpl(economy);
        }

        @Override
        public void close() {
            economy.shutdown();
        }
    }
}
