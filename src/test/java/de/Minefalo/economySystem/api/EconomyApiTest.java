package de.Minefalo.economySystem.api;

import de.Minefalo.economySystem.api.event.EconomyTransactionEvent;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.storage.AuditEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die öffentliche Entwickler-API gegen den echten
 * {@link EconomyService} und den {@code YAML}-Speicher.
 */
@DisplayName("EconomyApi: READ_ONLY, Idempotenz, Grenzen und Audit")
class EconomyApiTest {

    private static BuchungsErgebnis ergebnis(CompletableFuture<BuchungsErgebnis> future)
            throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static BigDecimal guthaben(EconomyApi api, UUID spieler) throws Exception {
        return api.guthaben(spieler).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("READ_ONLY blockiert API und Kernlogik")
    void readOnlyBlockiertBuchungen(@TempDir File folder) throws Exception {
        UUID spieler = UUID.randomUUID();
        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, true, "1000.00")) {
            assertEquals(BuchungsErgebnis.NICHT_ERLAUBT, ergebnis(fixture.api.abbuchen(
                    UUID.randomUUID(), spieler, "Alice", new BigDecimal("10.00"),
                    "SimplePlots", "Claim 3;7")));
            assertEquals(BuchungsErgebnis.NICHT_ERLAUBT, ergebnis(fixture.api.erstatten(
                    UUID.randomUUID(), spieler, "Alice", new BigDecimal("10.00"),
                    "SimplePlots", "Claim 3;7")));
            assertEquals(new BigDecimal("0.00"), guthaben(fixture.api, spieler));

            Account account = fixture.economy.accountAsync(spieler, "Alice").join();
            assertFalse(fixture.economy.deposit(account, new BigDecimal("5.00")));
            assertFalse(fixture.economy.withdraw(account, new BigDecimal("5.00")));
            fixture.economy.setBalance(account, new BigDecimal("5.00"));
            assertEquals(new BigDecimal("100.00"), account.balance());
        }
    }

    @Test
    @DisplayName("Abbuchung ist vor der Rückgabe dauerhaft gespeichert")
    void abbuchungWirdDauerhaftGespeichert(@TempDir File folder) throws Exception {
        UUID spieler = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, false, "1000.00")) {
            assertEquals(BuchungsErgebnis.OK, ergebnis(fixture.api.abbuchen(
                    transactionId, spieler, "Alice", new BigDecimal("25.00"),
                    "SimplePlots", "Claim 3;7")));
            assertEquals(new BigDecimal("75.00"), guthaben(fixture.api, spieler));
        }

        // Der Wert steht bereits auf dem Datenträger, nicht nur im Cache.
        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, false, "1000.00")) {
            assertEquals(new BigDecimal("75.00"),
                    fixture.storage.load(spieler).orElseThrow().balance());
            assertTrue(fixture.storage.hasTransaction(transactionId));
        }
    }

    @Test
    @DisplayName("Dieselbe transactionId bucht nur einmal")
    void gleicheTransactionIdBuchtNurEinmal(@TempDir File folder) throws Exception {
        UUID spieler = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, false, "1000.00")) {
            assertEquals(BuchungsErgebnis.OK, ergebnis(fixture.api.abbuchen(
                    transactionId, spieler, "Alice", new BigDecimal("10.00"),
                    "SimplePlots", "Claim 3;7")));
            assertEquals(new BigDecimal("90.00"), guthaben(fixture.api, spieler));

            assertEquals(BuchungsErgebnis.DUPLIKAT, ergebnis(fixture.api.abbuchen(
                    transactionId, spieler, "Alice", new BigDecimal("10.00"),
                    "SimplePlots", "Claim 3;7")));
            assertEquals(new BigDecimal("90.00"), guthaben(fixture.api, spieler));
        }
    }

    @Test
    @DisplayName("Idempotenz überlebt einen Neustart des Speichers")
    void idempotenzUeberlebtNeustart(@TempDir File folder) throws Exception {
        UUID spieler = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, false, "1000.00")) {
            assertEquals(BuchungsErgebnis.OK, ergebnis(fixture.api.abbuchen(
                    transactionId, spieler, "Alice", new BigDecimal("10.00"),
                    "SimplePlots", "Claim 3;7")));
        }

        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, false, "1000.00")) {
            assertEquals(new BigDecimal("90.00"), guthaben(fixture.api, spieler));
            assertEquals(BuchungsErgebnis.DUPLIKAT, ergebnis(fixture.api.abbuchen(
                    transactionId, spieler, "Alice", new BigDecimal("10.00"),
                    "SimplePlots", "Claim 3;7")));
            assertEquals(new BigDecimal("90.00"), guthaben(fixture.api, spieler));
        }
    }

    @Test
    @DisplayName("Zu wenig Guthaben liefert KEIN_GELD")
    void zuWenigGuthaben(@TempDir File folder) throws Exception {
        UUID spieler = UUID.randomUUID();
        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, false, "1000.00")) {
            assertEquals(BuchungsErgebnis.KEIN_GELD, ergebnis(fixture.api.abbuchen(
                    UUID.randomUUID(), spieler, "Alice", new BigDecimal("150.00"),
                    "SimplePlots", "Claim 3;7")));
            assertEquals(new BigDecimal("100.00"), guthaben(fixture.api, spieler));
        }
    }

    @Test
    @DisplayName("Erstattung beachtet das maximale Guthaben")
    void erstaettungBeachtetMaximum(@TempDir File folder) throws Exception {
        UUID spieler = UUID.randomUUID();
        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, false, "1000.00")) {
            assertEquals(BuchungsErgebnis.MAXIMUM, ergebnis(fixture.api.erstatten(
                    UUID.randomUUID(), spieler, "Alice", new BigDecimal("950.00"),
                    "SimplePlots", "Claim 3;7")));
            assertEquals(new BigDecimal("100.00"), guthaben(fixture.api, spieler));

            assertEquals(BuchungsErgebnis.OK, ergebnis(fixture.api.erstatten(
                    UUID.randomUUID(), spieler, "Alice", new BigDecimal("900.00"),
                    "SimplePlots", "Claim 3;7")));
            assertEquals(new BigDecimal("1000.00"), guthaben(fixture.api, spieler));
        }
    }

    @Test
    @DisplayName("Unbrauchbare Beträge werden abgelehnt")
    void unbrauchbareBetraege(@TempDir File folder) throws Exception {
        UUID spieler = UUID.randomUUID();
        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, false, "1000.00")) {
            for (BigDecimal betrag : List.of(BigDecimal.ZERO,
                    new BigDecimal("-1"), new BigDecimal("0.001"))) {
                assertEquals(BuchungsErgebnis.UNGUELTIGER_BETRAG,
                        ergebnis(fixture.api.abbuchen(UUID.randomUUID(), spieler, "Alice",
                                betrag, "SimplePlots", "Test")), betrag::toPlainString);
            }
            assertEquals(BuchungsErgebnis.UNGUELTIGER_BETRAG,
                    ergebnis(fixture.api.abbuchen(null, spieler, "Alice",
                            new BigDecimal("1.00"), "SimplePlots", "Test")));
            assertEquals(new BigDecimal("0.00"), guthaben(fixture.api, spieler));
        }
    }

    @Test
    @DisplayName("Beträge werden mit der konfigurierten Rundung normalisiert")
    void betraegeWerdenGerundet(@TempDir File folder) throws Exception {
        UUID spieler = UUID.randomUUID();
        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, false, "1000.00")) {
            assertEquals(BuchungsErgebnis.OK, ergebnis(fixture.api.abbuchen(
                    UUID.randomUUID(), spieler, "Alice", new BigDecimal("10.005"),
                    "SimplePlots", "Test")));
            assertEquals(new BigDecimal("89.99"), guthaben(fixture.api, spieler));
        }
    }

    @Test
    @DisplayName("API-Buchungen landen mit Quelle und Grund im Audit-Trail")
    void apiBuchungWirdProtokolliert(@TempDir File folder) throws Exception {
        UUID spieler = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, false, "1000.00")) {
            assertEquals(BuchungsErgebnis.OK, ergebnis(fixture.api.abbuchen(
                    transactionId, spieler, "Alice", new BigDecimal("25.00"),
                    "SimplePlots", "Claim 3;7")));

            List<AuditEntry> entries = fixture.storage.audit(
                    fixture.settings.network().scopeKey(), 10);
            assertEquals(1, entries.size());
            AuditEntry entry = entries.get(0);
            assertEquals(transactionId, entry.txId());
            assertEquals("API", entry.origin());
            assertEquals("api.abbuchen", entry.action());
            assertEquals("SimplePlots: Claim 3;7", entry.reason());
            assertEquals(spieler, entry.targetId());
            assertEquals(new BigDecimal("25.00"), entry.amount());
            assertEquals(new BigDecimal("75.00"), entry.balanceAfter());
            assertEquals(AuditEntry.Result.APPLIED, entry.result());
        }
    }

    @Test
    @DisplayName("EconomyTransactionEvent beschreibt die Buchung")
    void eventBeschreibtDieBuchung(@TempDir File folder) throws Exception {
        UUID spieler = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        ApiTestSupport.clearEvents();
        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, false, "1000.00")) {
            assertEquals(BuchungsErgebnis.OK, ergebnis(fixture.api.abbuchen(
                    transactionId, spieler, "Alice", new BigDecimal("25.00"),
                    "SimplePlots", "Claim 3;7")));
        }

        List<EconomyTransactionEvent> events = ApiTestSupport.events().stream()
                .filter(EconomyTransactionEvent.class::isInstance)
                .map(EconomyTransactionEvent.class::cast)
                .toList();
        assertEquals(1, events.size());
        EconomyTransactionEvent event = events.get(0);
        assertTrue(event.isAsynchronous());
        assertEquals(transactionId, event.transactionId());
        assertEquals(spieler, event.spielerId());
        assertEquals("Alice", event.spielerName());
        assertEquals(new BigDecimal("25.00"), event.betrag());
        assertEquals(BuchungsTyp.ABBUCHUNG, event.typ());
        assertEquals("SimplePlots", event.quelle());
        assertEquals("Claim 3;7", event.grund());
        assertEquals(BuchungsErgebnis.OK, event.ergebnis());
    }

    @Test
    @DisplayName("Speicherfehler liefert FEHLER und rollt den Cache zurück")
    void speicherfehlerRolltZurueck(@TempDir File folder) throws Exception {
        UUID spieler = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, false, "1000.00", true)) {
            assertEquals(BuchungsErgebnis.FEHLER, ergebnis(fixture.economy.abbuchen(
                    transactionId, spieler, "Alice", new BigDecimal("10.00"),
                    "SimplePlots", "Test")));

            Account account = fixture.economy.accountAsync(spieler, "Alice")
                    .get(5, TimeUnit.SECONDS);
            assertEquals(new BigDecimal("100.00"), account.balance());
            assertFalse(fixture.storage.hasTransaction(transactionId));
        }
    }

    @Test
    @DisplayName("Lesen und Formatieren ohne Konto")
    void lesenUndFormatieren(@TempDir File folder) throws Exception {
        try (ApiTestSupport.Fixture fixture =
                     new ApiTestSupport.Fixture(folder, false, "1000.00")) {
            assertEquals(new BigDecimal("0.00"),
                    guthaben(fixture.api, UUID.randomUUID()));
            assertEquals("12,50 €", fixture.api.formatiere(new BigDecimal("12.5")));
            assertEquals("0,00 €", fixture.api.formatiere(null));
        }
    }

}
