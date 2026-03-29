# SPEC-provider-spi.md — Muralis

> This spec defines the `provider/` package in full. It covers the
> `MarketDataProvider` interface, `MarketDataListener` interface, and
> the provider discovery pattern used in Phase 1 with the documented
> upgrade path to Java ServiceLoader for Phase 2+.
>
> **Claude Code instruction:** All classes generated from this spec live
> in `com.muralis.provider`. They may import from `com.muralis.model`
> only. No imports from `com.muralis.ingestion`, `com.muralis.engine`,
> or `com.muralis.ui` are permitted. See `ARCHITECTURE.md` Section 4.

---

## 1. Scope

This spec covers exactly the following types:

| Type | Kind | Package | Responsibility |
|---|---|---|---|
| `MarketDataProvider` | Interface | `provider/` | Contract every adapter must implement |
| `MarketDataListener` | Interface | `provider/` | Callback contract for event consumers |
| `ProviderType` | Enum | `provider/` | Identifies the exchange/feed source |
| `ProviderConfig` | Record | `provider/` | Connection parameters passed to `connect()` |
| `ConnectionState` | Enum | `model/` | Provider connection lifecycle states (lives in `model/` so `MarketEvent` sealed permits works — see `DATA-CONTRACTS.md` Section 2.1) |
| `ConnectionEvent` | Record | `model/` | Connection state change event (lives in `model/` as a `MarketEvent` subtype — see `DATA-CONTRACTS.md` Section 3.5) |

These types are already referenced in `DATA-CONTRACTS.md`. This spec
defines their complete method signatures and behavioural contracts.

---

## 2. `MarketDataProvider` — interface specification

```java
package com.muralis.provider;

public interface MarketDataProvider {

    /**
     * Returns the unique name of this provider.
     * Used for logging and status display only.
     * Example: "Binance Spot", "Rithmic CME"
     */
    String getName();

    /**
     * Returns the ProviderType enum value for this provider.
     */
    ProviderType getType();

    /**
     * Initiates the connection and bootstrap sequence.
     * Blocks until the WebSocket connection is established and
     * the first snapshot bootstrap has begun.
     * Does NOT block until CONNECTED — the caller receives
     * ConnectionState updates via the registered listeners.
     *
     * Precondition:  At least one MarketDataListener is registered.
     * Precondition:  connect() has not already been called.
     * Postcondition: A ConnectionEvent(CONNECTING) has been published.
     *
     * @param config Connection parameters (symbol, URL overrides, etc.)
     * @throws IllegalStateException if connect() is called twice
     */
    void connect(ProviderConfig config);

    /**
     * Disconnects cleanly. Publishes ConnectionEvent(DISCONNECTED).
     * Safe to call from any thread.
     * Idempotent — calling disconnect() twice is safe and has no effect
     * after the first call.
     *
     * Postcondition: No further events are published after this returns.
     */
    void disconnect();

    /**
     * Registers a listener to receive MarketEvent notifications.
     * Must be called before connect().
     * Multiple listeners may be registered.
     *
     * @param listener Must not be null.
     */
    void addListener(MarketDataListener listener);

    /**
     * Returns the InstrumentSpec for the currently connected symbol.
     * Returns null if connect() has not been called yet.
     */
    InstrumentSpec getInstrumentSpec();

    /**
     * Returns the current ConnectionState.
     * Thread-safe — may be called from any thread.
     */
    ConnectionState getConnectionState();
}
```

### 2.1 Behavioural contract

Every implementation of `MarketDataProvider` must satisfy:

**Event ordering guarantee:**
```
A listener must receive events in this order per session:
  ConnectionEvent(CONNECTING)
  → OrderBookSnapshot          (exactly one per connect/reconnect cycle)
  → OrderBookDelta*            (zero or more, in sequence order)
  → NormalizedTrade*           (interleaved with deltas)
  → ConnectionEvent(RECONNECTING | DISCONNECTED)

If reconnecting:
  → ConnectionEvent(CONNECTING)
  → OrderBookSnapshot          (fresh snapshot — full book reset)
  → ... (resumes normal flow)
```

**No events before `CONNECTING`:** A listener must never receive an
`OrderBookSnapshot`, `OrderBookDelta`, or `NormalizedTrade` before the
first `ConnectionEvent(CONNECTING)` in a session.

**No events after `DISCONNECTED`:** Once `ConnectionEvent(DISCONNECTED)`
is published, no further events of any type may be published to any
listener for the lifetime of this provider instance.

**`OrderBookSnapshot` before any delta:** A listener must never receive
an `OrderBookDelta` before the first `OrderBookSnapshot` for that
connect/reconnect cycle. This is enforced by the bootstrap sequence
in `SPEC-ingestion.md` Section 3.

**Listener invocation thread:** All listener callbacks are invoked on
the ingestion thread. Listeners must not block. The engine thread
consumes events from the queue — listeners do not process events inline.

### 2.2 What `MarketDataProvider` does NOT define

The interface deliberately excludes:

- **Subscription management** — Phase 1 is single-instrument. A
  `subscribe(symbol)` / `unsubscribe(symbol)` method is reserved for
  Phase 2 when multi-instrument support is added.
- **Authentication** — Binance public market data requires no auth.
  Rithmic/CQG auth is a Phase 2 concern handled in `ProviderConfig`.
- **Reconnection policy** — reconnection strategy is an implementation
  detail of each adapter, not a contract of the interface.

---

## 3. `MarketDataListener` — interface specification

```java
package com.muralis.provider;

public interface MarketDataListener {

    /**
     * Called when a full order book snapshot is available.
     * The engine must reset all order book state when this is received.
     */
    void onSnapshot(OrderBookSnapshot snapshot);

    /**
     * Called for each incremental order book update.
     * Guaranteed to arrive after onSnapshot() in each connect cycle.
     */
    void onDelta(OrderBookDelta delta);

    /**
     * Called for each matched trade.
     * May arrive before or after onSnapshot() — see SPEC-ingestion.md
     * Section 3.1 Step 1 (trades bypass the pre-buffer).
     */
    void onTrade(NormalizedTrade trade);

    /**
     * Called on every ConnectionState transition.
     * Always called before the first onSnapshot() in each connect cycle.
     */
    void onConnectionEvent(ConnectionEvent event);
}
```

### 3.1 Listener implementation in `OrderBookEngine`

`OrderBookEngine` implements `MarketDataListener` by publishing each
received event to the `LinkedTransferQueue`. It does not process events
inline — it is a pure forwarding listener.

```java
// In OrderBookEngine (com.muralis.engine):
@Override public void onSnapshot(OrderBookSnapshot s) { queue.offer(s); }
@Override public void onDelta(OrderBookDelta d)       { queue.offer(d); }
@Override public void onTrade(NormalizedTrade t)      { queue.offer(t); }
@Override public void onConnectionEvent(ConnectionEvent c) { queue.offer(c); }
```

This is the only implementation of `MarketDataListener` in Phase 1.

---

## 4. `ProviderConfig` — record specification

```java
package com.muralis.provider;

public record ProviderConfig(
    String symbol,          // Canonical symbol e.g. "BTCUSDT"
    String wsUrlOverride,   // null = use provider default URL
    String restUrlOverride, // null = use provider default URL
    int    connectTimeoutMs // default: 10_000
) {
    public static ProviderConfig defaultFor(String symbol) {
        return new ProviderConfig(symbol, null, null, 10_000);
    }
}
```

`wsUrlOverride` and `restUrlOverride` exist to support testing against
a local mock server without modifying adapter code. Both are `null` in
production — the adapter uses its hardcoded default URLs.

**Symbol consistency rule:** The `ProviderConfig.symbol` and the
`InstrumentSpec.symbol` passed to the adapter must match. The adapter
must validate this in `connect()` and throw `IllegalArgumentException`
if they differ. This prevents a wiring bug where the adapter connects
to one symbol but the engine formats prices for another.

---

## 5. Provider discovery — Phase 1 (hardcoded)

In Phase 1, the active provider is instantiated directly in
`Application.main()`. There is no dynamic discovery.

```java
// In com.muralis.Application — the composition root:

// ── PROVIDER SEAM ─────────────────────────────────────────────────
// Phase 1: BinanceAdapter is the only provider. Hardcoded here.
// Phase 2: Replace these two lines with ServiceLoader discovery
//          (see SPEC-provider-spi.md Section 6 for upgrade path).
// ──────────────────────────────────────────────────────────────────
MarketDataProvider provider = new BinanceAdapter(queue, instrumentSpec);
provider.addListener(engine);
provider.connect(ProviderConfig.defaultFor("BTCUSDT"));
```

The comment block is mandatory. It marks the seam explicitly so any
developer (or Claude Code session) reading `Application.java` understands
that this is an intentional extension point, not an oversight.

---

## 6. Provider discovery — Phase 2 upgrade path (ServiceLoader)

When a second provider is added (e.g. `RithmicAdapter` for CME), convert
the hardcoded instantiation to ServiceLoader discovery as follows.

### 6.1 Registration

Each adapter JAR includes a file:
```
src/main/resources/META-INF/services/com.muralis.provider.MarketDataProvider
```
Containing the fully qualified class name of the adapter:
```
com.muralis.ingestion.BinanceAdapter
```

For a second adapter in a separate module:
```
com.muralis.rithmic.RithmicAdapter
```

### 6.2 Discovery in `Application.main()`

```java
// Phase 2 replacement for the hardcoded seam:
String targetProvider = System.getProperty("muralis.provider", "BINANCE_SPOT");

MarketDataProvider provider = StreamSupport
    .stream(ServiceLoader.load(MarketDataProvider.class).spliterator(), false)
    .filter(p -> p.getType().name().equals(targetProvider))
    .findFirst()
    .orElseThrow(() -> new IllegalStateException(
        "No provider found for: " + targetProvider
    ));

provider.addListener(engine);
provider.connect(ProviderConfig.defaultFor(symbol));
```

Provider selection via JVM system property:
```
java -Dmuralis.provider=BINANCE_SPOT -jar muralis.jar
java -Dmuralis.provider=CME_RITHMIC  -jar muralis.jar
```

### 6.3 What each adapter must implement for ServiceLoader

- A **public no-argument constructor** (ServiceLoader requirement)
- The `queue` and `instrumentSpec` dependencies must be injected via
  setter methods called after discovery but before `connect()`:
  ```java
  provider.setQueue(queue);
  provider.setInstrumentSpec(instrumentSpec);
  ```
- Or alternatively, inject via `ProviderConfig` fields for Phase 2.

This is a known constraint of `ServiceLoader` — it cannot inject
constructor arguments. The `Application.java` composition root is
responsible for post-discovery wiring.

---

## 7. Normalization contract — what every adapter must guarantee

Every implementation of `MarketDataProvider` — regardless of exchange
or protocol — must deliver events that conform to `DATA-CONTRACTS.md`
exactly. This section summarises the normalization obligations specific
to the provider layer.

### 7.1 Price and quantity normalization

| Raw format | Normalized to |
|---|---|
| Binance JSON string `"97432.51"` | `long 9743251L` (priceScale=2) |
| Binance JSON string `"0.00041800"` | `long 41800L` (qtyScale=8) |
| Rithmic protobuf int32 price (Phase 2) | `long` via `InstrumentSpec.tickSize` |
| CQG decimal price (Phase 2) | `long` via `parsePrice()` pattern |

All normalization happens inside the adapter. The engine and UI receive
only `long` fixed-point values. They never see raw strings, floats, or
provider-specific types.

### 7.2 Symbol normalization

| Raw format | Normalized to |
|---|---|
| Binance `"BTCUSDT"` | `"BTCUSDT"` (no change) |
| Binance URL lowercase `"btcusdt"` | `"BTCUSDT"` (uppercased in adapter) |
| Rithmic `"ESH6"` (Phase 2) | `"ES-H26"` (reformatted with hyphen) |
| CQG `"F.US.EP H26"` (Phase 2) | `"ES-H26"` (reformatted) |

### 7.3 Timestamp normalization

| Raw format | Normalized to |
|---|---|
| Binance `T` field (trade time ms) | `exchangeTs` in `NormalizedTrade` |
| Binance `E` field (event time ms) | `exchangeTs` in `OrderBookDelta` |
| Rithmic nanosecond timestamp (Phase 2) | truncated to milliseconds |
| CQG seconds-since-epoch (Phase 2) | multiplied by 1000 to milliseconds |

### 7.4 Aggressor side normalization

| Raw format | Normalized to |
|---|---|
| Binance `"m": false` | `AggressorSide.BUY` |
| Binance `"m": true` | `AggressorSide.SELL` |
| Rithmic `aggressor` field (Phase 2) | mapped to `AggressorSide` enum |
| CQG trade side flag (Phase 2) | mapped to `AggressorSide` enum |

The `AggressorSide` derivation for Binance is defined in
`DATA-CONTRACTS.md` Section 2.2 and `SPEC-ingestion.md` Section 2.3.
It must not be re-derived anywhere outside the adapter.

---

## 8. Invariant checklist (Claude Code enforcement)

When generating any class in `com.muralis.provider`, verify:

- [ ] `MarketDataProvider` has no imports from `engine/`, `ingestion/`,
      or `ui/` — it depends on `model/` only
- [ ] `ConnectionState` and `ConnectionEvent` are imported from
      `com.muralis.model`, not from `com.muralis.provider`
- [ ] `MarketDataListener` callbacks are never called after
      `ConnectionEvent(DISCONNECTED)` is published
- [ ] `disconnect()` is idempotent — safe to call multiple times
- [ ] `connect()` throws `IllegalStateException` if called twice on the
      same instance
- [ ] `connect()` validates that `config.symbol()` matches
      `instrumentSpec.symbol()` — throws `IllegalArgumentException` if not
- [ ] `ProviderConfig.wsUrlOverride` and `restUrlOverride` are `null`
      in all production call sites
- [ ] The `PROVIDER SEAM` comment block is present in `Application.java`
      exactly as specified in Section 5
- [ ] No provider-specific type (e.g. `JsonObject`, Rithmic protobuf)
      leaks beyond the adapter boundary into `model/` types

---

## 9. File to create for ServiceLoader registration (Phase 1)

Even though ServiceLoader is not used in Phase 1, create the registration
file now. It costs nothing and means the Phase 2 upgrade requires zero
file creation — only a code change in `Application.java`.

```
File: src/main/resources/META-INF/services/com.muralis.provider.MarketDataProvider
Contents: com.muralis.ingestion.BinanceAdapter
```

---

*Last updated: SPEC-provider-spi.md v1.1 — ConnectionState/ConnectionEvent moved to model/ package. Symbol validation added to connect(). Invariant checklist updated.*
*Next file: BUILD.md*
