package de.Minefalo.economySystem.api.event;

import de.Minefalo.economySystem.api.BuchungsErgebnis;
import de.Minefalo.economySystem.api.BuchungsTyp;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Wird nach jeder Buchung über die {@link de.Minefalo.economySystem.api.EconomyApi}
 * ausgelöst – auch wenn die Buchung abgelehnt wurde.
 *
 * <p>Die Buchungen laufen auf dem Storage-Executor des Plugins. Das Event ist
 * deshalb als asynchrones Bukkit-Event ({@code super(true)}) deklariert und
 * darf aus jedem Thread ausgelöst und empfangen werden. Listener, die auf den
 * Server-Thread wechseln müssen, nutzen
 * {@code Bukkit.getScheduler().runTask(plugin, ...)}.</p>
 */
public final class EconomyTransactionEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID transactionId;
    private final UUID spielerId;
    private final String spielerName;
    private final BigDecimal betrag;
    private final BuchungsTyp typ;
    private final String quelle;
    private final String grund;
    private final BuchungsErgebnis ergebnis;

    /**
     * @param transactionId Idempotenzschlüssel der Buchung
     * @param spielerId     betroffenes Konto
     * @param spielerName   Anzeigename des Spielers
     * @param betrag        normalisierter Betrag
     * @param typ           Abbuchung oder Erstattung
     * @param quelle        aufrufendes Plugin
     * @param grund         Begründung der Buchung
     * @param ergebnis      Ergebnis der Buchung
     */
    public EconomyTransactionEvent(UUID transactionId,
                                   UUID spielerId,
                                   String spielerName,
                                   BigDecimal betrag,
                                   BuchungsTyp typ,
                                   String quelle,
                                   String grund,
                                   BuchungsErgebnis ergebnis) {
        // Asynchron: Das Event wird aus dem CompletableFuture bzw. vom
        // Storage-Executor ausgelöst und darf deshalb keinen Main-Thread
        // voraussetzen.
        super(true);
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.spielerId = Objects.requireNonNull(spielerId, "spielerId");
        this.spielerName = spielerName;
        this.betrag = Objects.requireNonNull(betrag, "betrag");
        this.typ = Objects.requireNonNull(typ, "typ");
        this.quelle = quelle;
        this.grund = grund;
        this.ergebnis = Objects.requireNonNull(ergebnis, "ergebnis");
    }

    public UUID transactionId() {
        return transactionId;
    }

    public UUID spielerId() {
        return spielerId;
    }

    public String spielerName() {
        return spielerName;
    }

    public BigDecimal betrag() {
        return betrag;
    }

    public BuchungsTyp typ() {
        return typ;
    }

    public String quelle() {
        return quelle;
    }

    public String grund() {
        return grund;
    }

    public BuchungsErgebnis ergebnis() {
        return ergebnis;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
