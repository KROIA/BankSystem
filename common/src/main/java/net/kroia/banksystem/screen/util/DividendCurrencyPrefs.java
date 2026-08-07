package net.kroia.banksystem.screen.util;

import net.kroia.banksystem.banking.company.PayoutSchedule;

import java.util.HashMap;
import java.util.Map;

/** Session-scoped store for the last dividend currency per company. */
public final class DividendCurrencyPrefs {
    private static final Map<Integer, Short> BY_COMPANY = new HashMap<>();

    private DividendCurrencyPrefs() {}

    public static short get(int companyId) {
        return BY_COMPANY.getOrDefault(companyId, PayoutSchedule.MONEY_CURRENCY);
    }

    public static void set(int companyId, short currencyItem) {
        BY_COMPANY.put(companyId, currencyItem);
    }

    public static void clear() {
        BY_COMPANY.clear();
    }
}
