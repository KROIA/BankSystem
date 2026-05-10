# Async Function Forwarding Architecture

## Why This Exists

BankSystem supports a **master-slave multi-server topology** where one master server holds the authoritative bank data and multiple slave servers forward all banking operations to it. The problem: slave servers and clients don't have direct access to the `ServerBankManager`, `ServerBankAccount`, or `ServerBank` objects — those only exist on the master.

Without async forwarding, every mod that consumes the banking API (e.g. StockMarket) would need to know whether it's running on a master or slave, build its own packets, handle serialization, manage request-response matching, and implement security checks. That's unreasonable.

The async forwarding system solves this by providing **a single unified API** (`IAsyncBank`, `IAsyncBankAccount`, `IAsyncBankManager`) that works identically regardless of where the caller is running. On the master, calls resolve locally. On a slave or client, calls are transparently serialized, sent to the master, executed, and the result returned — all behind a `CompletableFuture`.

### The Three Execution Contexts

| Context | Has bank data? | How async calls work |
|---------|---------------|---------------------|
| **Master server** | Yes — authoritative | Calls execute locally via sync interfaces |
| **Slave server** | No | Calls forwarded to master over TCP, result returned via future |
| **Client** | No | Calls forwarded to the connected server (which may itself forward to master) |

## Architecture Overview

```
                    ┌─────────────────────────────────────────────┐
                    │              Master Server                  │
                    │                                             │
                    │  ServerBankManager (authoritative data)     │
                    │       │                                     │
                    │  handleOnMasterServer()                     │
                    │    ├─ security check (client / slave trust) │
                    │    ├─ decode InputData                      │
                    │    ├─ dispatch to sync method               │
                    │    └─ encode OutputData → return future     │
                    └──────────────┬──────────────────────────────┘
                                   │ TCP (ARRS protocol)
                    ┌──────────────┴──────────────────────────────┐
                    │              Slave Server                   │
                    │                                             │
                    │  AsyncBankManager / AsyncBankAccount /      │
                    │  AsyncBank (forwarding implementations)     │
                    │    ├─ encode InputData                      │
                    │    ├─ sendRequestToMaster()                 │
                    │    └─ return CompletableFuture              │
                    │                      │                      │
                    │               Minecraft client              │
                    │                 sendRequestToServer()       │
                    └─────────────────────────────────────────────┘
```

## Class Hierarchy

```
GenericRequest<IN, OUT>                          (ModUtilities — ARRS framework)
  └─ BankSystemGenericRequest<IN, OUT>           (BankSystem — adds admin checks, routing)
       └─ AsyncForwardingRequest<Enum, IN, OUT>  (BankSystem — generic forwarding base)
            ├─ AsyncBank.Request                 (44 forwarded bank operations)
            ├─ AsyncBankAccount.Request           (26 forwarded account operations)
            └─ AsyncBankManager.Request           (61 forwarded manager operations)
```

Each `Request` class is a **singleton** registered with the `AsynchronousRequestResponseSystem` (ARRS) from ModUtilities. ARRS handles packet transport, request-response matching, and future completion.

## Core Components

### 1. FunctionType Enum

Each async class defines an enum listing every forwardable method:

```java
public enum FunctionType {
    GetBalanceAsync,
    DepositAsync,
    WithdrawAsync,
    TransferAsync,
    // ... one entry per forwardable method
}
```

Overloaded methods use suffixes: `GetNormalizedAmountAsync_1` (double param), `GetNormalizedAmountAsync_2` (long param).

### 2. Codec Registry

A static map pairs each `FunctionType` with its input and output `StreamCodec`:

```java
public static final Map<FunctionType, AsyncFunctionDataCodecs> codecs = new HashMap<>() {{
    put(FunctionType.GetBalanceAsync,  codecPacket(null, ByteBufCodecs.VAR_LONG.cast()));
    put(FunctionType.DepositAsync,     codecPacket(ByteBufCodecs.VAR_LONG.cast(), BankStatus.STREAM_CODEC));
    put(FunctionType.TransferAsync,    codecPacket(ParamGroup_long_int.STREAM_CODEC, BankStatus.STREAM_CODEC));
}};
```

- `null` input codec = parameterless function
- `null` output codec = void return
- All codecs wrapped in `ExtraCodecUtils.nullable()` to handle null values safely

### 3. AsyncFunctionInputData / AsyncFunctionOutputData

Generic containers that hold a `FunctionType`, the matching codec, and the pre-encoded byte array:

```java
// Encoding (caller side)
InputData inputData = InputData.of(FunctionType.DepositAsync, accountNr, itemID, amount);

// Decoding (master side)
long amount = inputData.decodeParams();  // Uses the codec from the registry
```

Encoding allocates a temporary Netty `ByteBuf`, writes via the codec, extracts the readable bytes, and releases the buffer.

### 4. AsyncForwardingRequest (Base Class)

Handles the wire protocol — enum-based function dispatch:

```
Wire format:  [FunctionType enum ordinal] [byte[] encoded params/result]
```

Key methods:
- `encodeInput/decodeInput` — serialize/deserialize the function call
- `encodeOutput/decodeOutput` — serialize/deserialize the return value
- `handleOnServer` — entry point when a request arrives
- `handleOnMasterServer` — abstract; each impl dispatches to the real sync method
- `isAllowedToCallByClient` — security whitelist for direct client calls
- `isAllowedToCallByUntrustedSlaveServer` — security whitelist for untrusted slaves

### 5. Request Singleton & Registration

```java
public static class Request extends AsyncForwardingRequest<FunctionType, InputData, OutputData> {
    public static final Request instance = (Request) AsynchronousRequestResponseSystem.register(new Request());
    // ...
}
```

The ARRS system uses `getRequestTypeID()` (the class name) to match requests with their handler on the receiving end.

## Request Flow (Step by Step)

### Caller Side (Slave/Client)

```java
// 1. Create the async bank proxy
AsyncBank bank = AsyncBank.createSlaveServerBank(accountNr, itemID);

// 2. Call any method — returns immediately
CompletableFuture<BankStatus> future = bank.depositAsync(500L);

// 3. Handle the result when it arrives
future.thenAccept(status -> {
    if (status == BankStatus.SUCCESS) { /* ... */ }
});
```

Inside `depositAsync()`:
```java
public CompletableFuture<BankStatus> depositAsync(long amount) {
    // Guard: is the bank system reachable?
    if (!MultiServerUtils.canInteractWithBankSystem())
        return CompletableFuture.completedFuture(BankStatus.FAILED);

    CompletableFuture<BankStatus> future = new CompletableFuture<>();

    // Encode: FunctionType + accountNr + itemID + amount → byte[]
    InputData inputData = InputData.of(FunctionType.DepositAsync, accountNr, itemID, amount);

    // Send: client→server or slave→master
    CompletableFuture<OutputData> outputFuture = sendRequest(inputData);

    // Decode result when it arrives
    outputFuture.thenAccept(outputData -> future.complete(outputData.decodeResult()));

    return future;
}
```

### Master Side

```java
public CompletableFuture<OutputData> handleOnMasterServer(InputData input, String slaveID, UUID playerSender) {
    // 1. Security: check untrusted slave whitelist
    if (!isAllowedToCallByUntrustedSlaveServer(input)) {
        if (!serverBankManager.isSlaveServerTrusted(slaveID)) {
            return CompletableFuture.completedFuture(OutputData.of(input.function));  // empty response
        }
    }

    // 2. Security: check client whitelist
    if (playerSender != null && !isAllowedToCallByClient(input)) {
        return CompletableFuture.completedFuture(OutputData.of(input.function));
    }

    // 3. Decode input, look up the real bank object
    BankIdentifyAndDataPacket inputData = input.decodeParams();
    ISyncServerBank bank = serverBankManager.getBankAccount(inputData.accountNr).getBank(inputData.itemID);

    // 4. Dispatch to the actual sync method
    return CompletableFuture.completedFuture(switch (input.function) {
        case DepositAsync -> OutputData.of(input.function, bank.deposit((long) inputData.extra));
        case WithdrawAsync -> OutputData.of(input.function, bank.withdraw((long) inputData.extra));
        // ... 44 cases total
    });
}
```

## Security Model

### Two-Tier Whitelisting

Every `Request` class implements two security gates:

| Gate | Who it filters | Typical policy |
|------|---------------|----------------|
| `isAllowedToCallByClient(input)` | Direct client connections | Read-only operations only |
| `isAllowedToCallByUntrustedSlaveServer(input)` | Slave servers not in the trusted list | Conservative — defaults to same as client |

**Trusted slave servers bypass both gates** and can call any function including mutations (deposit, withdraw, transfer, delete).

### Why Clients Can't Mutate

From the source code comment:
> An exploit can be done by manually compiling this mod with a custom access method, that calls for example a bank deposit method which then deposits items for free. Depositing should only be possible under the control of a server instance and never by a client directly!

Clients can only call read-only operations. All mutations (deposits, withdrawals, transfers) must originate from server-side code — either the master itself or a trusted slave.

### Per-Method Permission Checks

Some operations add additional authorization beyond the whitelist:
- `SetAccountNameAsync` / `SetAccountIconAsync` — requires `MANAGE` permission on the account
- `DeleteBankAccountAsync` — requires admin OR `MANAGE` permission
- `AllowItemIDAsync` / `DisallowItemIDAsync` — requires admin
- `SetBanksystemAdminModeAsync` — requires existing admin status

## Multi-Parameter Handling

Functions with more than one parameter use `record` types with their own `StreamCodec`:

```java
private record ParamGroup_long_int(long longValue, int integer) {
    public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_long_int> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, p -> p.longValue,
            ByteBufCodecs.INT,      p -> p.integer,
            ParamGroup_long_int::new
        );
}
```

Used for operations like `transferAsync(long amount, int targetAccountNr)`.

### Bank Identification Packet

`AsyncBank` wraps every call in a `BankIdentifyAndDataPacket<T>(int accountNr, ItemID itemID, T extra)` — this tells the master which bank account and item bank to look up before dispatching. `AsyncBankAccount` uses a simpler variant with just the account number.

## How to Add a New Forwarded Method

### Step 1: Add to the interface

```java
// In IAsyncBank.java
CompletableFuture<BankStatus> myNewMethodAsync(long param);
```

### Step 2: Add FunctionType enum entry

```java
// In AsyncBank.FunctionType
MyNewMethodAsync,
```

### Step 3: Register codecs

```java
// In AsyncBank.codecs map
put(FunctionType.MyNewMethodAsync, codecPacket(ByteBufCodecs.VAR_LONG.cast(), BankStatus.STREAM_CODEC));
```

### Step 4: Implement the caller side

```java
// In AsyncBank
@Override
public CompletableFuture<BankStatus> myNewMethodAsync(long param) {
    if (!MultiServerUtils.canInteractWithBankSystem())
        return CompletableFuture.completedFuture(BankStatus.FAILED);
    CompletableFuture<BankStatus> future = new CompletableFuture<>();
    InputData inputData = InputData.of(FunctionType.MyNewMethodAsync, accountNr, itemID, param);
    CompletableFuture<OutputData> outputFuture = sendRequest(inputData);
    outputFuture.thenAccept(out -> future.complete(out.decodeResult()));
    return future;
}
```

### Step 5: Add the master-side dispatch case

```java
// In AsyncBank.Request.handleOnMasterServer() switch statement
case FunctionType.MyNewMethodAsync -> OutputData.of(input.function, bank.myNewMethod((long) inputData.extra));
```

### Step 6: Add to security whitelists (if appropriate)

```java
// In isAllowedToCallByClient() — only if the method is safe for clients
case FunctionType.MyNewMethodAsync -> true;
```

### Step 7: Add the sync method to the server-side interface

```java
// In ISyncServerBank.java
BankStatus myNewMethod(long param);

// Implement in ServerBank.java
```

## File Reference

| File | Role |
|------|------|
| `util/async_function_forwarding/AsyncForwardingRequest.java` | Generic base — wire protocol, security gates |
| `util/async_function_forwarding/AsyncFunctionInputData.java` | Encode/decode function parameters |
| `util/async_function_forwarding/AsyncFunctionOutputData.java` | Encode/decode function return values |
| `util/async_function_forwarding/AsyncFunctionDataCodecs.java` | Codec pair holder (input + output) |
| `util/BankSystemGenericRequest.java` | BankSystem-specific request base (admin checks, routing) |
| `banking/bank/AsyncBank.java` | 44 forwarded bank operations |
| `banking/bankaccount/AsyncBankAccount.java` | 26 forwarded account operations |
| `banking/bankmanager/AsyncBankManager.java` | 61 forwarded manager operations |
| `api/bank/IAsyncBank.java` | Async bank interface (48 methods) |
| `api/bankaccount/IAsyncBankAccount.java` | Async account interface (35+ methods) |
| `api/bankmanager/IAsyncBankManager.java` | Async manager interface (60+ methods) |

All paths relative to `common/src/main/java/net/kroia/banksystem/`.

## Statistics

- **131 total forwarded functions** across the three async classes
- **131 codec pairs** registered (one per function)
- Wire format per call: enum ordinal (4 bytes) + encoded params (variable)
- Transport: ModUtilities ARRS (Asynchronous Request-Response System) over TCP
