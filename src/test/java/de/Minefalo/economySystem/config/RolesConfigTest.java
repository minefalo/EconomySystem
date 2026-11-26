package de.Minefalo.economySystem.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die Auflösung der {@code roles.yml} ohne laufendes Plugin: Rollen
 * bündeln Permissions, dürfen andere Rollen einbinden und erkennen Zyklen.
 */
@DisplayName("roles.yml: Rollen zu Permissions auflösen")
class RolesConfigTest {

    @Test
    @DisplayName("Rollen bündeln Permissions und können andere Rollen einbinden")
    void rolesBundlePermissionsAndNestedRoles() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("economysystem.role.moderator",
                List.of("economysystem.command.balance", "economysystem.command.pay"));
        raw.put("economysystem.role.admin",
                List.of("economysystem.role.moderator", "economysystem.command.admin"));

        Map<String, Set<String>> roles = RolesConfig.resolve(raw, message -> {
            throw new AssertionError(message);
        });

        assertEquals(Set.of("economysystem.command.balance", "economysystem.command.pay"),
                roles.get("economysystem.role.moderator"));
        assertEquals(Set.of("economysystem.command.balance", "economysystem.command.pay",
                        "economysystem.command.admin"),
                roles.get("economysystem.role.admin"));
    }

    @Test
    @DisplayName("Zyklen werden gemeldet, Wildcards decken passende Knoten ab")
    void cyclesAreReportedAndWildcardsMatch() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("economysystem.role.one", List.of("economysystem.role.two"));
        raw.put("economysystem.role.two", List.of("economysystem.role.one", "economysystem.command.*"));

        List<String> warnings = new ArrayList<>();
        Map<String, Set<String>> roles = RolesConfig.resolve(raw, warnings::add);

        assertFalse(warnings.isEmpty(), "ein Rollen-Zyklus muss gemeldet werden");
        assertTrue(roles.get("economysystem.role.one").contains("economysystem.command.*"));

        assertTrue(RolesConfig.matches("economysystem.command.*", "economysystem.command.pay"));
        assertFalse(RolesConfig.matches("economysystem.command.*", "economysystem.pay"));
        assertTrue(RolesConfig.matches("*", "economysystem.command.pay"));
        assertTrue(RolesConfig.matches("economysystem.command.pay", "economysystem.command.pay"));
        assertFalse(RolesConfig.matches("economysystem.command.pay", "economysystem.command.pay.other"));
    }
}
