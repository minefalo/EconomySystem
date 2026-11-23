package de.Minefalo.economySystem.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Prüft den Config-Abgleich: Ergänzen fehlender Schlüssel, Kommentar-Abgleich,
 * Erhalt eigener Werte und Zusatzschlüssel, Umgang mit strukturierten Werten
 * sowie das Verhalten bei kaputten oder unveränderten Dateien.
 */
class ConfigUpdaterTest {

    private static final Logger LOG = Logger.getLogger(ConfigUpdaterTest.class.getName());

    private static final String TEMPLATE = """
            config-version: 2
            alpha: 1
            """;

    @TempDir
    Path temp;

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    @Test
    void ergaenztFehlendeSchluesselUndBehaeltWerte() {
        String template = """
                # Kopfzeile
                config-version: 2
                alpha: 1
                # Erklärung zu beta
                beta: 2
                """;
        String content = """
                # Kopfzeile
                config-version: 1
                alpha: 9
                """;

        YamlConfiguration merged = parse(merge(template, content, ConfigUpdater.Markup.NONE));

        assertEquals(9, merged.getInt("alpha"));
        assertEquals(2, merged.getInt("beta"));
        assertEquals(2, merged.getInt("config-version"));
        assertEquals(List.of("Erklärung zu beta"), merged.getComments("beta"));
    }

    @Test
    void aktualisiertKommentareUndLaesstEigeneStehen() {
        String template = """
                config-version: 2
                # Neuer Standardkommentar
                wert: 1
                ohne: 2
                """;
        String content = """
                config-version: 1
                # Alter Kommentar
                wert: 5
                # Eigener Kommentar
                ohne: 6
                """;

        String text = merge(template, content, ConfigUpdater.Markup.NONE);
        YamlConfiguration merged = parse(text);

        assertEquals(5, merged.getInt("wert"));
        assertEquals(6, merged.getInt("ohne"));
        assertTrue(text.contains("# Neuer Standardkommentar"), text);
        assertFalse(text.contains("# Alter Kommentar"), text);
        assertTrue(text.contains("# Eigener Kommentar"), text);
    }

    @Test
    void behaeltZusatzschluesselUndReihenfolge() {
        String template = """
                config-version: 2
                alpha: 1
                beta: 2
                """;
        String content = """
                config-version: 1
                beta: 20
                alpha: 10
                zeta: 30
                """;

        String text = merge(template, content, ConfigUpdater.Markup.NONE);
        YamlConfiguration merged = parse(text);

        assertEquals(10, merged.getInt("alpha"));
        assertEquals(20, merged.getInt("beta"));
        assertEquals(30, merged.getInt("zeta"));
        assertTrue(text.indexOf("alpha:") < text.indexOf("beta:"), text);
        assertTrue(text.indexOf("beta:") < text.indexOf("zeta:"), text);
    }

    @Test
    void versionWirdAufMaximumGesetzt() {
        String older = "config-version: 1\nalpha: 9\n";
        assertEquals(2, parse(merge(TEMPLATE, older, ConfigUpdater.Markup.NONE))
                .getInt("config-version"));

        String newer = "config-version: 7\nalpha: 9\n";
        assertEquals(7, parse(merge(TEMPLATE, newer, ConfigUpdater.Markup.NONE))
                .getInt("config-version"));
    }

    // ------------------------------------------------------------------
    // Strukturierte Werte und Markup
    // ------------------------------------------------------------------

    @Test
    void listenVonMapsBleibenStruktur() {
        String template = """
                config-version: 2
                pages:
                  - title: "&aStart"
                    lines:
                      - "&bZeile"
                """;
        String content = """
                config-version: 1
                pages:
                  - title: "&cAlt"
                    lines:
                      - "&dZeile"
                """;

        YamlConfiguration merged = parse(merge(template, content, ConfigUpdater.Markup.TEXT));
        List<Map<?, ?>> pages = merged.getMapList("pages");

        assertEquals(1, pages.size(), "pages muss eine Liste von Maps bleiben");
        assertEquals("<red>Alt", pages.get(0).get("title"));
        assertEquals(List.of("<light_purple>Zeile"), pages.get(0).get("lines"));
    }

    @Test
    void guiSchluesselBleibenUnveraendert() {
        String template = """
                config-version: 2
                items:
                  test:
                    name: "&aName"
                    permission: "economysystem.&a"
                    commands:
                      - "pay &a"
                    inputs:
                      - type: text
                        prompt: "&bEingabe"
                        pattern: "[&a]"
                """;
        String content = """
                config-version: 1
                items:
                  test:
                    name: "&cAlt"
                    permission: "economysystem.&a"
                    commands:
                      - "pay &a"
                    inputs:
                      - type: text
                        prompt: "&dEingabe"
                        pattern: "[&a]"
                """;

        YamlConfiguration merged = parse(merge(template, content, ConfigUpdater.Markup.GUI_TEXT));

        assertEquals("<red>Alt", merged.getString("items.test.name"));
        assertEquals("economysystem.&a", merged.getString("items.test.permission"));
        assertEquals(List.of("pay &a"), merged.getStringList("items.test.commands"));
        List<Map<?, ?>> inputs = merged.getMapList("items.test.inputs");
        assertEquals(1, inputs.size(), "inputs muss eine Liste von Maps bleiben");
        assertEquals("<light_purple>Eingabe", inputs.get(0).get("prompt"));
        assertEquals("[&a]", inputs.get(0).get("pattern"));
    }

    @Test
    void markupWandeltLegacyCodes() {
        assertEquals("<green>Hallo", ConfigUpdater.markup("&aHallo"));
        assertEquals("<#aabbcc>Text", ConfigUpdater.markup("&#AABBCCText"));
        assertEquals("<aqua>Text", ConfigUpdater.markup("§bText"));
        assertEquals("&x bleibt", ConfigUpdater.markup("&x bleibt"));
        assertEquals("100 % &", ConfigUpdater.markup("100 % &"));
    }

    // ------------------------------------------------------------------
    // Dateien
    // ------------------------------------------------------------------

    @Test
    void kaputteUndLeereDateiBleibenUnveraendert() throws IOException {
        Path config = Files.createDirectories(temp.resolve("config"));
        Path settings = config.resolve("settings.yml");

        String broken = "kaputt: [\n";
        Files.writeString(settings, broken, StandardCharsets.UTF_8);
        assertFalse(ConfigUpdater.update(settings.toFile(), TEMPLATE, LOG,
                ConfigUpdater.Markup.NONE));
        assertEquals(broken, Files.readString(settings, StandardCharsets.UTF_8));
        assertFalse(Files.exists(config.resolve("backup")));

        Files.writeString(settings, "", StandardCharsets.UTF_8);
        assertFalse(ConfigUpdater.update(settings.toFile(), TEMPLATE, LOG,
                ConfigUpdater.Markup.NONE));
        assertEquals("", Files.readString(settings, StandardCharsets.UTF_8));
        assertFalse(Files.exists(config.resolve("backup")));
    }

    @Test
    void unveraenderteDateiWirdNichtGeschrieben() throws IOException {
        Path config = Files.createDirectories(temp.resolve("config"));
        Path settings = config.resolve("settings.yml");
        Files.writeString(settings, TEMPLATE, StandardCharsets.UTF_8);

        assertFalse(ConfigUpdater.update(settings.toFile(), TEMPLATE, LOG,
                ConfigUpdater.Markup.NONE));
        assertEquals(TEMPLATE, Files.readString(settings, StandardCharsets.UTF_8));
        assertFalse(Files.exists(config.resolve("backup")));
    }

    @Test
    void fehlendeDateiWirdAngelegt() throws IOException {
        Path settings = temp.resolve("config").resolve("settings.yml");

        assertTrue(ConfigUpdater.update(settings.toFile(), TEMPLATE, LOG,
                ConfigUpdater.Markup.NONE));
        assertEquals(TEMPLATE, Files.readString(settings, StandardCharsets.UTF_8));
    }

    @Test
    void aenderungLegtSicherungImBackupOrdnerAn() throws IOException {
        Path config = Files.createDirectories(temp.resolve("config"));
        Path settings = config.resolve("settings.yml");
        String content = "config-version: 1\nalpha: 5\n";
        Files.writeString(settings, content, StandardCharsets.UTF_8);

        assertTrue(ConfigUpdater.update(settings.toFile(), TEMPLATE, LOG,
                ConfigUpdater.Markup.NONE));

        Path backup = config.resolve("backup")
                .resolve("settings.yml" + ConfigFileWriter.BACKUP_SUFFIX);
        assertTrue(Files.exists(backup), "Sicherung muss im backup-Ordner liegen");
        assertEquals(content, Files.readString(backup, StandardCharsets.UTF_8));
    }

    @Test
    void pfadeLiegenImConfigOrdner() {
        File dataFolder = temp.toFile();

        assertEquals(new File(dataFolder, "config"), ConfigUpdater.configFolder(dataFolder));
        assertEquals(new File(dataFolder, "config/settings.yml"),
                ConfigUpdater.file(dataFolder, "settings.yml"));
    }

    // ------------------------------------------------------------------
    // Ausgelieferte Dateien
    // ------------------------------------------------------------------

    @Test
    void ausgelieferteDateienSindIdempotent() throws IOException {
        for (String name : List.of("settings.yml", "messages.yml", "commands.yml",
                "help.yml", "gui.yml")) {
            String template = resource("config/" + name);
            String content = template + "\ncustom-key: \"custom\"\n";
            ConfigUpdater.Markup markup = ConfigUpdater.markupFor(name);

            String first = ConfigUpdater.merge(template, content, markup, LOG);
            String second = ConfigUpdater.merge(template, first, markup, LOG);

            assertEquals(first, second, name + " muss idempotent sein");
            assertTrue(first.contains("custom-key"), name + " muss Zusatzschlüssel behalten");

            YamlConfiguration parsed = parse(first);
            assertFalse(parsed.getKeys(false).isEmpty(), name + " muss Schlüssel enthalten");
            assertNotNull(parsed.getString("config-version"), name + " muss config-version enthalten");

            String firstLine = first.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
            assertTrue(firstLine.startsWith("#"),
                    name + " muss den Kopfkommentar behalten, war: " + firstLine);
        }
    }

    // ------------------------------------------------------------------
    // Hilfsmittel
    // ------------------------------------------------------------------

    private static String merge(String template, String content, ConfigUpdater.Markup markup) {
        return ConfigUpdater.merge(template, content, markup, LOG);
    }

    private static YamlConfiguration parse(String text) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(text);
        } catch (InvalidConfigurationException exception) {
            fail("YAML ist ungültig: " + exception.getMessage() + "\n" + text);
        }
        return configuration;
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = ConfigUpdaterTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertNotNull(input, path + " fehlt im Klassenpfad");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
