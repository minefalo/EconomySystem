package de.Minefalo.economySystem.storage;

import de.Minefalo.economySystem.config.Settings;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
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
    private final String transferTable;
    private final String auditTable;
    private final String lockTable;
    private final String runTable;
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
        // Jede schreibende Aktion erhöht `revision` (Commands.md, 11.3).
        this.upsertSql = "INSERT INTO `" + table
                + "` (`uuid`, `name`, `balance`, `updated_at`, `revision`) "
                + "VALUES (?, ?, ?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE `name` = ?, `balance` = ?, `updated_at` = ?, "
                + "`revision` = `revision` + 1";

        this.transferTable = prefix + "transfers";
        this.auditTable = prefix + "audit";
        this.lockTable = prefix + "locks";
        this.runTable = prefix + "interest_runs";

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

        createNetworkTables(current);

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
     * Legt die Tabellen für den Netzwerkbetrieb an und ergänzt
     * {@code accounts.revision}, falls die Tabelle aus einer früheren Version
     * stammt (Migration nach {@code Commands.md}, 11.6).
     */
    private void createNetworkTables(Connection current) throws SQLException {
        addColumnIfMissing(current, "revision", "`revision` BIGINT NOT NULL DEFAULT 0");

        String transfers = "CREATE TABLE IF NOT EXISTS `" + transferTable + "` ("
                + "`id` CHAR(36) NOT NULL,"
                + "`idempotency_key` CHAR(36) NOT NULL,"
                + "`scope` VARCHAR(32) NOT NULL,"
                + "`from_uuid` CHAR(36) NOT NULL,"
                + "`to_uuid` CHAR(36) NOT NULL,"
                + "`amount` DECIMAL(30,8) NOT NULL,"
                + "`source_server` VARCHAR(32) NOT NULL,"
                + "`target_server` VARCHAR(32) NULL,"
                + "`state` ENUM('pending','applied','failed','expired') NOT NULL DEFAULT 'pending',"
                + "`created_at` BIGINT NOT NULL,"
                + "`applied_at` BIGINT NULL,"
                + "PRIMARY KEY (`id`),"
                + "UNIQUE KEY `uq_idempotency` (`idempotency_key`),"
                + "KEY `idx_state` (`scope`, `state`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

        String audit = "CREATE TABLE IF NOT EXISTS `" + auditTable + "` ("
                + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
                + "`tx_id` CHAR(36) NOT NULL,"
                + "`occurred_at` BIGINT NOT NULL,"
                + "`server_id` VARCHAR(32) NOT NULL,"
                + "`scope` VARCHAR(32) NOT NULL,"
                + "`actor_uuid` CHAR(36) NULL,"
                + "`actor_name` VARCHAR(64) NULL,"
                + "`origin` ENUM('PLAYER','CONSOLE','GUI','API','SCHEDULER') NOT NULL,"
                + "`action` VARCHAR(48) NOT NULL,"
                + "`target_uuid` CHAR(36) NULL,"
                + "`target_name` VARCHAR(64) NULL,"
                + "`amount` DECIMAL(30,8) NULL,"
                + "`balance_after` DECIMAL(30,8) NULL,"
                + "`reason` VARCHAR(160) NULL,"
                + "`result` ENUM('APPLIED','REJECTED','DRY_RUN') NOT NULL,"
                + "PRIMARY KEY (`id`),"
                + "UNIQUE KEY `uq_tx` (`tx_id`),"
                + "KEY `idx_scope_time` (`scope`, `occurred_at`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

        String locks = "CREATE TABLE IF NOT EXISTS `" + lockTable + "` ("
                + "`scope` VARCHAR(32) NOT NULL,"
                + "`holder` VARCHAR(32) NOT NULL,"
                + "`leased_until` BIGINT NOT NULL,"
                + "PRIMARY KEY (`scope`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

        String runs = "CREATE TABLE IF NOT EXISTS `" + runTable + "` ("
                + "`scope` VARCHAR(32) NOT NULL,"
                + "`cycle_key` VARCHAR(48) NOT NULL,"
                + "`created_at` BIGINT NOT NULL,"
                + "PRIMARY KEY (`scope`, `cycle_key`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

        try (Statement statement = current.createStatement()) {
            statement.execute(transfers);
            statement.execute(audit);
            statement.execute(locks);
            statement.execute(runs);
        }
    }

    /**
     * Ergänzt eine Spalte nur, wenn sie fehlt; {@code ALTER TABLE … ADD COLUMN
     * IF NOT EXISTS} ist nicht in jeder MySQL-Version verfügbar.
     */
    private void addColumnIfMissing(Connection current, String column, String definition)
            throws SQLException {
        String query = "SELECT COUNT(*) FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement statement = current.prepareStatement(query)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getInt(1) > 0) {
                    return;
                }
            }
        }
        try (Statement statement = current.createStatement()) {
            statement.execute("ALTER TABLE `" + table + "` ADD COLUMN " + definition);
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

    /**
     * Holt die Bestenliste direkt aus der Datenbank. Ohne diese Abfrage müsste
     * für jede Anzeige die gesamte Kontentabelle gelesen und im Speicher
     * sortiert werden (SICHERHEITSAUDIT, ROT-2).
     */
    @Override
    public List<AccountData> loadTop(int limit) {
        String sql = "SELECT `uuid`, `name`, `balance` FROM `" + table + "` "
                + "WHERE `balance` > 0 ORDER BY `balance` DESC, `name` ASC LIMIT ?";
        List<AccountData> accounts = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setInt(1, Math.max(1, limit));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        accounts.add(readAccount(result));
                    }
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

    // ------------------------------------------------------------------
    // Netzwerkbetrieb (Commands.md, Abschnitt 11)
    // ------------------------------------------------------------------

    /**
     * Bucht in einer Transaktion: Outbox-Zeile, Absender belasten, Empfänger
     * gutschreiben, Status {@code applied}. Der eindeutige
     * {@code idempotency_key} sorgt dafür, dass ein zweiter Aufruf nichts
     * mehr ändert (11.2/11.6).
     */
    @Override
    public TransferResult transfer(TransferData transfer, BigDecimal maxBalance) {
        String insertTransfer = "INSERT INTO `" + transferTable + "` (`id`, `idempotency_key`, `scope`,"
                + " `from_uuid`, `to_uuid`, `amount`, `source_server`, `target_server`, `state`,"
                + " `created_at`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)";
        String debit = "UPDATE `" + table + "` SET `balance` = `balance` - ?,"
                + " `revision` = `revision` + 1, `updated_at` = ?"
                + " WHERE `uuid` = ? AND `balance` >= ?";
        String lockTarget = "SELECT `balance` FROM `" + table + "` WHERE `uuid` = ? FOR UPDATE";
        String credit = "INSERT INTO `" + table
                + "` (`uuid`, `name`, `balance`, `updated_at`, `revision`)"
                + " VALUES (?, ?, ?, ?, 1)"
                + " ON DUPLICATE KEY UPDATE `balance` = `balance` + ?,"
                + " `revision` = `revision` + 1, `updated_at` = ?";
        String applied = "UPDATE `" + transferTable
                + "` SET `state` = 'applied', `applied_at` = ? WHERE `id` = ?";

        synchronized (lock) {
            Connection current = null;
            boolean autoCommit = true;
            try {
                current = connection();
                autoCommit = current.getAutoCommit();
                current.setAutoCommit(false);

                long now = System.currentTimeMillis();
                try (PreparedStatement statement = current.prepareStatement(insertTransfer)) {
                    statement.setString(1, transfer.id().toString());
                    statement.setString(2, transfer.idempotencyKey());
                    statement.setString(3, transfer.scope());
                    statement.setString(4, transfer.from().toString());
                    statement.setString(5, transfer.to().toString());
                    statement.setBigDecimal(6, transfer.amount());
                    statement.setString(7, transfer.sourceServer());
                    statement.setString(8, transfer.targetServer());
                    statement.setLong(9, now);
                    statement.executeUpdate();
                } catch (SQLIntegrityConstraintViolationException duplicate) {
                    current.rollback();
                    return TransferResult.DUPLICATE;
                }

                try (PreparedStatement statement = current.prepareStatement(debit)) {
                    statement.setBigDecimal(1, transfer.amount());
                    statement.setLong(2, now);
                    statement.setString(3, transfer.from().toString());
                    statement.setBigDecimal(4, transfer.amount());
                    if (statement.executeUpdate() == 0) {
                        current.rollback();
                        return TransferResult.INSUFFICIENT_FUNDS;
                    }
                }

                BigDecimal targetBalance = null;
                try (PreparedStatement statement = current.prepareStatement(lockTarget)) {
                    statement.setString(1, transfer.to().toString());
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            targetBalance = result.getBigDecimal("balance");
                        }
                    }
                }
                if (targetBalance != null && maxBalance != null
                        && targetBalance.add(transfer.amount()).compareTo(maxBalance) > 0) {
                    current.rollback();
                    return TransferResult.MAX_BALANCE;
                }

                try (PreparedStatement statement = current.prepareStatement(credit)) {
                    statement.setString(1, transfer.to().toString());
                    statement.setString(2, transfer.toName());
                    statement.setBigDecimal(3, transfer.amount());
                    statement.setLong(4, now);
                    statement.setBigDecimal(5, transfer.amount());
                    statement.setLong(6, now);
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = current.prepareStatement(applied)) {
                    statement.setLong(1, now);
                    statement.setString(2, transfer.id().toString());
                    statement.executeUpdate();
                }

                current.commit();
                return TransferResult.APPLIED;
            } catch (SQLException exception) {
                if (current != null) {
                    try {
                        current.rollback();
                    } catch (SQLException rollbackException) {
                        exception.addSuppressed(rollbackException);
                    }
                }
                throw fail("überweisen", exception);
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

    @Override
    public void recordAudit(AuditEntry entry) {
        String sql = "INSERT IGNORE INTO `" + auditTable + "` (`tx_id`, `occurred_at`, `server_id`,"
                + " `scope`, `actor_uuid`, `actor_name`, `origin`, `action`, `target_uuid`,"
                + " `target_name`, `amount`, `balance_after`, `reason`, `result`)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, entry.txId().toString());
                statement.setLong(2, entry.occurredAt());
                statement.setString(3, entry.serverId());
                statement.setString(4, entry.scope());
                statement.setString(5, entry.actorId() == null ? null : entry.actorId().toString());
                statement.setString(6, entry.actorName());
                statement.setString(7, entry.origin());
                statement.setString(8, entry.action());
                statement.setString(9, entry.targetId() == null ? null : entry.targetId().toString());
                statement.setString(10, entry.targetName());
                statement.setBigDecimal(11, entry.amount());
                statement.setBigDecimal(12, entry.balanceAfter());
                statement.setString(13, entry.reason());
                statement.setString(14, entry.result().name());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw fail("protokollieren", exception);
            }
        }
    }

    @Override
    public List<AuditEntry> audit(String scope, int limit) {
        String sql = "SELECT `tx_id`, `occurred_at`, `server_id`, `scope`, `actor_uuid`, `actor_name`,"
                + " `origin`, `action`, `target_uuid`, `target_name`, `amount`, `balance_after`,"
                + " `reason`, `result` FROM `" + auditTable + "` WHERE `scope` = ?"
                + " ORDER BY `occurred_at` DESC, `id` DESC LIMIT ?";
        List<AuditEntry> entries = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, scope);
                statement.setInt(2, Math.max(1, Math.min(limit, 100)));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        entries.add(new AuditEntry(
                                UUID.fromString(rows.getString("tx_id")),
                                rows.getLong("occurred_at"),
                                rows.getString("server_id"),
                                rows.getString("scope"),
                                uuidOrNull(rows.getString("actor_uuid")),
                                rows.getString("actor_name"),
                                rows.getString("origin"),
                                rows.getString("action"),
                                uuidOrNull(rows.getString("target_uuid")),
                                rows.getString("target_name"),
                                rows.getBigDecimal("amount"),
                                rows.getBigDecimal("balance_after"),
                                rows.getString("reason"),
                                AuditEntry.Result.valueOf(rows.getString("result"))));
                    }
                }
            } catch (SQLException exception) {
                throw fail("lesen", exception);
            }
        }
        return entries;
    }

    private static UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    /**
     * Holt die Lease oder verlängert sie, wenn sie frei oder abgelaufen ist
     * (11.4). Die Prüfung passiert in derselben Anweisung, damit zwei Server
     * nicht gleichzeitig glauben, sie hätten die Lease.
     */
    @Override
    public boolean acquireLease(String scope, String holder, long ttlMillis) {
        long now = System.currentTimeMillis();
        long until = now + Math.max(1L, ttlMillis);
        String sql = "INSERT INTO `" + lockTable + "` (`scope`, `holder`, `leased_until`)"
                + " VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE `holder` = IF(`leased_until` < ?, ?, `holder`),"
                + " `leased_until` = IF(`leased_until` < ?, ?, `leased_until`)";
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, scope);
                statement.setString(2, holder);
                statement.setLong(3, until);
                statement.setLong(4, now);
                statement.setString(5, holder);
                statement.setLong(6, now);
                statement.setLong(7, until);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw fail("reservieren", exception);
            }
        }
        return lease(scope).map(LeaseInfo::holder).map(holder::equals).orElse(false);
    }

    @Override
    public boolean renewLease(String scope, String holder, long ttlMillis) {
        return acquireLease(scope, holder, ttlMillis);
    }

    @Override
    public void releaseLease(String scope, String holder) {
        String sql = "UPDATE `" + lockTable + "` SET `leased_until` = 0"
                + " WHERE `scope` = ? AND `holder` = ?";
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, scope);
                statement.setString(2, holder);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw fail("freigeben", exception);
            }
        }
    }

    @Override
    public Optional<LeaseInfo> lease(String scope) {
        String sql = "SELECT `scope`, `holder`, `leased_until` FROM `" + lockTable + "`"
                + " WHERE `scope` = ?";
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, scope);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new LeaseInfo(result.getString("scope"),
                            result.getString("holder"), result.getLong("leased_until")));
                }
            } catch (SQLException exception) {
                throw fail("lesen", exception);
            }
        }
    }

    @Override
    public boolean claimInterestRun(String scope, String cycleKey) {
        String sql = "INSERT IGNORE INTO `" + runTable + "` (`scope`, `cycle_key`, `created_at`)"
                + " VALUES (?, ?, ?)";
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, scope);
                statement.setString(2, cycleKey);
                statement.setLong(3, System.currentTimeMillis());
                return statement.executeUpdate() == 1;
            } catch (SQLException exception) {
                throw fail("reservieren", exception);
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
