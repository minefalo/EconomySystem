package de.Minefalo.economySystem.command.tests;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.economy.EconomyService;
import de.Minefalo.economySystem.economy.InterestResult;
import org.bukkit.command.CommandSender;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /economysystem-admin tests zins-zyklus add <Anzahl>} - führt den
 * Zinszyklus sofort {@code <Anzahl>}-mal aus und simuliert damit vergangene
 * Zyklen.
 *
 * <p>Diese Klasse wird im Maven-Profil {@code production} nicht kompiliert.
 * Sie verändert echte Kontostände und gehört deshalb nicht in ein
 * Produktionsartefakt.</p>
 */
public final class InterestCycleTestCommand implements TestCommand {

    private static final String PERMISSION_TESTS = "economysystem.tests";
    private static final int MAX_TEST_CYCLES = 1000;

    private final EconomySystem plugin;
    private final EconomyService economy;

    public InterestCycleTestCommand(EconomySystem plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!plugin.settings().testsEnabled()) {
            plugin.messages().send(sender, "tests-disabled");
            return;
        }
        if (!sender.hasPermission(PERMISSION_TESTS)) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        if (!plugin.settings().interest().enabled()) {
            plugin.messages().send(sender, "tests-interest-disabled");
            return;
        }
        if (args.length != 4
                || !matchesIgnoreCase(args[1], "zins-zyklus", "zinszyklus", "interest-cycle")
                || !args[2].equalsIgnoreCase("add")) {
            plugin.messages().send(sender, "usage-admin-tests");
            return;
        }

        int cycles = parseCycleCount(sender, args[3]);
        if (cycles <= 0) {
            return;
        }

        simulateCycles(sender, cycles);
    }

    private int parseCycleCount(CommandSender sender, String input) {
        try {
            int cycles = Integer.parseInt(input.trim());
            if (cycles >= 1 && cycles <= MAX_TEST_CYCLES) {
                return cycles;
            }
        } catch (NumberFormatException ignored) {
            // Fällt unten auf die Fehlermeldung zurück.
        }
        plugin.messages().send(sender, "tests-cycles-invalid", "max", String.valueOf(MAX_TEST_CYCLES));
        return 0;
    }

    private void simulateCycles(CommandSender sender, int cycles) {
        BigDecimal factor = plugin.settings().interest().savingsFactor();

        plugin.messages().send(sender, "tests-started",
                "cycles", String.valueOf(cycles),
                "factor", factor.stripTrailingZeros().toPlainString());
        plugin.getLogger().info("Test: " + cycles + " Zinszyklen werden sofort simuliert (Sparzins-Faktor "
                + factor.stripTrailingZeros().toPlainString() + ").");

        BigDecimal[] totalCredited = {BigDecimal.ZERO};
        int[] accountsCredited = {0};
        int[] accountsChecked = {0};

        // Die Zyklen werden nacheinander ausgeführt, damit jeder Lauf auf dem
        // Guthaben des vorherigen aufbaut (wie bei echten Zyklen).
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
                    plugin.messages().send(sender, "tests-failed", "error", errorMessage);
                    return;
                }
                plugin.refreshPlaceholders();
                plugin.messages().send(sender, "tests-result",
                        "cycles", String.valueOf(cycles),
                        "credited", String.valueOf(accountsCredited[0]),
                        "checked", String.valueOf(accountsChecked[0]),
                        "total", plugin.settings().currency().format(totalCredited[0]));
            });
        });
    }

    private static boolean matchesIgnoreCase(String input, String... options) {
        for (String option : options) {
            if (input.equalsIgnoreCase(option)) {
                return true;
            }
        }
        return false;
    }

    private static String messageOf(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
