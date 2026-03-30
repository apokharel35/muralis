package com.muralis.ingestion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.muralis.model.AggressorSide;
import com.muralis.model.InstrumentSpec;
import com.muralis.model.NormalizedTrade;
import com.muralis.model.OrderBookDelta;
import com.muralis.model.OrderBookSnapshot;

/**
 * Stateless parser for raw Binance WebSocket and REST JSON payloads.
 * All methods are static. No floating-point arithmetic is used anywhere —
 * all prices and quantities are parsed to fixed-point long via string
 * manipulation only, matching the behaviour of BigDecimal.longValueExact().
 */
class BinanceMessageParser {

    private BinanceMessageParser() {}

    // ── Public parse entry points ─────────────────────────────────────────

    /**
     * Parses a decimal string to a fixed-point long using the given scale.
     * "97432.51" with scale=2 → 9743251L
     * Throws ArithmeticException if fractional digits exceed scale (precision loss).
     */
    static long parsePrice(String raw, int scale) {
        return parseFixedPoint(raw, scale);
    }

    /**
     * Parses a decimal string to a fixed-point long using the given scale.
     * "0.00041800" with scale=8 → 41800L
     * Throws ArithmeticException if fractional digits exceed scale (precision loss).
     */
    static long parseQty(String raw, int scale) {
        return parseFixedPoint(raw, scale);
    }

    /**
     * Parses a Binance depth update payload (the "data" object, not the envelope).
     * Field mapping: E→exchangeTs, s→symbol, U→firstUpdateId, u→finalUpdateId,
     * b→bids, a→asks.
     */
    static OrderBookDelta parseDelta(JsonObject json, InstrumentSpec spec) {
        long receivedTs    = System.currentTimeMillis();
        String symbol      = json.get("s").getAsString();
        long firstUpdateId = json.get("U").getAsLong();
        long finalUpdateId = json.get("u").getAsLong();
        long exchangeTs    = json.get("E").getAsLong();

        long[][] bids = parseLevels(json.getAsJsonArray("b"), spec.priceScale(), spec.qtyScale());
        long[][] asks = parseLevels(json.getAsJsonArray("a"), spec.priceScale(), spec.qtyScale());

        return new OrderBookDelta(
            symbol, firstUpdateId, finalUpdateId, exchangeTs, receivedTs,
            bids[0], bids[1], asks[0], asks[1]
        );
    }

    /**
     * Parses a Binance REST depth snapshot response body.
     * The REST response has no timestamp field; exchangeTs is approximated as
     * System.currentTimeMillis() at parse time — see SPEC-ingestion.md Section 9.2.
     * Symbol is taken from the InstrumentSpec (not present in REST response body).
     */
    static OrderBookSnapshot parseSnapshot(JsonObject json, InstrumentSpec spec) {
        long receivedTs  = System.currentTimeMillis();
        // Binance REST snapshot body contains no server timestamp.
        // exchangeTs is approximated as local receipt time — documented approximation.
        long exchangeTs  = receivedTs;
        long lastUpdateId = json.get("lastUpdateId").getAsLong();

        long[][] bids = parseLevels(json.getAsJsonArray("bids"), spec.priceScale(), spec.qtyScale());
        long[][] asks = parseLevels(json.getAsJsonArray("asks"), spec.priceScale(), spec.qtyScale());

        return new OrderBookSnapshot(
            spec.symbol(), lastUpdateId, exchangeTs, receivedTs,
            bids[0], bids[1], asks[0], asks[1]
        );
    }

    /**
     * Parses a Binance Futures @aggTrade stream payload (the "data" object).
     * Field mapping: T→exchangeTs (trade time, NOT E event time), s→symbol,
     * a→tradeId (aggregate trade ID — Futures has no "t" field), p→price,
     * q→qty (aggregate quantity). Fields nq, f, l are ignored.
     * AggressorSide: m=false → BUY (buyer lifted offer), m=true → SELL (seller hit bid).
     */
    static NormalizedTrade parseAggTrade(JsonObject json, InstrumentSpec spec) {
        long receivedTs      = System.currentTimeMillis();
        String symbol        = json.get("s").getAsString();
        long tradeId         = json.get("a").getAsLong();   // "a" = aggregate trade ID
        long price           = parsePrice(json.get("p").getAsString(), spec.priceScale());
        long qty             = parseQty(json.get("q").getAsString(), spec.qtyScale());
        boolean isBuyerMaker = json.get("m").getAsBoolean();
        AggressorSide aggressorSide = isBuyerMaker ? AggressorSide.SELL : AggressorSide.BUY;
        long exchangeTs      = json.get("T").getAsLong();   // T = trade time; E = event time (later)

        return new NormalizedTrade(symbol, tradeId, price, qty, aggressorSide, exchangeTs, receivedTs);
    }

    /**
     * Parses a Binance Futures @depth20 WebSocket partial-book snapshot payload.
     * Field mapping: E→exchangeTs, s→symbol, u→lastUpdateId, b→bids, a→asks.
     * Used during bootstrap to replace the geo-blocked REST snapshot endpoint.
     */
    static OrderBookSnapshot parseDepth20Snapshot(JsonObject json, InstrumentSpec spec) {
        long receivedTs   = System.currentTimeMillis();
        String symbol     = json.get("s").getAsString();
        long lastUpdateId = json.get("u").getAsLong();
        long exchangeTs   = json.get("E").getAsLong();

        long[][] bids = parseLevels(json.getAsJsonArray("b"), spec.priceScale(), spec.qtyScale());
        long[][] asks = parseLevels(json.getAsJsonArray("a"), spec.priceScale(), spec.qtyScale());

        return new OrderBookSnapshot(
            symbol, lastUpdateId, exchangeTs, receivedTs,
            bids[0], bids[1], asks[0], asks[1]
        );
    }

    /**
     * Extracts the {@code pu} (previous final update ID) field from a Futures depth
     * delta payload. Used by {@link BinanceAdapter} for gap detection only — this
     * value is NOT stored in {@link com.muralis.model.OrderBookDelta}.
     */
    static long parsePu(JsonObject json) {
        return json.get("pu").getAsLong();
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Parses a [[price, qty], ...] JsonArray into two parallel long arrays.
     * Returns long[2][]: index 0 = prices, index 1 = qtys.
     * A parsed qty of 0L is preserved as-is — it signals level removal to the engine.
     */
    private static long[][] parseLevels(JsonArray levels, int priceScale, int qtyScale) {
        int n = levels.size();
        long[] prices = new long[n];
        long[] qtys   = new long[n];
        for (int i = 0; i < n; i++) {
            JsonArray level = levels.get(i).getAsJsonArray();
            prices[i] = parsePrice(level.get(0).getAsString(), priceScale);
            qtys[i]   = parseQty(level.get(1).getAsString(), qtyScale);
        }
        return new long[][] { prices, qtys };
    }

    /**
     * Converts a decimal string to a fixed-point long without any floating-point
     * arithmetic. Semantically equivalent to:
     *   new BigDecimal(raw).movePointRight(scale).longValueExact()
     *
     * Examples:
     *   "97432.51",   scale=2 → 9743251L
     *   "0.00041800", scale=8 → 41800L
     *   "0.00000000", scale=8 → 0L     (level removal — preserved, not filtered)
     *   "1234",       scale=2 → 123400L
     *
     * Throws ArithmeticException if fracDigits > scale (precision would be lost),
     * matching the fail-fast contract of longValueExact().
     */
    private static long parseFixedPoint(String raw, int scale) {
        int dotIndex = raw.indexOf('.');
        if (dotIndex < 0) {
            // No decimal point — integer string, scale up directly
            return Long.parseLong(raw) * pow10(scale);
        }

        int fracDigits = raw.length() - dotIndex - 1;
        if (fracDigits > scale) {
            throw new ArithmeticException(
                "Scale underflow: \"" + raw + "\" has " + fracDigits
                + " fractional digits but scale=" + scale
            );
        }

        String intPart  = raw.substring(0, dotIndex);
        String fracPart = raw.substring(dotIndex + 1);

        long intValue  = Long.parseLong(intPart) * pow10(scale);
        long fracValue = Long.parseLong(fracPart) * pow10(scale - fracDigits);

        // Correct sign handling for the fractional component (prices are always
        // positive from Binance, but defensive for correctness)
        return intValue < 0 ? intValue - fracValue : intValue + fracValue;
    }

    private static long pow10(int n) {
        long result = 1L;
        for (int i = 0; i < n; i++) result *= 10L;
        return result;
    }
}
