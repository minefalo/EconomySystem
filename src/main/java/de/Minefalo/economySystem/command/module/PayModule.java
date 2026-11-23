package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandPermissions;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.config.Settings;
import de.Minefalo.economySystem.economy.Account;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.storage.TransferData;
import de.Minefalo.economySystem.storage.TransferResult;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /eco pay <konto> <betrag> [--reason <text>]} - überweist Geld an
 * einen anderen Spieler.
 *
 * <p>Die Permission prüft der Router ({@code economysystem.command.pay} oder
 * der bisherige Knoten). Im Modus {@code LOCAL} muss das Ziel online sein; das
 * bleibt so, bis {@code network.transfer.mode} umgestellt wird.</p>
 */
public final class PayModule extends AbstractModule {

    private static final String PERMISSION_PAY = "economysystem.pay";

    /** Recht für Ziele, die nicht auf diesem Server online sind (11.2). */
    private static final String PERMISSION_REMOTE = "economysystem.pay.remote";

    /** Ergebnis der Fernbuchung zwischen {@code prepare()} und {@code execute()}. */
    private static final String STATE_REMOTE = "pay.remote";

    private record RemoteOutcome(CommandResult result) {
    }

    public PayModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    public String id() {
        return "pay";
    }

    @Override
    public String path() {
        return "pay";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public String usage() {
        return "/eco pay <konto> <betrag> [--reason <text>]";
    }

    @Override
    public CompletableFuture<?> prepare(CommandContext context) {
        CommandSender sender = context.sender();
        List<String> args = context.args().positional();
        // Die Permission hat der Router bereits geprüft (Stufe 4); hier wird
        // nur geladen, was für die Ausführung nötig ist.
        if (!(sender instanceof Player player)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?> preparation = economy.accountAsync(player.getUniqueId(), player.getName());
        if (args.size() != 2) {
            return preparation;
        }
        if (Bukkit.getPlayerExact(args.get(0)) != null) {
            return CompletableFuture.allOf(preparation, economy.findAsync(args.get(0)));
        }
        // Ziel nicht auf diesem Server: buchen, wenn der Modus es erlaubt (11.2).
        return CompletableFuture.allOf(preparation,
                prepareRemote(context, player, args.get(0), args.get(1)));
    }

    /**
     * Bucht eine Überweisung an ein nicht lokal anwesendes Ziel idempotent im
     * Speicher. Bei {@code LOCAL} oder fehlendem Recht wird nichts vorbereitet;
     * {@link #execute} meldet dann den passenden Fehler.
     */
    private CompletableFuture<?> prepareRemote(CommandContext context, Player player,
                                               String name, String rawAmount) {
        Settings.Network network = plugin.settings().network();
        if (network.transfer().mode() != Settings.TransferMode.ANY
                || !player.hasPermission(PERMISSION_REMOTE)) {
            return CompletableFuture.completedFuture(null);
        }
        Optional<BigDecimal> parsed = currency().parseAmount(rawAmount);
        if (parsed.isEmpty() || parsed.get().signum() <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        BigDecimal amount = currency().normalize(parsed.get());

        return economy.findAsync(name).thenCompose(found -> {
            if (found.isEmpty()) {
                context.state().put(STATE_REMOTE, new RemoteOutcome(
                        CommandResult.rejected("player-not-found").with("player", name)));
                return CompletableFuture.completedFuture(null);
            }
            Account target = found.get();
            if (target.uniqueId().equals(player.getUniqueId())) {
                context.state().put(STATE_REMOTE, new RemoteOutcome(CommandResult.rejected("pay-self")));
                return CompletableFuture.completedFuture(null);
            }

            TransferData transfer = TransferData.create(network.scopeKey(),
                    player.getUniqueId(), player.getName(),
                    target.uniqueId(), target.name(), amount, network.id(), null);
            return economy.remoteTransferAsync(transfer)
                    .thenAccept(result -> context.state().put(STATE_REMOTE, new RemoteOutcome(
                            remoteResult(result, target.name(), amount, transfer.id()))))
                    .exceptionally(error -> {
                        plugin.getLogger().warning("Fernbuchung an " + name + " fehlgeschlagen: "
                                + messageOf(error));
                        context.state().put(STATE_REMOTE, new RemoteOutcome(
                                CommandResult.rejected("command.pay.error.remote-failed")));
                        return null;
                    });
        });
    }

    @Override
    public CommandResult execute(CommandContext context) {
        CommandSender sender = context.sender();
        if (!(sender instanceof Player player)) {
            return CommandResult.rejected("command._default.error.player-only");
        }

        List<String> args = context.args().positional();
        if (args.size() != 2) {
            usageError(sender, String.join(" ", args));
            return CommandResult.handled();
        }

        Player recipient = Bukkit.getPlayerExact(args.get(0));
        if (recipient == null) {
            return remote(context, args.get(0), args.get(1));
        }
        Account source = economy.account(player.getUniqueId(), player.getName());
        Account target = economy.account(recipient.getUniqueId(), recipient.getName());
        if (target == null) {
            return CommandResult.handled();
        }
        if (target.uniqueId().equals(source.uniqueId())) {
            return CommandResult.rejected("pay-self");
        }

        BigDecimal amount = parseAmount(sender, args.get(1), false);
        if (amount == null) {
            return CommandResult.handled();
        }

        if (source.balance().compareTo(amount) < 0) {
            return CommandResult.rejected("insufficient-funds")
                    .with("balance", currency().format(source.balance()))
                    .with("currency", currency().name());
        }

        BigDecimal maxBalance = currency().maxBalance();
        if (maxBalance != null && target.balance().add(amount).compareTo(maxBalance) > 0) {
            return CommandResult.rejected("amount-too-large")
                    .with("max", currency().format(maxBalance))
                    .with("player", target.name());
        }

        if (!economy.transfer(source, target, amount)) {
            return CommandResult.rejected("insufficient-funds")
                    .with("balance", currency().format(source.balance()))
                    .with("currency", currency().name());
        }

        Player onlineTarget = Bukkit.getPlayer(target.uniqueId());
        if (onlineTarget != null) {
            messages().send(onlineTarget, "pay-received",
                    "player", player.getName(),
                    "amount", currency().format(amount),
                    "balance", currency().format(target.balance()),
                    "currency", currency().name());
        }

        return CommandResult.ok("pay-sent")
                .with("player", target.name())
                .with("amount", currency().format(amount))
                .with("balance", currency().format(source.balance()))
                .with("currency", currency().name());
    }

    /**
     * Antwort für ein Ziel, das nicht auf diesem Server online ist: je nach
     * {@code network.transfer.mode} ein Fehler oder das Ergebnis der in
     * {@link #prepareRemote} gebuchten Überweisung.
     */
    private CommandResult remote(CommandContext context, String name, String rawAmount) {
        Settings.Network network = plugin.settings().network();
        if (network.transfer().mode() == Settings.TransferMode.LOCAL) {
            return CommandResult.rejected("command.pay.error.recipient-online-only");
        }
        // PROXY braucht einen bekannten Zielserver; eine Serverliste gibt es
        // noch nicht, deshalb ist das kein stilles Umleiten (11.2).
        if (network.transfer().mode() == Settings.TransferMode.PROXY) {
            return CommandResult.rejected("command.pay.error.no-route");
        }
        if (!context.sender().hasPermission(PERMISSION_REMOTE)) {
            return CommandResult.rejected("command.pay.error.remote-not-allowed");
        }
        if (context.state().get(STATE_REMOTE) instanceof RemoteOutcome outcome) {
            return outcome.result();
        }
        // Nichts vorbereitet (z. B. ungültiger Betrag): dieselbe Meldung wie lokal.
        return CommandResult.rejected("invalid-amount").with("input", rawAmount);
    }

    private CommandResult remoteResult(TransferResult result, String targetName,
                                       BigDecimal amount, UUID txId) {
        return switch (result) {
            case APPLIED -> CommandResult.ok("command.pay.remote-sent")
                    .with("player", targetName)
                    .with("amount", currency().format(amount))
                    .with("tx", txId.toString());
            case DUPLICATE -> CommandResult.rejected("command.pay.error.duplicate");
            case INSUFFICIENT_FUNDS -> CommandResult.rejected("command.pay.error.insufficient");
            case MAX_BALANCE -> CommandResult.rejected("amount-too-large")
                    .with("max", currency().maxBalance() == null
                            ? "" : currency().format(currency().maxBalance()))
                    .with("player", targetName);
            case UNSUPPORTED, FAILED -> CommandResult.rejected("command.pay.error.remote-failed");
        };
    }

    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.isEmpty() || !CommandPermissions.granted(context.sender(), PERMISSION_PAY)) {
            return List.of();
        }
        if (raw.size() == 1) {
            return filter(onlinePlayerNames(), raw.get(0));
        }
        if (raw.size() == 2) {
            return filter(plugin.settings().suggestions().amounts(), raw.get(1));
        }
        return List.of();
    }
}
