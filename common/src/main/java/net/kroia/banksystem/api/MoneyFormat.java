package net.kroia.banksystem.api;

import net.kroia.banksystem.BankSystemModSettings;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money display format shared by BankSystem placeholders and dependent mods (e.g. StockMarket),
 * so all money values look the same. Amounts are raw bank units
 * ({@link BankSystemModSettings#ITEM_FRACTION_SCALE_FACTOR} units = 1 money).
 * Thread-safe; may be called from placeholder threads.
 *
 * @since 2.2.0
 */
public final class MoneyFormat {

    private static volatile String pattern = "$%s";

    private MoneyFormat() {
    }

    /** Formatted with the server's MONEY_FORMAT setting, e.g. 123456 -> "$1'234.56"; negative: -123456 -> "$-1'234.56". */
    public static String formatMoney(long rawUnits) {
        return formatMoney(rawUnits, pattern);
    }

    /** Formatted with {@code pattern} ({@code %s} = number); a pattern without {@code %s} yields the bare number. */
    public static String formatMoney(long rawUnits, String pattern) {
        String plain = formatMoneyRaw(Math.abs(rawUnits));
        int dot = plain.indexOf('.');
        String integer = dot < 0 ? plain : plain.substring(0, dot);
        StringBuilder sb = new StringBuilder(rawUnits < 0 ? "-" : "");
        for (int i = 0; i < integer.length(); i++) {
            if (i > 0 && (integer.length() - i) % 3 == 0)
                sb.append('\'');
            sb.append(integer.charAt(i));
        }
        if (dot >= 0)
            sb.append(plain.substring(dot));
        return pattern == null || !pattern.contains("%s") ? sb.toString() : pattern.replace("%s", sb);
    }

    /** Plain decimal without separators or symbol, e.g. 123456 -> "1234.56". */
    public static String formatMoneyRaw(long rawUnits) {
        return BigDecimal.valueOf(rawUnits)
                .divide(BigDecimal.valueOf(BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR), 2, RoundingMode.DOWN)
                .toPlainString();
    }

    /** Current MONEY_FORMAT pattern of this server. */
    public static String getPattern() {
        return pattern;
    }

    /** Set by BankSystem from the MONEY_FORMAT setting. */
    public static void setPattern(String newPattern) {
        if (newPattern != null)
            pattern = newPattern;
    }
}
