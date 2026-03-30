package com.muralis.ingestion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muralis.model.AggressorSide;
import com.muralis.model.InstrumentSpec;
import com.muralis.model.NormalizedTrade;
import com.muralis.model.OrderBookDelta;
import com.muralis.provider.ProviderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceMessageParserTest {

    private static final InstrumentSpec BTCUSDT = new InstrumentSpec(
            "BTCUSDT", 2, 1L, 3, 1L, "USDT", ProviderType.BINANCE_FUTURES
    );

    // ── parsePrice() tests ────────────────────────────────────────────────────

    @Test
    void parsePrice_67083_40_withScale2_returns6708340() {
        assertThat(BinanceMessageParser.parsePrice("67083.40", 2)).isEqualTo(6708340L);
    }

    @Test
    void parsePrice_0_00_withScale2_returns0() {
        assertThat(BinanceMessageParser.parsePrice("0.00", 2)).isEqualTo(0L);
    }

    @Test
    void parsePrice_tooManyDecimalPlaces_throwsArithmeticException() {
        // "67083.401" has 3 fractional digits but priceScale=2 → precision loss
        assertThatThrownBy(() -> BinanceMessageParser.parsePrice("67083.401", 2))
                .isInstanceOf(ArithmeticException.class);
    }

    // ── parseQty() tests ──────────────────────────────────────────────────────

    @Test
    void parseQty_0_041_withScale3_returns41() {
        assertThat(BinanceMessageParser.parseQty("0.041", 3)).isEqualTo(41L);
    }

    @Test
    void parseQty_1_234_withScale3_returns1234() {
        assertThat(BinanceMessageParser.parseQty("1.234", 3)).isEqualTo(1234L);
    }

    // ── parseAggTrade() tests ─────────────────────────────────────────────────

    @Test
    void parseAggTrade_tradeIdFromAField() {
        JsonObject json = aggTrade(5933014L, "67083.40", "0.041", false, 1234567891123L);

        NormalizedTrade trade = BinanceMessageParser.parseAggTrade(json, BTCUSDT);

        assertThat(trade.tradeId()).isEqualTo(5933014L);
    }

    @Test
    void parseAggTrade_isBuyerMakerFalse_returnsBuy() {
        JsonObject json = aggTrade(1001L, "67083.40", "0.041", false, 1234567891123L);

        NormalizedTrade trade = BinanceMessageParser.parseAggTrade(json, BTCUSDT);

        assertThat(trade.aggressorSide()).isEqualTo(AggressorSide.BUY);
    }

    @Test
    void parseAggTrade_isBuyerMakerTrue_returnsSell() {
        JsonObject json = aggTrade(1002L, "67083.40", "0.041", true, 1234567891123L);

        NormalizedTrade trade = BinanceMessageParser.parseAggTrade(json, BTCUSDT);

        assertThat(trade.aggressorSide()).isEqualTo(AggressorSide.SELL);
    }

    @Test
    void parseAggTrade_exchangeTsFromTField_notEField() {
        // E field (event time) is intentionally different from T field (trade time)
        JsonObject json = JsonParser.parseString("""
                {
                  "e": "aggTrade",
                  "E": 9999999999999,
                  "s": "BTCUSDT",
                  "a": 1003,
                  "p": "67083.40",
                  "q": "0.041",
                  "T": 1234567891123,
                  "m": false
                }
                """).getAsJsonObject();

        NormalizedTrade trade = BinanceMessageParser.parseAggTrade(json, BTCUSDT);

        assertThat(trade.exchangeTs()).isEqualTo(1234567891123L);
    }

    // ── parseDelta() tests ────────────────────────────────────────────────────

    @Test
    void parseDelta_qty0_preservedAs0L_notFiltered() {
        JsonObject json = JsonParser.parseString("""
                {
                  "e": "depthUpdate",
                  "E": 1234567891234,
                  "s": "BTCUSDT",
                  "U": 100,
                  "u": 105,
                  "pu": 99,
                  "b": [["67083.40", "0.000"]],
                  "a": []
                }
                """).getAsJsonObject();

        OrderBookDelta delta = BinanceMessageParser.parseDelta(json, BTCUSDT);

        assertThat(delta.bidPrices()).hasSize(1);
        assertThat(delta.bidQtys()).hasSize(1);
        assertThat(delta.bidQtys()[0]).isEqualTo(0L);
    }

    @Test
    void parseDelta_bidPricesAndBidQtys_areParallelArrays() {
        JsonObject json = JsonParser.parseString("""
                {
                  "e": "depthUpdate",
                  "E": 1234567891234,
                  "s": "BTCUSDT",
                  "U": 100,
                  "u": 105,
                  "pu": 99,
                  "b": [
                    ["67083.40", "0.002"],
                    ["67083.30", "3.906"]
                  ],
                  "a": []
                }
                """).getAsJsonObject();

        OrderBookDelta delta = BinanceMessageParser.parseDelta(json, BTCUSDT);

        assertThat(delta.bidPrices().length).isEqualTo(delta.bidQtys().length);
        assertThat(delta.bidPrices()[0]).isEqualTo(6708340L);
        assertThat(delta.bidQtys()[0]).isEqualTo(2L);
        assertThat(delta.bidPrices()[1]).isEqualTo(6708330L);
        assertThat(delta.bidQtys()[1]).isEqualTo(3906L);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private JsonObject aggTrade(long tradeId, String price, String qty,
                                 boolean isBuyerMaker, long tradeTime) {
        return JsonParser.parseString(String.format("""
                {
                  "e": "aggTrade",
                  "E": 1234567891234,
                  "s": "BTCUSDT",
                  "a": %d,
                  "p": "%s",
                  "q": "%s",
                  "T": %d,
                  "m": %b
                }
                """, tradeId, price, qty, tradeTime, isBuyerMaker)).getAsJsonObject();
    }
}
