package net.kroia.banksystem.api.ledger;

/**
 * Task #109 — why a trade row was written. Persisted by name in the {@code tag} column.
 * PERIODIC: interval flush of an open order; SHUTDOWN: flush on server stop; CLOSE: the order
 * finished (filled or cancelled).
 */
public enum TradeRowKind { PERIODIC, SHUTDOWN, CLOSE }
