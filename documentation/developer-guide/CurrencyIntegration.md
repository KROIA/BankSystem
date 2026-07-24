# External Currency Integration Guide

This guide is for authors of Minecraft currency mods (Numismatics, Lightman's Currency, and future contenders) who want to make their mod bindable from BankSystem. Once your adapter is registered, any BankSystem consumer — including StockMarket — can move money through your mod transparently.

Audience assumes fluency in Java and Minecraft modding. It assumes **nothing** about BankSystem internals: everything you need to implement is on the small SPI in `net.kroia.banksystem.api.currency`.

Added in BankSystem v2.0.5. All classes referenced in this document are `@since 2.0.5`.

---

## 1. What this is

BankSystem lets a player **bind** one of their BankSystem bank-account slots to an external currency account owned by your mod. After that:

- `getBalance` / `deposit` / `withdraw` on the BankSystem side route through your mod.
- The locked-balance ledger (used by trade reservations in StockMarket) stays inside BankSystem — your mod never sees "locked funds."
- The player's actual money lives in your mod. BankSystem stores only the binding pointer and the local locked amount.

**Binding, not replacement.** BankSystem accounts remain authoritative for identity, permissions, and locked balance. A single BankSystem account can bind different item slots to different external accounts (or leave some slots native). Bindings are opt-in per slot and per account.

**Consumer promise.** StockMarket, and every other `IServerBank` consumer, works transparently through the binding — zero consumer-side code changes.

---

## 2. When to write an adapter — and when not to

**Write one if:**

- Your mod stores per-player currency balances that other systems could benefit from routing through (StockMarket trading, mod-cross transfers, wealth aggregation).
- Your mod has an account model that survives player logouts and world reloads. Sessions-in-inventory-only currency does not qualify.

**Do not bother if:**

- Your mod's currency is item-based only — physical coins in inventory with no account model. BankSystem already treats items generically; players can just deposit those coins directly.
- Your mod is a Bukkit/Spigot plugin (Vault, EssentialsX). BankSystem targets Fabric / NeoForge / Quilt via Architectury and cannot reach Bukkit APIs directly. A companion plugin would be required — see the umbrella spec.

---

## 3. The mental model

Four types make up the SPI. Read this section end to end — every implementation detail later refers back to these terms.

### `ExternalCurrencyProvider`

Your mod's entry point. One instance, registered once at mod init. Answers: "which of this player's accounts are bindable right now" and "given a saved reference, open a live handle to it."

### `ExternalAccount`

A thin, stateless handle to exactly one account in your mod. Obtained from `ExternalCurrencyProvider.open(ref)`. Handed to BankSystem for one operation, then discarded. Do **not** cache; do **not** hold cross-call state on it. The handle is expected to proxy every method call directly to your mod's live account state.

### `ExternalAccountRef`

A small, persistable pointer that BankSystem stores in its savedata. A record of four fields:

| Field | Type | Meaning |
|---|---|---|
| `providerId` | `String` | Matches your `ExternalCurrencyProvider.providerId()` |
| `accountKey` | `String` | Opaque to BankSystem — whatever your mod needs to re-open this account |
| `label` | `String` | Human-readable name shown in the binding picker UI |
| `shared` | `boolean` | `true` if the external account is multi-owner on your side |

`providerId` + `accountKey` together are the durable identity. `label` and `shared` are cosmetic / advisory and may change on refresh.

### `ProviderFeature`

An enum of capability flags. Your provider advertises the ones it actually supports. Missing flags cause BankSystem to skip UI paths or treat calls as best-effort no-ops. See [Section 5](#5-feature-flags).

### Two invariants that apply everywhere

- **Server-thread only.** Every method on `ExternalCurrencyProvider` and `ExternalAccount` is called on the Minecraft server thread. Implementations do not need to be thread-safe. Do not spin up background threads that mutate provider state from your adapter.
- **Locked balance is BankSystem's problem, not yours.** You expose raw free-balance primitives (`getBalance`, `deposit`, `withdraw`, `canWithdraw`). BankSystem tracks per-slot locked amounts in its own savedata and layers reservation semantics on top. Your adapter never sees a "locked funds" concept.
- **Currency unit on the wire is `long`.** You convert to/from your mod's native representation (int spurs, `MoneyValue`, whatever) inside the adapter. Document your ratio choice for users.

---

## 4. Minimal walkthrough — implementing a provider

The five steps every adapter goes through, with real code sketches. Substitute your own mod's API where the placeholders live.

### 4.1 Declare identity and feature set

```java
public final class MyMoneyProvider implements ExternalCurrencyProvider {

    public static final String PROVIDER_ID = "my_money";

    private static final Set<ProviderFeature> FEATURES = EnumSet.of(
            ProviderFeature.PERSONAL_ACCOUNTS,
            ProviderFeature.SUFFICIENT_FUNDS_CHECK
    );

    @Override
    public @NotNull String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isAvailable() {
        // Cheap live check — mod loaded AND API bootstrapped.
        return Platform.isModLoaded("my_money") && MyMoneyApi.isReady();
    }

    @Override
    public @NotNull Set<ProviderFeature> features() {
        return FEATURES;
    }
    // ... listBindableAccounts, open below
}
```

`providerId()` is the durable identifier. It is written to every binding row that points at your mod. **Never change it after release.** If you do, existing bindings orphan.

`isAvailable()` is consulted before every UI path AND before every server-side balance read. Keep it cheap — no I/O.

### 4.2 List the player's bindable accounts

```java
@Override
public @NotNull List<ExternalAccountRef> listBindableAccounts(@NotNull UUID player) {
    List<ExternalAccountRef> out = new ArrayList<>();

    // Personal account.
    MyAccount personal = MyMoneyApi.getPersonalAccount(player);
    if (personal != null) {
        out.add(new ExternalAccountRef(
                PROVIDER_ID,
                personal.uuid().toString(),   // accountKey — stable identifier
                personal.displayName(),       // label for the picker
                false                         // personal, not shared
        ));
    }

    // Any shared accounts the player is a member of.
    for (MyAccount shared : MyMoneyApi.getSharedAccountsForPlayer(player)) {
        out.add(new ExternalAccountRef(
                PROVIDER_ID,
                shared.uuid().toString(),
                shared.displayName(),
                true
        ));
    }

    return out;
}
```

Called on demand when the player opens the binding picker. Return live results — do not cache. Order is presentation-relevant; personal-first, then shared, is the recommended convention.

### 4.3 Open a live handle from a saved reference

```java
@Override
public @Nullable ExternalAccount open(@NotNull ExternalAccountRef ref) {
    if (!PROVIDER_ID.equals(ref.providerId())) return null;
    try {
        UUID uuid = UUID.fromString(ref.accountKey());
        MyAccount account = MyMoneyApi.getAccount(uuid);
        if (account == null) return null;
        return new MyExternalAccount(ref, account);
    } catch (IllegalArgumentException e) {
        // Malformed accountKey — reference belongs to a foreign schema.
        return null;
    }
}
```

Return `null` freely for any ref that no longer resolves (account deleted, player lost access, provider not yet ready). BankSystem treats a null return as "the binding is temporarily unavailable" and degrades gracefully — see [Section 9](#9-when-your-mod-is-not-loaded).

### 4.4 Implement the `ExternalAccount` handle

```java
final class MyExternalAccount implements ExternalAccount {

    private final ExternalAccountRef ref;
    private final MyAccount account;

    MyExternalAccount(ExternalAccountRef ref, MyAccount account) {
        this.ref = ref;
        this.account = account;
    }

    @Override public @NotNull ExternalAccountRef ref() { return ref; }

    @Override public long getBalance() {
        return account.getBalance();      // read-through; no caching
    }

    @Override public boolean deposit(long amount) {
        if (amount < 0) return false;
        return account.tryDeposit(amount);   // atomic; false on overflow
    }

    @Override public boolean withdraw(long amount) {
        if (amount < 0) return false;
        return account.tryWithdraw(amount);  // atomic; false on insufficient funds
    }

    @Override public boolean canWithdraw(long amount) {
        return amount >= 0 && account.getBalance() >= amount;
    }

    @Override public boolean isSharedAccount() {
        return ref.shared();
    }

    @Override public @NotNull Set<UUID> currentMembers() {
        return new HashSet<>(account.trustList());   // defensive copy
    }

    @Override public void syncMembership(@NotNull Set<UUID> withdrawCapableUuids) {
        account.setTrustList(new ArrayList<>(withdrawCapableUuids));
    }
}
```

Every method proxies straight to `account`. No caching, no batching. If your mod's read is expensive, cache **inside your mod** — the adapter must never hold a stale value across two BankSystem calls (see [Section 8](#8-read-through-no-caching)).

### 4.5 Register at mod init

```java
public class MyMoneyMod {
    public static void onInitialize() {
        // Your normal init...
        if (Platform.isModLoaded("banksystem")) {
            BankSystemMod.getAPI().registerCurrencyProvider(new MyMoneyProvider());
        }
    }
}
```

If you would rather not hard-import BankSystem, guard the call behind reflection or place it in a separate Architectury service. Re-registering with the same `providerId` is a "last-wins" replace — safe to call multiple times, useful for hot reload in dev.

Adapters that need to drop themselves — most commonly the in-game test suite's stub provider — can call `BankSystemMod.getAPI().unregisterCurrencyProvider(providerId)`. Real currency-mod adapters normally never need this and live for the JVM lifetime.

---

## 5. Feature flags

`ProviderFeature` is a five-value enum. Your `features()` return set governs which UI branches BankSystem offers and which SPI calls are treated as authoritative.

| Flag | Meaning |
|---|---|
| `PERSONAL_ACCOUNTS` | You can enumerate at least one per-player account. Required for basic binding. |
| `MULTI_ACCOUNT_PER_PLAYER` | `listBindableAccounts` may return more than one entry for one player. |
| `SHARED_ACCOUNTS` | You have accounts with multiple owners / members. |
| `MEMBERSHIP_SYNC` | You will honor `syncMembership` calls and reflect the change on your side. |
| `SUFFICIENT_FUNDS_CHECK` | Your `canWithdraw` is authoritative — no side effects, always correct. |

### `PERSONAL_ACCOUNTS`

Advertise this when every player has (or can create) at least one account you would happily bind to a BankSystem slot. Without it, BankSystem does not offer personal-account bindings for your provider.

### `MULTI_ACCOUNT_PER_PLAYER`

Advertise this if `listBindableAccounts(player)` can return more than one entry per player — for example, Numismatics's Blaze Banker model where one player owns several named accounts. Without it, BankSystem assumes the list has at most one personal-account entry per player.

### `SHARED_ACCOUNTS`

Advertise this if you have accounts co-owned by multiple players. Bindings from a shared BankSystem account are only offered when this flag is set. Personal BankSystem accounts can still bind to your personal accounts either way.

### `MEMBERSHIP_SYNC`

Advertise this if you actually implement `syncMembership` — i.e. calling it changes your mod's trust list / member roster in a way subsequent operations respect. Without this flag, `syncMembership` calls are treated as best-effort no-ops (BankSystem still calls it, so a future minor-version upgrade to your adapter can start honoring it without a coordination round-trip).

### `SUFFICIENT_FUNDS_CHECK`

Advertise this only if your `canWithdraw` implementation is a true simulate primitive — no side effects, and the answer is guaranteed correct for a `withdraw` issued immediately afterward on the same thread. If your mod does not expose a simulate primitive (Lightman's Currency, for example, does not), do **not** advertise this flag. BankSystem will then treat `canWithdraw` as advisory only and will always attempt the real `withdraw`, tolerating a `false` return.

### Rule of thumb

Advertise only the flags you actually support. A minimal provider with just `PERSONAL_ACCOUNTS` is perfectly valid and behaves correctly through every BankSystem code path.

---

## 6. Shared vs personal accounts

BankSystem accounts come in two shapes:

- **Personal.** One owner, `personalBankOwnerData` populated. Every player gets one auto-created on first login.
- **Shared.** Multi-user, per-user permission mask (`DEPOSIT`, `WITHDRAW`, `MANAGE`). Created by any player who wants a co-owned account.

The `shared` flag on `ExternalAccountRef` tells BankSystem the shape of the external account. Binding compatibility follows from combining the two:

| BankSystem account | External `ref.shared` | Outcome |
|---|---|---|
| Personal | `false` | Auto-bindable |
| Personal | `true` | Allowed, user-explicit — a personal player can spend from a co-owned external pool |
| Shared | `true` | Allowed |
| Shared | `false` | **Rejected** — permission model mismatch |

### If you support shared accounts

- `ExternalAccount.isSharedAccount()` returns `true`.
- `currentMembers()` returns the withdraw-capable UUIDs (defensive copy — callers must not mutate).
- `syncMembership(Set<UUID>)` receives a full replacement set. BankSystem calls this whenever a shared BankSystem account's user list or per-user permission mask changes. Diff against your current member list and adjust; do not throw for transient failures (log and return).
- Advertise `SHARED_ACCOUNTS` in `features()`. Also advertise `MEMBERSHIP_SYNC` if you actually apply the change.

### BankSystem permission → external membership mapping

BankSystem's permission enum has three bits (`DEPOSIT`, `WITHDRAW`, `MANAGE`). Only players with the **WITHDRAW** permission are placed into the set passed to `syncMembership`. Deposit-only users are **not** added — they can put funds in via BankSystem, but they should not be able to spend the external account directly through your mod's own UI. `MANAGE`-only users (permission-editors) are likewise not added.

Rule of thumb: the `syncMembership` set is the set of BankSystem users who are allowed to move money **out** of the linked external account through any channel.

---

## 7. Overflow and rejection

`deposit` and `withdraw` return `boolean`. **Never throw** for expected conditions. Return `false` on any of:

- **Insufficient funds** (withdraw).
- **Overflow** — your native representation cannot hold the amount. Numismatics's `int` spur cap is the archetype: any deposit that would push the balance above `Integer.MAX_VALUE` must return `false` with no state change.
- **Any mod-specific rejection** — account frozen by admin, transaction rate-limited, per-account debit ceiling exceeded, etc.

BankSystem maps a `false` return to a status the consumer already understands:

| SPI outcome | `BankStatus` mapped by BankSystem |
|---|---|
| `deposit` returns `false` | `FAILED_OVERFLOW` |
| `withdraw` returns `false` | `FAILED_NOT_ENOUGH_FUNDS` |
| `open` returns `null` OR `isAvailable() == false` | `FAILED_EXTERNAL_UNAVAILABLE` |

You do not have to translate the failure yourself — return `false` and BankSystem does the rest.

Log at DEBUG or INFO if you want to leave breadcrumbs for server admins; BankSystem does not consume your logs.

---

## 8. Read-through, no caching

`getBalance()` is called on **every** read from BankSystem. Do not cache the return value inside the adapter.

Why:

- Your mod's own UI (ATM, Bank Terminal, wallet HUD, admin `/eco` command) can change balances behind BankSystem's back. Read-through is how BankSystem's drift-clamp path recovers.
- BankSystem's locked-balance tracking depends on always seeing a live free-balance number. A stale positive read here can put the ledger into an inconsistent state that clamps on the next real write — bad UX at best, a StockMarket order refund at worst.

If your mod's own account lookup is expensive, cache **inside your mod** using a cheap primary key. The adapter's `getBalance` then reads a cheap cache-hit — but it is a cache your mod invalidates on every write it processes, from any source.

---

## 9. When your mod is not loaded

BankSystem tolerates a bound provider being absent. Two shapes of unavailability:

1. **Mod uninstalled.** Your `providerId` is not registered at all. Any binding row pointing at it is now dangling.
2. **Mod loaded but not ready.** `isAvailable()` returns `false` — for example, because your mod's API has not finished bootstrapping.

In either case:

- **Reads** return `0` from BankSystem's perspective.
- **Writes** fail with `FAILED_EXTERNAL_UNAVAILABLE`, no state change on either side.
- A **one-shot WARN** is logged the first time a bound slot is touched, keyed on `(bankAccountId, itemIdShort)`. No log flood — repeat touches on the same key are silent for the rest of the session.

When the player re-adds your mod and the server restarts, existing bindings resume working automatically as long as `providerId()` and each stored `accountKey` still resolve.

### Persistence stability contract

- `providerId()` MUST be stable across releases of your adapter. Renaming it orphans every existing binding.
- Any `accountKey` you mint MUST be stable for the lifetime of the underlying account. If you re-key accounts across a version bump, either translate at load time (deserialize the old key, produce the new one) or expect existing bindings to break.
- The `ExternalAccountRef` NBT layout is fixed by BankSystem — you do not control its schema. But the **contents** of `providerId` and `accountKey` are entirely your namespace.

---

## 10. Testing your adapter

The BankSystem in-game test suite covers the Task #33 SPI end-to-end. Mirror it. The seven scenarios in `common/src/main/java/net/kroia/banksystem/testing/tests/ExternalCurrencyBindingTests.java` are the canonical smoke suite for any adapter:

1. **Round-trip.** Bind an account, deposit through BankSystem, withdraw through BankSystem, verify the external balance moves and the BankSystem read agrees.
2. **Locked-balance protocol.** `lockAmount` and `unlockAmount` do NOT touch external. `withdrawLocked` DOES. Confirm your mod's balance is unchanged after a lock/unlock cycle.
3. **Drift-clamp.** Change the external balance behind BankSystem's back (simulate the player using your ATM). Verify BankSystem's next read reflects the new value and clamps its local `lockedBalance` if the external dropped below it.
4. **Overflow guard.** Configure your mod to reject a large deposit (e.g. cap at 1000). Attempt a deposit past the cap. Verify `deposit` returns `false` with no state change.
5. **Provider-unavailable degraded state.** Toggle your `isAvailable()` to `false`. Verify reads return 0 and writes fail with `FAILED_EXTERNAL_UNAVAILABLE`.
6. **Shared/personal ref-mismatch rejection.** Try to bind a personal external account to a shared BankSystem account. Verify BankSystem rejects the bind.
7. **Cascade cleanup on account delete / bank removal.** Delete the BankSystem account. Verify the binding row is removed from savedata (no orphan pointing at a dead account).

For pre-integration smoke tests where wiring your real mod into a dev world is impractical, reuse `StubCurrencyProvider` (`common/src/main/java/net/kroia/banksystem/testing/StubCurrencyProvider.java`). It is not part of the public API surface — it lives under `testing/` — but Task #34's Numismatics adapter and future adapters may import it directly.

Run in-game tests via `/banksystem test` (BankSystem does not use Gradle-side unit tests for the SPI; the fixtures need a live server).

---

## 11. Multi-server considerations

BankSystem has a master / slave topology. On slave servers, banking calls forward to master via the ARRS protocol.

Bindings live **only on master**. Your adapter runs **only on master**. Slave servers do not consult a `BankAccountBindings` singleton; they never call your adapter directly. Every balance read on a slave hits master over ARRS, and master calls your adapter locally.

Consequence for adapter authors: **treat every call as server-side and single-process.** Do not try to route around BankSystem's master-slave discipline; do not care about it. If your own mod has multi-server state of its own (very rare), coordinate with BankSystem's master identity to avoid dual-authority conflicts — but that is a design detail of your mod, not of the SPI.

The slave-side binding management UI is proxied to master transparently via ARRS. No additional cross-server work is needed from your side.

---

## 12. Wire format — `ExternalAccountRef`

`ExternalAccountRef` is serialized two ways:

1. **NBT** (`toNbt` / `fromNbt`) for savedata. Keys are the `NBT_KEY_*` constants on `ExternalAccountRef`. The layout is: `providerId (String)`, `accountKey (String)`, `label (String)`, `shared (Boolean)`.
2. **`StreamCodec`** (`STREAM_CODEC` on `ExternalAccountRef`) for network transport (binding-picker request/response between client, slave, and master). Encodes as four fields: UTF-8 string × 3, boolean × 1.

You do not implement either. You only produce and consume `ExternalAccountRef` instances via the record constructor. BankSystem handles the wire.

### Evolving your `accountKey` format

The NBT layout is fixed by BankSystem, but the **contents** of `accountKey` (and `label`) are yours. If you evolve the shape — say, from `<uuid>` to `<uuid>#<index>` — you need to translate at load time yourself: when `open(ref)` receives an old-shape `accountKey`, recognize it and resolve appropriately. A schema-version prefix inside the string (e.g. `v1:<uuid>`) is a reasonable pattern.

Never break existing bindings without translation. Users' savedata contains rows that reference your old shapes.

---

## 13. Known limitations (as of v2.0.5)

- **No push-based staleness invalidation.** The binding-management UI has a manual "Refresh" button. If your mod's own UI changes an account balance, BankSystem's **client-side** snapshot may be stale until the next refresh or the next BankSystem write. Server-side reads are always live via read-through.
- **No cross-server binding migration.** A binding row references `(providerId, accountKey)`. If the same conceptual account has different `accountKey`s on two servers (e.g. a Numismatics account whose UUID differs across worlds), bindings do not transfer. Player must re-bind after migrating.

---

## 14. Common shapes — Numismatics-style vs Lightman's-style

Two illustrative sketches of how existing currency mods map onto the SPI.

### Numismatics-style

Single-scalar balance (`int` spurs), UUID-keyed accounts, membership via an explicit `trustList: List<UUID>` on shared accounts (Blaze Banker). Two account kinds — PLAYER (single-owner) and BLAZE_BANKER (shared). PLAYER accounts do not support co-ownership; BLAZE_BANKER accounts do.

Recommended feature set:

```java
EnumSet.of(
    ProviderFeature.PERSONAL_ACCOUNTS,
    ProviderFeature.MULTI_ACCOUNT_PER_PLAYER,   // a player owns multiple Blaze Bankers
    ProviderFeature.SHARED_ACCOUNTS,
    ProviderFeature.MEMBERSHIP_SYNC,            // trust list is writable
    ProviderFeature.SUFFICIENT_FUNDS_CHECK      // deduct(amount, simulate=true) exists
)
```

`accountKey` shape: `account.uuid().toString()`. One string identifier per account, stable, unique. The `shared` bit is `false` for PLAYER, `true` for BLAZE_BANKER.

Notes:
- 32-bit spur cap. Any deposit that would push above `Integer.MAX_VALUE` returns `false`. Document the cap so users understand why very large deposits refuse.
- `syncMembership` translates directly to `trustList.setAll(uuids)`.

### Lightman's-style

Multi-key money model (`MoneyValue` × `MoneyKey`) — balances are per-currency-type maps rather than a single scalar. Personal accounts only in the public API. NeoForge-only distribution (does not ship on Fabric/Quilt today).

Recommended feature set:

```java
EnumSet.of(
    ProviderFeature.PERSONAL_ACCOUNTS
)
```

`accountKey` shape: composite — `<playerUuid>#<moneyKey>` — because a single Lightman's account holds separate balances for each coin denomination and the binding must pin one. The `shared` bit is always `false`.

Notes:
- No simulate primitive → do NOT advertise `SUFFICIENT_FUNDS_CHECK`. `canWithdraw` is `getBalance() >= amount`, advisory only.
- No shared team accounts in the public API → do NOT advertise `SHARED_ACCOUNTS` or `MEMBERSHIP_SYNC`.
- On Fabric/Quilt, `Platform.isModLoaded("lightmanscurrency")` returns `false` and the provider is simply never registered. No config needed from the user.

---

## 15. Full-provider skeleton

Copy this file into your mod, rename the class, wire the `MyMoneyApi` calls to your real API, and register at mod init. Roughly 90 lines end-to-end.

```java
package com.example.mymoney.compat.banksystem;

import net.kroia.banksystem.api.currency.ExternalAccount;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.api.currency.ExternalCurrencyProvider;
import net.kroia.banksystem.api.currency.ProviderFeature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class MyMoneyProvider implements ExternalCurrencyProvider {

    public static final String PROVIDER_ID = "my_money";

    private static final Set<ProviderFeature> FEATURES = EnumSet.of(
            ProviderFeature.PERSONAL_ACCOUNTS,
            ProviderFeature.SUFFICIENT_FUNDS_CHECK
    );

    @Override public @NotNull String providerId() { return PROVIDER_ID; }

    @Override public boolean isAvailable() {
        return MyMoneyApi.isReady();
    }

    @Override public @NotNull Set<ProviderFeature> features() { return FEATURES; }

    @Override
    public @NotNull List<ExternalAccountRef> listBindableAccounts(@NotNull UUID player) {
        List<ExternalAccountRef> out = new ArrayList<>();
        MyAccount personal = MyMoneyApi.getPersonalAccount(player);
        if (personal != null) {
            out.add(new ExternalAccountRef(
                    PROVIDER_ID,
                    personal.uuid().toString(),
                    personal.displayName(),
                    false
            ));
        }
        return out;
    }

    @Override
    public @Nullable ExternalAccount open(@NotNull ExternalAccountRef ref) {
        if (!PROVIDER_ID.equals(ref.providerId())) return null;
        try {
            UUID uuid = UUID.fromString(ref.accountKey());
            MyAccount account = MyMoneyApi.getAccount(uuid);
            return account == null ? null : new Handle(ref, account);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final class Handle implements ExternalAccount {
        private final ExternalAccountRef ref;
        private final MyAccount account;

        Handle(ExternalAccountRef ref, MyAccount account) {
            this.ref = ref;
            this.account = account;
        }

        @Override public @NotNull ExternalAccountRef ref() { return ref; }

        @Override public long getBalance() {
            return account.getBalance();
        }

        @Override public boolean deposit(long amount) {
            if (amount < 0) return false;
            return account.tryDeposit(amount);
        }

        @Override public boolean withdraw(long amount) {
            if (amount < 0) return false;
            return account.tryWithdraw(amount);
        }

        @Override public boolean canWithdraw(long amount) {
            return amount >= 0 && account.getBalance() >= amount;
        }

        @Override public boolean isSharedAccount() {
            return ref.shared();
        }

        @Override public @NotNull Set<UUID> currentMembers() {
            return new HashSet<>(account.members());
        }

        @Override public void syncMembership(@NotNull Set<UUID> withdrawCapableUuids) {
            // No-op — MEMBERSHIP_SYNC feature not advertised.
        }
    }
}
```

Register at mod init:

```java
public static void onServerInit() {
    if (Platform.isModLoaded("banksystem")) {
        BankSystemMod.getAPI().registerCurrencyProvider(new MyMoneyProvider());
    }
}
```

That is a complete, compilable adapter modulo the `MyMoneyApi` / `MyAccount` types you supply. Advertise more feature bits as you implement them; test each addition against the seven-scenario smoke suite in [Section 10](#10-testing-your-adapter).

---

## 16. Where to find help

- **Design rationale** and locked-balance protocol: `.claude/Features/CurrencyModSupport.md` in the BankSystem repository. Read this if a behavior surprises you — the umbrella spec is the authoritative source for the "why."
- **Canonical shapes**:
  - `common/src/main/java/net/kroia/banksystem/testing/StubCurrencyProvider.java` — a working provider you can imitate.
  - `common/src/main/java/net/kroia/banksystem/testing/tests/ExternalCurrencyBindingTests.java` — the seven-scenario smoke suite.
- **Persistence layout** (curious readers only — adapter authors never touch this): `common/src/main/java/net/kroia/banksystem/banking/binding/BankAccountBindings.java`.
- **BankSystem API entry point**: `common/src/main/java/net/kroia/banksystem/api/BankSystemAPI.java`. The two methods you care about are `registerCurrencyProvider` and `getCurrencyProvider(id)`.
- **Reference adapter (Numismatics)**: shipping in BankSystem v2.0.5 alongside the SPI (Task #34). Reading its source is the fastest way to see a real-world provider that advertises every feature bit.

Cross-reference the [BankSystem API Reference](API.md) if your adapter needs to touch other parts of BankSystem (rare — the SPI is deliberately narrow).
