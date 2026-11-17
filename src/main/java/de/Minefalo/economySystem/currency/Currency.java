package de.Minefalo.economySystem.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Repräsentiert die konfigurierte Währung und kapselt sämtliche
 * BigDecimal-Operationen (Parsen, Formatieren, Runden).
 */
public final class Currency {

    private final String name;
    private final String symbol;
    private final String displayFormat;
    private final String numberFormat;
    private final Locale locale;
    private final int decimals;
    private final RoundingMode rounding;
    private final BigDecimal startingBalance;
    private final BigDecimal maxBalance;
    private final BigDecimal minBalance;

    public Currency(String name,
                    String symbol,
                    String displayFormat,
                    String numberFormat,
                    Locale locale,
                    int decimals,
                    RoundingMode rounding,
                    BigDecimal startingBalance,
                    BigDecimal maxBalance,
                    BigDecimal minBalance) {
        this.name = Objects.requireNonNullElse(name, "Coins");
        this.symbol = Objects.requireNonNullElse(symbol, "$");
        this.displayFormat = Objects.requireNonNullElse(displayFormat, "%symbol%%amount%");
        this.numberFormat = Objects.requireNonNullElse(numberFormat, "#,##0.00");
        this.locale = locale == null ? Locale.GERMANY : locale;
        this.decimals = Math.max(0, Math.min(8, decimals));
        this.rounding = rounding == null ? RoundingMode.HALF_UP : rounding;

        BigDecimal start = startingBalance == null ? BigDecimal.ZERO : startingBalance;
        if (start.signum() < 0) {
            start = BigDecimal.ZERO;
        }
        this.maxBalance = maxBalance == null || maxBalance.signum() < 0
                ? null
                : normalize(maxBalance);

        BigDecimal normalizedStart = normalize(start);
        if (this.maxBalance != null && normalizedStart.compareTo(this.maxBalance) > 0) {
            normalizedStart = this.maxBalance;
        }
        this.startingBalance = normalizedStart;

        // Untergrenze für Zwangsabbuchungen (z. B. fällige Kredite). 0 bedeutet
        // "kein Überziehen", negative Werte erlauben einen Dispo bis hierhin.
        this.minBalance = minBalance == null || minBalance.signum() > 0
                ? BigDecimal.ZERO.setScale(this.decimals, this.rounding)
                : normalize(minBalance);
    }

    /**
     * Rundet einen Betrag auf die konfigurierten Nachkommastellen.
     */
    public BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(decimals, rounding);
    }

    /**
     * Parst eine Nutzereingabe (z. B. "1.234,56", "100.5" oder "100").
     * Negative Beträge und zu viele Nachkommastellen werden abgelehnt.
     */
    public Optional<BigDecimal> parseAmount(String input) {
        if (input == null) {
            return Optional.empty();
        }

        String cleaned = input.trim()
                .replace(" ", "")
                .replace("_", "")
                .replace("'", "");
        if (cleaned.startsWith("+")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isEmpty() || !cleaned.matches("[0-9.,]+")) {
            return Optional.empty();
        }

        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
        char localeDecimal = symbols.getDecimalSeparator();

        long dots = cleaned.chars().filter(c -> c == '.').count();
        long commas = cleaned.chars().filter(c -> c == ',').count();

        String normalized;
        if (dots > 0 && commas > 0) {
            // Der letzte Separator ist das Dezimaltrennzeichen, der andere gruppiert.
            char decimalSeparator = cleaned.lastIndexOf('.') > cleaned.lastIndexOf(',') ? '.' : ',';
            char groupingSeparator = decimalSeparator == '.' ? ',' : '.';
            normalized = cleaned.replace(String.valueOf(groupingSeparator), "")
                    .replace(decimalSeparator, '.');
        } else if (dots > 0 || commas > 0) {
            char separator = dots > 0 ? '.' : ',';
            long count = Math.max(dots, commas);
            long digitsAfter = cleaned.length() - cleaned.lastIndexOf(separator) - 1L;

            if (count > 1) {
                // "1.234.567" -> Gruppierung, sofern alle Gruppen 3 Stellen haben
                if (!validGrouping(cleaned, separator)) {
                    return Optional.empty();
                }
                normalized = cleaned.replace(String.valueOf(separator), "");
            } else if (digitsAfter == 0) {
                return Optional.empty();
            } else if (separator != localeDecimal && digitsAfter == 3) {
                // "1.000" im deutschen Locale ist eine Gruppierung
                normalized = cleaned.replace(String.valueOf(separator), "");
            } else {
                normalized = cleaned.replace(separator, '.');
            }
        } else {
            normalized = cleaned;
        }

        if (!normalized.matches("\\d+(\\.\\d+)?")) {
            return Optional.empty();
        }

        BigDecimal value;
        try {
            value = new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }

        if (value.signum() < 0) {
            return Optional.empty();
        }
        if (value.stripTrailingZeros().scale() > decimals) {
            // Kein stilles Runden von Nutzereingaben -> Geldbeträge bleiben exakt.
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private static boolean validGrouping(String value, char separator) {
        String[] groups = value.split("\\" + separator);
        if (groups.length < 2 || groups[0].isEmpty() || groups[0].length() > 3) {
            return false;
        }
        if (!groups[0].matches("\\d{1,3}")) {
            return false;
        }
        for (int i = 1; i < groups.length; i++) {
            if (!groups[i].matches("\\d{3}")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Formatiert einen Betrag inklusive Symbol/Format (z. B. "$1.234,56").
     */
    public String format(BigDecimal amount) {
        String formatted = formatAmount(amount);
        return displayFormat
                .replace("%symbol%", symbol)
                .replace("%amount%", formatted)
                .replace("%name%", name);
    }

    /**
     * Formatiert einen Betrag ohne Symbol (z. B. "1.234,56").
     */
    public String formatAmount(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat(numberFormat, DecimalFormatSymbols.getInstance(locale));
        format.setMinimumFractionDigits(decimals);
        format.setMaximumFractionDigits(decimals);
        format.setRoundingMode(rounding);
        return format.format(normalize(amount));
    }

    public String name() {
        return name;
    }

    public String symbol() {
        return symbol;
    }

    public int decimals() {
        return decimals;
    }

    public RoundingMode rounding() {
        return rounding;
    }

    public BigDecimal startingBalance() {
        return startingBalance;
    }

    /**
     * @return maximales Guthaben oder {@code null}, wenn unbegrenzt
     */
    public BigDecimal maxBalance() {
        return maxBalance;
    }

    /**
     * Untergrenze des Guthabens bei erzwungenen Abbuchungen (z. B. wenn ein
     * Kredit fällig wird). {@code 0} bedeutet, dass nicht überzogen werden darf.
     */
    public BigDecimal minBalance() {
        return minBalance;
    }
}
