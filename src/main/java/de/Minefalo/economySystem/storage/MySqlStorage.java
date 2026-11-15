package de.Minefalo.economySystem.storage;

import de.Minefalo.economySystem.config.Settings;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * MySQL-Speicher. Die Tabelle wird beim Start automatisch angelegt.
 * Geldbeträge liegen als {@code DECIMAL(30,8)} vor und werden verlustfrei
 * als {@link BigDecimal} gelesen und geschrieben.
 */
public final class MySqlStorage implements EconomyStorage {

    private final JavaPlugin plugin;
    private final Settings.Mysql config;
    private final String table;
    private final String upsertSql;
    private final Object lock = new Object();

    private Connection connection;

    public MySqlStorage(JavaPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.config = settings.mysql();

        String prefix = config.tablePrefix() == null ? "" : config.tablePrefix().trim();
        if (!prefix.matches("[A-Za-z0-9_]{0,40}")) {
            throw new StorageException("Ungültiges table-prefix in der settings.yml: '" + prefix
                    + "' (erlaubt: A-Z, a-z, 0-9 und _, maximal 40 Zeichen)");
        }

        this.table = prefix + "accounts";
        this.upsertSql = "INSERT INTO `" + table + "` (`uuid`, `name`, `balance`, `updated_at`) "
                + "VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE `name` = ?, `balance` = ?, `updated_at` = ?";
    }

    @Override
    public String name() {
        return "MySQL";
    }

    @Override
    public void init() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException exception) {
            throw new StorageException("MySQL-Treiber nicht gefunden. "
                    + "Prüfe den Eintrag 'libraries' in der plugin.yml.", exception);
        }

        synchronized (lock) {
            try {
                connection = openConnection();
                createTable(connection);
            } catch (SQLException exception) {
                closeConnection();
                throw new StorageException("Verbindung zu MySQL fehlgeschlagen ("
                        + config.host() + ":" + config.port() + "/" + config.database() + "): "
                        + exception.getMessage(), exception);
            }
        }

        plugin.getLogger().info("MySQL verbunden (" + config.host() + ":" + config.port()
                + "/" + config.database() + ", Tabelle '" + table + "').");
    }

    private Connection openConnection() throws SQLException {
        String url = "jdbc:mysql://" + config.host() + ":" + config.port() + "/" + config.database()
                + "?useSSL=" + config.useSsl()
                + "&allowPublicKeyRetrieval=true"
                + "&autoReconnect=true"
                + "&characterEncoding=UTF-8"
                + "&connectionTimeZone=UTC"
                + "&connectTimeout=" + config.connectionTimeoutMs()
                + "&socketTimeout=30000";

        Properties properties = new Properties();
        properties.setProperty("user", config.username() == null ? "" : config.username());
        properties.setProperty("password", config.password() == null ? "" : config.password());
        return DriverManager.getConnection(url, properties);
    }

    private void createTable(Connection current) throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS `" + table + "` ("
                + "`uuid` CHAR(36) NOT NULL,"
                + "`name` VARCHAR(64) NOT NULL,"
                + "`balance` DECIMAL(30,8) NOT NULL DEFAULT 0,"
                + "`updated_at` BIGINT NOT NULL,"
                + "PRIMARY KEY (`uuid`),"
                + "KEY `idx_name` (`name`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        try (Statement statement = current.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Liefert eine gültige Verbindung und baut sie bei Bedarf neu auf.
     * Muss innerhalb von {@link #lock} aufgerufen werden.
     */
    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(3)) {
            closeConnection();
            connection = openConnection();
            createTable(connection);
        }
        return connection;
    }

    @Override
    public Optional<AccountData> load(UUID uniqueId) {
        String sql = "SELECT `name`, `balance` FROM `" + table + "` WHERE `uuid` = ?";
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, uniqueId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new AccountData(uniqueId,
                            result.getString("name"),
                            result.getBigDecimal("balance")));
                }
            } catch (SQLException exception) {
                throw fail("laden", exception);
            }
        }
    }

    @Override
    public Optional<AccountData> loadByName(String name) {
        String sql = "SELECT `uuid`, `name`, `balance` FROM `" + table + "` "
                + "WHERE LOWER(`name`) = LOWER(?) ORDER BY `updated_at` DESC LIMIT 1";
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(readAccount(result));
                }
            } catch (SQLException exception) {
                throw fail("laden", exception);
            }
        }
    }

    @Override
    public List<AccountData> loadAll() {
        String sql = "SELECT `uuid`, `name`, `balance` FROM `" + table + "`";
        List<AccountData> accounts = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    accounts.add(readAccount(result));
                }
            } catch (SQLException exception) {
                throw fail("laden", exception);
            }
        }
        return accounts;
    }

    private static AccountData readAccount(ResultSet result) throws SQLException {
        UUID uniqueId = UUID.fromString(result.getString("uuid"));
        String name = result.getString("name");
        BigDecimal balance = result.getBigDecimal("balance");
        return new AccountData(uniqueId, name, balance);
    }

    @Override
    public void save(AccountData account) {
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(upsertSql)) {
                bindUpsert(statement, account);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw fail("speichern", exception);
            }
        }
    }

    @Override
    public void saveAll(Collection<AccountData> accounts) {
        if (accounts.isEmpty()) {
            return;
        }

        synchronized (lock) {
            Connection current = null;
            boolean autoCommit = true;
            try {
                current = connection();
                autoCommit = current.getAutoCommit();
                current.setAutoCommit(false);

                try (PreparedStatement statement = current.prepareStatement(upsertSql)) {
                    for (AccountData account : accounts) {
                        bindUpsert(statement, account);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                current.commit();
            } catch (SQLException exception) {
                if (current != null) {
                    try {
                        current.rollback();
                    } catch (SQLException rollbackException) {
                        exception.addSuppressed(rollbackException);
                    }
                }
                throw fail("speichern", exception);
            } finally {
                if (current != null) {
                    try {
                        current.setAutoCommit(autoCommit);
                    } catch (SQLException ignored) {
                        // Verbindung wird beim nächsten Zugriff ohnehin geprüft.
                    }
                }
            }
        }
    }

    private static void bindUpsert(PreparedStatement statement, AccountData account) throws SQLException {
        long now = System.currentTimeMillis();
        statement.setString(1, account.uniqueId().toString());
        statement.setString(2, account.name());
        statement.setBigDecimal(3, account.balance());
        statement.setLong(4, now);
        statement.setString(5, account.name());
        statement.setBigDecimal(6, account.balance());
        statement.setLong(7, now);
    }

    private StorageException fail(String action, SQLException exception) {
        // Defekte Verbindung verwerfen, damit der nächste Aufruf neu verbindet.
        closeConnection();
        return new StorageException("MySQL: Konto konnte nicht " + action
                + " werden: " + exception.getMessage(), exception);
    }

    private void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Nichts mehr zu tun.
            }
            connection = null;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeConnection();
        }
    }
}
