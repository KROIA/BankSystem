package net.kroia.banksystem.api.event;

import net.kroia.banksystem.api.ledger.LedgerActor;

/**
 * Payload of {@link net.kroia.banksystem.api.IBankSystemEvents#getAccountFreezeChangedEvent()}.
 * <p>
 * Fires on the <b>master JVM only</b>, once per real change (setting the state it already
 * has fires nothing), after the flag was written and the ACCOUNT_FREEZE ledger row was queued.
 *
 * @param accountNumber the bank account whose freeze flag changed
 * @param frozen        the new state: {@code true} = frozen
 * @param actor         who changed it (admin, console, system or an add-on)
 * @since 2.2.0
 */
public record AccountFreezeChangedEvent(int accountNumber, boolean frozen, LedgerActor actor) {
}
