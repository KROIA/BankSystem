package net.kroia.banksystem.banking.binding;

import net.kroia.banksystem.util.ItemID;

/**
 * Composite key that identifies one {@code IServerBank} slot inside the
 * {@link BankAccountBindings} table.
 * <p>
 * The key is the pair {@code (bankAccountId, itemIdShort)} — the numeric
 * BankSystem account number plus the {@link ItemID#getShort() short} of the
 * item slot. Using the short (rather than the {@link ItemID} instance) keeps
 * hashing / equality trivial and lets the key survive across sessions without
 * pulling in the item registry.
 *
 * @param bankAccountId the BankSystem account number
 *                      (see {@code ServerBankAccount.INVALID_ACCOUNT_NUMBER})
 * @param itemIdShort   the item slot's {@link ItemID#getShort()}
 *
 * @since 2.0.5
 */
public record BindingKey(int bankAccountId, short itemIdShort) {

    /**
     * Convenience factory that projects an {@link ItemID} down to its short.
     *
     * @param bankAccountId the BankSystem account number
     * @param itemId        the item slot; must not be {@code null}
     * @return a fresh key
     */
    public static BindingKey of(int bankAccountId, ItemID itemId) {
        return new BindingKey(bankAccountId, itemId.getShort());
    }
}
