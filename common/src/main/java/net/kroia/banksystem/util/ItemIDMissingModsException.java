package net.kroia.banksystem.util;

import java.util.List;
import java.util.Map;

/**
 * Thrown during master world load when saved ItemIDs no longer decode because their mod (or
 * data from a mod) is missing, and {@code CONFIRM_ITEMID_REMOVE_MISSING} is false. Same
 * contract as every {@link BankSystemStartupAbortException}: nothing saved, session
 * save-prohibited. {@link #missingNamespaces} maps each missing mod namespace to its item count;
 * {@link #pendingMerges} lists the ItemID merges the next successful load would apply (BS-250).
 * {@link #missingItems} names every entry that removing would drop (ItemIDManager.describeMissingItems).
 */
public class ItemIDMissingModsException extends BankSystemStartupAbortException {

    public final int missingCount;
    public final Map<String, Integer> missingNamespaces;
    public final List<String> pendingMerges;
    public final List<String> missingItems;

    public ItemIDMissingModsException(String report, int missingCount, Map<String, Integer> missingNamespaces,
                                      List<String> pendingMerges, List<String> missingItems) {
        super(report);
        this.missingCount = missingCount;
        this.missingNamespaces = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(missingNamespaces));
        this.pendingMerges = List.copyOf(pendingMerges);
        this.missingItems = List.copyOf(missingItems);
    }
}
