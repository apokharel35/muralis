# ADR-001: Switch data source from Binance Spot to Binance USDⓈ-M Futures

**Date:** 2026-03-29
**Status:** Accepted
**Supersedes:** Original Binance Spot decision in PROJECT.md Section 2.3

---

## Decision

Replace Binance Spot (`stream.binance.com`, `api.binance.com`) with
Binance USDⓈ-M Futures (`fstream.binance.com`, `fapi.binance.com`)
as the sole data source for Phase 1.

The `ProviderType` value changes from `BINANCE_SPOT` to
`BINANCE_FUTURES`.

---

## Reason

Binance Spot (`stream.binance.com`) returns HTTP 451
"Unavailable For Legal Reasons" for all US IP addresses. Since
Muralis is built for US-based traders, a VPN workaround is not
acceptable — it introduces legal ambiguity, adds latency, and
creates a failure mode outside the application's control.

Binance USDⓈ-M Futures (`fstream.binance.com`) is not geo-blocked
in the US and provides the same order book depth and trade data
with structurally similar (but not identical) message formats.

---

## Impact analysis — every difference that affects implementation

### 1. URLs

| Component | Spot (old) | Futures (new) |
|---|---|---|
| WebSocket base | `wss://stream.binance.com:9443` | `wss://fstream.binance.com` |
| Combined stream path | `/stream?streams=` | `/stream?streams=` (same) |
| Depth stream name | `btcusdt@depth@100ms` | `btcusdt@depth@100ms` (same) |
| Trade stream name | `btcusdt@trade` | `btcusdt@aggTrade` **(CHANGED)** |
| REST snapshot URL | `https://api.binance.com/api/v3/depth` | `https://fapi.binance.com/fapi/v1/depth` |
| REST snapshot params | `?symbol=BTCUSDT&limit=5000` | `?symbol=BTCUSDT&limit=1000` **(max is 1000, not 5000)** |

### 2. Depth stream — field differences

| Field | Spot | Futures | Impact |
|---|---|---|---|
| `e` | `"depthUpdate"` | `"depthUpdate"` | Same |
| `E` | Event time (ms) | Event time (ms) | Same |
| `T` | *absent* | Transaction time (ms) **(NEW)** | Informational only — we use `E` for `exchangeTs` on deltas |
| `s` | Symbol | Symbol | Same |
| `U` | First update ID | First update ID | Same |
| `u` | Final update ID | Final update ID | Same |
| `pu` | *absent* | **Previous final update ID (NEW)** | Critical — enables simpler gap detection (see Section 4) |
| `b` | Bid changes | Bid changes | Same format |
| `a` | Ask changes | Ask changes | Same format |

### 3. Trade stream — `@trade` vs `@aggTrade`

This is the **largest structural difference**. Binance Futures does
not offer a `@trade` stream. It offers `@aggTrade` (aggregate trades),
which bundles fills at the same price and same taker side within a
100ms window into a single message.

| Field | Spot `@trade` | Futures `@aggTrade` | Impact |
|---|---|---|---|
| `e` | `"trade"` | `"aggTrade"` | Stream routing key changes |
| `t` (trade ID) | Individual trade ID | *absent* | **Use `a` instead** |
| `a` (agg trade ID) | *absent* | Aggregate trade ID | Maps to `tradeId` in `NormalizedTrade` |
| `p` | Price | Price | Same |
| `q` | Quantity | Aggregate quantity (all fills at this price) | Same semantics for our use |
| `nq` | *absent* | Normal quantity (excludes RPI) **(NEW)** | Ignore — use `q` |
| `f` | *absent* | First constituent trade ID | Ignore |
| `l` | *absent* | Last constituent trade ID | Ignore |
| `T` | Trade time (ms) | Trade time (ms) | Same field, same usage |
| `m` | `isBuyerMaker` | `isBuyerMaker` | Same — `AggressorSide` derivation unchanged |

**Impact on bubble rendering:** Aggregate trades are slightly
"chunkier" — instead of 5 individual fills at the same price, you
get 1 aggregate with the combined quantity. This means fewer but
larger bubbles during rapid fills. This is actually desirable for
visualization — it reduces visual noise during sweeps.

**Impact on duplicate detection:** `tradeId` now comes from the `a`
field (aggregate trade ID) instead of `t` (individual trade ID).
The `TradeBuffer.seenTradeIds` logic works identically — the ID
source changes, not the deduplication mechanism.

### 4. Bootstrap sequence — `pu` field enables simpler gap detection

The Futures depth stream includes a `pu` (previous `u`) field that
the Spot stream does not have. Binance's official Futures order
book management guide states:

> "each new event's `pu` should be equal to the previous event's
> `u`, otherwise initialize the process from step 3."

This is a **simpler and more reliable** gap detection mechanism
than the Spot approach. For Phase 1, we can use either method:

**Option A (recommended — use `pu` field):**
```
For each incoming delta after the first:
    if delta.pu != lastPublishedFinalUpdateId:
        → GAP DETECTED → reconnect
    else:
        → publish delta
        → lastPublishedFinalUpdateId = delta.u
```

**Option B (current spec approach — still works):**
```
For each incoming delta after the first:
    expected = lastPublishedFinalUpdateId + 1
    if delta.U != expected:
        → check for duplicate (delta.u <= lastPublished)
        → otherwise GAP DETECTED → reconnect
```

Both work. Option A is cleaner because Binance guarantees `pu`
linkage across delta events, removing the need to compute
`expected = last + 1`. The adapter should use **Option A** since
Binance explicitly provides `pu` for this purpose.

### 5. Bootstrap sequence — snapshot validation changes

Futures bootstrap (from Binance docs):

```
1. Open stream to wss://fstream.binance.com/stream?streams=btcusdt@depth
2. Buffer events
3. GET https://fapi.binance.com/fapi/v1/depth?symbol=BTCUSDT&limit=1000
4. Drop any event where u < lastUpdateId in the snapshot
5. First processed event should have U <= lastUpdateId AND u >= lastUpdateId
6. Subsequent events: validate pu == previous u
7. Quantity 0 = remove level (same as Spot)
```

This is structurally identical to the Spot bootstrap in
SPEC-ingestion.md Section 3.1, with two differences:
- Step 6 uses `pu` instead of computing `firstUpdateId == lastPublished + 1`
- REST limit max is 1000 (Spot allows 5000)

The 1000-level limit is sufficient. A typical BTCUSDT book has
500-1500 active levels. If more depth is needed, the deltas will
fill in levels beyond the snapshot range.

### 6. REST snapshot response — identical structure

```json
{
  "lastUpdateId": 10040,
  "E": 1234567890123,    // Event time (NEW — Spot doesn't have this)
  "T": 1234567890122,    // Transaction time (NEW)
  "bids": [["97432.50", "1.234"], ...],
  "asks": [["97433.00", "0.500"], ...]
}
```

The Futures snapshot includes `E` (event time) in the response body.
This is better than Spot — we can use it directly as `exchangeTs`
instead of parsing the HTTP `Date` header.

### 7. InstrumentSpec — BTCUSDT Futures vs Spot

| Field | Spot (old) | Futures (new) | Notes |
|---|---|---|---|
| `symbol` | `"BTCUSDT"` | `"BTCUSDT"` | Same |
| `priceScale` | `2` | `2` | **Same — Binance reverted tick to 0.01 in June 2025** |
| `tickSize` | `1L` (= 0.01) | `1L` (= 0.01) | Same |
| `qtyScale` | `8` | `3` | **CHANGED — futures qty precision is 3 decimals** |
| `minQty` | `100L` (= 0.000001) | `1L` (= 0.001) | **CHANGED** |
| `currency` | `"USDT"` | `"USDT"` | Same |
| `provider` | `BINANCE_SPOT` | `BINANCE_FUTURES` | Changed |

**`priceScale` remains 2 — same as Spot.**

The ADR initially specified `priceScale=1` based on a 2022 forum post
showing tick size `0.10`. However, Binance reverted the BTCUSDT Futures
tick size to `0.01` in June 2025. The live API confirms prices have
2 decimal places (e.g., `"67083.40"`). With `priceScale=2`:
- Price `67083.40` → stored as `6708340L`
- Display: divide by 100 → `"67,083.40"`

**Lesson learned:** Always verify `InstrumentSpec` against the live
API (`GET /fapi/v1/exchangeInfo`) before release. Tick sizes change.

**CRITICAL: `qtyScale` changes from 8 to 3.**

Futures BTCUSDT quantities have 3 decimal places (stepSize = 0.001).
- Quantity `0.041` → stored as `41L`
- Quantity `1.234` → stored as `1234L`

**WARNING: Tick size is subject to change by Binance.** The
application should ideally fetch `InstrumentSpec` from
`GET /fapi/v1/exchangeInfo` at startup rather than hardcoding. For
Phase 1, hardcoding is acceptable but the values must be verified
against the live API before release.

### 8. Impact on fixed-point arithmetic rules

The rules in DATA-CONTRACTS.md Section 1 are unchanged in principle.
Only the scale factors change:

| | Spot | Futures |
|---|---|---|
| Price: `"67083.40"` | `6708340L` (scale 2) | `6708340L` (scale 2) |
| Qty: `"0.041"` | `4100000L` (scale 8) | `41L` (scale 3) |

The `parsePrice()` and `parseQty()` functions use
`movePointRight(instrumentSpec.priceScale())` — they don't hardcode
the scale. As long as `InstrumentSpec` is correct, parsing works
automatically.

### 9. `OrderBookDelta` — new `pu` field

The `OrderBookDelta` record needs an additional field:

```java
public record OrderBookDelta(
    String symbol,
    long   firstUpdateId,    // U
    long   finalUpdateId,    // u
    long   prevFinalUpdateId, // pu — NEW, Futures only
    long   exchangeTs,       // E
    long   receivedTs,
    long[] bidPrices,
    long[] bidQtys,
    long[] askPrices,
    long[] askQtys
) implements MarketEvent {}
```

For Spot adapters (Phase 2 if needed), `prevFinalUpdateId` would be
set to `-1` to indicate "not available." The gap detection logic in
the adapter checks `pu` only when the provider is Futures.

Alternatively, keep `OrderBookDelta` unchanged and have the adapter
use `pu` internally for gap detection without passing it through the
queue. The engine doesn't need `pu` — gap detection is an adapter
responsibility. **This is the cleaner option.**

**Recommendation:** Do NOT add `pu` to `OrderBookDelta`. The adapter
reads `pu` from the JSON, uses it for gap detection internally, and
does not expose it to the engine. This keeps the model types
provider-agnostic.

---

## Spec files that require updating

### 1. PROJECT.md
- Section 2.3: Change "Binance Spot WebSocket" to "Binance USDⓈ-M
  Futures WebSocket"
- Update `wss://stream.binance.com:9443` to `wss://fstream.binance.com`
- Update streams: `<symbol>@trade` to `<symbol>@aggTrade`
- Note: Phase 2 scope references are unaffected

### 2. DATA-CONTRACTS.md
- Section 2.2: `AggressorSide` derivation — unchanged (Futures
  `aggTrade` uses same `m` field with same semantics)
- Section 3.2: `OrderBookDelta` — no field changes (adapter uses
  `pu` internally)
- Section 3.3: `NormalizedTrade` — note that `tradeId` is now the
  aggregate trade ID (`a` field), not individual trade ID (`t` field)
- Section 4.1: Change `BINANCE_SPOT` default to `BINANCE_FUTURES`
- Section 5.1: Update BTCUSDT instrument spec:
  `priceScale=2`, `tickSize=1L` (= 0.01), `qtyScale=3`,
  `minQty=1L` (= 0.001), `provider=BINANCE_FUTURES`
- Section 6: No changes (queue contract is provider-agnostic)
- Section 7: Parsing examples — update raw values and scales

### 3. ARCHITECTURE.md
- Section 8: Append this ADR
- No structural changes — architecture is provider-agnostic

### 4. SPEC-ingestion.md
- Section 2.1: Update WebSocket URL to `wss://fstream.binance.com`
- Section 2.2: Note `pu` field in depth stream; note `T` field
- Section 2.3: Replace `@trade` with `@aggTrade`; update field
  mapping (`a` → `tradeId`, not `t`)
- Section 3.1: Update REST URL to `fapi.binance.com/fapi/v1/depth`;
  limit=1000 (not 5000)
- Section 3.3: Update REST endpoint details
- Section 4.1: Update gap detection to use `pu` field
- Section 5.2: No changes (reconnection backoff is provider-agnostic)
- Section 6: Update `BinanceAdapter` → consider renaming to keep as
  is since it's still Binance
- Section 8: Update parser field references (`a` not `t`, `q` for
  aggregate qty, ignore `nq`/`f`/`l`)
- Section 9: Update SnapshotFetcher REST URL

### 5. SPEC-provider-spi.md
- Section 5: Update provider seam comment to reference `BINANCE_FUTURES`
- Section 7: Update normalization examples for Futures formats

### 6. SPEC-rendering.md
- No changes required. Rendering is provider-agnostic. The
  `InstrumentSpec` in `RenderSnapshot` drives all formatting.

### 7. BUILD.md
- No changes required. Dependencies are unchanged.

### 8. DEV-PLAN.md
- Step 4 prompt: Update URLs and stream names
- Step 4 expected output: Update log message format

### 9. CLAUDE.md
- Update default instrument spec values
- Note Futures-specific differences

### 10. manual.md
- No changes required (decisions are principle-level, not URL-level)

---

## Risks and mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Binance changes BTCUSDT Futures tick size again | Medium | Fetch from `/fapi/v1/exchangeInfo` at startup in Phase 2. Hardcode for Phase 1 but verify before release. |
| Aggregate trades produce fewer, larger bubbles | Low | Actually desirable — reduces noise during sweeps. No mitigation needed. |
| REST snapshot limited to 1000 levels (vs 5000 for Spot) | Low | Typical BTC book has 500-1500 levels. Deltas fill gaps beyond snapshot depth. |
| `pu` field creates Futures-specific gap detection path | Low | Keep `pu` usage inside the adapter. Engine and model types remain provider-agnostic. |
| Futures data may require Binance account activation | Low | Market data streams are public. No account or API key needed for read-only depth and trade data. |
| Geo-blocking status of fstream.binance.com could change | Medium | Monitor Binance announcements. If blocked, evaluate Binance.US Futures or alternative exchanges. |

---

## What does NOT change

- Thread model (3 threads, same ownership)
- Queue type (`LinkedTransferQueue<MarketEvent>`)
- `MarketEvent` sealed interface and its subtypes
- `RenderSnapshot` structure
- `OrderBook` and `TradeBuffer` internals
- All rendering logic
- All dependency rules between packages
- Fixed-point arithmetic rules (only scale factors change)
- `BigDecimal.movePointRight().longValueExact()` parsing pattern

---

## ADR-001 Addendum: REST geo-block and WebSocket-only bootstrap

**Date:** 2026-03-29 (same day as original ADR)

### Additional finding

`fapi.binance.com` (Futures REST API) is **also geo-blocked** for US
IPs, returning HTTP 451. Alternative endpoints (`fapi1.binance.com`)
return HTTP 202 with empty body. No working REST endpoint was found
for US-based snapshot fetching.

### Bootstrap redesigned — REST → WebSocket-only

The bootstrap now uses the `@depth20@100ms` Partial Book Depth stream
as the snapshot source. The adapter subscribes to THREE streams:

```
btcusdt@depth20@100ms   (snapshot — top 20 levels, every 100ms)
btcusdt@depth@100ms     (diffs — incremental updates)
btcusdt@aggTrade        (trades)
```

**Key design decision:** The `@depth20` and `@depth` diff streams use
**different update ID sequences**. The `@depth20` stream's `u` value
never appears as a diff's `pu` value. Therefore, the first live diff
after an `@depth20` sync is accepted unconditionally (no `pu`
validation), using an `awaitingFirstDiff` flag. This anchors the diff
chain, and all subsequent diffs validate normally.

**Impact:** The order book starts with 20 levels of depth and fills
out within 1-2 seconds as diffs arrive. `SnapshotFetcher.java` remains
in the codebase but is not called during bootstrap.

### priceScale corrected

The original ADR specified `priceScale=1` based on stale 2022 data.
The live API sends prices with 2 decimal places. Corrected to
`priceScale=2` in all spec files and in `Application.java`.

### Spec files updated
- `SPEC-ingestion.md` Section 3 — full rewrite for WebSocket-only bootstrap
- `DATA-CONTRACTS.md` — priceScale=2, conversion examples, snapshot description
- `CLAUDE.md` — all values corrected, three-stream connection documented
- `DEV-PLAN.md` — Step 4 and Step 8 values corrected

---

*ADR-001 with Addendum — Approved 2026-03-29. Append to ARCHITECTURE.md Section 8.*
