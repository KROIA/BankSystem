# BankSystem Events & Signals Reference

This guide documents every event and signal BankSystem exposes to dependent mods (e.g. StockMarket) through its public API. Use it to react to bank-system state changes — user/account changes, ItemID merges, multi-server connection lifecycle, and slave-trust changes — without polling.

All events and signals are **server-side** and are reached through a single accessor.

## Entry Point

```java
BankSystemAPI api = BankSystemMod.getAPI();
IBankSystemEvents events = api.getEvents();
```

`getEvents()` returns the same instance for the lifetime of a loaded world. Attach your listeners once BankSystem is present (see [When to subscribe](#when-to-subscribe)).

## Two primitive types

BankSystem uses two observer primitives from ModUtilities (`net.kroia.modutilities.event`):

| Type | Payload | Subscribe with | Listener shape |
|------|---------|----------------|----------------|
| `Signal` | none | `signal.addListener(Runnable)` | `() -> { ... }` |
| `DataEvent<T>` | one value of type `T` | `event.addListener(Consumer<T>)` | `value -> { ... }` |

Both support an optional call-count limit: `addListener(listener, maxCalls)` auto-removes the listener after `maxCalls` fires (`-1` = unlimited). Both are backed by a `CopyOnWriteArrayList` and dispatch over a snapshot, so a listener may add/remove listeners during dispatch without a `ConcurrentModificationException` (such changes take effect on the *next* fire, not the current one).

```java
// Signal (no payload)
events.getBanksystemSetupCompleteSignal().addListener(() -> {
    // BankSystem finished setup
});

// DataEvent<T> (typed payload)
events.getTrustChangedSignal().addListener(info -> {
    String slaveID = info.slaveID();
    boolean trusted = info.trusted();
});

// Fire at most once, then auto-remove
events.getBanksystemSetupCompleteSignal().addListener(this::onReady, 1);
```

## Threading & side discipline

Two properties matter for every subscription — **which JVM** fires it (master / slave / both) and **which thread**:

- **Server-thread events** (most of them) are dispatched on the Minecraft server thread. It is safe to touch server-side game state directly from the listener.
- **Network-callback events** (the multi-server connection lifecycle) fire on a **Netty event-loop thread**. **Listeners must not block**, and must **not** touch world/game state directly — marshal onto the server thread first (e.g. `server.execute(() -> ...)`).

Each entry below states its side and thread. When in doubt, marshal to the server thread.

---

## Full catalog

| Getter | Type | Payload | Side | Thread | Fires when |
|--------|------|---------|------|--------|-----------|
| `getUserAddedEvent()` | `DataEvent<User>` | added user | both | server | a user is added to the bank manager |
| `getUserRemovedEvent()` | `DataEvent<User>` | removed user | both | server | a user is removed from the bank manager |
| `getBankAccountCreatedEvent()` | `DataEvent<ISyncServerBankAccount>` | new account | both | server | a bank account is created |
| `getBankAccountDeletedEvent()` | `DataEvent<ISyncServerBankAccount>` | deleted account | both | server | a bank account is deleted |
| `getItemIDsMergedEvent()` | `DataEvent<Map<ItemID,ItemID>>` | alias → canonical map (unmodifiable) | **master** | server | an ItemID volatile-component merge was consolidated |
| `getBankDataSavedToFileSignal()` | `Signal` | — | both | server | bank data is saved to disk |
| `getBankDataLoadedFromFileSignal()` | `Signal` | — | both | server | bank data is loaded from disk |
| `getSettingsSavedToFileSignal()` | `Signal` | — | both | server | settings are saved to disk |
| `getSettingsLoadedFromFileSignal()` | `Signal` | — | both | server | settings are loaded from disk |
| `getBanksystemSetupCompleteSignal()` | `Signal` | — | both | server | BankSystem finished its per-world setup |
| `getMasterServerSlaveConnected()` | `Signal` | — | **master** | network callback | a slave finished its handshake with this master |
| `getMasterServerSlaveDisconnected()` | `DataEvent<String>` | departing slaveID | **master** | network callback | a connected slave disconnected |
| `getSlaveConnectionAcceptedSignal()` | `Signal` | — | **slave** | Netty | this slave's handshake with its master completed |
| `getSlaveConnectionLostSignal()` | `Signal` | — | **slave** | Netty | this slave lost its master connection (edge-triggered) |
| `getTrustChangedSignal()` | `DataEvent<TrustChangeInfo>` | `{slaveID, trusted}` | **master** | server main | a slave was trusted/untrusted at runtime |

---

## Multi-server lifecycle (the events StockMarket cares about most)

BankSystem's master/slave model produces four connection-lifecycle events. Two fire on the **master** (describing *slaves connecting/leaving*), two fire on the **slave** (describing *its own link to the master*). They are **not** interchangeable — pick by which JVM your code runs on.

```
      MASTER JVM                                  SLAVE JVM
  ┌───────────────────────────┐              ┌──────────────────────────────┐
  │ MASTER_SERVER_SLAVE_       │  slave       │ SLAVE_CONNECTION_ACCEPTED    │
  │   CONNECTED   (Signal)     │◀── connects ─▶│   (Signal)                   │
  │                            │              │                              │
  │ MASTER_SERVER_SLAVE_       │  slave       │ SLAVE_CONNECTION_LOST        │
  │   DISCONNECTED (String)    │◀── leaves ──▶│   (Signal, edge-triggered)   │
  └───────────────────────────┘              └──────────────────────────────┘
```

### `getSlaveConnectionAcceptedSignal()` — slave side

Fires on the **slave** once the slave→master handshake completes and BankSystem's async forwarding channel is usable (i.e. `MultiServerUtils.canInteractWithBankSystem()` genuinely returns `true`). This is the moment cross-server async requests (e.g. `IAsyncBankManager.isSlaveServerTrustedAsync(...)`) become safe — issuing them earlier short-circuits synchronously to the fail-closed default.

- Fires **again on every reconnect** (each successful re-handshake).
- Fired on a **Netty event-loop thread** — do not block.

Use it to (re)build any cache derived from the master.

### `getSlaveConnectionLostSignal()` — slave side

Fires on the **slave** when its established master connection drops (unexpected loss **or** clean shutdown). Pair it with the accepted signal to invalidate master-derived caches until the next handshake.

- **Edge-triggered:** fires **exactly once** per connected→disconnected transition. The slave's auto-reconnect loop retries on a fixed cadence while the master is down, but this signal is gated by an internal latch, so it does **not** re-fire on each failed reconnect attempt. It fires again only after a subsequent `SLAVE_CONNECTION_ACCEPTED` re-arms the latch.
- Fired on a **Netty event-loop thread** — do not block.

> Because it is edge-triggered, your listener does not *need* to be idempotent to avoid spam — but keeping cache-invalidation idempotent is still good practice.

### `getMasterServerSlaveConnected()` — master side

Fires on the **master** when a slave has finished its handshake (after the master has verified the slave's mod version and sent its ItemID sync). Parameterless `Signal`.

- Fired from a **network-completion callback** — treat as off-thread; marshal to the server thread before touching game state.

### `getMasterServerSlaveDisconnected()` — master side

Fires on the **master** when a connected slave disconnects. **Payload: the departing `slaveID` (`String`)** so you can evict any per-slave state you built while it was connected.

- Fired from a **network callback** — treat as off-thread; marshal to the server thread before touching game state.

> **Note the asymmetry:** the *connected* counterpart is a parameterless `Signal`, while *disconnected* is a `DataEvent<String>` carrying the slaveID — per-slave eviction needs to know *which* slave left. If you build per-slave state, key it off a master query at connect time (or from your own bookkeeping) and evict by the slaveID delivered here.

---

## Trust changes

### `getTrustChangedSignal()` — master side

Fires on the **master** after `ServerBankManager.trustSlaveServer(String)` / `untrustSlaveServer(String)` mutates the trust set. Payload is a `TrustChangeInfo`:

```java
events.getTrustChangedSignal().addListener(info -> {
    String slaveID = info.slaveID();   // affected slave
    boolean trusted = info.trusted();  // post-mutation state (true = now trusted)
    // push the new trust state to your own slaves/clients — no re-query needed
});
```

Contract:
- **Master JVM only.** On a slave this never fires; slaves learn their own trust status via S2S packets sent by mods that subscribe here on the master. This keeps "which other slaves does the master trust?" from leaking to slaves.
- Payload reflects the **post-mutation** state — propagate it directly without re-querying.
- Fires on the **server main thread** (invoked from the mutator, not an async worker).
- Fires **once per mutator call** (idempotent contract). In practice the admin-command surface short-circuits before calling the mutator when the state already holds, so it only fires on real transitions.

---

## ItemID merges

### `getItemIDsMergedEvent()` — master side

Fires on the **master** after a volatile-component ItemID merge (see the Async Forwarding / ItemID docs) has been fully consolidated into BankSystem's own state (balances, locked balances, allowed items, account icons). Payload is an **unmodifiable** `Map<ItemID, ItemID>` of merged alias → canonical ItemID.

Dependent mods must consolidate their own ItemID-keyed state (markets, orders, price histories, ...) under the canonical IDs when this fires. Dispatched on the **server thread**, after BankSystem's own consolidation completed.

---

## When to subscribe

Attach listeners as early as your mod initializes, but note that `getEvents()` requires BankSystem to be present. A robust pattern:

1. Subscribe to `getBanksystemSetupCompleteSignal()` for anything that needs BankSystem's per-world state ready.
2. On a slave, gate master-dependent work behind `getSlaveConnectionAcceptedSignal()`.
3. Keep a reference to each listener if you intend to remove it later.

## Teardown

`IBankSystemEvents.removeListeners()` clears **all** listeners on every event/signal (BankSystem calls this on world stop). To remove only your own listener, keep the reference and call `removeListener(...)`:

```java
Runnable onSetup = () -> { /* ... */ };
events.getBanksystemSetupCompleteSignal().addListener(onSetup);
// later:
events.getBanksystemSetupCompleteSignal().removeListener(onSetup);
```

> Lambdas and method references are only removable if you keep the exact same instance you registered. Store it in a field if you need to unsubscribe.

## Related

- [API Reference](API.md) — overall API entry points and bank-account access.
- [Async Forwarding Architecture](AsyncForwardingArchitecture.md) — the master/slave RPC system behind the multi-server events.
