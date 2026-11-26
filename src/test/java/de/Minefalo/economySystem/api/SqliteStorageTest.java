package de.Minefalo.economySystem.api;

import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.storage.AccountData;
import de.Minefalo.economySystem.storage.AuditEntry;
import de.Minefalo.economySystem.storage.LoanData;
import de.Minefalo.economySystem.storage.SqliteStorage;
import de.Minefalo.economySystem.storage.TransferData;
import de.Minefalo.economySystem.storage.TransferResult;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft den SQLite-Speicher gegen eine echte Datei: Konten, Bestenliste,
 * Kredite, idempotente Buchungen, Transfers, Audit und Lease.
 */
final class SqliteStorageTest {

    @TempDir
    File folder;

    private SqliteStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        File configFolder = new File(folder, "config");
        assertTrue(configFolder.mkdirs() || configFolder.isDirectory());
        Files.writeString(new File(configFolder, "settings.yml").toPath(), """
                currency:
                  starting-balance: "100.00"
                storage:
                  type: SQLITE
                  save-interval-seconds: 0
                  sqlite:
                    file: "test.db"
                """, StandardCharsets.UTF_8);

        JavaPlugin plugin = ApiTestSupport.plugin(folder);
        Settings settings = new Settings(plugin);
        storage = new SqliteStorage(plugin, settings);
        storage.init();
        assertTrue(new File(folder, "data/test.db").isFile());
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    void speichertKontenUndLiefertDieBestenliste() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        storage.save(new AccountData(alice, "Alice", new BigDecimal("100.00")));
        storage.save(new AccountData(bob, "Bob", new BigDecimal("1234.56000000")));
        storage.save(new AccountData(alice, "Alice", new BigDecimal("200.50")));

        AccountData loaded = storage.load(alice).orElseThrow();
        assertEquals("Alice", loaded.name());
        assertEquals(0, loaded.balance().compareTo(new BigDecimal("200.50")));
        assertTrue(storage.loadByName("alice").isPresent());
        assertEquals(2, storage.loadAll().size());

        List<AccountData> top = storage.loadTop(1);
        assertEquals(1, top.size());
        assertEquals(bob, top.getFirst().uniqueId());
        assertEquals(2, storage.loadTop(10, true).size());
    }

    @Test
    void speichertKrediteUndBuchtTransfersIdempotent() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        storage.save(new AccountData(from, "Sender", new BigDecimal("100.00")));
        storage.save(new AccountData(to, "Target", new BigDecimal("10.00")));

        TransferData transfer = TransferData.create("global", from, "Sender", to, "Target",
                new BigDecimal("30.00"), "test", null);
        assertEquals(TransferResult.APPLIED, storage.transfer(transfer, null));
        assertEquals(0, storage.load(from).orElseThrow().balance()
                .compareTo(new BigDecimal("70.00")));
        assertEquals(0, storage.load(to).orElseThrow().balance()
                .compareTo(new BigDecimal("40.00")));

        // Derselbe Idempotenzschlüssel darf nicht erneut buchen.
        assertEquals(TransferResult.DUPLICATE, storage.transfer(transfer, null));
        assertEquals(0, storage.load(from).orElseThrow().balance()
                .compareTo(new BigDecimal("70.00")));

        TransferData tooMuch = TransferData.create("global", from, "Sender", to, "Target",
                new BigDecimal("1000.00"), "test", null);
        assertEquals(TransferResult.INSUFFICIENT_FUNDS, storage.transfer(tooMuch, null));

        TransferData capped = TransferData.create("global", from, "Sender", to, "Target",
                new BigDecimal("1.00"), "test", null);
        assertEquals(TransferResult.MAX_BALANCE,
                storage.transfer(capped, new BigDecimal("40.00")));
        assertEquals(0, storage.load(from).orElseThrow().balance()
                .compareTo(new BigDecimal("70.00")));

        UUID transactionId = UUID.randomUUID();
        assertTrue(storage.saveIdempotent(
                new AccountData(from, "Sender", new BigDecimal("65.00")), transactionId));
        assertFalse(storage.saveIdempotent(
                new AccountData(from, "Sender", new BigDecimal("5.00")), transactionId));
        assertTrue(storage.hasTransaction(transactionId));
        assertEquals(0, storage.load(from).orElseThrow().balance()
                .compareTo(new BigDecimal("65.00")));
    }

    @Test
    void speichertKrediteUndAudit() {
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        LoanData loan = new LoanData(UUID.randomUUID(), owner, "Owner",
                new BigDecimal("50.00"), new BigDecimal("5.00"),
                new BigDecimal("40.00"), new BigDecimal("5.00"),
                30, 1_000L, 2_000L, true);
        storage.saveLoan(loan);
        assertEquals(1, storage.loadLoans().size());
        assertEquals(new BigDecimal("40.00"), storage.loadLoans().getFirst().principalRemaining());

        storage.saveTransaction(List.of(new AccountData(owner, "Owner", new BigDecimal("10.00"))),
                List.of(), List.of(loan.id()));
        assertTrue(storage.loadLoans().isEmpty());

        AuditEntry entry = new AuditEntry(UUID.randomUUID(), 1_234L, "test", "global",
                owner, "Owner", "PLAYER", "pay", target, "Target",
                new BigDecimal("30.00"), new BigDecimal("70.00"), null,
                AuditEntry.Result.APPLIED);
        storage.recordAudit(entry);
        storage.recordAudit(entry);
        assertEquals(1, storage.audit("global", 10).size());
        assertEquals(1, storage.pruneAudit("global", 2_000L, 100));
        assertTrue(storage.audit("global", 10).isEmpty());
    }

    @Test
    void vergibtDieLeaseNurEinmalUndReserviertDenZinslauf() {
        assertTrue(storage.acquireLease("global", "server-a", 60_000L));
        assertFalse(storage.acquireLease("global", "server-b", 60_000L));
        assertEquals("server-a", storage.lease("global").orElseThrow().holder());

        storage.releaseLease("global", "server-a");
        assertTrue(storage.acquireLease("global", "server-b", 60_000L));
        assertEquals("server-b", storage.lease("global").orElseThrow().holder());

        assertTrue(storage.claimInterestRun("global", "2026-11-26T00:01"));
        assertFalse(storage.claimInterestRun("global", "2026-11-26T00:01"));
    }
}
