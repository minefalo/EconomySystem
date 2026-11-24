package de.Minefalo.economySystem.config;

import de.Minefalo.economySystem.currency.Currency;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
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
    private boolean testsEnabled;
    private Credits credits;
    private Leaderboard leaderboard;
    private Notifications notifications;
    private Suggestions suggestions;
    private Network network;
    private ZoneId zoneId;
    private Placeholders placeholders;
    private Vault vault;
    private Audit audit;
    private int shutdownTimeoutSeconds;
    private int backupCount;
    private int reasonMaxLength;
    private int testMaxCycles;

    public Settings(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = ConfigUpdater.file(plugin, "settings.yml");
        reload();
    }

    public void reload() {
        // Die Anzahl der Sicherungen steht in der Datei, die gerade geprüft
        // wird. Deshalb vor dem Abgleich roh lesen.
        ConfigUpdater.update(plugin, "settings.yml", readBackupCount());
        config = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = ConfigUpdater.bundled(plugin, "settings.yml");
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
        BigDecimal minBalance = decimal("currency.min-balance", "-1000.00");

        this.currency = new Currency(name, symbol, format, numberFormat, locale, decimals,
                rounding, startingBalance, maxBalance, minBalance);

        String rawType = config.getString("storage.type", "YAML");
        this.storageType = parseStorageType(rawType);

        this.saveIntervalSeconds = Math.max(0, config.getInt("storage.save-interval-seconds", 300));

        String folder = config.getString("storage.yaml.folder", "accounts");
        this.yamlFolder = folder == null || folder.isBlank() ? "accounts" : folder.trim();

        this.mysql = new Mysql(
                config.getString("storage.mysql.host", "localhost"),
                clamp(config.getInt("storage.mysql.port", 3306), 1, 65535),
                config.getString("storage.mysql.database", "minecraft"),
                config.getString("storage.mysql.username", "economysystem"),
                config.getString("storage.mysql.password", ""),
                config.getString("storage.mysql.table-prefix", "economy_"),
                readSslMode(),
                clamp(config.getInt("storage.mysql.connection-timeout-ms", 5000), 1000, 60000),
                clamp(config.getInt("storage.mysql.socket-timeout-ms", 30000), 1000, 120000),
                clamp(intValue("storage.mysql.retry-seconds", 5), 1, 300)
        );

        this.interest = readInterest();

        this.testsEnabled = booleanValue("tests.enabled", false);

        this.credits = readCredits();
        this.leaderboard = readLeaderboard();
        this.notifications = readNotifications();
        this.suggestions = readSuggestions();
        this.network = readNetwork();
        this.zoneId = readZone();
        this.placeholders = readPlaceholders();
        this.vault = readVault();
        this.audit = new Audit(booleanValue("audit.enabled", true),
                clamp(intValue("audit.retention-days", 0), 0, 3650));
        this.shutdownTimeoutSeconds = clamp(
                intValue("storage.shutdown-timeout-seconds", 60), 5, 600);
        this.backupCount = clamp(intValue("config.backup-count", 1), 1, 20);
        this.reasonMaxLength = clamp(intValue("commands.reason-max-length", 160), 1, 160);
        this.testMaxCycles = clamp(intValue("tests.max-cycles", 1000), 1, 10_000);
    }

    /**
     * Liest {@code config.backup-count} vor dem Abgleich. Die Einstellung steht
     * in derselben Datei, die dabei gesichert wird; deshalb wird sie roh aus
     * dem vorhandenen Stand gelesen.
     */
    private int readBackupCount() {
        File existing = ConfigUpdater.file(plugin, "settings.yml");
        if (!existing.exists()) {
            return 1;
        }
        int configured = YamlConfiguration.loadConfiguration(existing)
                .getInt("config.backup-count", 1);
        return clamp(configured, 1, 20);
    }

    /**
     * Liest {@code network} (Abschnitt 11). Fehlende Schlüssel fallen auf die
     * Einzelserver-Werte zurück; nur {@code READ_ONLY}, ein abweichender Scope
     * oder ein anderer Transfer-Modus ändern das Verhalten.
     */
    private Network readNetwork() {
        String id = text("network.id", "default");
        String group = text("network.group", "default");
        EconomyScope scope = parseEnum(EconomyScope.class, "network.economy-scope", "SHARED");
        ServerMode mode = parseEnum(ServerMode.class, "network.mode", "FULL");
        Invalidation invalidation = parseEnum(Invalidation.class, "network.invalidation", "TTL");
        int cacheTtl = clamp(intValue("network.cache-ttl-seconds", 60), 0, 86_400);
        int leaseTtl = clamp(intValue("network.lease-ttl-seconds", 300), 60, 3600);
        TransferMode transferMode = parseEnum(TransferMode.class, "network.transfer.mode", "LOCAL");
        int timeout = clamp(intValue("network.transfer.timeout-seconds", 30), 1, 600);

        Limits limits = new Limits(
                readLimit("pay", 10, 60, 3),
                readLimit("credit.take", 3, 300, 10),
                readLimit("admin.balance", 60, 60, 0),
                readLimit("gui", 0, 60, 2),
                readLimit("balance.top", 0, 60, 2));

        if (id.isBlank()) {
            plugin.getLogger().warning("network.id ist leer - verwende 'default'.");
            id = "default";
        }
        return new Network(id, group, scope, mode, invalidation, cacheTtl, leaseTtl,
                new Transfer(transferMode, timeout), limits);
    }

    /**
     * Liest eine Limit-Gruppe. Gruppen wie {@code credit.take} liegen
     * verschachtelt in der YAML-Datei ({@code credit: take:}); Bukkit löst
     * den Punkt im Pfad deshalb korrekt auf.
     */
    private RateLimit readLimit(String group, int count, int seconds, int cooldown) {
        return readLimit(config.getConfigurationSection("network.limits"),
                group, count, seconds, cooldown);
    }

    static RateLimit readLimit(ConfigurationSection limits, String group,
                               int count, int seconds, int cooldown) {
        ConfigurationSection section = limits == null ? null
                : limits.getConfigurationSection(group);
        return new RateLimit(
                Math.max(0, sectionInt(section, "count", count)),
                Math.max(1, sectionInt(section, "seconds", seconds)),
                Math.max(0, sectionInt(section, "cooldown-seconds", cooldown)));
    }

    private static int sectionInt(ConfigurationSection section, String key, int fallback) {
        if (section == null) {
            return fallback;
        }
        Object raw = section.get(key);
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

    /**
     * Liest einen Textwert; ein unbekannter Wert wird gemeldet und durch den
     * Standard ersetzt, statt den Start zu verhindern.
     */
    private String text(String path, String fallback) {
        String raw = config.getString(path, fallback);
        return raw == null ? fallback : raw.trim();
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String path, String fallback) {
        String raw = text(path, fallback);
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Unbekannter Wert für " + path + ": '" + raw
                    + "' - verwende " + fallback + ".");
            return Enum.valueOf(type, fallback);
        }
    }

    /**
     * Liest {@code storage.mysql.ssl-mode}. Leere Werte fallen auf das
     * bisherige {@code use-ssl} zurück, damit bestehende Konfigurationen
     * unverändert funktionieren.
     */
    private String readSslMode() {
        String raw = config.getString("storage.mysql.ssl-mode", "");
        if (raw == null || raw.isBlank()) {
            return config.getBoolean("storage.mysql.use-ssl", true)
                    ? "VERIFY_IDENTITY"
                    : "DISABLED";
        }

        String mode = raw.trim().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY" -> mode;
            default -> {
                plugin.getLogger().warning("Unbekannter storage.mysql.ssl-mode '" + raw
                        + "' - verwende VERIFY_IDENTITY.");
                yield "VERIFY_IDENTITY";
            }
        };
    }

    private Credits readCredits() {
        boolean enabled = booleanValue("credits.enabled", true);
        int maxLoans = clamp(intValue("credits.max-loans", 3), 1, 100);
        BigDecimal limitMultiplier = positiveDecimal("credits.limit-multiplier", "3.0");
        BigDecimal slope = nonNegativeDecimal("credits.slope", "0.5");
        BigDecimal minAmount = nonNegativeDecimal("credits.minimum-amount", "10.00");
        int minDays = clamp(intValue("credits.min-days", 1), 1, 3650);
        int maxDays = clamp(intValue("credits.max-days", 365), 1, 3650);
        if (maxDays < minDays) {
            plugin.getLogger().warning("credits.max-days ist kleiner als credits.min-days - verwende "
                    + minDays + ".");
            maxDays = minDays;
        }
        int checkInterval = clamp(intValue("credits.check-interval-seconds", 60), 5, 3600);
        String dateFormat = dateFormat("credits.date-format", "dd.MM.yyyy HH:mm");
        return new Credits(enabled, maxLoans, limitMultiplier, slope, minAmount,
                minDays, maxDays, checkInterval, dateFormat);
    }

    private Interest readInterest() {
        boolean enabled = booleanValue("interest.enabled", true);
        LocalTime startTime = parseTime(config.getString("interest.start-time", "00:01"));
        int intervalMinutes = clamp(intValue("interest.interval-minutes", 1440),
                1, MAX_INTERVAL_MINUTES);
        BigDecimal savingsFactor = positiveDecimal("interest.factors.spar", "1.3");
        BigDecimal creditFactor = positiveDecimal("interest.factors.kredit", "1.3");
        boolean notifyPlayers = booleanValue("interest.notify-players", true);
        String executor = text("interest.executor", "auto");
        if (executor.isBlank()) {
            executor = "auto";
        }
        return new Interest(enabled, startTime, intervalMinutes, savingsFactor, creditFactor,
                notifyPlayers, executor);
    }

    private Leaderboard readLeaderboard() {
        int pageSize = clamp(intValue("leaderboard.page-size", 10), 1, 50);
        int maxEntries = clamp(intValue("leaderboard.max-entries", 100), pageSize, 1000);
        boolean includeZero = booleanValue("leaderboard.include-zero-balances", false);
        return new Leaderboard(pageSize, maxEntries, includeZero);
    }

    private Notifications readNotifications() {
        boolean balanceOnJoin = booleanValue("notifications.balance-on-join", true);
        int delay = clamp(intValue("notifications.balance-on-join-delay-ticks", 20), 0, 200);
        return new Notifications(balanceOnJoin, delay);
    }

    /**
     * Liest {@code time-zone}. Ein leerer Wert verwendet die Zeitzone des
     * Servers; ein ungültiger Wert wird gemeldet und ebenfalls ersetzt.
     */
    private ZoneId readZone() {
        String raw = config.getString("time-zone", "");
        if (raw == null || raw.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(raw.trim());
        } catch (DateTimeException exception) {
            plugin.getLogger().warning("Unbekannte time-zone '" + raw
                    + "' - verwende die Zeitzone des Servers.");
            return ZoneId.systemDefault();
        }
    }

    private Placeholders readPlaceholders() {
        int ttl = clamp(intValue("placeholder.cache-ttl-seconds", 30), 1, 3600);
        int negativeTtl = clamp(intValue("placeholder.negative-cache-ttl-seconds", 10), 1, 3600);
        int retention = clamp(intValue("placeholder.cache-retention-seconds", 300), ttl, 86_400);
        int topSize = clamp(intValue("placeholder.top-size", 50), 1, 1000);
        int refresh = clamp(intValue("placeholder.top-refresh-seconds", 60), 5, 3600);
        return new Placeholders(ttl, negativeTtl, retention, topSize, refresh);
    }

    /**
     * Liest {@code vault}. Die Anbindung ist optional; ohne installiertes
     * Vault bleibt der Block wirkungslos.
     */
    private Vault readVault() {
        boolean enabled = booleanValue("vault.enabled", true);
        VaultPriority priority = parseEnum(VaultPriority.class, "vault.priority", "HIGH");
        int timeout = clamp(intValue("vault.timeout-ms", 3000), 500, 30_000);
        String singular = text("vault.currency-name-singular", "");
        return new Vault(enabled, priority, timeout, singular);
    }

    private Suggestions readSuggestions() {
        return new Suggestions(
                textList("suggestions.amounts", "1", "10", "100", "1000", "10000"),
                textList("suggestions.credit-amounts", "100", "500", "1000", "5000"),
                textList("suggestions.repay-amounts", "10", "50", "100", "500"),
                textList("suggestions.credit-days", "7", "30", "90"));
    }

    /**
     * Liest eine Liste von Textwerten (z. B. Tab-Vorschläge). Zahlen in der
     * YAML-Datei werden ebenfalls akzeptiert.
     */
    private List<String> textList(String path, String... defaults) {
        List<?> raw = config.getList(path);
        List<String> values = new ArrayList<>();
        if (raw != null) {
            for (Object item : raw) {
                if (item == null) {
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    values.add(text);
                }
            }
        }
        if (raw == null) {
            values.addAll(List.of(defaults));
        }
        return List.copyOf(values);
    }

    /**
     * Prüft ein Datumsformat, damit ein Tippfehler nicht erst beim Anzeigen
     * eines Kredits auffällt.
     */
    private String dateFormat(String path, String fallback) {
        String raw = config.getString(path, fallback);
        if (raw == null || raw.isBlank()) {
            plugin.getLogger().warning("Leeres Datumsformat für " + path
                    + " - verwende " + fallback + ".");
            return fallback;
        }
        try {
            DateTimeFormatter.ofPattern(raw);
            return raw;
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Ungültiges Datumsformat für " + path + ": '" + raw
                    + "' - verwende " + fallback + ".");
            return fallback;
        }
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

        String before;
        try {
            before = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("settings.yml konnte nicht gelesen werden: "
                    + exception.getMessage(), exception);
        }

        List<String> lines = new ArrayList<>(List.of(before.split("\\R", -1)));
        YamlFactorEditor.writeFactor(lines, key, value);
        String lineSeparator = before.contains("\r\n") ? "\r\n" : "\n";
        String after = String.join(lineSeparator, lines);

        try {
            ConfigFileWriter.writeIfChanged(file.toPath(), before, after,
                    ConfigUpdater.backupFolder(plugin).toPath(), backupCount, plugin.getLogger());
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
            RoundingMode mode = RoundingMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            if (mode == RoundingMode.UNNECESSARY) {
                plugin.getLogger().warning("Rundungsmodus UNNECESSARY ist für berechnete Beträge nicht geeignet - verwende HALF_UP.");
                return RoundingMode.HALF_UP;
            }
            return mode;
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

    private BigDecimal nonNegativeDecimal(String path, String fallback) {
        Object raw = config.get(path);
        String text = raw == null ? fallback : raw.toString().trim();
        try {
            BigDecimal value = new BigDecimal(text);
            if (value.signum() >= 0) {
                return value;
            }
            plugin.getLogger().warning("Wert für " + path + " darf nicht negativ sein - verwende " + fallback + ".");
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

    /**
     * Ob die Test-Befehle unter {@code /economysystem-admin tests}
     * freigeschaltet sind ({@code tests.enabled}).
     */
    public boolean testsEnabled() {
        return testsEnabled;
    }

    /**
     * Einstellungen des Kreditsystems.
     */
    public Credits credits() {
        return credits;
    }

    public Leaderboard leaderboard() {
        return leaderboard;
    }

    public Notifications notifications() {
        return notifications;
    }

    public Suggestions suggestions() {
        return suggestions;
    }

    /**
     * Netzwerkeinstellungen aus Abschnitt 11. Ohne eigene Angaben gelten die
     * Einzelserver-Werte ({@code shared}, {@code FULL}, {@code LOCAL}).
     */
    public Network network() {
        return network;
    }

    /**
     * Zeitzone für Zinsplan und Datumsanzeigen ({@code time-zone}).
     */
    public ZoneId zoneId() {
        return zoneId;
    }

    /**
     * Einstellungen der PlaceholderAPI-Erweiterung ({@code placeholder}).
     */
    public Placeholders placeholders() {
        return placeholders;
    }

    /**
     * Einstellungen der Vault-Anbindung ({@code vault}).
     */
    public Vault vault() {
        return vault;
    }

    /**
     * Audit-Einstellungen ({@code audit}).
     */
    public Audit audit() {
        return audit;
    }

    public int shutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    public int backupCount() {
        return backupCount;
    }

    public int reasonMaxLength() {
        return reasonMaxLength;
    }

    public int testMaxCycles() {
        return testMaxCycles;
    }

    /**
     * Wer teilt sich Guthaben? (Abschnitt 11.1)
     */
    public enum EconomyScope {
        SHARED,
        GROUP,
        SERVER
    }

    /**
     * Betriebsmodus dieses Servers (Abschnitt 11.8).
     */
    public enum ServerMode {
        FULL,
        READ_ONLY
    }

    /**
     * Verbreitungsweg für Cache-Invalidierung (Abschnitt 11.3).
     */
    public enum Invalidation {
        NONE,
        TTL,
        PLUGIN_MESSAGE,
        REDIS
    }

    /**
     * Zustellung von {@code /eco pay} (Abschnitt 11.2).
     */
    public enum TransferMode {
        LOCAL,
        PROXY,
        ANY
    }

    /**
     * @param id           Name dieses Servers in der Datenbank
     * @param group        Gruppe für {@code economy-scope: group}
     * @param scope        wer sich Guthaben teilt
     * @param mode         {@code FULL} oder {@code READ_ONLY}
     * @param invalidation Verbreitungsweg für Cache-Invalidierung
     * @param cacheTtlSeconds Lebensdauer zwischengespeicherter Kontostände
     * @param leaseTtlSeconds Laufzeit der Zins-Lease
     * @param transfer     Zustellung von {@code /eco pay}
     * @param limits       Rate-Limits je Spieler und Pfad
     */
    public record Network(String id,
                          String group,
                          EconomyScope scope,
                          ServerMode mode,
                          Invalidation invalidation,
                          int cacheTtlSeconds,
                          int leaseTtlSeconds,
                          Transfer transfer,
                          Limits limits) {

        public boolean readOnly() {
            return mode == ServerMode.READ_ONLY;
        }

        /**
         * Kennung des Scopes in der Datenbank (Abschnitt 11.1).
         */
        public String scopeKey() {
            return switch (scope) {
                case SHARED -> "global";
                case GROUP -> "group:" + group;
                case SERVER -> "server:" + id;
            };
        }
    }

    /**
     * @param mode           {@code LOCAL}, {@code PROXY} oder {@code ANY}
     * @param timeoutSeconds Zeit bis zur Rückbuchung einer nicht zugestellten Überweisung
     */
    public record Transfer(TransferMode mode, int timeoutSeconds) {
    }

    /**
     * Rate-Limit eines Pfades (Abschnitt 11.7).
     */
    public record RateLimit(int count, int seconds, int cooldownSeconds) {
    }

    public record Limits(RateLimit pay, RateLimit creditTake, RateLimit adminBalance,
                         RateLimit gui, RateLimit balanceTop) {
    }

    /**
     * Einstellungen des Kreditsystems.
     *
     * @param enabled              ob Spieler Kredite aufnehmen können
     * @param maxLoans             maximale Anzahl gleichzeitiger Kredite pro Spieler
     * @param limitMultiplier      Kreditrahmen = Multiplikator * (Guthaben - offene Kredite)
     * @param slope                m in {@code y = x * m + a} (Zinsen pro Tag)
     * @param minimumAmount        Mindestbetrag eines Kredits
     * @param minDays              minimale Laufzeit in Tagen
     * @param maxDays              maximale Laufzeit in Tagen
     * @param checkIntervalSeconds Intervall, in dem fällige Kredite geprüft werden
     * @param dateFormat           Format für Fälligkeitsdaten
     */
    public record Credits(boolean enabled,
                          int maxLoans,
                          BigDecimal limitMultiplier,
                          BigDecimal slope,
                          BigDecimal minimumAmount,
                          int minDays,
                          int maxDays,
                          int checkIntervalSeconds,
                          String dateFormat) {

        /**
         * Zinsen für eine Laufzeit nach {@code y = x * m + a}.
         *
         * @param days          Laufzeit in Tagen (x)
         * @param creditFactor  Kreditzins (a)
         */
        public BigDecimal interestFor(int days, BigDecimal creditFactor) {
            return slope.multiply(BigDecimal.valueOf(days)).add(creditFactor);
        }
    }

    public record Mysql(String host,
                        int port,
                        String database,
                        String username,
                        String password,
                        String tablePrefix,
                        String sslMode,
                        int connectionTimeoutMs,
                        int socketTimeoutMs,
                        int retrySeconds) {
    }

    /**
     * Einstellungen des Zinszyklus.
     *
     * @param enabled         ob der Zyklus automatisch läuft
     * @param startTime       Uhrzeit des ersten Laufs (Zeit in {@code time-zone})
     * @param intervalMinutes Abstand zwischen zwei Läufen in Minuten
     * @param savingsFactor   a im Sparzins (y = a * sqrt(x))
     * @param creditFactor    a im Kreditzins für neue Kredite
     * @param notifyPlayers   Online-Spieler über ihre Gutschrift informieren
     * @param executor        {@code auto} (Lease) oder eine Server-ID aus network.id
     */
    public record Interest(boolean enabled,
                           LocalTime startTime,
                           int intervalMinutes,
                           BigDecimal savingsFactor,
                           BigDecimal creditFactor,
                           boolean notifyPlayers,
                           String executor) {

        public BigDecimal factor(InterestType type) {
            return type == InterestType.SAVINGS ? savingsFactor : creditFactor;
        }

        public Interest withFactor(InterestType type, BigDecimal factor) {
            return type == InterestType.SAVINGS
                    ? new Interest(enabled, startTime, intervalMinutes, factor, creditFactor,
                            notifyPlayers, executor)
                    : new Interest(enabled, startTime, intervalMinutes, savingsFactor, factor,
                            notifyPlayers, executor);
        }

        /**
         * @return {@code true}, wenn dieser Server den Zyklus ausführen darf
         *         (bei {@code auto} entscheidet zusätzlich die Lease)
         */
        public boolean runsOn(String serverId) {
            return executor == null || executor.isBlank() || executor.equalsIgnoreCase("auto")
                    || executor.equalsIgnoreCase(serverId);
        }
    }

    /**
     * Einstellungen für /money top.
     *
     * @param pageSize            Einträge pro Seite
     * @param maxEntries          maximale Anzahl geladener Einträge
     * @param includeZeroBalances auch Konten mit Guthaben 0 aufnehmen
     */
    public record Leaderboard(int pageSize, int maxEntries, boolean includeZeroBalances) {
    }

    /**
     * Kleine Benachrichtigungen für Spieler.
     *
     * @param balanceOnJoin            Guthaben nach dem Beitritt anzeigen
     * @param balanceOnJoinDelayTicks  Verzögerung in Ticks (20 = 1 Sekunde)
     */
    public record Notifications(boolean balanceOnJoin, int balanceOnJoinDelayTicks) {
    }

    /**
     * Einstellungen der PlaceholderAPI-Erweiterung.
     *
     * @param cacheTtlSeconds         Lebensdauer zwischengespeicherter Offline-Guthaben
     * @param negativeCacheTtlSeconds Lebensdauer für "kein Konto vorhanden"
     * @param cacheRetentionSeconds   Aufräumalter des Caches
     * @param topSize                 Plätze der Platzhalter-Top-Liste
     * @param topRefreshSeconds       Aktualisierungsintervall der Top-Liste
     */
    public record Placeholders(int cacheTtlSeconds,
                               int negativeCacheTtlSeconds,
                               int cacheRetentionSeconds,
                               int topSize,
                               int topRefreshSeconds) {
    }

    /**
     * Einstellungen der Vault-Anbindung.
     *
     * @param enabled               Provider registrieren
     * @param priority              Priorität bei mehreren Vault-Anbietern
     * @param timeoutMillis         Frist für Lade- und Buchungsaufrufe
     * @param currencyNameSingular  Einzahlungsname; leer = {@code currency.name}
     */
    public record Vault(boolean enabled,
                        VaultPriority priority,
                        int timeoutMillis,
                        String currencyNameSingular) {
    }

    /**
     * Priorität der Vault-Registrierung (entspricht den Bukkit-Service-
     * Prioritäten).
     */
    public enum VaultPriority {
        LOWEST,
        LOW,
        NORMAL,
        HIGH,
        HIGHEST
    }

    /**
     * Audit-Einstellungen.
     *
     * @param enabled       Audit-Einträge für Fernbuchungen schreiben
     * @param retentionDays Aufbewahrung in Tagen; 0 = unbegrenzt
     */
    public record Audit(boolean enabled, int retentionDays) {
    }

    /**
     * Vorschläge für die Tab-Vervollständigung. Die Werte werden unverändert
     * angezeigt; Beträge und Tage werden nicht zusätzlich validiert.
     */
    public record Suggestions(List<String> amounts,
                              List<String> creditAmounts,
                              List<String> repayAmounts,
                              List<String> creditDays) {
    }
}
