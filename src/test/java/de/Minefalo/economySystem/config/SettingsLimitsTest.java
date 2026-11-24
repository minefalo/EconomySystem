package de.Minefalo.economySystem.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prüft {@code network.limits}: Gruppen wie {@code credit.take} liegen
 * verschachtelt und müssen über den Bukkit-Pfad gefunden werden.
 */
class SettingsLimitsTest {

    @Test
    void liestGruppenMitPunktImNamen() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                network:
                  limits:
                    credit:
                      take:
                        count: 7
                        seconds: 120
                        cooldown-seconds: 4
                    balance:
                      top:
                        count: 2
                        seconds: 30
                        cooldown-seconds: 1
                """);

        ConfigurationSection limits = config.getConfigurationSection("network.limits");

        Settings.RateLimit take = Settings.readLimit(limits, "credit.take", 3, 300, 10);
        assertEquals(7, take.count());
        assertEquals(120, take.seconds());
        assertEquals(4, take.cooldownSeconds());

        Settings.RateLimit top = Settings.readLimit(limits, "balance.top", 0, 60, 2);
        assertEquals(2, top.count());
        assertEquals(30, top.seconds());
        assertEquals(1, top.cooldownSeconds());
    }

    @Test
    void unbekannteGruppeNutztDieStandardwerte() {
        Settings.RateLimit limit = Settings.readLimit(null, "gui", 0, 60, 2);

        assertEquals(0, limit.count());
        assertEquals(60, limit.seconds());
        assertEquals(2, limit.cooldownSeconds());
    }
}
