package de.Minefalo.economySystem.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Gleicht die Konfigurationsdateien mit den mitgelieferten Vorlagen ab.
 *
 * <p>Der Abgleich folgt dem Vorbild aus PunishSystem: Die Vorlage und die
 * vorhandene Datei werden als SnakeYAML-Knoten gelesen und zusammengeführt.
 * Fehlende Schlüssel kommen samt Standardkommentar aus der Vorlage, die
 * Reihenfolge folgt der Vorlage, vorhandene Werte bleiben unverändert und
 * eigene Zusatzschlüssel bleiben am Ende ihres Abschnitts erhalten. Die
 * Standardkommentare werden dabei aktualisiert. Geschrieben wird nur, wenn
 * sich der Text tatsächlich ändert; vorher entsteht eine Sicherung im
 * {@code backup}-Ordner.</p>
 *
 * <p>Alle Konfigurationsdateien liegen unter {@code plugins/EconomySystem/config/}.
 * Daten wie {@code loans.yml} oder {@code accounts/} liegen unter
 * {@code plugins/EconomySystem/data/}.</p>
 */
public final class ConfigUpdater {

    private static final String CONFIG_FOLDER = "config";
    private static final String BACKUP_FOLDER = "backup";
    private static final String RESOURCE_PREFIX = "config/";
    /** Zentrale Datei mit den Versionen aller Konfigurationsdateien. */
    public static final String VERSION_FILE = "config-version.yml";
    /** Veralteter Schlüssel; ältere Installationen tragen ihn noch in der Datei. */
    private static final String VERSION_KEY = "config-version";
    private static final String FALLBACK_VERSION_FILE =
            "# Versionen der Konfigurationsdateien. Wird vom Updater gepflegt.\n";
    private static final long MAX_FILE_SIZE = 64L * 1024L * 1024L;
    private static final int MAX_CODE_POINTS = 32 * 1024 * 1024;

    /** Dateien, deren Texte Legacy-Farbcodes enthalten dürfen. */
    enum Markup {
        NONE,
        TEXT,
        GUI_TEXT
    }

    /**
     * Schlüssel in der {@code gui.yml}, die keine Anzeigetexte sind und
     * deshalb nicht in MiniMessage umgeschrieben werden.
     */
    private static final Set<String> GUI_NON_TEXT_KEYS = Set.of(
            "permission", "material", "commands", "open", "head",
            "type", "pattern", "filter", "sort", "path");

    private static final Map<Character, String> LEGACY_TAGS = Map.ofEntries(
            Map.entry('0', "black"),
            Map.entry('1', "dark_blue"),
            Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"),
            Map.entry('4', "dark_red"),
            Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"),
            Map.entry('7', "gray"),
            Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"),
            Map.entry('a', "green"),
            Map.entry('b', "aqua"),
            Map.entry('c', "red"),
            Map.entry('d', "light_purple"),
            Map.entry('e', "yellow"),
            Map.entry('f', "white"),
            Map.entry('k', "obfuscated"),
            Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"),
            Map.entry('n', "underlined"),
            Map.entry('o', "italic"),
            Map.entry('r', "reset"));

    private ConfigUpdater() {
    }

    // ------------------------------------------------------------------
    // Pfade
    // ------------------------------------------------------------------

    /**
     * Ordner aller Konfigurationsdateien ({@code plugins/EconomySystem/config}).
     */
    public static File configFolder(JavaPlugin plugin) {
        return configFolder(plugin.getDataFolder());
    }

    static File configFolder(File dataFolder) {
        return new File(dataFolder, CONFIG_FOLDER);
    }

    /**
     * Ordner der Sicherungen ({@code config/backup}); wird bei Bedarf angelegt.
     */
    public static File backupFolder(JavaPlugin plugin) {
        return new File(configFolder(plugin), BACKUP_FOLDER);
    }

    /**
     * Zielpfad einer Konfigurationsdatei.
     */
    public static File file(JavaPlugin plugin, String fileName) {
        return file(plugin.getDataFolder(), fileName);
    }

    static File file(File dataFolder, String fileName) {
        return new File(configFolder(dataFolder), fileName);
    }

    // ------------------------------------------------------------------
    // Versionen
    // ------------------------------------------------------------------

    /**
     * Version einer Konfigurationsdatei aus der {@code config-version.yml}.
     *
     * <p>Solange eine ältere Installation den Schlüssel noch in der Datei
     * selbst trägt, zählt der größere der beiden Werte. So bleibt eine
     * neuere Datei auch beim ersten Start nach dem Wechsel erkannt.</p>
     *
     * @return die gespeicherte Version oder {@code 0}, wenn keine bekannt ist
     */
    public static int version(JavaPlugin plugin, String fileName) {
        return version(file(plugin, VERSION_FILE), file(plugin, fileName), fileName);
    }

    static int version(File versionsFile, File target, String fileName) {
        return Math.max(storedVersion(versionsFile, fileName), legacyVersion(target));
    }

    private static int storedVersion(File versionsFile, String fileName) {
        if (!versionsFile.isFile()) {
            return 0;
        }
        try {
            return YamlConfiguration.loadConfiguration(versionsFile).getInt(fileName, 0);
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static int legacyVersion(File target) {
        if (!target.isFile()) {
            return 0;
        }
        try {
            return YamlConfiguration.loadConfiguration(target).getInt(VERSION_KEY, 0);
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    /**
     * Version einer Datei aus der JAR-Vorlage der {@code config-version.yml}.
     */
    private static int bundledVersion(JavaPlugin plugin, String fileName) {
        String text = readResource(plugin, VERSION_FILE);
        if (text == null) {
            return 0;
        }
        YamlConfiguration versions = new YamlConfiguration();
        try {
            versions.loadFromString(text);
        } catch (InvalidConfigurationException exception) {
            plugin.getLogger().warning("JAR-Vorlage " + RESOURCE_PREFIX + VERSION_FILE
                    + " ist kein gültiges YAML: " + exception.getMessage());
            return 0;
        }
        return versions.getInt(fileName, 0);
    }

    /**
     * Schreibt die Version einer Datei in die {@code config-version.yml}.
     */
    private static boolean storeVersion(JavaPlugin plugin, String fileName, int version) {
        return storeVersion(file(plugin, VERSION_FILE), readResource(plugin, VERSION_FILE),
                fileName, version, plugin.getLogger());
    }

    /**
     * Setzt eine Version in der bestehenden Versiondatei. Fehlt die Datei,
     * entsteht sie aus {@code template}. Kommentare bleiben erhalten, und ein
     * unveränderter Wert wird nicht neu geschrieben.
     */
    static boolean storeVersion(File versionsFile, String template, String fileName, int version,
                                Logger logger) {
        try {
            File parent = versionsFile.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }

            if (!versionsFile.exists()) {
                // Nur die gerade erfolgreich abgeglichene Datei eintragen.
                // Würden alle Vorlagenwerte übernommen, gälte eine Datei
                // mit fehlgeschlagenem Abgleich fälschlich als aktuell.
                Files.writeString(versionsFile.toPath(),
                        versionHeader(template) + fileName + ": " + version + "\n",
                        StandardCharsets.UTF_8);
                return true;
            }

            String before = Files.readString(versionsFile.toPath(), StandardCharsets.UTF_8);
            String after = setVersionText(before, fileName, version);
            if (after.equals(before)) {
                return false;
            }
            Files.writeString(versionsFile.toPath(), after, StandardCharsets.UTF_8);
            return true;
        } catch (Exception exception) {
            logger.warning(VERSION_FILE + " konnte nicht aktualisiert werden;"
                    + " die Versionen bleiben unverändert. (" + exception.getMessage() + ")");
            return false;
        }
    }

    /**
     * Übernimmt den Kopfkommentar der JAR-Vorlage für eine neu angelegte
     * Versiondatei. Die Einträge entstehen erst beim erfolgreichen Abgleich
     * der jeweiligen Datei.
     */
    private static String versionHeader(String template) {
        if (template == null || template.isBlank()) {
            return FALLBACK_VERSION_FILE;
        }
        StringBuilder header = new StringBuilder();
        for (String line : template.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                break;
            }
            header.append(line).append('\n');
        }
        return header.toString().isBlank() ? FALLBACK_VERSION_FILE : header.toString();
    }

    /**
     * Setzt {@code <fileName>: <version>} in einem YAML-Mapping, ohne die
     * übrigen Einträge und Kommentare zu verändern.
     */
    static String setVersionText(String text, String fileName, int version) {
        String base = text == null || text.isBlank() ? FALLBACK_VERSION_FILE : text;
        LoaderOptions options = loaderOptions();
        DumperOptions output = dumperOptions(base);
        Yaml yaml = new Yaml(new SafeConstructor(options), new Representer(output), output, options);
        Node root = yaml.compose(new StringReader(base));
        if (!(root instanceof MappingNode mapping) || !Tag.MAP.equals(mapping.getTag())) {
            // Leere oder reine Kommentardatei: Eintrag anhängen und den
            // Kopfkommentar erhalten. SnakeYAML liefert für solche Texte
            // einen Mapping-Knoten mit dem Tag "comment" und ohne Werte.
            String header = base.endsWith("\n") ? base : base + "\n";
            return header + fileName + ": " + version + "\n";
        }
        if (!setInt(mapping, fileName, version)) {
            return base;
        }
        return write(yaml, mapping);
    }

    /**
     * Ersetzt oder ergänzt einen ganzzahligen Eintrag im Mapping.
     *
     * @return {@code true}, wenn sich der Wert geändert hat
     */
    private static boolean setInt(MappingNode mapping, String key, int version) {
        List<NodeTuple> tuples = new ArrayList<>(mapping.getValue().size());
        boolean replaced = false;
        for (NodeTuple tuple : mapping.getValue()) {
            if (!replaced
                    && tuple.getKeyNode() instanceof ScalarNode keyNode
                    && key.equals(keyNode.getValue())
                    && tuple.getValueNode() instanceof ScalarNode scalar) {
                String value = Integer.toString(version);
                if (value.equals(scalar.getValue().trim())) {
                    return false;
                }
                ScalarNode replacement = new ScalarNode(Tag.INT, value,
                        scalar.getStartMark(), scalar.getEndMark(), DumperOptions.ScalarStyle.PLAIN);
                copyComments(scalar, replacement);
                tuples.add(new NodeTuple(tuple.getKeyNode(), replacement));
                replaced = true;
            } else {
                tuples.add(tuple);
            }
        }
        if (replaced) {
            mapping.setValue(tuples);
            return true;
        }

        tuples.add(new NodeTuple(
                new ScalarNode(Tag.STR, key, null, null, DumperOptions.ScalarStyle.PLAIN),
                new ScalarNode(Tag.INT, Integer.toString(version), null, null,
                        DumperOptions.ScalarStyle.PLAIN)));
        mapping.setValue(tuples);
        return true;
    }

    // ------------------------------------------------------------------
    // Abgleich
    // ------------------------------------------------------------------

    /**
     * Legt die Datei bei Bedarf aus der JAR-Vorlage an und gleicht sie sonst
     * mit ihr ab.
     *
     * @return {@code true}, wenn die Datei angelegt oder geändert wurde
     */
    public static boolean update(JavaPlugin plugin, String fileName) {
        return update(plugin, fileName, backupCount(plugin));
    }

    /**
     * Wie {@link #update(JavaPlugin, String)}, hält aber bis zu
     * {@code backupCount} Sicherungen je Datei vor.
     *
     * <p>Die Dateiversion steht in der {@code config-version.yml}. Sie wird
     * nach erfolgreichem Abgleich auf den größeren Wert aus vorhandener
     * Version und JAR-Vorlage gesetzt.</p>
     */
    public static boolean update(JavaPlugin plugin, String fileName, int backupCount) {
        String template = readResource(plugin, fileName);
        if (template == null) {
            return false;
        }

        int bundledVersion = bundledVersion(plugin, fileName);
        int installedVersion = version(plugin, fileName);
        if (installedVersion > bundledVersion) {
            plugin.getLogger().warning("Die Version von " + fileName + " in " + VERSION_FILE
                    + " ist " + installedVersion + ", die Vorlage hat " + bundledVersion
                    + " - Werte bleiben erhalten, Kommentare stammen aus der Vorlage.");
        }

        Result result = apply(file(plugin, fileName), template, plugin.getLogger(),
                markupFor(fileName), backupCount);
        if (result == Result.FAILED) {
            // Eine kaputte oder nicht schreibbare Datei wird nicht als
            // aktuell markiert; sonst liefen spätere Migrationen nie.
            return false;
        }

        int newVersion = Math.max(installedVersion, bundledVersion);
        boolean stored = newVersion > 0 && storeVersion(plugin, fileName, newVersion);
        return result == Result.CHANGED || stored;
    }

    /**
     * Liest {@code config.backup-count} roh aus der vorhandenen
     * {@code settings.yml}. Die Einstellung steht in der Datei, die beim
     * Abgleich selbst gesichert wird, und muss deshalb vorher bekannt sein.
     */
    public static int backupCount(JavaPlugin plugin) {
        File settings = file(plugin, "settings.yml");
        if (!settings.exists()) {
            return 1;
        }
        int configured = YamlConfiguration.loadConfiguration(settings)
                .getInt("config.backup-count", 1);
        return Math.max(1, Math.min(20, configured));
    }

    /**
     * Liefert die mitgelieferte Vorlage als Konfiguration, damit Leser
     * fehlende Schlüssel auch dann mit Standardwerten beantworten können,
     * wenn die Datei nicht abgeglichen werden konnte.
     *
     * @return die Vorlage oder {@code null}, wenn sie fehlt oder ungültig ist
     */
    public static YamlConfiguration bundled(JavaPlugin plugin, String fileName) {
        String text = readResource(plugin, fileName);
        if (text == null) {
            return null;
        }
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(text);
            return configuration;
        } catch (InvalidConfigurationException exception) {
            plugin.getLogger().warning("JAR-Vorlage " + RESOURCE_PREFIX + fileName
                    + " ist kein gültiges YAML: " + exception.getMessage());
            return null;
        }
    }

    /**
     * Kern des Abgleichs ohne laufendes Plugin (testbar).
     */
    static boolean update(File target, String template, Logger logger, Markup markup) {
        return update(target, template, logger, markup, 1);
    }

    static boolean update(File target, String template, Logger logger, Markup markup,
                          int backupCount) {
        return apply(target, template, logger, markup, backupCount) == Result.CHANGED;
    }

    /** Ergebnis eines Abgleichs, damit ein Fehler nicht als "unverändert" gilt. */
    private enum Result {
        UNCHANGED,
        CHANGED,
        FAILED
    }

    private static Result apply(File target, String template, Logger logger, Markup markup,
                                int backupCount) {
        if (template == null) {
            return Result.FAILED;
        }

        try {
            File parent = target.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }

            if (!target.exists()) {
                Files.writeString(target.toPath(), template, StandardCharsets.UTF_8);
                logger.info(display(target) + " wurde aus dem JAR angelegt.");
                return Result.CHANGED;
            }

            if (Files.size(target.toPath()) > MAX_FILE_SIZE) {
                throw new IOException("Konfiguration ist zu groß: " + target.getName());
            }

            String before = Files.readString(target.toPath(), StandardCharsets.UTF_8);
            String after = merge(template, before, markup, logger);
            File backup = new File(target.getParentFile(), BACKUP_FOLDER);
            boolean changed = ConfigFileWriter.writeIfChanged(target.toPath(), before, after,
                    backup.toPath(), backupCount, logger);
            if (changed) {
                logger.info(display(target) + " um aktuelle Standards und Kommentare ergänzt.");
            }
            return changed ? Result.CHANGED : Result.UNCHANGED;
        } catch (Exception exception) {
            logger.warning(display(target) + " konnte nicht abgeglichen werden;"
                    + " vorhandene Datei bleibt erhalten. (" + exception.getMessage() + ")");
            return Result.FAILED;
        }
    }

    /**
     * Führt Vorlage und vorhandenen Text zusammen.
     *
     * <p>Package-private, damit der Abgleich ohne Server geprüft werden kann.</p>
     */
    static String merge(String template, String content, Markup markup, Logger logger) {
        if (template.equals(content)) {
            return content;
        }

        LoaderOptions options = loaderOptions();
        ConfigConstructor constructor = new ConfigConstructor(options);
        DumperOptions output = dumperOptions(content);
        Yaml yaml = new Yaml(constructor, new Representer(output), output, options);

        MappingNode templateNode = constructor.document(
                yaml.compose(new StringReader(template)), "Die mitgelieferte Vorlage");
        MappingNode currentNode = constructor.document(
                yaml.compose(new StringReader(content)), "Die vorhandene Datei");

        // Die Version steht seit dem Wechsel in config-version.yml. Ein
        // alter Schlüssel in der Datei selbst wird beim Abgleich entfernt.
        removeVersion(templateNode);
        removeVersion(currentNode);

        Node merged = mergeNodes(templateNode, currentNode);
        merged = convertMarkup(merged, null, markup);

        // SnakeYAML hängt eine Leerzeile vor dem ersten Schlüssel eines
        // Sequenzeintrags als Kommentar an diesen Schlüssel. Dadurch bricht
        // der erste Durchlauf nach dem Aufzählungszeichen um. Ein zweiter
        // Durchlauf liefert die stabile Form; ohne ihn würde dieselbe Datei
        // bei jedem Start erneut geschrieben.
        String once = write(yaml, merged);
        Yaml normalizer = normalizer(once);
        Node normalized = normalizer.compose(new StringReader(once));
        return normalized == null ? once : write(normalizer, normalized);
    }

    private static String write(Yaml yaml, Node node) {
        StringWriter writer = new StringWriter();
        yaml.serialize(node, writer);
        return writer.toString();
    }

    private static Yaml normalizer(String content) {
        LoaderOptions options = loaderOptions();
        DumperOptions output = dumperOptions(content);
        return new Yaml(new SafeConstructor(options), new Representer(output), output, options);
    }

    private static LoaderOptions loaderOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setCodePointLimit(MAX_CODE_POINTS);
        options.setProcessComments(true);
        return options;
    }

    private static DumperOptions dumperOptions(String content) {
        DumperOptions options = new DumperOptions();
        options.setProcessComments(true);
        options.setIndent(2);
        options.setSplitLines(false);
        options.setLineBreak(content.contains("\r\n")
                ? DumperOptions.LineBreak.WIN
                : DumperOptions.LineBreak.UNIX);
        return options;
    }

    // ------------------------------------------------------------------
    // Knoten zusammenführen
    // ------------------------------------------------------------------

    /**
     * Ergänzt alle Schlüssel der Vorlage, die im vorhandenen Knoten fehlen.
     * Vorhandene Werte werden nie ersetzt; eigene Zusatzschlüssel bleiben am
     * Ende ihres Abschnitts stehen.
     */
    private static Node mergeNodes(Node template, Node current) {
        copyComments(template, current);

        if (template instanceof MappingNode templateMapping
                && current instanceof MappingNode currentMapping) {
            Map<Map.Entry<Tag, String>, NodeTuple> remaining = new LinkedHashMap<>();
            for (NodeTuple tuple : currentMapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key)) {
                    throw new IllegalArgumentException("Konfigurationsschlüssel müssen Skalare sein.");
                }
                remaining.put(Map.entry(key.getTag(), key.getValue()), tuple);
            }

            List<NodeTuple> merged = new ArrayList<>();
            for (NodeTuple tuple : templateMapping.getValue()) {
                ScalarNode key = (ScalarNode) tuple.getKeyNode();
                NodeTuple existing = remaining.remove(Map.entry(key.getTag(), key.getValue()));
                if (existing == null) {
                    merged.add(tuple);
                } else {
                    copyComments(tuple.getKeyNode(), existing.getKeyNode());
                    merged.add(new NodeTuple(existing.getKeyNode(),
                            mergeNodes(tuple.getValueNode(), existing.getValueNode())));
                }
            }
            merged.addAll(remaining.values());
            currentMapping.setValue(merged);
            currentMapping.setFlowStyle(templateMapping.getFlowStyle());
        }

        return current;
    }

    /**
     * Übernimmt die Standardkommentare der Vorlage. Hat die Vorlage an einer
     * Stelle keinen Kommentar, bleibt der eigene Kommentar stehen.
     */
    private static void copyComments(Node template, Node current) {
        List<CommentLine> block = template.getBlockComments();
        if (block != null && !block.isEmpty()) {
            current.setBlockComments(block);
        }
        List<CommentLine> inline = template.getInLineComments();
        if (inline != null && !inline.isEmpty()) {
            current.setInLineComments(inline);
        }
        List<CommentLine> end = template.getEndComments();
        if (end != null && !end.isEmpty()) {
            current.setEndComments(end);
        }
    }

    /** Entfernt den veralteten {@code config-version}-Schlüssel. */
    private static void removeVersion(MappingNode root) {
        List<NodeTuple> tuples = new ArrayList<>(root.getValue().size());
        for (NodeTuple tuple : root.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode key
                    && VERSION_KEY.equals(key.getValue())) {
                continue;
            }
            tuples.add(tuple);
        }
        root.setValue(tuples);
    }

    // ------------------------------------------------------------------
    // Legacy-Farbcodes
    // ------------------------------------------------------------------

    private static Node convertMarkup(Node node, String key, Markup markup) {
        if (markup == Markup.NONE) {
            return node;
        }

        if (node instanceof MappingNode mapping) {
            List<NodeTuple> tuples = new ArrayList<>(mapping.getValue().size());
            for (NodeTuple tuple : mapping.getValue()) {
                String name = tuple.getKeyNode() instanceof ScalarNode keyNode
                        ? keyNode.getValue()
                        : key;
                if (markup == Markup.GUI_TEXT && name != null && GUI_NON_TEXT_KEYS.contains(name)) {
                    tuples.add(tuple);
                } else {
                    tuples.add(new NodeTuple(tuple.getKeyNode(),
                            convertMarkup(tuple.getValueNode(), name, markup)));
                }
            }
            mapping.setValue(tuples);
            return mapping;
        }
        if (node instanceof SequenceNode sequence) {
            List<Node> children = sequence.getValue();
            for (int index = 0; index < children.size(); index++) {
                children.set(index, convertMarkup(children.get(index), key, markup));
            }
            return sequence;
        }
        if (node instanceof ScalarNode scalar && Tag.STR.equals(scalar.getTag())) {
            String converted = markup(scalar.getValue());
            if (!converted.equals(scalar.getValue())) {
                ScalarNode replacement = new ScalarNode(scalar.getTag(), converted,
                        scalar.getStartMark(), scalar.getEndMark(), scalar.getScalarStyle());
                copyComments(scalar, replacement);
                return replacement;
            }
        }
        return node;
    }

    /**
     * Wandelt {@code &amp;0-&amp;f}, {@code &amp;k-&amp;o}, {@code &amp;r} und
     * {@code &amp;#RRGGBB} in die entsprechenden MiniMessage-Tags um.
     */
    static String markup(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if ((current != '&' && current != '§') || index + 1 >= value.length()) {
                result.append(current);
                continue;
            }

            char next = value.charAt(index + 1);
            if (next == '#' && index + 7 < value.length()) {
                String hex = value.substring(index + 2, index + 8);
                if (hex.matches("[0-9A-Fa-f]{6}")) {
                    result.append("<#").append(hex.toLowerCase()).append('>');
                    index += 7;
                    continue;
                }
            }

            String tag = LEGACY_TAGS.get(Character.toLowerCase(next));
            if (tag == null) {
                result.append(current);
                continue;
            }
            result.append('<').append(tag).append('>');
            index++;
        }
        return result.toString();
    }

    // ------------------------------------------------------------------
    // Hilfsmittel
    // ------------------------------------------------------------------

    private static String readResource(JavaPlugin plugin, String fileName) {
        try (InputStream input = plugin.getResource(RESOURCE_PREFIX + fileName)) {
            if (input == null) {
                plugin.getLogger().warning("JAR-Vorlage " + RESOURCE_PREFIX + fileName
                        + " nicht gefunden.");
                return null;
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("JAR-Vorlage " + RESOURCE_PREFIX + fileName
                    + " konnte nicht gelesen werden: " + exception.getMessage());
            return null;
        }
    }

    static Markup markupFor(String fileName) {
        return switch (fileName) {
            case "messages.yml", "help.yml" -> Markup.TEXT;
            case "gui.yml" -> Markup.GUI_TEXT;
            default -> Markup.NONE;
        };
    }

    private static String display(File target) {
        File parent = target.getParentFile();
        return parent == null ? target.getName() : parent.getName() + "/" + target.getName();
    }

    /**
     * Prüft, dass das Dokument ein YAML-Mapping ist, und lässt die Knoten
     * vom sicheren Konstruktor validieren.
     */
    private static final class ConfigConstructor extends SafeConstructor {

        private ConfigConstructor(LoaderOptions options) {
            super(options);
        }

        private MappingNode document(Node node, String description) {
            if (!(node instanceof MappingNode mapping) || !Tag.MAP.equals(node.getTag())) {
                throw new IllegalArgumentException(description
                        + " muss ein YAML-Mapping enthalten.");
            }
            constructDocument(node);
            return mapping;
        }
    }
}
