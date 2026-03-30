# ADR-002: Dynamic InstrumentSpec and CME Provider Strategy

**Date:** 2026-03-30
**Status:** Proposed
**Relates to:** SPEC-provider-spi.md Sections 5-6, DATA-CONTRACTS.md Section 5

---

## Part 1: Dynamic InstrumentSpec from Exchange API

### Problem

`InstrumentSpec` is hardcoded in `Application.java`:
```java
new InstrumentSpec("BTCUSDT", 2, 1L, 3, 1L, "USDT", ProviderType.BINANCE_FUTURES)
```

This caused a production bug (priceScale was wrong — see ADR-001
Addendum). Binance changes tick sizes without notice. The hardcoded
approach fails silently when values change — the symptom is
`ArithmeticException` from `longValueExact()`, which triggers an
infinite reconnect loop.

### Decision

Fetch `InstrumentSpec` dynamically at startup from the exchange API.
Fall back to hardcoded defaults if the API is unreachable.

### Implementation for Binance Futures

**Source:** `GET /fapi/v1/exchangeInfo`

**Problem:** This endpoint is geo-blocked for US IPs (HTTP 451), same
as the depth REST endpoint (ADR-001 Addendum).

**Solution:** Use the `@depth20@100ms` WebSocket stream to infer
the spec at connection time:

```
1. Connect to WebSocket (three streams as normal)
2. Receive the first @depth20 message
3. Before using it as the bootstrap snapshot, inspect the raw JSON:
   - Parse one bid price string → count decimal places → priceScale
   - Parse one bid qty string → count decimal places → qtyScale
   - tickSize = 1L (always, by definition of our fixed-point scheme)
   - minQty = 1L (conservative default; not critical for visualization)
4. Construct InstrumentSpec from inferred values
5. Proceed with normal bootstrap using this InstrumentSpec
```

**Why this works:** The WebSocket data itself encodes the precision.
A price `"67083.40"` has 2 decimal places → `priceScale=2`. A quantity
`"0.041"` has 3 decimal places → `qtyScale=3`. This is self-describing
and always accurate because it reflects the actual data format Binance
is sending.

**Edge case — trailing zeros:** Binance sometimes sends `"67083.00"`
(2 decimal places even though the trailing digits are zero). The
decimal place count from one sample could undercount. Mitigation:
inspect ALL price strings in the first `@depth20` message (up to 20
prices) and take the maximum decimal place count observed. This
handles both `"67083.4"` (1 dp) and `"67083.40"` (2 dp) correctly.

**Fallback:** If the inferred values match the hardcoded defaults,
proceed silently. If they differ, log a WARN:
```
WARN: InstrumentSpec inferred from live data: priceScale=2, qtyScale=3
      (hardcoded defaults: priceScale=2, qtyScale=3)
```
If they differ and the hardcoded values are wrong, the inferred values
win. This auto-corrects future tick size changes.

### Implementation sequence

This should be implemented as a **Phase 2 enhancement** (Step P2.7)
after the delta tint feature is complete. The change is contained
within `BinanceAdapter`:

```
1. In BinanceAdapter.handleDepth20Message() during bootstrap:
   - Before parsing prices with the existing InstrumentSpec,
     inspect the raw JSON strings
   - Count max decimal places across all bid/ask price strings
   - Count max decimal places across all bid/ask qty strings
   - If inferred priceScale != instrumentSpec.priceScale()
     OR inferred qtyScale != instrumentSpec.qtyScale():
       - Log WARN with both old and new values
       - Construct new InstrumentSpec with inferred values
       - Replace the adapter's instrumentSpec reference
       - Pass the new InstrumentSpec to the parser
2. Continue bootstrap with the (possibly updated) InstrumentSpec
```

**No engine or UI changes needed.** The engine receives `InstrumentSpec`
via `RenderSnapshot` — when the adapter updates its spec, the next
snapshot carries the new values, and the UI picks them up automatically.

### For CME providers (Phase 4+)

Rithmic and CQG provide instrument metadata through their APIs:

- **Rithmic:** Symbol lookup via R|Protocol API returns tick size,
  contract size, and precision. Available before market data subscription.
- **CQG:** `InstrumentDefinition` message includes tick size and
  contract multiplier.

Each CME adapter will fetch `InstrumentSpec` from its own metadata API
during its `connect()` sequence, before subscribing to market data. The
hardcoded-fallback pattern still applies.

---

## Part 2: CME Provider Strategy — Rithmic vs CQG

### Options evaluated

| Provider | Protocol | Language | Full depth | MBO data | Auth | Cost |
|---|---|---|---|---|---|---|
| **Rithmic R\|Protocol API** | WebSocket + protobuf | Any (wire spec) | Yes | Yes (CME) | Credentials from broker | ~$20/mo via broker |
| **Rithmic R\|API+** | C++/.NET library | C++, C# only | Yes | Yes (CME) | Same | Same |
| **CQG Continuum** | WebSocket + protobuf | Any (wire spec) | Yes (CL, limited) | No | CQG account | ~$40+/mo |
| **CME WebSocket API** | WebSocket + JSON | Any | Top-of-book only | No | CME license | $0.50/GB + ILA fees |
| **dxFeed** | Proprietary | Java SDK available | Yes | No | Account | Variable |

### Decision: Rithmic R|Protocol API

**Rithmic R|Protocol API is the recommended CME provider** for these
reasons:

1. **WebSocket + protobuf** — language-independent wire protocol.
   Muralis can implement it in Java without native libraries. No JNI,
   no C++ bridge, no .NET dependency.

2. **Full depth + MBO data** — Rithmic provides full order book depth
   for CME futures, including Market-by-Order (MBO) data which shows
   individual orders, not just aggregated price levels. This is critical
   for the Phase 4 heatmap — it's the same data quality that Bookmap
   uses when connected to Rithmic.

3. **Industry standard for order flow tools** — Bookmap, Sierra Chart,
   Jigsaw, ATAS, Quantower all support Rithmic. The data format and
   quirks are well-documented by the trading community.

4. **Broker-neutral** — credentials come from any Rithmic-compatible
   broker (AMP, EdgeClear, Tradovate, Optimus, etc.). Users can use
   their existing futures trading account.

5. **Paper trading available** — Rithmic offers a paper trading system
   with live CME market data. Development and testing can happen
   without a funded trading account.

6. **Cost-effective** — as low as $20/month through discount brokers
   like EdgeClear, which includes data fees.

### Why not the others

- **R|API+** (C++/.NET): Requires native library integration. Muralis is
  pure Java. JNI bridge is fragile and platform-dependent. R|Protocol API
  provides the same data via a clean wire protocol.

- **CQG Continuum**: WebSocket + protobuf (same pattern as Rithmic),
  but Bookmap's documentation notes limited full-depth support (only CL
  crude oil confirmed for full depth). ES/NQ depth may be restricted.
  Higher cost. Less community documentation for custom integrations.

- **CME WebSocket API**: Top-of-book only (no full depth). Useless for
  an order book visualization tool. Also requires CME data licensing
  agreements.

- **dxFeed**: Has a Java SDK (convenient), provides full depth, but no
  MBO data. Less common in the retail order flow community. Would work
  as a secondary option if Rithmic proves problematic.

### Rithmic integration architecture

```
                    Rithmic R|Protocol API
                    (WebSocket + protobuf)
                           │
                           ▼
                ┌─────────────────────┐
                │  RithmicAdapter     │  com.muralis.rithmic
                │  (new package)      │
                │                     │
                │  ● WebSocket client │
                │  ● Protobuf decode  │
                │  ● InstrumentSpec   │
                │    from symbol      │
                │    lookup           │
                │  ● Depth → OrderBook│
                │    Delta            │
                │  ● Trade → Normal-  │
                │    izedTrade        │
                │  ● Reconnection     │
                └─────────┬───────────┘
                          │  publishes MarketEvent
                          ▼
                LinkedTransferQueue<MarketEvent>
                (same queue, same engine, same UI)
```

### New package structure

```
com.muralis.rithmic/
├── RithmicAdapter.java         implements MarketDataProvider
├── RithmicWebSocketClient.java WebSocket + protobuf framing
├── RithmicMessageParser.java   protobuf → model types
└── RithmicProtobuf.java        generated protobuf classes (or inline)
```

**Dependency rule:** `com.muralis.rithmic` may import from
`com.muralis.model` and `com.muralis.provider` only. Same rule as
`com.muralis.ingestion`. The engine and UI never know which adapter
is active.

### New dependency for Rithmic

```groovy
// In build.gradle — added for Rithmic support:
implementation 'com.google.protobuf:protobuf-java:3.25.3'
```

This is the only new dependency. The WebSocket client
(`org.java_websocket`) is already in BUILD.md.

### ProviderType addition

```java
public enum ProviderType {
    BINANCE_FUTURES,    // existing
    BINANCE_SPOT,       // preserved for reference
    CME_RITHMIC,        // new
    CME_CQG             // reserved for future
}
```

### ProviderConfig additions for authenticated providers

```java
public record ProviderConfig(
    String symbol,
    String wsUrlOverride,
    String restUrlOverride,
    int    connectTimeoutMs,
    // ── Phase 2+ additions ──
    String username,         // null for Binance (no auth)
    String password,         // null for Binance
    String systemName,       // Rithmic system name, e.g. "Rithmic Paper Trading"
    String appName,          // Application identifier for Rithmic
    String appVersion        // Application version for Rithmic
) {
    // Existing factory preserved:
    public static ProviderConfig defaultFor(String symbol) {
        return new ProviderConfig(symbol, null, null, 10_000,
                                  null, null, null, null, null);
    }

    // New factory for Rithmic:
    public static ProviderConfig rithmic(String symbol, String user,
                                          String password, String systemName) {
        return new ProviderConfig(symbol, null, null, 10_000,
                                  user, password, systemName,
                                  "Muralis", "1.0");
    }
}
```

Credentials are passed via ProviderConfig, NOT stored in the adapter.
The composition root (`Application.java`) reads them from system
properties or a config file:

```java
// In Application.java (Phase 4):
String user = System.getProperty("muralis.rithmic.user");
String pass = System.getProperty("muralis.rithmic.password");
ProviderConfig config = ProviderConfig.rithmic("ES", user, pass,
    "Rithmic Paper Trading");
```

---

## Part 3: InstrumentSpec differences — Binance vs CME

| Field | BTCUSDT (Binance) | ES (CME) | NQ (CME) |
|---|---|---|---|
| `symbol` | `"BTCUSDT"` | `"ES-H26"` | `"NQ-H26"` |
| `priceScale` | `2` | `2` (0.25 tick = 25L) | `2` (0.25 tick = 25L) |
| `tickSize` | `1L` (= 0.01) | `25L` (= 0.25) | `25L` (= 0.25) |
| `qtyScale` | `3` | `0` (whole contracts) | `0` (whole contracts) |
| `minQty` | `1L` (= 0.001 BTC) | `1L` (= 1 contract) | `1L` (= 1 contract) |
| `currency` | `"USDT"` | `"USD"` | `"USD"` |
| `provider` | `BINANCE_FUTURES` | `CME_RITHMIC` | `CME_RITHMIC` |

**Key difference: tickSize for CME.**

ES and NQ have a 0.25 tick size. With `priceScale=2`:
- Price 5450.25 → stored as `545025L`
- Tick size = `25L` (not `1L`)
- Price levels in the order book increment by 25, not by 1

This means the ladder painter must use `instrumentSpec.tickSize()` to
determine row spacing, not assume every integer price level exists.
**This is already the correct behavior** — the ladder iterates by
`tickSize` per row. If any code assumes `tickSize=1L`, it's a bug
that should be fixed regardless of CME support.

### Quantity display difference

CME quantities are whole contracts (`qtyScale=0`). No decimal places.
The qty display formatter already handles this — `0` decimal places
means the display shows `"5"` not `"5.000"`.

### Symbol naming

CME futures have contract months: `ESH6` (March 2026), `ESM6` (June).
Muralis normalizes these to `"ES-H26"` format with a hyphen and 2-digit
year. The adapter handles this mapping — the engine and UI see the
normalized symbol only.

Front-month rollover is a Phase 4+ concern. For Phase 4, the adapter
connects to whichever contract the user specifies. Automatic rollover
is not in scope.

---

## Part 4: Implementation timeline

| Phase | What happens |
|---|---|
| **Phase 2** (current) | P2.7: Add InstrumentSpec inference from @depth20 in BinanceAdapter |
| **Phase 3** | No provider changes. Volume profile uses same data. |
| **Phase 4** | ServiceLoader activation. RithmicAdapter implementation. ProviderConfig auth fields. ProviderType.CME_RITHMIC. Protobuf dependency added to BUILD.md. |

### What to do NOW (Phase 2)

1. Add InstrumentSpec inference to BinanceAdapter (Step P2.7)
2. Verify tickSize-based row spacing in LadderPainter works for
   non-unit tick sizes (add a unit test with tickSize=25L)

### What to do in Phase 4

1. Add `protobuf-java` dependency to BUILD.md
2. Create `com.muralis.rithmic` package
3. Implement `RithmicAdapter` against R|Protocol API
4. Add `CME_RITHMIC` to `ProviderType`
5. Extend `ProviderConfig` with auth fields
6. Activate ServiceLoader discovery in `Application.java`
7. Register both adapters in META-INF/services

---

## Part 5: Risks and mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Rithmic R\|Protocol API documentation is sparse | Medium | Community libraries exist (Rust, Python). Protobuf definitions are the contract — reverse-engineer from those. |
| Rithmic requires broker relationship | Low | Paper trading available. Multiple discount brokers support it ($20/mo). |
| Protobuf adds a dependency (~2.5MB) | Low | Single dependency. No transitive bloat. Acceptable for Phase 4. |
| CME tick size (0.25) breaks ladder row iteration | **HIGH** | See Part 6 below. Must be addressed. |
| Symbol rollover (ESH6 → ESM6) | Low | Out of scope until Phase 5+. User specifies contract manually. |
| InstrumentSpec inference from @depth20 gets wrong decimal count | Low | Use max across all 20 price strings. Log inferred values. Hardcoded fallback. |

---

## Part 6: Confirmed architectural issue — tick size in ladder row iteration

### The problem

SPEC-rendering.md Section 5.2-5.4 defines the ladder as iterating
"each visible price level p" with a `priceIndex` that increments by 1
per row. The `rowY()` and `priceToRowIndex()` functions are not
explicitly defined in the spec, leaving the mapping between fixed-point
prices and row indices to the implementation.

For BTCUSDT with `tickSize=1L`, every consecutive fixed-point price is
a valid row. The ladder shows:
```
6708340  →  67,083.40
6708341  →  67,083.41
6708342  →  67,083.42
```

For CME ES with `tickSize=25L`, valid prices are spaced 25 apart:
```
545000  →  5,450.00
545025  →  5,450.25
545050  →  5,450.50
```

If the code iterates by incrementing the fixed-point price by 1 per
row (which is the natural reading of the spec), ES would show 25 rows
per tick — 24 of them empty. The ladder would be unusable.

### The fix

The row iteration must use `tickSize` as the step:

```java
// Correct: iterate by tickSize
long price = topVisiblePrice;
for (int row = 0; row < visibleRows; row++) {
    // paint row at 'price'
    price -= instrumentSpec.tickSize();  // decrement by one tick per row
}
```

The `priceToRowIndex` function must divide by tickSize:
```java
long priceToRowIndex(long price, long referencePrice, long tickSize) {
    return (referencePrice - price) / tickSize;
}
```

And `rowIndexToPrice` is the inverse:
```java
long rowIndexToPrice(long rowIndex, long referencePrice, long tickSize) {
    return referencePrice - (rowIndex * tickSize);
}
```

### Current status

The code was generated for BTCUSDT where `tickSize=1L`, so any
implementation that increments by 1 happens to work correctly.
**This needs to be verified in the actual code** and fixed if it
uses `price + 1` instead of `price + tickSize`.

### Recommendation

Add a unit test in Step P2.7 that constructs a mock `RenderSnapshot`
with `tickSize=25L` and prices spaced at 25-unit intervals, then
verifies that `priceToRowIndex()` returns consecutive indices. This
catches the bug before Phase 4 CME integration.

---

*ADR-002 — Proposed 2026-03-30. Review before Phase 2 Step P2.7.*
