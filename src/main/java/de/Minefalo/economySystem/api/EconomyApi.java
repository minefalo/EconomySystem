package de.Minefalo.economySystem.api;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Öffentliche Entwickler-Schnittstelle des Wirtschaftssystems.
 *
 * <p>Die Implementierung wird beim Aktivieren über den
 * {@link org.bukkit.plugin.ServicesManager} registriert und kann von anderen
 * Plugins ohne Reflection bezogen werden:</p>
 *
 * <pre>{@code
 * RegisteredServiceProvider<EconomyApi> provider =
 *         Bukkit.getServicesManager().getRegistration(EconomyApi.class);
 * if (provider != null) {
 *     EconomyApi api = provider.getProvider();
 * }
 * }</pre>
 *
 * <p>Alle Methoden arbeiten mit {@link BigDecimal}. Beträge werden mit der
 * konfigurierten Rundung und den Nachkommastellen aus der {@code settings.yml}
 * normalisiert; {@code min-balance} und {@code max-balance} werden bei jeder
 * Buchung berücksichtigt.</p>
 *
 * <p>Im Modus {@code network.mode: READ_ONLY} schlagen alle schreibenden
 * Buchungen mit {@link BuchungsErgebnis#NICHT_ERLAUBT} fehl.</p>
 */
public interface EconomyApi {

    /**
     * Liest den aktuellen Kontostand eines Spielers.
     *
     * <p>Es wird kein Konto angelegt: Existiert für die UUID noch kein Konto,
     * ist das Ergebnis {@code 0} in der konfigurierten Genauigkeit.</p>
     *
     * @param spielerId UUID des Spielers
     * @return Future mit dem Kontostand; es schließt außergewöhnlich ab, wenn
     *         der Speicher nicht gelesen werden kann
     */
    CompletableFuture<BigDecimal> guthaben(UUID spielerId);

    /**
     * Bucht einen Betrag dauerhaft und idempotent vom Konto ab.
     *
     * <p>Das Future schließt erst ab, nachdem der neue Kontostand im Speicher
     * ({@code SQLITE}, {@code YAML} oder {@code MYSQL}) geschrieben wurde. Bei einem
     * Speicherfehler bleibt der Kontostand unverändert und das Future liefert
     * {@link BuchungsErgebnis#FEHLER}.</p>
     *
     * <p>Die {@code transactionId} ist der Idempotenzschlüssel: Wurde sie
     * bereits verarbeitet, wird nichts erneut gebucht und das Ergebnis ist
     * {@link BuchungsErgebnis#DUPLIKAT}. Dadurch sind Wiederholungen nach
     * Timeouts oder Retries gefahrlos.</p>
     *
     * @param transactionId eindeutige ID dieser Buchung (vom Aufrufer erzeugt)
     * @param spielerId     UUID des zu belastenden Spielers
     * @param spielerName   Anzeigename des Spielers (nur für neue Konten und das Audit-Log)
     * @param betrag        positiver Betrag
     * @param quelle        Name des aufrufenden Plugins, z. B. {@code "SimplePlots"}
     * @param grund         Begründung, z. B. {@code "Claim 3;7"}
     * @return Future mit dem Ergebnis der Buchung
     */
    CompletableFuture<BuchungsErgebnis> abbuchen(UUID transactionId,
                                                 UUID spielerId,
                                                 String spielerName,
                                                 BigDecimal betrag,
                                                 String quelle,
                                                 String grund);

    /**
     * Schreibt einem Konto einen Betrag dauerhaft und idempotent gut.
     *
     * <p>Es gelten dieselben Garantien wie bei {@link #abbuchen}. Zusätzlich
     * wird das maximale Guthaben ({@code currency.max-balance}) beachtet; eine
     * Gutschrift, die darüber hinausgehen würde, liefert
     * {@link BuchungsErgebnis#MAXIMUM}. Offene Kredite werden – wie bei einer
     * regulären Gutschrift – über die Kredit-Tilgungslogik berücksichtigt.</p>
     *
     * @param transactionId eindeutige ID dieser Buchung (vom Aufrufer erzeugt)
     * @param spielerId     UUID des zu begünstigenden Spielers
     * @param spielerName   Anzeigename des Spielers (nur für neue Konten und das Audit-Log)
     * @param betrag        positiver Betrag
     * @param quelle        Name des aufrufenden Plugins, z. B. {@code "SimplePlots"}
     * @param grund         Begründung, z. B. {@code "Claim 3;7"}
     * @return Future mit dem Ergebnis der Buchung
     */
    CompletableFuture<BuchungsErgebnis> erstatten(UUID transactionId,
                                                  UUID spielerId,
                                                  String spielerName,
                                                  BigDecimal betrag,
                                                  String quelle,
                                                  String grund);

    /**
     * Formatiert einen Betrag mit der konfigurierten Währung
     * (Symbol, Trennzeichen, Nachkommastellen), z. B. {@code "$1.234,56"}.
     *
     * @param betrag Betrag; {@code null} wird als {@code 0} formatiert
     * @return formatierter Text
     */
    String formatiere(BigDecimal betrag);
}
