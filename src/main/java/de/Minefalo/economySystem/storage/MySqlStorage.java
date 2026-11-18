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
    private final String loanTable;
    private final String upsertSql;
    private final String loanUpsertSql;
    private final Object lock = new Object();

    private Connection connection;
    private long retryAfterNanos;
    private static final long RETRY_DELAY_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

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

        this.loanTable = prefix + "loans";
        this.loanUpsertSql = "INSERT INTO `" + loanTable + "` (`id`, `owner`, `owner_name`, `principal`, "
                + "`interest`, `principal_remaining`, `interest_remaining`, `days`, `created_at`, `due_at`, "
                + "`auto_collect`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE `owner_name` = ?, `principal` = ?, `interest` = ?, "
                + "`principal_remaining` = ?, `interest_remaining` = ?, `days` = ?, `due_at` = ?, "
                + "`auto_collect` = ?";
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
                String hint = "DISABLED".equals(config.sslMode())
                        ? ""
                        : " Prüfe bei aktivem TLS, ob das Serverzertifikat vertrauenswürdig ist und"
                                + " zum Host '" + config.host() + "' passt, oder passe storage.mysql.ssl-mode an.";
                throw new StorageException("Verbindung zu MySQL fehlgeschlagen ("
                        + config.host() + ":" + config.port() + "/" + config.database() + "): "
                        + exception.getMessage() + hint, exception);
            }
        }

        plugin.getLogger().info("MySQL verbunden (" + config.host() + ":" + config.port()
                + "/" + config.database() + ", Tabelle '" + table + "').");
    }

    private Connection openConnection() throws SQLException {
        // Ohne TLS muss der Treiber den öffentlichen Schlüssel des Servers
        // abrufen dürfen, sonst scheitert caching_sha2_password (MySQL 8).
        boolean allowPublicKeyRetrieval = "DISABLED".equals(config.sslMode())
                || "PREFERRED".equals(config.sslMode());
        String url = "jdbc:mysql://" + config.host() + ":" + config.port() + "/" + config.database()
                + "?sslMode=" + config.sslMode()
                + "&allowPublicKeyRetrieval=" + allowPublicKeyRetrieval
                + "&characterEncoding=UTF-8"
                + "&connectionTimeZone=UTC"
                + "&connectTimeout=" + config.connectionTimeoutMs()
                + "&socketTimeout=" + config.socketTimeoutMs();

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

        String loans = "CREATE TABLE IF NOT EXISTS `" + loanTable + "` ("
                + "`id` CHAR(36) NOT NULL,"
                + "`owner` CHAR(36) NOT NULL,"
                + "`owner_name` VARCHAR(64) NOT NULL,"
                + "`principal` DECIMAL(30,8) NOT NULL,"
                + "`interest` DECIMAL(30,8) NOT NULL,"
                + "`principal_remaining` DECIMAL(30,8) NOT NULL,"
                + "`interest_remaining` DECIMAL(30,8) NOT NULL,"
                + "`days` INT NOT NULL,"
                + "`created_at` BIGINT NOT NULL,"
                + "`due_at` BIGINT NOT NULL,"
                + "`auto_collect` TINYINT(1) NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (`id`),"
                + "KEY `idx_owner` (`owner`),"
                + "KEY `idx_due` (`due_at`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        try (Statement statement = current.createStatement()) {
            statement.execute(loans);
        }
    }

    /**
     * Liefert eine gültige Verbindung und baut sie bei Bedarf neu auf.
     * Muss innerhalb von {@link #lock} aufgerufen werden.
     */
    private Connection connection() throws SQLException {
        if (connection == null && retryAfterNanos != 0 && System.nanoTime() - retryAfterNanos < 0) {
            throw new SQLException("MySQL vorübergehend nicht verfügbar; erneuter Verbindungsversuch folgt.");
        }
        if (connection == null || connection.isClosed() || !connection.isValid(3)) {
            closeConnection();
            try {
                connection = openConnection();
                retryAfterNanos = 0;
            } catch (SQLException exception) {
                retryAfterNanos = System.nanoTime() + RETRY_DELAY_NANOS;
                throw exception;
            }
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
        saveTransaction(accounts, List.of(), List.of());
    }

    @Override
    public void saveTransaction(Collection<AccountData> accounts,
                                Collection<LoanData> loanUpdates,
                                Collection<UUID> deletedLoanIds) {
        if (accounts.isEmpty() && loanUpdates.isEmpty() && deletedLoanIds.isEmpty()) {
            return;
        }

        synchronized (lock) {
            Connection current = null;
            boolean autoCommit = true;
            try {
                current = connection();
                autoCommit = current.getAutoCommit();
                current.setAutoCommit(false);

                if (!accounts.isEmpty()) {
                    try (PreparedStatement statement = current.prepareStatement(upsertSql)) {
                        for (AccountData account : accounts) {
                            bindUpsert(statement, account);
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                }

                if (!loanUpdates.isEmpty()) {
                    try (PreparedStatement statement = current.prepareStatement(loanUpsertSql)) {
                        for (LoanData loan : loanUpdates) {
                            bindLoan(statement, loan);
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                }

                if (!deletedLoanIds.isEmpty()) {
                    try (PreparedStatement statement = current.prepareStatement(
                            "DELETE FROM `" + loanTable + "` WHERE `id` = ?")) {
                        for (UUID id : deletedLoanIds) {
                            statement.setString(1, id.toString());
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
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

    @Override
    public List<LoanData> loadLoans() {
        String sql = "SELECT `id`, `owner`, `owner_name`, `principal`, `interest`, `principal_remaining`, "
                + "`interest_remaining`, `days`, `created_at`, `due_at`, `auto_collect` FROM `"
                + loanTable + "`";
        List<LoanData> result = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql);
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new LoanData(
                            UUID.fromString(rows.getString("id")),
                            UUID.fromString(rows.getString("owner")),
                            rows.getString("owner_name"),
                            rows.getBigDecimal("principal"),
                            rows.getBigDecimal("interest"),
                            rows.getBigDecimal("principal_remaining"),
                            rows.getBigDecimal("interest_remaining"),
                            rows.getInt("days"),
                            rows.getLong("created_at"),
                            rows.getLong("due_at"),
                            rows.getBoolean("auto_collect")));
                }
            } catch (SQLException exception) {
                throw fail("laden", exception);
            }
        }
        return result;
    }

    @Override
    public void saveLoan(LoanData loan) {
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(loanUpsertSql)) {
                bindLoan(statement, loan);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw fail("speichern", exception);
            }
        }
    }

    private static void bindLoan(PreparedStatement statement, LoanData loan) throws SQLException {
        statement.setString(1, loan.id().toString());
        statement.setString(2, loan.owner().toString());
        statement.setString(3, loan.ownerName());
        statement.setBigDecimal(4, loan.principal());
        statement.setBigDecimal(5, loan.interest());
        statement.setBigDecimal(6, loan.principalRemaining());
        statement.setBigDecimal(7, loan.interestRemaining());
        statement.setInt(8, loan.days());
        statement.setLong(9, loan.createdAt());
        statement.setLong(10, loan.dueAt());
        statement.setBoolean(11, loan.autoCollect());
        // ON DUPLICATE KEY UPDATE
        statement.setString(12, loan.ownerName());
        statement.setBigDecimal(13, loan.principal());
        statement.setBigDecimal(14, loan.interest());
        statement.setBigDecimal(15, loan.principalRemaining());
        statement.setBigDecimal(16, loan.interestRemaining());
        statement.setInt(17, loan.days());
        statement.setLong(18, loan.dueAt());
        statement.setBoolean(19, loan.autoCollect());
    }

    @Override
    public void deleteLoan(UUID id) {
        String sql = "DELETE FROM `" + loanTable + "` WHERE `id` = ?";
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, id.toString());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw fail("löschen", exception);
            }
        }
    }

    private StorageException fail(String action, SQLException exception) {
        // Bei Fehlern nicht für jeden Aufruf erneut eine Verbindung aufbauen.
        closeConnection();
        if (retryAfterNanos == 0 || System.nanoTime() - retryAfterNanos >= 0) {
            retryAfterNanos = System.nanoTime() + RETRY_DELAY_NANOS;
        }
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
