package de.Minefalo.economySystem.command.module;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.command.CommandContext;
import de.Minefalo.economySystem.command.CommandPermissions;
import de.Minefalo.economySystem.command.CommandResult;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.economy.InterestResult;
import org.bukkit.command.CommandSender;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /eco admin test interest-cycle add <anzahl>} - führt den Zinszyklus
 * sofort mehrfach aus und simuliert damit vergangene Zyklen.
 *
 * <p>Der Pfad ist ein reguläres Modul (kein Reflective-Loading mehr) und
 * bleibt trotzdem inert, solange {@code tests.enabled} in der
 * {@code settings.yml} aus ist oder das Recht fehlt. Er verändert echte
 * Kontostände und gehört deshalb nicht auf Produktionsserver.</p>
 */
public final class AdminTestInterestCycleModule extends AbstractModule {

    private static final String PERMISSION_TESTS = "economysystem.tests";

    public AdminTestInterestCycleModule(EconomySystem plugin, EconomyService economy) {
        super(plugin, economy);
    }

    @Override
    public String id() {
        return "interest-cycle";
    }

    @Override
    public String path() {
        return "admin.test.interest-cycle";
    }

    @Override
    public String usage() {
        return "/eco admin test interest-cycle add <anzahl>";
    }

    @Override
    public CommandResult execute(CommandContext context) {
        List<String> args = context.args().positional();
        if (args.size() != 2 || !args.get(0).equalsIgnoreCase("add")) {
            usageError(context.sender(), String.join(" ", args));
            return CommandResult.handled();
        }
        return run(context.sender(), args.get(1));
    }

    /**
     * Prüft Freigabe und Recht und startet die Simulation. Wird auch von der
     * bisherigen Schreibweise {@code /economysystem-admin tests zins-zyklus
     * add <anzahl>} genutzt.
     */
    CommandResult run(CommandSender sender, String rawCount) {
        if (!plugin.settings().testsEnabled()) {
            return CommandResult.rejected("tests-disabled");
        }
        if (!CommandPermissions.granted(sender, PERMISSION_TESTS)) {
            return CommandResult.rejected("command._default.error.no-permission");
        }
        if (!plugin.settings().interest().enabled()) {
            return CommandResult.rejected("tests-interest-disabled");
        }

        int cycles = parseCycleCount(sender, rawCount);
        if (cycles <= 0) {
            return CommandResult.handled();
        }
        simulateCycles(sender, cycles);
        return CommandResult.handled();
    }

    private int parseCycleCount(CommandSender sender, String input) {
        int maximum = plugin.settings().testMaxCycles();
        try {
            int cycles = Integer.parseInt(input.trim());
            if (cycles >= 1 && cycles <= maximum) {
                return cycles;
            }
        } catch (NumberFormatException ignored) {
            // Fällt unten auf die Fehlermeldung zurück.
        }
        messages().send(sender, "tests-cycles-invalid", "max", String.valueOf(maximum));
        return 0;
    }

    private void simulateCycles(CommandSender sender, int cycles) {
        BigDecimal factor = plugin.settings().interest().savingsFactor();

        messages().send(sender, "tests-started",
                "cycles", String.valueOf(cycles),
                "factor", factor.stripTrailingZeros().toPlainString());
        plugin.getLogger().info("Test: " + cycles + " Zinszyklen werden sofort simuliert (Sparzins-Faktor "
                + factor.stripTrailingZeros().toPlainString() + ").");

        BigDecimal[] totalCredited = {BigDecimal.ZERO};
        int[] accountsCredited = {0};
        int[] accountsChecked = {0};

        // Die Zyklen laufen nacheinander, damit jeder auf dem Guthaben des
        // vorherigen aufbaut (wie bei echten Zyklen).
        CompletableFuture<InterestResult> chain = CompletableFuture.completedFuture(null);
        for (int cycle = 0; cycle < cycles; cycle++) {
            chain = chain.thenCompose(previous -> economy.runInterestCycle(factor)
                    .thenApply(result -> {
                        totalCredited[0] = totalCredited[0].add(result.totalCredited());
                        accountsCredited[0] += result.accountsCredited();
                        accountsChecked[0] = result.accountsChecked();
                        return result;
                    }));
        }

        chain.whenComplete((result, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            String errorMessage = error == null ? null : messageOf(error);
            if (errorMessage != null) {
                plugin.getLogger().severe("Test-Zinszyklen fehlgeschlagen: " + errorMessage);
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (errorMessage != null) {
                    messages().send(sender, "tests-failed", "error", errorMessage);
                    return;
                }
                plugin.refreshPlaceholders();
                messages().send(sender, "tests-result",
                        "cycles", String.valueOf(cycles),
                        "credited", String.valueOf(accountsCredited[0]),
                        "checked", String.valueOf(accountsChecked[0]),
                        "total", currency().format(totalCredited[0]));
            });
        });
    }

    @Override
    public List<String> complete(CommandContext context) {
        List<String> raw = context.args().raw();
        if (raw.size() == 1) {
            return filter(List.of("add"), raw.get(0));
        }
        if (raw.size() == 2) {
            return filter(List.of("1", "7", "30"), raw.get(1));
        }
        return List.of();
    }
}
