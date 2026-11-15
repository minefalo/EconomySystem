package de.Minefalo.economySystem.economy;

import de.Minefalo.economySystem.EconomySystem;
import de.Minefalo.economySystem.config.Settings;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plant den wiederkehrenden Zinszyklus anhand der settings.yml.
 * Der Zyklus selbst läuft asynchron im {@link EconomyService}.
 */
public final class InterestScheduler {

    private final EconomySystem plugin;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private BukkitTask task;

    public InterestScheduler(EconomySystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Plant den ersten Lauf zur konfigurierten Startzeit und danach im
     * konfigurierten Intervall.
     */
    public void start() {
        stop();

        Settings.Interest interest = plugin.settings().interest();
        if (!interest.enabled()) {
            plugin.getLogger().info("Der automatische Zinszyklus ist deaktiviert.");
            return;
        }

        long intervalTicks = interest.intervalMinutes() * 60L * 20L;
        long initialTicks = initialDelayTicks(interest.startTime());
        task = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::runCycle, initialTicks, intervalTicks);

        plugin.getLogger().info("Zinszyklus geplant: erster Lauf in " + formatDuration(initialTicks)
                + ", danach alle " + interest.intervalMinutes() + " Minuten.");
    }

    /**
     * Lädt den Zeitplan neu (z. B. nach /economysystem reload).
     */
    public void reload() {
        start();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void runCycle() {
        Settings.Interest interest = plugin.settings().interest();
        if (!interest.enabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            plugin.getLogger().warning("Der vorherige Zinszyklus läuft noch - dieser Lauf wird übersprungen.");
            return;
        }

        BigDecimal factor = interest.savingsFactor();
        EconomyService economy = plugin.economy();
        if (economy == null) {
            running.set(false);
            return;
        }

        plugin.getLogger().info("Zinszyklus gestartet (Sparzins-Faktor "
                + factor.stripTrailingZeros().toPlainString() + ").");

        economy.runInterestCycle(factor).whenComplete((result, error) -> {
            running.set(false);
            if (error != null) {
                plugin.getLogger().severe("Zinszyklus fehlgeschlagen: " + messageOf(error));
                return;
            }
            plugin.getLogger().info("Zinszyklus abgeschlossen: " + result.accountsCredited()
                    + " von " + result.accountsChecked() + " Konten verzinst, insgesamt "
                    + plugin.settings().currency().format(result.totalCredited())
                    + " gutgeschrieben.");
        });
    }

    private static long initialDelayTicks(java.time.LocalTime startTime) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.toLocalDate().atTime(startTime);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        long ticks = Duration.between(now, next).toMillis() / 50L;
        return Math.max(1L, ticks);
    }

    private static String formatDuration(long ticks) {
        long seconds = Math.max(0L, ticks / 20L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0) {
            return hours + "h " + minutes + "min";
        }
        return Math.max(1L, minutes) + "min";
    }

    private static String messageOf(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
