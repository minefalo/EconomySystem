package de.Minefalo.economySystem.config;

import java.util.List;

/**
 * Bearbeitet einzelne Faktoren innerhalb des {@code interest}-Blocks einer
 * YAML-Datei, ohne die übrigen Zeilen und Kommentare zu verändern.
 */
final class YamlFactorEditor {

    private YamlFactorEditor() {
    }

    /**
     * Ersetzt oder ergänzt {@code interest.factors.<key>} in den Zeilen.
     */
    static void writeFactor(List<String> lines, String key, String value) {
        int interestIndex = -1;
        int interestIndent = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isBlankOrComment(line)) {
                continue;
            }
            if (line.trim().startsWith("interest:")) {
                interestIndex = i;
                interestIndent = indentOf(line);
                break;
            }
        }

        if (interestIndex < 0) {
            interestIndent = 0;
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add("interest:");
            interestIndex = lines.size() - 1;
            lines.add("  enabled: true");
            lines.add("  start-time: \"00:01\"");
            lines.add("  interval-minutes: 1440");
            lines.add("  factors:");
            lines.add("    spar: \"1.3\"");
            lines.add("    kredit: \"1.3\"");
        }

        int factorsIndex = findLine(lines, interestIndex + 1, interestIndent, "factors");
        int factorsIndent;
        if (factorsIndex < 0) {
            factorsIndent = interestIndent + 2;
            lines.add(interestIndex + 1, indent(factorsIndent) + "factors:");
            lines.add(interestIndex + 2, indent(factorsIndent + 2) + "spar: \"1.3\"");
            lines.add(interestIndex + 3, indent(factorsIndent + 2) + "kredit: \"1.3\"");
            factorsIndex = interestIndex + 1;
        } else {
            factorsIndent = indentOf(lines.get(factorsIndex));
        }

        int keyIndex = findLine(lines, factorsIndex + 1, factorsIndent, key);
        String suffix = "";
        if (keyIndex >= 0) {
            String existing = lines.get(keyIndex);
            int comment = existing.indexOf('#');
            if (comment >= 0) {
                suffix = " " + existing.substring(comment);
            }
        }
        String factorLine = indent(factorsIndent + 2) + key + ": \"" + value + "\"" + suffix;
        if (keyIndex >= 0) {
            lines.set(keyIndex, factorLine);
        } else {
            lines.add(factorsIndex + 1, factorLine);
        }
    }

    /**
     * Sucht eine direkte Unterzeile des Blocks ab {@code from}. Die Suche
     * endet, sobald eine Zeile mit gleicher oder geringerer Einrückung
     * erreicht wird.
     */
    private static int findLine(List<String> lines, int from, int parentIndent, String key) {
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isBlankOrComment(line)) {
                continue;
            }
            if (indentOf(line) <= parentIndent) {
                return -1;
            }
            if (line.trim().regionMatches(true, 0, key + ":", 0, key.length() + 1)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isBlankOrComment(String line) {
        String trimmed = line.trim();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    private static int indentOf(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    private static String indent(int length) {
        return " ".repeat(Math.max(0, length));
    }
}
