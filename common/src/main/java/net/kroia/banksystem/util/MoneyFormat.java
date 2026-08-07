package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModSettings;

/**
 * Spec A.6 (v2.0.8) — UI-boundary conversion between the fixed-point money
 * representation (scale {@link BankSystemModSettings#ITEM_FRACTION_SCALE_FACTOR},
 * i.e. raw {@code 12345} = {@code 123.45} currency units) and decimal text.
 * <p>
 * The on-wire / persisted representation stays raw; only parse/format at the UI
 * boundary uses this helper.
 */
public final class MoneyFormat {

    /** Fixed-point scale (100 → 2 decimals). */
    public static final long SCALE = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

    private MoneyFormat() {}

    /**
     * Parse decimal user input ({@code "123.45"}) into raw fixed-point units
     * ({@code 12345}). Accepts up to 2 decimals, {@code '.'} or {@code ','} as
     * separator. Returns {@code -1} for empty, non-numeric, negative, or
     * more-than-2-decimals input.
     */
    public static long parseToRaw(String text) {
        if (text == null) return -1L;
        String s = text.trim().replace(',', '.');
        if (s.isEmpty()) return -1L;
        int dot = s.indexOf('.');
        String intPart;
        String fracPart;
        if (dot < 0) {
            intPart = s;
            fracPart = "";
        } else {
            intPart = s.substring(0, dot);
            fracPart = s.substring(dot + 1);
            if (fracPart.indexOf('.') >= 0) return -1L; // second separator
        }
        if (fracPart.length() > 2) return -1L;
        if (intPart.isEmpty() && fracPart.isEmpty()) return -1L;
        if (!intPart.isEmpty() && !intPart.chars().allMatch(Character::isDigit)) return -1L;
        if (!fracPart.isEmpty() && !fracPart.chars().allMatch(Character::isDigit)) return -1L;
        try {
            long whole = intPart.isEmpty() ? 0L : Long.parseLong(intPart);
            long frac = fracPart.isEmpty() ? 0L : Long.parseLong(fracPart);
            if (fracPart.length() == 1) frac *= 10L;
            return Math.addExact(Math.multiplyExact(whole, SCALE), frac);
        } catch (NumberFormatException | ArithmeticException e) {
            return -1L;
        }
    }

    /** Format raw fixed-point units ({@code 12345}) as decimal text ({@code "123.45"}). */
    public static String format(long raw) {
        boolean neg = raw < 0;
        long abs = Math.abs(raw);
        long whole = abs / SCALE;
        long frac = abs % SCALE;
        return (neg ? "-" : "") + whole + "." + (frac < 10 ? "0" + frac : String.valueOf(frac));
    }
}
