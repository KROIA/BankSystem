package net.kroia.banksystem.api.event;

/**
 * Payload carried by
 * {@link net.kroia.banksystem.api.IBankSystemEvents#getTrustChangedSignal()}
 * when the master's slave-trust set has just been mutated by
 * {@code ServerBankManager.trustSlaveServer(String)} or
 * {@code ServerBankManager.untrustSlaveServer(String)}.
 * <p>
 * The event fires on the <b>master JVM only</b> and always AFTER the mutation
 * has been applied to {@code trustedSlaveServers}, so {@link #trusted()} reflects
 * the post-change state that subscribers should propagate. The record also fires
 * on idempotent set-to-same-value transitions — subscribers should filter if
 * that matters to them.
 * <p>
 * Kept as a {@code record} in the {@code api.event} package so dependent mods
 * (e.g. StockMarket) can consume it without pulling any internal BankSystem
 * classes.
 *
 * @param slaveID ID of the slave whose trust flag was written
 * @param trusted the new trust value on the master AFTER the mutation
 */
public record TrustChangeInfo(String slaveID, boolean trusted) {
}
