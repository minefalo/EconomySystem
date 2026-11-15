package de.Minefalo.economySystem.config;

import de.Minefalo.economySystem.currency.Currency;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lädt und hält die Einstellungen aus der settings.yml.
 */
public final class Settings {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");
    private static final int MAX_INTERVAL_MINUTES = 525_600;

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration config;

    private Currency currency;
    private StorageType storageType;
    private int saveIntervalSeconds;
    private String yamlFolder;
    private Mysql mysql;
    private Interest interest;

    public Settings(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "settings.yml");
        reload();
    }

    public void reload() {
        if (!file.exists()) {
            plugin.saveResource("settings.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = loadDefaults();
        if (defaults != null) {
            // Fehlende Schlüssel fallen auf die Standardwerte zurück, ohne die
            // Datei (und damit ihre Kommentare) neu zu schreiben.
            config.setDefaults(defaults);
        }

        readValues();
    }

    private void readValues() {
        String name = config.getString("currency.name", "Coins");
        String symbol = config.getString("currency.symbol", "$");
        String format = config.getString("currency.format", "%symbol%%amount%");
        String numberFormat = config.getString("currency.number-format", "#,##0.00");
        Locale locale = parseLocale(config.getString("currency.locale", "de-DE"));
        int decimals = clamp(config.getInt("currency.decimals", 2), 0, 8);
        RoundingMode rounding = parseRounding(config.getString("currency.rounding", "HALF_UP"));
        BigDecimal startingBalance = decimal("currency.starting-balance", "100.00");
        BigDecimal maxBalance = decimal("currency.max-balance", "-1");

        this.currency = new Currency(name, symbol, format, numberFormat, locale, decimals,
                rounding, startingBalance, maxBalance);

        String rawType = config.getString("storage.type", "YAML");
        this.storageType = parseStorageType(rawType);

        this.saveIntervalSeconds = Math.max(0, config.getInt("storage.save-interval-seconds", 300));

        String folder = config.getString("storage.yaml.folder", "accounts");
        this.yamlFolder = folder == null || folder.isBlank() ? "accounts" : folder.trim();

        this.mysql = new Mysql(
                config.getString("storage.mysql.host", "localhost"),
                clamp(config.getInt("storage.mysql.port", 3306), 1, 65535),
                config.getString("storage.mysql.database", "minecraft"),
                config.getString("storage.mysql.username", "root"),
                config.getString("storage.mysql.password", ""),
                config.getString("storage.mysql.table-prefix", "economy_"),
                config.getBoolean("storage.mysql.use-ssl", false),
                clamp(config.getInt("storage.mysql.connection-timeout-ms", 5000), 1000, 60000)
        );

        this.interest = readInterest();
    }

    private Interest readInterest() {
        boolean enabled = booleanValue("interest.enabled", true);
        LocalTime startTime = parseTime(config.getString("interest.start-time", "00:01"));
        int intervalMinutes = clamp(intValue("interest.interval-minutes", 1440),
                1, MAX_INTERVAL_MINUTES);
        BigDecimal savingsFactor = positiveDecimal("interest.factors.spar", "1.3");
        BigDecimal creditFactor = positiveDecimal("interest.factors.kredit", "1.3");
        return new Interest(enabled, startTime, intervalMinutes, savingsFactor, creditFactor);
    }

    /**
     * Speichert einen Zinsfaktor in der settings.yml. Die Datei wird dabei
     * zeilenweise bearbeitet, damit Kommentare erhalten bleiben.
     *
     * @throws IllegalArgumentException wenn der Faktor nicht größer als 0 ist
     * @throws IllegalStateException    wenn die Datei nicht geschrieben werden kann
     */
    public synchronized void setInterestFactor(InterestType type, BigDecimal factor) {
        if (factor == null || factor.signum() <= 0) {
            throw new IllegalArgumentException("Der Zinsfaktor muss größer als 0 sein.");
        }

        String key = type.configKey();
        String value = factor.stripTrailingZeros().toPlainString();

        List<String> lines;
        try {
            lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("settings.yml konnte nicht gelesen werden: "
                    + exception.getMessage(), exception);
        }

        YamlFactorEditor.writeFactor(lines, key, value);

        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("settings.yml konnte nicht gespeichert werden: "
                    + exception.getMessage(), exception);
        }

        config.set("interest.factors." + key, factor.stripTrailingZeros());
        this.interest = interest.withFactor(type, factor.stripTrailingZeros());
    }

    private StorageType parseStorageType(String raw) {
        try {
            return StorageType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Unbekannter storage.type '" + raw + "' - verwende YAML.");
            return StorageType.YAML;
        }
    }

    private RoundingMode parseRounding(String raw) {
        try {
            return RoundingMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Unbekannter Rundungsmodus '" + raw + "' - verwende HALF_UP.");
            return RoundingMode.HALF_UP;
        }
    }

    private Locale parseLocale(String raw) {
        Locale locale = Locale.forLanguageTag(raw.replace('_', '-'));
        if (locale.getLanguage().isEmpty()) {
            plugin.getLogger().warning("Unbekannte Locale '" + raw + "' - verwende de-DE.");
            return Locale.GERMANY;
        }
        return locale;
    }

    private LocalTime parseTime(String raw) {
        String value = raw == null ? "" : raw.trim();
        try {
            return LocalTime.parse(value, TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            plugin.getLogger().warning("Ungültige interest.start-time '" + raw
                    + "' - verwende 00:01 (Format HH:mm).");
            return LocalTime.of(0, 1);
        }
    }

    private BigDecimal decimal(String path, String fallback) {
        String raw = config.getString(path, fallback);
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("Ungültiger Wert für " + path + ": '" + raw + "' - verwende " + fallback + ".");
            return new BigDecimal(fallback);
        }
    }

    private BigDecimal positiveDecimal(String path, String fallback) {
        Object raw = config.get(path);
        String text = raw == null ? fallback : raw.toString().trim();
        try {
            BigDecimal value = new BigDecimal(text);
            if (value.signum() > 0) {
                return value;
            }
            plugin.getLogger().warning("Wert für " + path + " muss größer als 0 sein - verwende " + fallback + ".");
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("Ungültiger Wert für " + path + ": '" + text + "' - verwende " + fallback + ".");
        }
        return new BigDecimal(fallback);
    }

    private int intValue(String path, int fallback) {
        Object raw = config.get(path);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                // Fällt unten auf den Standardwert zurück.
            }
        }
        return fallback;
    }

    private boolean booleanValue(String path, boolean fallback) {
        Object raw = config.get(path);
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.equalsIgnoreCase("true")) {
                return true;
            }
            if (trimmed.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return fallback;
    }

    private YamlConfiguration loadDefaults() {
        try (InputStream input = plugin.getResource("settings.yml")) {
            if (input == null) {
                return null;
            }
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return defaults;
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("Standard-settings.yml konnte nicht geladen werden: " + exception.getMessage());
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public Currency currency() {
        return currency;
    }

    public StorageType storageType() {
        return storageType;
    }

    public int saveIntervalSeconds() {
        return saveIntervalSeconds;
    }

    public String yamlFolder() {
        return yamlFolder;
    }

    public Mysql mysql() {
        return mysql;
    }

    public Interest interest() {
        return interest;
    }

    public record Mysql(String host,
                        int port,
                        String database,
                        String username,
                        String password,
                        String tablePrefix,
                        boolean useSsl,
                        int connectionTimeoutMs) {
    }

    /**
     * Einstellungen des Zinszyklus.
     *
     * @param enabled         ob der Zyklus automatisch läuft
     * @param startTime       Uhrzeit des ersten Laufs (Serverzeit)
     * @param intervalMinutes Abstand zwischen zwei Läufen in Minuten
     * @param savingsFactor   a im Sparzins (y = a * sqrt(x))
     * @param creditFactor    a im Kreditzins für neue Kredite
     */
    public record Interest(boolean enabled,
                           LocalTime startTime,
                           int intervalMinutes,
                           BigDecimal savingsFactor,
                           BigDecimal creditFactor) {

        public BigDecimal factor(InterestType type) {
            return type == InterestType.SAVINGS ? savingsFactor : creditFactor;
        }

        public Interest withFactor(InterestType type, BigDecimal factor) {
            return type == InterestType.SAVINGS
                    ? new Interest(enabled, startTime, intervalMinutes, factor, creditFactor)
                    : new Interest(enabled, startTime, intervalMinutes, savingsFactor, factor);
        }
    }
}
