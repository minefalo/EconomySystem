package de.Minefalo.economySystem.storage;

import de.Minefalo.economySystem.config.Settings;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
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
import java.util.UUID;

/**
 * SQLite-Speicher (Standard). Die Datenbank liegt als Datei im
 * {@code data}-Ordner und wird beim Start automatisch angelegt.
 *
 * <p>Geldbeträge liegen als {@code TEXT} vor (BigDecimal als Zeichenkette),
 * damit keine Rundungsfehler entstehen. Die Bestenliste sortiert über
 * {@code CAST(balance AS REAL)}; das ist für die Anzeige ausreichend genau,
 * die gespeicherten Beträge selbst bleiben exakt.</p>
 *
 * <p>Die Tabellen entsprechen dem MySQL-Schema: Konten, Kredite, Transfers,
 * Idempotenzschlüssel, Audit, Lease und Zinsläufe. Sie gelten nur für diese
 * Datei, also für einen Server; für mehrere Server bleibt MySQL nötig.</p>
 */
public final class SqliteStorage implements EconomyStorage {

    private final JavaPlugin plugin;
    private final File file;
    private final String upsertSql;
    private final String loanUpsertSql;
    private final Object lock = new Object();

    private Connection connection;

    public SqliteStorage(JavaPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.file = StoragePaths.resolve(plugin, settings.sqliteFile());

        // Jede schreibende Aktion erhöht `revision` (Commands.md, 11.3).
        this.upsertSql = "INSERT INTO accounts (uuid, name, balance, updated_at, revision) "
                + "VALUES (?, ?, ?, ?, 1) "
                + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, "
                + "balance = excluded.balance, updated_at = excluded.updated_at, "
                + "revision = revision + 1";

        this.loanUpsertSql = "INSERT INTO loans (id, owner, owner_name, principal, interest, "
                + "principal_remaining, interest_remaining, days, created_at, due_at, auto_collect) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET owner_name = excluded.owner_name, "
                + "principal = excluded.principal, interest = excluded.interest, "
                + "principal_remaining = excluded.principal_remaining, "
                + "interest_remaining = excluded.interest_remaining, days = excluded.days, "
                + "due_at = excluded.due_at, auto_collect = excluded.auto_collect";
    }

    @Override
    public String name() {
        return "SQLite";
    }

    @Override
    public void init() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new StorageException("SQLite-Treiber nicht gefunden. "
                    + "Prüfe den Eintrag 'libraries' in der plugin.yml.", exception);
        }

        File folder = file.getParentFile();
        if (folder != null && !folder.exists() && !folder.mkdirs()) {
            throw new StorageException("SQLite-Ordner konnte nicht angelegt werden: " + folder);
        }

        synchronized (lock) {
            try {
                connection = openConnection();
                createTables(connection);
            } catch (SQLException exception) {
                closeConnection();
                throw new StorageException("SQLite-Datenbank konnte nicht geöffnet werden ("
                        + file + "): " + exception.getMessage(), exception);
            }
        }

        plugin.getLogger().info("SQLite verbunden (" + file.getAbsolutePath() + ").");
    }

    /**
     * Öffnet die Datei und setzt die Verbindungs-PRAGMAs. {@code journal_mode}
     * bleibt in der Datei gespeichert, die übrigen gelten je Verbindung.
     */
    private Connection openConnection() throws SQLException {
        Connection current = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = current.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA busy_timeout = 5000");
            return current;
        } catch (SQLException exception) {
            try {
                current.close();
            } catch (SQLException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = openConnection();
            createTables(connection);
        }
        return connection;
    }

    private void createTables(Connection current) throws SQLException {
        try (Statement statement = current.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS accounts ("
                    + "uuid TEXT NOT NULL PRIMARY KEY,"
                    + "name TEXT NOT NULL,"
                    + "balance TEXT NOT NULL,"
                    + "updated_at INTEGER NOT NULL,"
                    + "revision INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_accounts_name ON accounts(name)");

            statement.execute("CREATE TABLE IF NOT EXISTS loans ("
                    + "id TEXT NOT NULL PRIMARY KEY,"
                    + "owner TEXT NOT NULL,"
                    + "owner_name TEXT NOT NULL,"
                    + "principal TEXT NOT NULL,"
                    + "interest TEXT NOT NULL,"
                    + "principal_remaining TEXT NOT NULL,"
                    + "interest_remaining TEXT NOT NULL,"
                    + "days INTEGER NOT NULL,"
                    + "created_at INTEGER NOT NULL,"
                    + "due_at INTEGER NOT NULL,"
                    + "auto_collect INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_loans_owner ON loans(owner)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_loans_due ON loans(due_at)");

            statement.execute("CREATE TABLE IF NOT EXISTS transfers ("
                    + "id TEXT NOT NULL PRIMARY KEY,"
                    + "idempotency_key TEXT NOT NULL,"
                    + "scope TEXT NOT NULL,"
                    + "from_uuid TEXT NOT NULL,"
                    + "to_uuid TEXT NOT NULL,"
                    + "amount TEXT NOT NULL,"
                    + "source_server TEXT NOT NULL,"
                    + "target_server TEXT,"
                    + "state TEXT NOT NULL DEFAULT 'pending',"
                    + "created_at INTEGER NOT NULL,"
                    + "applied_at INTEGER)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_transfers_idempotency "
                    + "ON transfers(idempotency_key)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfers_state "
                    + "ON transfers(scope, state)");

            statement.execute("CREATE TABLE IF NOT EXISTS api_transactions ("
                    + "tx_id TEXT NOT NULL PRIMARY KEY,"
                    + "applied_at INTEGER NOT NULL)");

            statement.execute("CREATE TABLE IF NOT EXISTS audit ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "tx_id TEXT NOT NULL UNIQUE,"
                    + "occurred_at INTEGER NOT NULL,"
                    + "server_id TEXT NOT NULL,"
                    + "scope TEXT NOT NULL,"
                    + "actor_uuid TEXT,"
                    + "actor_name TEXT,"
                    + "origin TEXT NOT NULL,"
                    + "action TEXT NOT NULL,"
                    + "target_uuid TEXT,"
                    + "target_name TEXT,"
                    + "amount TEXT,"
                    + "balance_after TEXT,"
                    + "reason TEXT,"
                    + "result TEXT NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_audit_scope_time "
                    + "ON audit(scope, occurred_at)");

            statement.execute("CREATE TABLE IF NOT EXISTS locks ("
                    + "scope TEXT NOT NULL PRIMARY KEY,"
                    + "holder TEXT NOT NULL,"
                    + "leased_until INTEGER NOT NULL)");

            statement.execute("CREATE TABLE IF NOT EXISTS interest_runs ("
                    + "scope TEXT NOT NULL,"
                    + "cycle_key TEXT NOT NULL,"
                    + "created_at INTEGER NOT NULL,"
                    + "PRIMARY KEY (scope, cycle_key))");
        }
    }

    @Override
    public Optional<AccountData> load(UUID uniqueId) {
        String sql = "SELECT name, balance FROM accounts WHERE uuid = ?";
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, uniqueId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new AccountData(uniqueId,
                            result.getString("name"),
                            money(result, "balance")));
                }
            } catch (SQLException exception) {
                throw fail("laden", exception);
            }
        }
    }

    @Override
    public Optional<AccountData> loadByName(String name) {
        String sql = "SELECT uuid, name, balance FROM accounts "
                + "WHERE LOWER(name) = LOWER(?) ORDER BY updated_at DESC LIMIT 1";
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
        String sql = "SELECT uuid, name, balance FROM accounts";
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
     * Holt die Bestenliste direkt aus der Datenbank. Die Sortierung über
     * {@code CAST(balance AS REAL)} ist für Anzeigezwecke ausreichend; die
     * gespeicherten Beträge bleiben unverändert exakt.
     */
    @Override
    public List<AccountData> loadTop(int limit, boolean includeZeroBalances) {
        String comparison = includeZeroBalances ? ">=" : ">";
        String sql = "SELECT uuid, name, balance FROM accounts "
                + "WHERE CAST(balance AS REAL) " + comparison + " 0 "
                + "ORDER BY CAST(balance AS REAL) DESC, name ASC LIMIT ?";
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
        return new AccountData(
                UUID.fromString(result.getString("uuid")),
                result.getString("name"),
                money(result, "balance"));
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
                            "DELETE FROM loans WHERE id = ?")) {
                        for (UUID id : deletedLoanIds) {
                            statement.setString(1, id.toString());
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                }

                current.commit();
            } catch (SQLException exception) {
                rollback(current, exception);
                throw fail("speichern", exception);
            } finally {
                restoreAutoCommit(current, autoCommit);
            }
        }
    }

    private static void bindUpsert(PreparedStatement statement, AccountData account)
            throws SQLException {
        statement.setString(1, account.uniqueId().toString());
        statement.setString(2, account.name());
        statement.setString(3, money(account.balance()));
        statement.setLong(4, System.currentTimeMillis());
    }

    @Override
    public List<LoanData> loadLoans() {
        String sql = "SELECT id, owner, owner_name, principal, interest, principal_remaining, "
                + "interest_remaining, days, created_at, due_at, auto_collect FROM loans";
        List<LoanData> result = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql);
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new LoanData(
                            UUID.fromString(rows.getString("id")),
                            UUID.fromString(rows.getString("owner")),
                            rows.getString("owner_name"),
                            money(rows, "principal"),
                            money(rows, "interest"),
                            money(rows, "principal_remaining"),
                            money(rows, "interest_remaining"),
                            rows.getInt("days"),
                            rows.getLong("created_at"),
                            rows.getLong("due_at"),
                            rows.getInt("auto_collect") != 0));
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
        statement.setString(4, money(loan.principal()));
        statement.setString(5, money(loan.interest()));
        statement.setString(6, money(loan.principalRemaining()));
        statement.setString(7, money(loan.interestRemaining()));
        statement.setInt(8, loan.days());
        statement.setLong(9, loan.createdAt());
        statement.setLong(10, loan.dueAt());
        statement.setInt(11, loan.autoCollect() ? 1 : 0);
    }

    @Override
    public void deleteLoan(UUID id) {
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(
                    "DELETE FROM loans WHERE id = ?")) {
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
     * Bucht in einer Transaktion: Transfer-Zeile, Absender belasten, Empfänger
     * gutschreiben, Status {@code applied}. Der eindeutige
     * {@code idempotency_key} sorgt dafür, dass ein zweiter Aufruf nichts
     * mehr ändert (11.2/11.6). {@code INSERT OR IGNORE} macht den doppelten
     * Schlüssel ohne Ausnahmebehandlung erkennbar.
     */
    @Override
    public TransferResult transfer(TransferData transfer, BigDecimal maxBalance) {
        String insertTransfer = "INSERT OR IGNORE INTO transfers (id, idempotency_key, scope, "
                + "from_uuid, to_uuid, amount, source_server, target_server, state, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)";
        String selectBalance = "SELECT balance FROM accounts WHERE uuid = ?";
        String updateBalance = "UPDATE accounts SET balance = ?, updated_at = ?, "
                + "revision = revision + 1 WHERE uuid = ?";
        String insertAccount = "INSERT INTO accounts (uuid, name, balance, updated_at, revision) "
                + "VALUES (?, ?, ?, ?, 1)";
        String applied = "UPDATE transfers SET state = 'applied', applied_at = ? WHERE id = ?";

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
                    statement.setString(6, money(transfer.amount()));
                    statement.setString(7, transfer.sourceServer());
                    statement.setString(8, transfer.targetServer());
                    statement.setLong(9, now);
                    if (statement.executeUpdate() == 0) {
                        current.rollback();
                        return TransferResult.DUPLICATE;
                    }
                }

                BigDecimal fromBalance = readBalance(current, selectBalance, transfer.from());
                if (fromBalance == null || fromBalance.compareTo(transfer.amount()) < 0) {
                    current.rollback();
                    return TransferResult.INSUFFICIENT_FUNDS;
                }
                try (PreparedStatement statement = current.prepareStatement(updateBalance)) {
                    statement.setString(1, money(fromBalance.subtract(transfer.amount())));
                    statement.setLong(2, now);
                    statement.setString(3, transfer.from().toString());
                    statement.executeUpdate();
                }

                BigDecimal toBalance = readBalance(current, selectBalance, transfer.to());
                if (toBalance != null) {
                    if (maxBalance != null
                            && toBalance.add(transfer.amount()).compareTo(maxBalance) > 0) {
                        current.rollback();
                        return TransferResult.MAX_BALANCE;
                    }
                    try (PreparedStatement statement = current.prepareStatement(updateBalance)) {
                        statement.setString(1, money(toBalance.add(transfer.amount())));
                        statement.setLong(2, now);
                        statement.setString(3, transfer.to().toString());
                        statement.executeUpdate();
                    }
                } else {
                    BigDecimal created = transfer.amount();
                    if (maxBalance != null && created.compareTo(maxBalance) > 0) {
                        current.rollback();
                        return TransferResult.MAX_BALANCE;
                    }
                    try (PreparedStatement statement = current.prepareStatement(insertAccount)) {
                        statement.setString(1, transfer.to().toString());
                        statement.setString(2, transfer.toName());
                        statement.setString(3, money(created));
                        statement.setLong(4, now);
                        statement.executeUpdate();
                    }
                }

                try (PreparedStatement statement = current.prepareStatement(applied)) {
                    statement.setLong(1, now);
                    statement.setString(2, transfer.id().toString());
                    statement.executeUpdate();
                }

                current.commit();
                return TransferResult.APPLIED;
            } catch (SQLException exception) {
                rollback(current, exception);
                throw fail("überweisen", exception);
            } finally {
                restoreAutoCommit(current, autoCommit);
            }
        }
    }

    private static BigDecimal readBalance(Connection current, String sql, UUID uniqueId)
            throws SQLException {
        try (PreparedStatement statement = current.prepareStatement(sql)) {
            statement.setString(1, uniqueId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? money(result, "balance") : null;
            }
        }
    }

    /**
     * Idempotente Einzelbuchung: Der Schlüssel wird zusammen mit dem neuen
     * Kontostand in einer Transaktion geschrieben. Ein bereits bekannter
     * Schlüssel wird ignoriert und rollt die Buchung zurück.
     */
    @Override
    public boolean saveIdempotent(AccountData account, UUID transactionId) {
        String claim = "INSERT OR IGNORE INTO api_transactions (tx_id, applied_at) VALUES (?, ?)";
        synchronized (lock) {
            Connection current = null;
            boolean autoCommit = true;
            try {
                current = connection();
                autoCommit = current.getAutoCommit();
                current.setAutoCommit(false);

                try (PreparedStatement statement = current.prepareStatement(claim)) {
                    statement.setString(1, transactionId.toString());
                    statement.setLong(2, System.currentTimeMillis());
                    if (statement.executeUpdate() == 0) {
                        current.rollback();
                        return false;
                    }
                }

                try (PreparedStatement statement = current.prepareStatement(upsertSql)) {
                    bindUpsert(statement, account);
                    statement.executeUpdate();
                }

                current.commit();
                return true;
            } catch (SQLException exception) {
                rollback(current, exception);
                throw fail("buchen", exception);
            } finally {
                restoreAutoCommit(current, autoCommit);
            }
        }
    }

    @Override
    public boolean hasTransaction(UUID transactionId) {
        String sql = "SELECT 1 FROM api_transactions WHERE tx_id = ?";
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, transactionId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            } catch (SQLException exception) {
                throw fail("prüfen", exception);
            }
        }
    }

    @Override
    public void recordAudit(AuditEntry entry) {
        String sql = "INSERT OR IGNORE INTO audit (tx_id, occurred_at, server_id, scope, "
                + "actor_uuid, actor_name, origin, action, target_uuid, target_name, amount, "
                + "balance_after, reason, result) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
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
                statement.setString(11, money(entry.amount()));
                statement.setString(12, money(entry.balanceAfter()));
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
        String sql = "SELECT tx_id, occurred_at, server_id, scope, actor_uuid, actor_name, "
                + "origin, action, target_uuid, target_name, amount, balance_after, reason, result "
                + "FROM audit WHERE scope = ? ORDER BY occurred_at DESC, id DESC LIMIT ?";
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
                                money(rows, "amount"),
                                money(rows, "balance_after"),
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
     * Entfernt alte Audit-Einträge in begrenzten Löschvorgängen. SQLite kennt
     * kein {@code DELETE … LIMIT}, deshalb wird über die IDs gelöscht.
     */
    @Override
    public int pruneAudit(String scope, long cutoffMillis, int batchSize) {
        int limit = Math.max(1, Math.min(batchSize, 1000));
        String sql = "DELETE FROM audit WHERE id IN (SELECT id FROM audit "
                + "WHERE scope = ? AND occurred_at < ? ORDER BY occurred_at ASC, id ASC LIMIT ?)";
        int deleted = 0;
        synchronized (lock) {
            // Höchstens 20 Batches je Lauf; der Rest folgt beim nächsten Tag.
            for (int batch = 0; batch < 20; batch++) {
                try (PreparedStatement statement = connection().prepareStatement(sql)) {
                    statement.setString(1, scope);
                    statement.setLong(2, cutoffMillis);
                    statement.setInt(3, limit);
                    int count = statement.executeUpdate();
                    deleted += count;
                    if (count < limit) {
                        break;
                    }
                } catch (SQLException exception) {
                    throw failAudit(exception);
                }
            }
        }
        return deleted;
    }

    /**
     * Holt die Lease oder verlängert sie, wenn sie frei oder abgelaufen ist
     * (11.4). Die Prüfung passiert in derselben Anweisung, damit sich zwei
     * Aufrufer nicht gleichzeitig als Halter sehen.
     */
    @Override
    public boolean acquireLease(String scope, String holder, long ttlMillis) {
        long now = System.currentTimeMillis();
        long until = now + Math.max(1L, ttlMillis);
        String sql = "INSERT INTO locks (scope, holder, leased_until) VALUES (?, ?, ?) "
                + "ON CONFLICT(scope) DO UPDATE SET "
                + "holder = CASE WHEN leased_until < ? THEN excluded.holder ELSE holder END, "
                + "leased_until = CASE WHEN leased_until < ? THEN excluded.leased_until "
                + "ELSE leased_until END";
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, scope);
                statement.setString(2, holder);
                statement.setLong(3, until);
                statement.setLong(4, now);
                statement.setLong(5, now);
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
        String sql = "UPDATE locks SET leased_until = 0 WHERE scope = ? AND holder = ?";
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
        String sql = "SELECT scope, holder, leased_until FROM locks WHERE scope = ?";
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
        String sql = "INSERT OR IGNORE INTO interest_runs (scope, cycle_key, created_at) "
                + "VALUES (?, ?, ?)";
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

    private static String money(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static BigDecimal money(ResultSet result, String column) throws SQLException {
        String raw = result.getString(column);
        return raw == null ? null : new BigDecimal(raw);
    }

    private static void rollback(Connection current, SQLException exception) {
        if (current != null) {
            try {
                current.rollback();
            } catch (SQLException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
        }
    }

    private static void restoreAutoCommit(Connection current, boolean autoCommit) {
        if (current != null) {
            try {
                current.setAutoCommit(autoCommit);
            } catch (SQLException ignored) {
                // Wird beim nächsten Zugriff ohnehin geprüft.
            }
        }
    }

    private StorageException fail(String action, SQLException exception) {
        return new StorageException("SQLite: Konto konnte nicht " + action
                + " werden: " + exception.getMessage(), exception);
    }

    private StorageException failAudit(SQLException exception) {
        return new StorageException("SQLite: Audit-Einträge konnten nicht aufgeräumt werden: "
                + exception.getMessage(), exception);
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
